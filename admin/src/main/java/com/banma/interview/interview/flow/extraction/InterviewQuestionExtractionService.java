package com.banma.interview.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.interview.api.io.req.InterviewQuestionReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewQuestionRespDTO;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardStage;
import com.banma.interview.interview.application.guard.lock.InterviewAiSessionLockService;
import com.banma.interview.interview.shared.InterviewAiInvoker;
import com.banma.interview.interview.shared.InterviewResponseParser;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试题目提取服务：上传简历、调用提取工作流、解析结构化结果并写入缓存与数据库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionExtractionService {

    private static final String EXTRACTION_PROMPT =
            "Extract technical interview questions from the uploaded resume. "
                    + "Return JSON only with keys questions, sugest, type, and resumeScore. "
                    + "Do not output smallTalk, greetings, or fallback chat content.";

    /** 按业务场景解析智能体配置（模型路由入口） */
    private final BusinessAgentResolver businessAgentResolver;
    /** 星辰（讯飞）AI 客户端，降级通道一期不启用 */
    private final XingChenAIClient xingChenAIClient;
    /** AI 调用唯一入口（限流熔断 + single-flight） */
    private final InterviewAiInvoker interviewAiInvoker;
    /** 会话级 AI 调用分布式锁 */
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** AI 响应防御式解析（剥围栏、别名取值、补默认值） */
    private final InterviewResponseParser interviewResponseParser;

    /**
     * 从简历中提取面试题目：会话加锁、上传简历、调用工作流、持久化原始响应并回填结构化结果。
     *
     * @return 提取结果；失败时 isSuccess 为 0 并携带错误信息
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());

        // （1）解析提取场景绑定的 agent 配置
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(
                BusinessAgentScene.INTERVIEW_QUESTION_EXTRACTION);
        reqDTO.setAgentId(agentProperties.getId());
        response.setIsSuccess(0);

        // 哈希计算是纯本地操作，在获取分布式锁之前完成，减少锁占用时间。
        String resumeContentHash = computeResumeHash(reqDTO.getResumePdf(), reqDTO.getSessionId());

        RLock heavyLock = null;
        long startTime = System.currentTimeMillis();
        try {
            // 同一 session 的提取属于重操作，先拿会话级重锁，避免并发上传/提取造成重复消耗和状态覆盖。
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            String fileUrl = uploadResumeIfPresent(reqDTO, agentProperties, response);
            if (fileUrl == null) {
                return response;
            }

            // （2）调用提取工作流，用简历内容哈希做 single-flight 去重
            String fullContent = interviewAiInvoker.callAiSyncWithFile(
                    EXTRACTION_PROMPT,
                    reqDTO.getSessionId(),
                    agentProperties,
                    fileUrl,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EXTRACTION, reqDTO.getSessionId(), resumeContentHash)
            );

            long responseTime = System.currentTimeMillis() - startTime;
            reqDTO.setResumeFileUrl(fileUrl);

            // 先持久化原始响应，再做结构化解析；解析失败时仍可通过原始响应排障与回补。
            persistRawResponse(reqDTO, fullContent, responseTime);

            response.setResumeFileUrl(fileUrl);
            response.setResponseTime((int) responseTime);

            if (!populateStructuredResponse(reqDTO, response, fullContent)) {
                return response;
            }

            // （3）结构化回填成功，标记成功并按需返回
            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}", reqDTO.getSessionId());
            return response;
        } catch (InterviewAiGuardException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Interview question extraction guarded failure, sessionId={}, code={}, message={}",
                    reqDTO.getSessionId(), e.getErrorCode(), e.getMessage());
            try {
                // 失败也落库，但仅记录错误信息；结构化字段覆盖保护在 service 层统一处理。
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction guard error record: {}", saveException.getMessage());
            }
            response.setErrorMessage(e.getMessage());
            response.setIsSuccess(0);
            return response;
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed: {}", e.getMessage(), e);
            try {
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction error record: {}", saveException.getMessage());
            }

            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    /**
     * 上传简历文件并返回可被工作流访问的 URL，文件缺失或上传失败时返回 null。
     */
    private String uploadResumeIfPresent(
            InterviewQuestionReqDTO reqDTO,
            AgentPropertiesDO agentProperties,
            InterviewQuestionRespDTO response) {
        if (reqDTO.getResumePdf() == null || reqDTO.getResumePdf().isEmpty()) {
            response.setErrorMessage("resume file does not exist");
            return null;
        }
        try {
            String fileUrl = xingChenAIClient.uploadFile(
                    reqDTO.getResumePdf(),
                    agentProperties.getApiKey(),
                    agentProperties.getApiSecret()
            );
            log.info("Resume uploaded successfully, url={}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("Resume upload failed: {}", e.getMessage());
            response.setErrorMessage("failed to upload resume file");
            return null;
        }
    }

    /**
     * 持久化工作流原始响应，解析失败时仍保留原始内容便于排障与回补。
     */
    private void persistRawResponse(InterviewQuestionReqDTO reqDTO, String fullContent, long responseTime) {
        try {
            interviewQuestionService.createFromAIResponse(
                    reqDTO,
                    fullContent,
                    (int) responseTime,
                    null
            );
            log.info("Interview question response saved, sessionId={}", reqDTO.getSessionId());
        } catch (Exception e) {
            log.error("Failed to save interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage());
        }
    }

    /**
     * 解析提取响应并回填结构化结果：题目、建议、方向、简历评分，同时写入缓存与数据库。
     *
     * @return 结构化回填成功返回 true，失败返回 false 并写入错误信息
     */
    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent) {
        try {
            // （1）打印响应摘要并校验工作流错误信息
            log.info("Start parsing interview question response, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    fullContent == null ? 0 : fullContent.length(),
                    digestForLog(fullContent));

            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(fullContent);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                response.setErrorMessage(workflowErrorMessage);
                log.warn("Interview question workflow returned error, sessionId={}, message={}",
                        reqDTO.getSessionId(), workflowErrorMessage);
                return false;
            }

            // （2）抽取回答正文并解析结构化字段
            String extractedContent = interviewResponseParser.extractContentFromInterviewResponse(fullContent);
            log.info("Extracted interview content summary, sessionId={}, contentLength={}, contentHash={}",
                    reqDTO.getSessionId(),
                    extractedContent == null ? 0 : extractedContent.length(),
                    digestForLog(extractedContent));
            if (StrUtil.isBlank(extractedContent)) {
                response.setErrorMessage("interview question response content is blank");
                return false;
            }

            Map<String, Object> responseMap = interviewResponseParser.extractStructuredResult(
                    extractedContent,
                    "questions",
                    "sugest",
                    "suggestions",
                    "resumeScore",
                    "type",
                    "smallTalk"
            );
            if (responseMap == null || responseMap.isEmpty()) {
                response.setErrorMessage("interview question response parse failed");
                log.warn("Interview question response parse failed, responseMap is null");
                return false;
            }

            log.info("Interview question response fields: {}", responseMap.keySet());
            // （3）缓存简历上下文，并校验题目是否为空
            Map<String, Object> resumeContext = buildResumeContext(responseMap);
            if (!resumeContext.isEmpty()) {
                interviewQuestionCacheService.cacheResumeContext(reqDTO.getSessionId(), resumeContext);
            }

            List<String> questions = normalizeStringList(responseMap.get("questions"));
            if (questions.isEmpty()) {
                String smallTalk = interviewResponseParser.asString(responseMap.get("smallTalk"));
                response.setErrorMessage(StrUtil.isNotBlank(smallTalk)
                        ? "workflow fell back to smallTalk instead of interview questions"
                        : "workflow returned empty interview questions");
                log.warn("Interview question extraction returned no questions, sessionId={}, smallTalk={}",
                        reqDTO.getSessionId(), smallTalk);
                return false;
            }

            // （4）缓存题目并初始化面试流程
            interviewQuestionCacheService.cacheInterviewQuestions(reqDTO.getSessionId(), questions);
            Map<String, String> questionMap =
                    interviewQuestionCacheService.getSessionInterviewQuestions(reqDTO.getSessionId());
            response.setQuestions(questionMap);
            response.setQuestionCount(questions.size());
            interviewQuestionCacheService.initInterviewFlow(reqDTO.getSessionId(), questions.size());

            // （5）回填并缓存面试建议
            List<String> suggestions = normalizeSuggestions(responseMap);
            if (!suggestions.isEmpty()) {
                interviewQuestionCacheService.cacheInterviewSuggestions(reqDTO.getSessionId(), suggestions);
                Map<String, String> suggestionMap =
                        interviewQuestionCacheService.getSessionInterviewSuggestions(reqDTO.getSessionId());
                response.setSuggestions(suggestionMap);
                response.setSuggestionCount(suggestions.size());
            } else {
                log.warn("Interview question response does not contain suggestions");
            }

            // type 字段兼容历史别名，保证 interviewDirection/interviewType 在不同模型输出下都能回补。
            // （6）回填面试方向，兼容多个历史别名字段
            String interviewType = interviewResponseParser.asString(responseMap.get("type"));
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewType"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("direction"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewDirection"));
            }
            if (StrUtil.isNotBlank(interviewType)) {
                interviewQuestionCacheService.cacheInterviewDirection(reqDTO.getSessionId(), interviewType);
                response.setInterviewType(interviewType);
            } else {
                log.warn("Interview question response does not contain type field");
            }

            // （7）回填简历评分
            Integer resumeScore = interviewResponseParser.parseScoreFromResponse(responseMap, "resumeScore");
            if (resumeScore != null) {
                interviewQuestionCacheService.cacheResumeScore(reqDTO.getSessionId(), resumeScore);
                response.setResumeScore(resumeScore);
            } else {
                log.warn("Interview question response does not contain valid resumeScore field");
            }

            // 结构化二次落库用于 Redis 丢失后的恢复来源，避免报告阶段出现字段缺失。
            // （8）结构化字段二次落库并重置会话分数
            persistStructuredFields(reqDTO, questions, suggestions, resumeScore, interviewType, resumeContext);
            interviewQuestionCacheService.resetSessionScore(reqDTO.getSessionId());
            log.info("Session score reset, sessionId={}", reqDTO.getSessionId());
            return true;
        } catch (Exception cacheException) {
            response.setErrorMessage("failed to parse interview question response");
            log.error(
                    "Failed to cache interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(),
                    cacheException.getMessage()
            );
            return false;
        }
    }

    /**
     * 归一化建议列表，优先取 sugest，其次取 suggestions。
     */
    private List<String> normalizeSuggestions(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> suggestions = normalizeStringList(responseMap.get("sugest"));
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        return normalizeStringList(responseMap.get("suggestions"));
    }

    /**
     * 把任意值转换为字符串列表，交给统一响应解析器处理。
     */
    private List<String> normalizeStringList(Object value) {
        return interviewResponseParser.asStringList(value);
    }

    /**
     * 计算简历文件内容的 SHA-256 哈希，用于 single-flight 去重。
     * 相同文件内容产生相同哈希，避免因上传 URL 每次变化导致去重失效。
     *
     * @param resumePdf 简历文件
     * @param sessionId 会话标识，仅用于日志
     * @return 文件内容的 SHA-256 十六进制字符串，文件为空时返回 null
     */
    private String computeResumeHash(MultipartFile resumePdf, String sessionId) {
        if (resumePdf == null || resumePdf.isEmpty()) {
            log.debug("Resume PDF is null or empty, cannot compute content hash, sessionId={}", sessionId);
            return null;
        }
        try {
            byte[] fileBytes = resumePdf.getBytes();
            String hash = DigestUtil.sha256Hex(fileBytes);
            log.debug("Computed resume content hash, sessionId={}, hash={}", sessionId, hash);
            return hash;
        } catch (Exception e) {
            log.warn("Failed to read resume PDF bytes for content hashing, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 计算文本的短哈希，用于日志定位且不输出完整内容。
     */
    private String digestForLog(String value) {
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        return DigestUtil.sha256Hex(value).substring(0, 16);
    }

    /**
     * 把结构化提取结果二次写入数据库，作为缓存丢失后的恢复来源；失败仅告警不影响主流程。
     */
    private void persistStructuredFields(
            InterviewQuestionReqDTO reqDTO,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext) {
        try {
            interviewQuestionService.upsertStructuredExtraction(
                    reqDTO.getSessionId(),
                    reqDTO.getUserName(),
                    reqDTO.getAgentId(),
                    reqDTO.getResumeFileUrl(),
                    questions,
                    suggestions,
                    resumeScore,
                    interviewType,
                    resumeContext
            );
        } catch (Exception ex) {
            log.warn("Failed to persist structured extraction fields, sessionId={}, error={}",
                    reqDTO.getSessionId(), ex.getMessage(), ex);
        }
    }

    /**
     * 从结构化结果中提取简历上下文，排除题目与建议等字段。
     */
    private Map<String, Object> buildResumeContext(Map<String, Object> responseMap) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (responseMap == null || responseMap.isEmpty()) {
            return context;
        }
        for (Map.Entry<String, Object> entry : responseMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("questions".equals(key) || "sugest".equals(key) || "suggestions".equals(key)) {
                continue;
            }
            context.put(key, value);
        }
        return context;
    }
}
