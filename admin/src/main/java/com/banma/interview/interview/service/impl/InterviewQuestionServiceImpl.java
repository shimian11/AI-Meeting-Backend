package com.banma.interview.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.interview.api.io.req.InterviewQuestionReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewQuestionRespDTO;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.repository.InterviewQuestionRepository;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.shared.InterviewJsonValueNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 面试题服务实现：负责面试题的保存、查询、分页与 AI 结果解析落库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionServiceImpl implements InterviewQuestionService {

    /** 面试题目仓储 */
    private final InterviewQuestionRepository interviewQuestionRepository;

    /**
     * 保存面试题：按会话复用已有记录，并补齐创建时间、更新时间与删除标记。
     *
     * @param interviewQuestion 面试题实体
     * @return 保存后的实体
     */
    @Override
    public InterviewQuestion saveInterviewQuestion(InterviewQuestion interviewQuestion) {
        // （1）按会话 id 查询已有记录，用于复用主键与原始字段
        InterviewQuestion existing = null;
        if (StrUtil.isNotBlank(interviewQuestion.getSessionId())) {
            existing = getBySessionId(interviewQuestion.getSessionId());
        }
        // （2）存在旧记录时继承主键、创建时间与删除标记
        if (existing != null) {
            interviewQuestion.setId(existing.getId());
            if (interviewQuestion.getCreateTime() == null) {
                interviewQuestion.setCreateTime(existing.getCreateTime());
            }
            if (interviewQuestion.getDelFlag() == null) {
                interviewQuestion.setDelFlag(existing.getDelFlag());
            }
        }
        // （3）补齐创建时间、更新时间与默认删除标记后落库
        if (interviewQuestion.getCreateTime() == null) {
            interviewQuestion.setCreateTime(new Date());
        }
        interviewQuestion.setUpdateTime(new Date());
        if (interviewQuestion.getDelFlag() == null) {
            interviewQuestion.setDelFlag(0);
        }
        return interviewQuestionRepository.save(interviewQuestion);
    }

    /**
     * 按会话 id 查询未删除的面试题。
     */
    @Override
    public InterviewQuestion getBySessionId(String sessionId) {
        Optional<InterviewQuestion> optional = interviewQuestionRepository.findBySessionIdAndDelFlag(sessionId, 0);
        return optional.orElse(null);
    }

    /**
     * 按用户名查询未删除的面试题，按创建时间倒序。
     */
    @Override
    public List<InterviewQuestion> getByUserName(String userName) {
        return interviewQuestionRepository.findByUserNameAndDelFlagOrderByCreateTimeDesc(userName, 0);
    }

    /**
     * 分页查询指定用户的面试题，并转换为响应 DTO。
     *
     * @param userName 用户名
     * @param current 当前页码，从 1 开始
     * @param size 每页条数
     * @return 分页响应
     */
    @Override
    public IPage<InterviewQuestionRespDTO> pageUserInterviewQuestions(String userName, Integer current, Integer size) {
        // （1）按页码转换分页参数并查询数据库
        Pageable pageable = PageRequest.of(current - 1, size);
        org.springframework.data.domain.Page<InterviewQuestion> questionPage =
                interviewQuestionRepository.findByUserNameAndDelFlagOrderByCreateTimeDesc(userName, 0, pageable);

        // （2）组装分页结果并转换记录
        Page<InterviewQuestionRespDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(questionPage.getTotalElements());
        resultPage.setRecords(
                questionPage.getContent().stream()
                        .map(this::convertToRespDTO)
                        .collect(Collectors.toList())
        );
        return resultPage;
    }

    /**
     * 分页查询全部未删除的面试题，并转换为响应 DTO。
     *
     * @param current 当前页码，从 1 开始
     * @param size 每页条数
     * @return 分页响应
     */
    @Override
    public IPage<InterviewQuestionRespDTO> pageAllInterviewQuestions(Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        org.springframework.data.domain.Page<InterviewQuestion> questionPage =
                interviewQuestionRepository.findByDelFlagOrderByCreateTimeDesc(0, pageable);

        Page<InterviewQuestionRespDTO> resultPage = new Page<>(current, size);
        resultPage.setTotal(questionPage.getTotalElements());
        resultPage.setRecords(
                questionPage.getContent().stream()
                        .map(this::convertToRespDTO)
                        .collect(Collectors.toList())
        );
        return resultPage;
    }

    /**
     * 按面试方向查询未删除的面试题，按创建时间倒序。
     */
    @Override
    public List<InterviewQuestion> getByInterviewType(String interviewType) {
        return interviewQuestionRepository.findByInterviewTypeAndDelFlagOrderByCreateTimeDesc(interviewType, 0);
    }

    /**
     * 逻辑删除面试题：将删除标记置 1 并更新修改时间。
     *
     * @param id 面试题主键
     * @return 删除成功返回 true，不存在或异常返回 false
     */
    @Override
    public boolean deleteInterviewQuestion(String id) {
        try {
            // （1）查询记录，存在则置为已删除并保存
            Optional<InterviewQuestion> optional = interviewQuestionRepository.findById(id);
            if (optional.isPresent()) {
                InterviewQuestion question = optional.get();
                question.setDelFlag(1);
                question.setUpdateTime(new Date());
                interviewQuestionRepository.save(question);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Delete interview question failed, id={}, error={}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 统计指定用户名下未删除的面试题数量。
     */
    @Override
    public Integer countByUserName(String userName) {
        return interviewQuestionRepository.countByUserNameAndDelFlag(userName, 0);
    }

    /**
     * 解析 AI 出题结果并落库：提取题目、建议、简历评分与面试方向，失败时保存原始响应。
     *
     * @param reqDTO 出题请求参数
     * @param aiResponseData AI 原始响应
     * @param responseTime 响应耗时
     * @param tokenCount token 消耗数
     * @return 保存后的面试题实体
     */
    @Override
    public InterviewQuestion createFromAIResponse(
            InterviewQuestionReqDTO reqDTO,
            String aiResponseData,
            Integer responseTime,
            Integer tokenCount) {
        try {
            // （1）构建待保存实体并解析 AI 响应中的结构化数据
            InterviewQuestion question = buildQuestionForUpsert(reqDTO, responseTime, tokenCount, aiResponseData);

            Map<String, Object> payload = parseStructuredPayload(aiResponseData);
            List<String> questions = toStringList(payload.get("questions"));
            List<String> suggestions = toStringList(firstNonNull(payload.get("sugest"), payload.get("suggestions")));
            Integer resumeScore = toInteger(firstNonNull(payload.get("resumeScore"), payload.get("score")));
            String interviewType = resolveInterviewType(payload);

            // （2）提取到有效数据时写入结构化字段，否则记录错误信息
            if (hasStructuredExtraction(questions, suggestions, resumeScore, interviewType)) {
                setQuestions(question, questions);
                setSuggestions(question, suggestions);
                if (resumeScore != null) {
                    question.setResumeScore(resumeScore);
                }
                if (StrUtil.isNotBlank(interviewType)) {
                    question.setInterviewType(interviewType.trim());
                }
                question.setErrorMessage(null);
            } else {
                String errorMessage = resolveErrorMessage(payload, aiResponseData);
                if (StrUtil.isNotBlank(errorMessage)) {
                    question.setErrorMessage(errorMessage);
                }
            }
            // （3）落库保存解析结果
            return saveInterviewQuestion(question);
        } catch (Exception e) {
            // （4）解析异常时保存原始响应，并把异常信息写入错误字段
            log.error("Create interview question from AI response failed, sessionId={}, error={}",
                    reqDTO == null ? null : reqDTO.getSessionId(), e.getMessage(), e);
            InterviewQuestion fallbackQuestion = buildQuestionForUpsert(reqDTO, responseTime, tokenCount, aiResponseData);
            if (StrUtil.isNotBlank(e.getMessage())) {
                fallbackQuestion.setErrorMessage(e.getMessage());
            }
            return saveInterviewQuestion(fallbackQuestion);
        }
    }

    /**
     * 以结构化结果新增或更新会话的面试题记录。
     *
     * @param sessionId 会话 id
     * @param userName 用户名
     * @param agentId 智能体 id
     * @param resumeFileUrl 简历文件地址
     * @param questions 面试题列表
     * @param suggestions 面试建议列表
     * @param resumeScore 简历评分
     * @param interviewType 面试方向
     * @param resumeContext 简历上下文
     * @return 保存后的实体，会话 id 为空时返回 null
     */
    @Override
    public InterviewQuestion upsertStructuredExtraction(
            String sessionId,
            String userName,
            Long agentId,
            String resumeFileUrl,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （1）按会话查询记录，不存在则新建
        InterviewQuestion question = getBySessionId(sessionId);
        if (question == null) {
            question = new InterviewQuestion();
            question.setSessionId(sessionId);
            question.setDelFlag(0);
        }
        // （2）覆盖写入请求中提供的非空基础字段
        if (StrUtil.isNotBlank(userName)) {
            question.setUserName(userName);
        }
        if (agentId != null) {
            question.setAgentId(agentId);
        }
        if (StrUtil.isNotBlank(resumeFileUrl)) {
            question.setResumeFileUrl(resumeFileUrl);
        }
        // （3）写入题目、建议、评分与面试方向
        setQuestions(question, questions);
        setSuggestions(question, suggestions);
        if (resumeScore != null) {
            question.setResumeScore(resumeScore);
        }
        if (StrUtil.isNotBlank(interviewType)) {
            question.setInterviewType(interviewType.trim());
        }
        // （4）原始响应为空时用简历上下文补齐，然后落库
        if ((question.getRawResponseData() == null || question.getRawResponseData().isBlank())
                && resumeContext != null
                && !resumeContext.isEmpty()) {
            question.setRawResponseData(JSON.toJSONString(resumeContext));
        }
        return saveInterviewQuestion(question);
    }

    /**
     * 解析 AI 响应中的结构化载荷，找不到候选对象时返回空 Map。
     */
    private Map<String, Object> parseStructuredPayload(String aiResponseData) {
        // （1）响应为空时返回空 Map
        if (StrUtil.isBlank(aiResponseData)) {
            return Collections.emptyMap();
        }
        try {
            // （2）解析 JSON 并优先定位包含结构化字段的对象
            Object parsed = JSON.parse(aiResponseData);
            Map<String, Object> candidate = findStructuredCandidate(parsed);
            if (candidate != null) {
                return candidate;
            }
            if (parsed instanceof JSONObject jsonObject) {
                Map<String, Object> normalized = InterviewJsonValueNormalizer.asMap(jsonObject);
                return normalized == null ? Collections.emptyMap() : normalized;
            }
        } catch (Exception ignored) {
            // Fallback to empty map.
        }
        // （3）无法解析时返回空 Map
        return Collections.emptyMap();
    }

    /**
     * 递归查找第一个包含结构化字段的 Map，兼容嵌套对象、数组与 JSON 字符串。
     */
    private Map<String, Object> findStructuredCandidate(Object value) {
        // （1）空值直接返回 null
        if (value == null) {
            return null;
        }
        // （2）统一转换为普通 Map/List 结构后递归查找
        if (value instanceof JSONObject jsonObject) {
            return findStructuredCandidate(InterviewJsonValueNormalizer.asMap(jsonObject));
        }
        if (value instanceof JSONArray jsonArray) {
            return findStructuredCandidate(InterviewJsonValueNormalizer.asList(jsonArray));
        }
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) rawMap);
            if (containsStructuredKey(map)) {
                return map;
            }
            for (Object nested : map.values()) {
                Map<String, Object> candidate = findStructuredCandidate(nested);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        if (value instanceof List<?> rawList) {
            for (Object nested : rawList) {
                Map<String, Object> candidate = findStructuredCandidate(nested);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        if (value instanceof String text) {
            if (StrUtil.isBlank(text) || (!text.contains("{") && !text.contains("["))) {
                return null;
            }
            try {
                return findStructuredCandidate(JSON.parse(text));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 判断 Map 是否包含题目、建议、评分或面试方向等结构化字段。
     */
    private boolean containsStructuredKey(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return false;
        }
        return map.containsKey("questions")
                || map.containsKey("sugest")
                || map.containsKey("suggestions")
                || map.containsKey("resumeScore")
                || map.containsKey("type")
                || map.containsKey("interviewType")
                || map.containsKey("direction")
                || map.containsKey("interviewDirection");
    }

    /**
     * 将任意值转换为去空白的字符串列表，兼容数组、JSON 字符串与分隔符文本。
     */
    private List<String> toStringList(Object value) {
        // （1）空值返回空列表
        if (value == null) {
            return Collections.emptyList();
        }
        // （2）List 类型直接逐项转换并过滤空白
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim())
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }
        if (value instanceof JSONArray jsonArray) {
            List<Object> normalizedList = InterviewJsonValueNormalizer.asList(jsonArray);
            if (normalizedList == null) {
                return Collections.emptyList();
            }
            return normalizedList.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim())
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }
        String raw = String.valueOf(value).trim();
        // （3）空字符串返回空列表
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        // （4）形如 JSON 数组时优先按 JSON 解析
        if (raw.startsWith("[") && raw.endsWith("]")) {
            try {
                List<Object> normalizedList = InterviewJsonValueNormalizer.asList(JSON.parse(raw));
                if (normalizedList == null) {
                    return Collections.emptyList();
                }
                return normalizedList.stream()
                        .map(item -> item == null ? null : String.valueOf(item).trim())
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toList());
            } catch (Exception ignored) {
                // Fallback to split.
            }
        }
        // （5）按中英文逗号、分号或换行切分
        String[] parts = raw.split("[,;\\uFF0C\\uFF1B\\n]+");
        return java.util.Arrays.stream(parts)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * 将任意值转换为整数，支持数字与数值字符串，失败返回 null。
     */
    private Integer toInteger(Object value) {
        // （1）空值返回 null
        if (value == null) {
            return null;
        }
        // （2）数字类型直接取整
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        // （3）空字符串返回 null
        if (StrUtil.isBlank(text)) {
            return null;
        }
        // （4）按浮点数解析后四舍五入
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从载荷中解析面试方向，兼容 type、interviewType、direction 等字段。
     */
    private String resolveInterviewType(Map<String, Object> payload) {
        // （1）载荷为空时返回 null
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        // （2）按优先级取第一个非空的候选字段
        Object value = firstNonNull(
                payload.get("type"),
                payload.get("interviewType"),
                payload.get("direction"),
                payload.get("interviewDirection")
        );
        if (value == null) {
            return null;
        }
        String interviewType = String.valueOf(value).trim();
        // （3）空白值返回 null
        return StrUtil.isBlank(interviewType) ? null : interviewType;
    }

    /**
     * 返回参数列表中第一个非 null 的值。
     */
    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 构建用于保存的面试题实体：复用已有记录并填充请求参数与 AI 原始响应。
     */
    private InterviewQuestion buildQuestionForUpsert(
            InterviewQuestionReqDTO reqDTO,
            Integer responseTime,
            Integer tokenCount,
            String aiResponseData) {
        String sessionId = reqDTO == null ? null : reqDTO.getSessionId();
        // （1）按会话查询已有实体，不存在则新建
        InterviewQuestion question = StrUtil.isNotBlank(sessionId) ? getBySessionId(sessionId) : null;
        if (question == null) {
            question = new InterviewQuestion();
            question.setSessionId(sessionId);
            question.setDelFlag(0);
        }

        // （2）覆盖写入请求中的非空基础字段
        if (reqDTO != null) {
            if (StrUtil.isNotBlank(reqDTO.getUserName())) {
                question.setUserName(reqDTO.getUserName());
            }
            if (reqDTO.getAgentId() != null) {
                question.setAgentId(reqDTO.getAgentId());
            }
            if (StrUtil.isNotBlank(reqDTO.getResumeFileUrl())) {
                question.setResumeFileUrl(reqDTO.getResumeFileUrl());
            }
        }
        // （3）记录响应耗时、token 消耗与原始响应
        question.setResponseTime(responseTime);
        question.setTokenCount(tokenCount);
        if (StrUtil.isNotBlank(aiResponseData)) {
            question.setRawResponseData(aiResponseData);
        }
        return question;
    }

    /**
     * 判断 AI 响应中是否提取到题目、建议、评分或面试方向等有效数据。
     */
    private boolean hasStructuredExtraction(
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType) {
        return (questions != null && !questions.isEmpty())
                || (suggestions != null && !suggestions.isEmpty())
                || resumeScore != null
                || StrUtil.isNotBlank(interviewType);
    }

    /**
     * 解析错误信息：优先取载荷中的错误字段，其次取较短的原始响应。
     */
    private String resolveErrorMessage(Map<String, Object> payload, String aiResponseData) {
        // （1）优先从载荷中提取 error、message 或 msg
        String fromPayload = asNonBlankString(firstNonNull(
                payload == null ? null : payload.get("error"),
                payload == null ? null : payload.get("message"),
                payload == null ? null : payload.get("msg")
        ));
        if (StrUtil.isNotBlank(fromPayload)) {
            return fromPayload;
        }
        // （2）原始响应过短时直接作为错误信息展示
        if (StrUtil.isBlank(aiResponseData)) {
            return null;
        }
        String normalized = aiResponseData.trim();
        if (normalized.length() > 500) {
            return null;
        }
        return normalized;
    }

    /**
     * 将值转换为去空白的字符串，空白时返回 null。
     */
    private String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StrUtil.isBlank(text) ? null : text;
    }

    /**
     * 写入题目列表，并同步按题号生成 JSON 映射字段。
     */
    private void setQuestions(InterviewQuestion question, List<String> questions) {
        // （1）题目为空时不做写入
        if (question == null || questions == null || questions.isEmpty()) {
            return;
        }
        // （2）写入列表字段并按题号生成 JSON 映射
        question.setQuestions(questions);
        Map<String, String> questionsMap = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            questionsMap.put(String.valueOf(i + 1), questions.get(i));
        }
        question.setQuestionsJson(JSON.toJSONString(questionsMap));
    }

    /**
     * 写入建议列表，并同步按编号生成 JSON 映射字段。
     */
    private void setSuggestions(InterviewQuestion question, List<String> suggestions) {
        // （1）建议为空时不做写入
        if (question == null || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        // （2）写入列表字段并按编号生成 JSON 映射
        question.setSuggestions(suggestions);
        Map<String, String> suggestionsMap = new LinkedHashMap<>();
        for (int i = 0; i < suggestions.size(); i++) {
            suggestionsMap.put(String.valueOf(i + 1), suggestions.get(i));
        }
        question.setSuggestionsJson(JSON.toJSONString(suggestionsMap));
    }

    /**
     * 将面试题实体转换为响应 DTO，并根据错误信息设置成功标记。
     */
    private InterviewQuestionRespDTO convertToRespDTO(InterviewQuestion question) {
        // （1）复制实体字段并转换响应 DTO
        InterviewQuestionRespDTO respDTO = new InterviewQuestionRespDTO();
        BeanUtils.copyProperties(question, respDTO);
        // （2）无错误信息视为成功，否则视为失败
        if (StrUtil.isBlank(question.getErrorMessage())) {
            respDTO.setIsSuccess(1);
        } else {
            respDTO.setIsSuccess(0);
        }
        return respDTO;
    }
}
