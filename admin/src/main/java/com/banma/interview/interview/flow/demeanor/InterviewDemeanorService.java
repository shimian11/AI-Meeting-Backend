package com.banma.interview.interview.flow.demeanor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import com.banma.interview.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardErrorCode;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardStage;
import com.banma.interview.interview.application.guard.lock.InterviewAiSessionLockService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.banma.interview.interview.application.strategy.DemeanorNormalizationStrategy;
import com.banma.interview.interview.shared.InterviewAiInvoker;
import com.banma.interview.interview.shared.InterviewResponseParser;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
/**
 * Demeanor scoring service.
 * Responsible for image upload, workflow invocation, response parsing, and cache persistence.
 * 神态评分服务：负责照片上传、神态工作流调用、分值解析归一与缓存落库。
 */
public class InterviewDemeanorService {

    private static final String KEY_PANIC_LEVEL = "panicLevel";
    private static final String KEY_SERIOUSNESS_LEVEL = "seriousnessLevel";
    private static final String KEY_EMOTICON_HANDLING = "emoticonHandling";
    private static final String KEY_COMPOSITE_SCORE = "compositeScore";

    /** 星辰（讯飞）AI 客户端，降级通道一期不启用 */
    private final XingChenAIClient xingChenAIClient;
    /** 按业务场景解析智能体配置（模型路由入口） */
    private final BusinessAgentResolver businessAgentResolver;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** AI 调用唯一入口（限流熔断 + single-flight） */
    private final InterviewAiInvoker interviewAiInvoker;
    /** 会话级 AI 调用分布式锁 */
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    /** AI 响应防御式解析（剥围栏、别名取值、补默认值） */
    private final InterviewResponseParser interviewResponseParser;
    /** 神态分值归一化策略（0-10 与 0-100 互转） */
    private final DemeanorNormalizationStrategy demeanorNormalizationStrategy;
    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;

    /**
     * 执行神态评估：会话加锁、上传照片、调用神态工作流、解析归一化分值并写缓存。
     *
     * @return 评估完成提示文本
     */
    public String evaluateDemeanor(DemeanorEvaluationReqDTO reqDTO) {
        String sessionId = null;
        RLock heavyLock = null;
        try {
            // （1）先抢会话级重锁，避免同一 session 并发神态评估互相覆盖。
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_DEMEANOR);
            if (heavyLock == null) {
                throw new ClientException(
                        "AI_OVERLOADED: demeanor evaluation is processing, please retry",
                        InterviewErrorCodeEnum.AI_OVERLOADED
                );
            }

            String imageUrl = null;

            // Upload image first and get a workflow-readable URL.
            if (reqDTO.getUserPhoto() != null && !reqDTO.getUserPhoto().isEmpty()) {
                try {
                    // （2）上传照片，拿到 AI workflow 可访问的文件 URL。
                    AgentPropertiesDO agentProperties = resolveRequiredAgent(reqDTO);
                    if (agentProperties == null) {
                        throw new ClientException(InterviewErrorCodeEnum.AGENT_CONFIG_NOT_FOUND);
                    }

                    imageUrl = xingChenAIClient.uploadFile(
                            reqDTO.getUserPhoto(),
                            agentProperties.getApiKey(),
                            agentProperties.getApiSecret()
                    );
                    log.info("Image uploaded successfully, URL: {}", imageUrl);
                } catch (ClientException ce) {
                    throw ce;
                } catch (Exception e) {
                    log.error("Image upload failed: {}", e.getMessage());
                    throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_FILE_UPLOAD_FAILED);
                }
            }

            if (imageUrl == null) {
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_USER_PHOTO_NOT_FOUND);
            }

            String promptBuilder = "Evaluate this photo and return integer scores (0-100) for panicLevel, seriousnessLevel, emoticonHandling and compositeScore.";
            AgentPropertiesDO agentProperties = resolveRequiredAgent(reqDTO);
            if (agentProperties == null) {
                throw new ClientException(InterviewErrorCodeEnum.AGENT_CONFIG_NOT_FOUND);
            }

            // （3）调用神态工作流并解析结构化分值，最后统一归一化后落缓存。
            String aiResponseStr = interviewAiInvoker.callAiSyncWithFile(
                    promptBuilder,
                    reqDTO.getSessionId() != null ? reqDTO.getSessionId() : "demeanor_" + System.currentTimeMillis(),
                    agentProperties,
                    imageUrl,
                    InterviewAiGuardStage.INTERVIEW_DEMEANOR,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_DEMEANOR, reqDTO.getSessionId(), imageUrl)
            );

            log.info("Demeanor response received, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    aiResponseStr == null ? 0 : aiResponseStr.length(),
                    digestForLog(aiResponseStr));
            sessionId = reqDTO.getSessionId();
            // （4）校验工作流返回的错误信息
            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(aiResponseStr);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                log.error("Demeanor workflow failed, sessionId={}, {}", sessionId, workflowErrorMessage);
                throw new ClientException(workflowErrorMessage, InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_INVALID);
            }
            try {
                // （5）解析结构化分值字段
                Map<String, Object> contentMap = interviewResponseParser.extractStructuredResult(
                        aiResponseStr,
                        KEY_PANIC_LEVEL,
                        KEY_SERIOUSNESS_LEVEL,
                        KEY_EMOTICON_HANDLING,
                        KEY_COMPOSITE_SCORE
                );
                log.info("Parsed demeanor content keys: {}", contentMap == null ? null : contentMap.keySet());

                if (contentMap == null || contentMap.isEmpty()) {
                    log.error("Missing structured result in demeanor response");
                    throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_FORMAT_ERROR);
                }

                Integer panicLevel = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_PANIC_LEVEL);
                Integer seriousnessLevel = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_SERIOUSNESS_LEVEL);
                Integer emoticonHandling = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_EMOTICON_HANDLING);
                Integer compositeScore = interviewResponseParser.parseScoreFromResponse(contentMap, KEY_COMPOSITE_SCORE);

                if (panicLevel != null && seriousnessLevel != null
                        && emoticonHandling != null && compositeScore != null) {
                    boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(
                            panicLevel,
                            seriousnessLevel,
                            emoticonHandling,
                            compositeScore
                    );
                    int normalizedPanic = demeanorNormalizationStrategy.normalize(panicLevel, tenScaleDetected);
                    int normalizedSeriousness = demeanorNormalizationStrategy.normalize(seriousnessLevel, tenScaleDetected);
                    int normalizedEmoticon = demeanorNormalizationStrategy.normalize(emoticonHandling, tenScaleDetected);
                    int normalizedComposite = demeanorNormalizationStrategy.normalize(compositeScore, tenScaleDetected);

                    if (tenScaleDetected) {
                        log.info("Demeanor score detected as 0-10 scale, converted to 0-100, sessionId={}", sessionId);
                    }

                    interviewQuestionCacheService.cacheDemeanorScoreDetails(
                            sessionId, normalizedPanic, normalizedSeriousness, normalizedEmoticon, normalizedComposite
                    );
                    // （6）分值落入缓存并刷新运行时快照
                    interviewQuestionCacheService.cacheDemeanorScore(sessionId, normalizedComposite);
                    runtimeSnapshotService.refreshAfterDemeanorEvaluated(sessionId);
                    log.info("Demeanor score success, sessionId={}, panic={}, seriousness={}, emoticon={}, composite={}",
                            sessionId, normalizedPanic, normalizedSeriousness, normalizedEmoticon, normalizedComposite);
                    return "Demeanor evaluation completed";
                }

                log.error("Invalid score fields: panicLevel={}, seriousnessLevel={}, emoticonHandling={}, compositeScore={}",
                        panicLevel, seriousnessLevel, emoticonHandling, compositeScore);
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_INVALID);
            } catch (ClientException ce) {
                throw ce;
            } catch (Exception parseException) {
                log.error("Failed to parse demeanor response, sessionId={}, payloadHash={}, error={}",
                        sessionId, digestForLog(aiResponseStr), parseException.getMessage(), parseException);
                throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_AI_RESPONSE_PARSE_ERROR);
            }

        } catch (ClientException ce) {
            throw ce;
        } catch (InterviewAiGuardException aiGuardException) {
            throw new ClientException(aiGuardException.getMessage(), mapAiGuardError(aiGuardException.getErrorCode()));
        } catch (Exception e) {
            log.error("Demeanor evaluation failed, sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new ClientException(InterviewErrorCodeEnum.DEMEANOR_EVALUATION_FAILED);
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    /**
     * 计算响应的短哈希，用于日志中定位问题且不泄露原始内容。
     */
    private String digestForLog(String payload) {
        if (StrUtil.isBlank(payload)) {
            return "-";
        }
        return DigestUtil.sha256Hex(payload).substring(0, 16);
    }

    /**
     * 解析神态评估场景绑定的 agent 配置，并回填到请求对象。
     */
    private AgentPropertiesDO resolveRequiredAgent(DemeanorEvaluationReqDTO reqDTO) {
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_DEMEANOR);
        reqDTO.setAgentId(agentProperties.getId());
        return agentProperties;
    }

    /**
     * 把 AI 门禁错误码映射为对外业务错误码。
     */
    private InterviewErrorCodeEnum mapAiGuardError(InterviewAiGuardErrorCode errorCode) {
        if (errorCode == null) {
            return InterviewErrorCodeEnum.AI_UNAVAILABLE;
        }
        return switch (errorCode) {
            case AI_TIMEOUT -> InterviewErrorCodeEnum.AI_TIMEOUT;
            case AI_OVERLOADED -> InterviewErrorCodeEnum.AI_OVERLOADED;
            default -> InterviewErrorCodeEnum.AI_UNAVAILABLE;
        };
    }

}
