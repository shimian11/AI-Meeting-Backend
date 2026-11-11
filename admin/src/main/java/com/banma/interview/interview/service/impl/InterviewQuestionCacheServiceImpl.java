package com.banma.interview.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.api.io.req.DemeanorScoreDTO;
import com.banma.interview.interview.api.io.resp.RadarChartDTO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewRadarService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewScoreService;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 面试题缓存服务实现，负责面试题、建议、评分与流程状态的缓存读写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewQuestionCacheServiceImpl implements InterviewQuestionCacheService {

    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 得分累加与聚合 */
    private final InterviewScoreService interviewScoreService;
    /** 雷达图维度分值计算 */
    private final InterviewRadarService interviewRadarService;
    
    /**
     * 面试题缓存键前缀。
     */
    private static final String INTERVIEW_QUESTIONS_KEY = "interview:questions:session:";
    
    /**
     * 面试建议缓存键前缀。
     */
    private static final String INTERVIEW_SUGGESTIONS_KEY = "interview:suggestions:session:";
    
    /**
     * 简历评分缓存键前缀。
     */
    private static final String RESUME_SCORE_KEY = "interview:resume_score:session:";

    /**
     * 简历上下文缓存键前缀。
     */
    private static final String RESUME_CONTEXT_KEY = "interview:resume_context:session:";
    
    /**
     * 仪态评分缓存键前缀。
     */
    private static final String DEMEANOR_SCORE_KEY = "interview:demeanor_score:session:";
    
    /**
     * 面试方向缓存键前缀。
     */
    private static final String INTERVIEW_DIRECTION_KEY = "interview:direction:session:";

    /**
     * 面试流程状态缓存键前缀。
     */
    private static final String INTERVIEW_FLOW_KEY = "interview:flow:session:";

    /**
     * 追问问题缓存键前缀。
     */
    private static final String INTERVIEW_FOLLOW_UP_QUESTIONS_KEY = "interview:follow_up_questions:session:";

    /**
     * 面试回答请求 ID（幂等）缓存键前缀。
     */
    private static final String INTERVIEW_ANSWER_REQUEST_KEY = "interview:answer:req:session:";

    /**
     * 面试轮次日志缓存键前缀。
     */
    private static final String INTERVIEW_TURNS_KEY = "interview:turns:session:";
    /**
     * 轮次请求幂等标记缓存键前缀。
     */
    private static final String INTERVIEW_TURN_REQUEST_KEY = "interview:turn:req:session:";

    /**
     * 单个会话保留的最大轮次日志条数。
     */
    private static final int MAX_TURN_LOGS = 200;
    /**
     * 流程状态 CAS 更新的最大重试次数。
     */
    private static final int FLOW_CAS_MAX_RETRIES = 5;

    /**
     * 流程状态：初始化。
     */
    private static final String FLOW_STATUS_INIT = "INIT";
    /**
     * 流程状态：提问中。
     */
    private static final String FLOW_STATUS_ASKING = "ASKING";
    /**
     * 流程状态：评估中。
     */
    private static final String FLOW_STATUS_EVALUATING = "EVALUATING";
    /**
     * 流程状态：追问中。
     */
    private static final String FLOW_STATUS_FOLLOW_UP = "FOLLOW_UP";
    /**
     * 流程状态：已完成。
     */
    private static final String FLOW_STATUS_COMPLETED = "COMPLETED";

    /**
     * 流程状态 CAS 更新脚本：仅当版本号一致时才写入新状态并刷新过期时间。
     */
    private static final String FLOW_CAS_UPDATE_SCRIPT_TEXT =
            "local current = redis.call('HGET', KEYS[1], 'version') "
                    + "if current == false then current = '0' end "
                    + "if tostring(current) ~= tostring(ARGV[1]) then return 0 end "
                    + "redis.call('HSET', KEYS[1], "
                    + "'status', ARGV[2], "
                    + "'currentIndex', ARGV[3], "
                    + "'currentQuestionNumber', ARGV[4], "
                    + "'totalQuestions', ARGV[5], "
                    + "'followUpCount', ARGV[6], "
                    + "'maxFollowUp', ARGV[7], "
                    + "'version', ARGV[8]) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[9])) "
                    + "return 1";

    private static final DefaultRedisScript<Long> FLOW_CAS_UPDATE_SCRIPT = initFlowCasScript();
    
    /**
     * 缓存默认过期时间（小时）。
     */
    private static final long CACHE_EXPIRE_HOURS = 24;
    
    /**
     * 按题号写入面试题缓存，写入前清理旧数据。
     *
     * @param sessionId 会话 id
     * @param questions 面试题列表，按顺序从 1 开始编号
     */
    @Override
    public void cacheInterviewQuestions(String sessionId, List<String> questions) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            
            // 覆盖写入前先清理旧缓存，避免历史数据残留。
            stringRedisTemplate.delete(cacheKey);
            
            // 将题目列表转换为 Hash 结构（题号 -> 题目内容）。
            Map<String, String> questionMap = new HashMap<>();
            for (int i = 0; i < questions.size(); i++) {
                String questionNumber = String.valueOf(i + 1);
                questionMap.put(questionNumber, questions.get(i));
            }
            
            if (!questionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, questionMap);
                // 设置缓存过期时间。
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview questions, sessionId: {}, count: {}", sessionId, questions.size());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 按编号写入面试建议缓存，写入前清理旧数据。
     *
     * @param sessionId 会话 id
     * @param suggestions 面试建议列表
     */
    @Override
    public void cacheInterviewSuggestions(String sessionId, List<String> suggestions) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;

            stringRedisTemplate.delete(cacheKey);

            Map<String, String> suggestionMap = new HashMap<>();
            for (int i = 0; i < suggestions.size(); i++) {
                String suggestionNumber = String.valueOf(i + 1);
                suggestionMap.put(suggestionNumber, suggestions.get(i));
            }
            
            if (!suggestionMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(cacheKey, suggestionMap);
                stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            }
            
            log.info("Cached interview suggestions, sessionId: {}, count: {}", sessionId, suggestions.size());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 缓存会话的简历评分，覆盖旧值并设置过期时间。
     *
     * @param sessionId 会话 id
     * @param resumeScore 简历评分
     */
    @Override
    public void cacheResumeScore(String sessionId, Integer resumeScore) {
        try {
            // （1）清理旧缓存后写入新的简历评分
            String cacheKey = RESUME_SCORE_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);

            stringRedisTemplate.opsForValue().set(cacheKey, resumeScore.toString());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, resumeScore);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 缓存会话的仪态评分，覆盖旧值并设置过期时间。
     *
     * @param sessionId 会话 id
     * @param demeanorScore 仪态评分
     */
    @Override
    public void cacheDemeanorScore(String sessionId, Integer demeanorScore) {
        try {
            // （1）写入仪态评分并设置过期时间
            String cacheKey = DEMEANOR_SCORE_KEY + sessionId;
            stringRedisTemplate.opsForValue().set(cacheKey, demeanorScore.toString());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, demeanorScore);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 读取会话的面试题缓存，并按题号数值顺序返回。
     *
     * @param sessionId 会话 id
     * @return 题号到题目内容的映射，读取失败时返回空 Map
     */
    @Override
    public Map<String, String> getSessionInterviewQuestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 使用 LinkedHashMap，保证排序后遍历顺序稳定。
            Map<String, String> questionMap = new LinkedHashMap<>();
            
            // 按题号排序返回，避免 Hash 无序导致前端展示顺序混乱。
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 统一按字符串处理键，兼容不同序列化类型。
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 两个键都是数字时按数值排序（如 2 < 10）。
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 非数字键按字典序排序。
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 解析异常时回退到字符串比较，保证流程不中断。
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    questionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview questions from cache, sessionId: {}, count: {}", sessionId, questionMap.size());
            return questionMap;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * 读取会话的简历评分，缓存不存或解析失败时返回 null。
     *
     * @param sessionId 会话 id
     * @return 简历评分
     */
    @Override
    public Integer getSessionResumeScore(String sessionId) {
        try {
            String cacheKey = RESUME_SCORE_KEY + sessionId;
            String scoreStr = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(scoreStr)) {
                return Integer.parseInt(scoreStr);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get resume score, sessionId: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 缓存会话的简历上下文，缓存为空时改为删除对应键。
     *
     * @param sessionId 会话 id
     * @param resumeContext 简历上下文数据
     */
    @Override
    public void cacheResumeContext(String sessionId, Map<String, Object> resumeContext) {
        try {
            // （1）会话 id 为空时不做处理
            if (StrUtil.isBlank(sessionId)) {
                return;
            }
            String cacheKey = RESUME_CONTEXT_KEY + sessionId;
            // （2）上下文为空时清理旧缓存
            if (resumeContext == null || resumeContext.isEmpty()) {
                stringRedisTemplate.delete(cacheKey);
                return;
            }
            // （3）序列化上下文并写入缓存，设置过期时间
            String payload = JSON.toJSONString(resumeContext);
            stringRedisTemplate.opsForValue().set(cacheKey, payload);
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, resumeContext.keySet());
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 读取会话的简历上下文，无缓存或解析失败时返回空 Map。
     *
     * @param sessionId 会话 id
     * @return 简历上下文数据
     */
    @Override
    public Map<String, Object> getSessionResumeContext(String sessionId) {
        try {
            // （1）会话 id 为空时返回空 Map
            if (StrUtil.isBlank(sessionId)) {
                return new HashMap<>();
            }
            // （2）读取并反序列化缓存内容
            String cacheKey = RESUME_CONTEXT_KEY + sessionId;
            String payload = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isBlank(payload)) {
                return new HashMap<>();
            }
            Map<String, Object> parsed = JSON.parseObject(payload, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * 读取会话的仪态评分，异常时返回 null。
     *
     * @param sessionId 会话 id
     * @return 归一化后的仪态评分
     */
    @Override
    public Integer getSessionDemeanorScore(String sessionId) {
        try {
            return interviewScoreService.getSessionDemeanorScore(sessionId);
        } catch (Exception e) {
            log.error("Failed to get demeanor score, sessionId: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 缓存单个追问题目，题号与内容为空时直接跳过。
     *
     * @param sessionId 会话 id
     * @param questionNumber 题号
     * @param questionContent 追问题目内容
     */
    @Override
    public void cacheFollowUpQuestion(String sessionId, String questionNumber, String questionContent) {
        // （1）参数不完整时不写入缓存
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber) || StrUtil.isBlank(questionContent)) {
            return;
        }
        try {
            // （2）以题号为字段写入 Hash 并设置过期时间
            String cacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            stringRedisTemplate.opsForHash().put(cacheKey, questionNumber.trim(), questionContent.trim());
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Cached follow-up question, sessionId: {}, questionNumber: {}", sessionId, questionNumber);
        } catch (Exception e) {
            log.error("Failed to cache follow-up question, sessionId: {}, questionNumber: {}", sessionId, questionNumber, e);
        }
    }

    /**
     * 读取会话的全部追问题目，异常时返回已收集的部分结果。
     *
     * @param sessionId 会话 id
     * @return 题号到追问内容的映射
     */
    @Override
    public Map<String, String> getSessionFollowUpQuestions(String sessionId) {
        // （1）会话 id 为空时返回空 Map
        Map<String, String> followUpQuestionMap = new LinkedHashMap<>();
        if (StrUtil.isBlank(sessionId)) {
            return followUpQuestionMap;
        }
        try {
            // （2）读取 Hash 并过滤掉空的题号或内容
            String cacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            if (rawMap == null || rawMap.isEmpty()) {
                return followUpQuestionMap;
            }
            rawMap.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                String cachedQuestionNumber = key.toString();
                String cachedQuestionContent = value.toString();
                if (StrUtil.isNotBlank(cachedQuestionNumber) && StrUtil.isNotBlank(cachedQuestionContent)) {
                    followUpQuestionMap.put(cachedQuestionNumber, cachedQuestionContent);
                }
            });
            return followUpQuestionMap;
        } catch (Exception e) {
            log.error("Failed to get follow-up questions, sessionId: {}", sessionId, e);
            return followUpQuestionMap;
        }
    }

    /**
     * 读取会话的面试建议缓存，并按编号数值顺序返回。
     *
     * @param sessionId 会话 id
     * @return 编号到建议内容的映射，读取失败时返回空 Map
     */
    @Override
    public Map<String, String> getSessionInterviewSuggestions(String sessionId) {
        try {
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(cacheKey);
            
            // 使用 LinkedHashMap，保证排序后遍历顺序稳定。
            Map<String, String> suggestionMap = new LinkedHashMap<>();
            
            // 按编号排序返回，避免 Hash 无序导致前端展示顺序混乱。
            rawMap.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    try {
                        // 统一按字符串处理键，兼容不同序列化类型。
                        String key1 = entry1.getKey().toString();
                        String key2 = entry2.getKey().toString();
                        
                        // 两个键都是数字时按数值排序（如 2 < 10）。
                        if (key1.matches("\\d+") && key2.matches("\\d+")) {
                            return Integer.compare(Integer.parseInt(key1), Integer.parseInt(key2));
                        }
                        // 非数字键按字典序排序。
                        return key1.compareTo(key2);
                    } catch (NumberFormatException e) {
                        // 解析异常时回退到字符串比较，保证流程不中断。
                        return entry1.getKey().toString().compareTo(entry2.getKey().toString());
                    }
                })
                .forEach(entry -> {
                    suggestionMap.put(entry.getKey().toString(), entry.getValue().toString());
                });
            
            log.info("Loaded interview suggestions from cache, sessionId: {}, count: {}", sessionId, suggestionMap.size());
            return suggestionMap;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * 按题号查询题目：优先取正式题目，未命中时回退追问题目。
     *
     * @param sessionId 会话 id
     * @param questionNumber 题号
     * @return 题目内容，未找到时返回 null
     */
    @Override
    public String getQuestionByNumber(String sessionId, String questionNumber) {
        try {
            // （1）优先从正式题目缓存中查询
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            Object question = stringRedisTemplate.opsForHash().get(cacheKey, questionNumber);
            // （2）正式题目未命中时回退到追问题目缓存
            if (question == null) {
                String followUpCacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
                question = stringRedisTemplate.opsForHash().get(followUpCacheKey, questionNumber);
            }
            return question != null ? question.toString() : null;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, questionNumber, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 清理会话的面试题与追问题目缓存。
     *
     * @param sessionId 会话 id
     */
    @Override
    public void clearSessionQuestions(String sessionId) {
        try {
            // （1）同时删除正式题目与追问题目两个缓存键
            String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
            String followUpCacheKey = INTERVIEW_FOLLOW_UP_QUESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(List.of(cacheKey, followUpCacheKey));
            log.info("Cleared interview question cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 清理会话的面试建议缓存。
     *
     * @param sessionId 会话 id
     */
    @Override
    public void clearSessionSuggestions(String sessionId) {
        try {
            // （1）删除面试建议缓存键
            String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
            stringRedisTemplate.delete(cacheKey);
            log.info("Cleared interview suggestion cache, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载面试题到缓存。
     * 优先解析 questionsJson，解析失败时回退到 questions 列表。
     */
    public void loadInterviewQuestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview question data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 优先使用 JSON 字段恢复题目（保留题号）。
            if (StrUtil.isNotBlank(question.getQuestionsJson())) {
                try {
                    Map<String, String> questionsMap = JSON.parseObject(
                        question.getQuestionsJson(), 
                        new TypeReference<LinkedHashMap<String, String>>() {}
                    );
                    
                    String cacheKey = INTERVIEW_QUESTIONS_KEY + sessionId;
                    stringRedisTemplate.delete(cacheKey);
                    
                    if (!questionsMap.isEmpty()) {
                        stringRedisTemplate.opsForHash().putAll(cacheKey, questionsMap);
                        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
                    }
                    
                    log.info("Interview cache service message", sessionId, questionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("Interview cache service message", e.getMessage());
                }
            }
            
            // JSON 不可用时回退到列表字段写入缓存。
            if (question.getQuestions() != null && !question.getQuestions().isEmpty()) {
                cacheInterviewQuestions(sessionId, question.getQuestions());
                log.info("Interview cache service message", sessionId, question.getQuestions().size());
            }
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载面试建议到缓存。
     * 优先解析 suggestionsJson，解析失败时回退到 suggestions 列表。
     */
    public void loadInterviewSuggestionsFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null) {
                log.warn("Interview suggestion data not found, sessionId: {}", sessionId);
                return;
            }
            
            // 优先使用 JSON 字段恢复建议（保留编号）。
            if (StrUtil.isNotBlank(question.getSuggestionsJson())) {
                try {
                    Map<String, String> suggestionsMap = JSON.parseObject(
                        question.getSuggestionsJson(), 
                        new TypeReference<LinkedHashMap<String, String>>() {}
                    );
                    
                    String cacheKey = INTERVIEW_SUGGESTIONS_KEY + sessionId;
                    stringRedisTemplate.delete(cacheKey);
                    
                    if (!suggestionsMap.isEmpty()) {
                        stringRedisTemplate.opsForHash().putAll(cacheKey, suggestionsMap);
                        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
                    }
                    
                    log.info("Interview cache service message", sessionId, suggestionsMap.size());
                    return;
                } catch (Exception e) {
                    log.warn("Interview cache service message", e.getMessage());
                }
            }
            
            // JSON 不可用时回退到列表字段写入缓存。
            if (question.getSuggestions() != null && !question.getSuggestions().isEmpty()) {
                cacheInterviewSuggestions(sessionId, question.getSuggestions());
                log.info("Interview cache service message", sessionId, question.getSuggestions().size());
            }
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 从数据库加载简历评分到缓存。
     */
    public void loadResumeScoreFromDatabase(String sessionId) {
        try {
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            if (question == null || question.getResumeScore() == null) {
                log.warn("Resume score data not found, sessionId: {}", sessionId);
                return;
            }
            
            cacheResumeScore(sessionId, question.getResumeScore());
            log.info("Interview cache service message", sessionId, question.getResumeScore());
            
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 获取会话的面试平均分，异常时返回 0。
     *
     * @param sessionId 会话 id
     * @return 面试平均分
     */
    @Override
    public Integer getSessionTotalScore(String sessionId) {
        try {
            return interviewScoreService.getSessionTotalScore(sessionId, getInterviewTurns(sessionId));
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 累加一次答题分数并返回更新后的平均分，异常时回退为读取当前总分。
     *
     * @param sessionId 会话 id
     * @param score 本次得分
     * @return 更新后的平均分
     */
    @Override
    public Integer addSessionScore(String sessionId, Integer score) {
        try {
            // （1）调用评分服务累加分数并返回最新平均分
            Integer total = interviewScoreService.addSessionScore(sessionId, score);
            log.info("Interview cache service message", sessionId, score, total);
            return total;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, score, e.getMessage(), e);
            return getSessionTotalScore(sessionId);
        }
    }

    /**
     * 重置会话的面试分数统计，删除平均分、总分与计数缓存。
     *
     * @param sessionId 会话 id
     */
    @Override
    public void resetSessionScore(String sessionId) {
        try {
            // （1）调用评分服务清理该会话的分数缓存
            interviewScoreService.resetSessionScore(sessionId);
            log.info("Reset session score, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 汇总简历、面试与仪态评分生成雷达图数据，异常时返回全 0 的默认图表。
     *
     * @param sessionId 会话 id
     * @return 雷达图数据
     */
    @Override
    public RadarChartDTO getRadarChartData(String sessionId) {
        try {
            // （1）读取三个维度的评分
            Integer resumeScore = getSessionResumeScore(sessionId);
            Integer interviewScore = getSessionTotalScore(sessionId);
            Integer demeanorScore = getSessionDemeanorScore(sessionId);
            // （2）委托雷达服务计算各维度得分
            return interviewRadarService.buildRadarChart(resumeScore, interviewScore, demeanorScore);
        } catch (Exception e) {
            log.error("Failed to get radar chart data, sessionId: {}", sessionId, e);
            RadarChartDTO defaultChart = new RadarChartDTO();
            defaultChart.setResumeScore(0);
            defaultChart.setInterviewPerformance(0);
            defaultChart.setDemeanorEvaluation(0);
            defaultChart.setProfessionalSkills(0);
            defaultChart.setPotentialIndex(0);
            return defaultChart;
        }
    }

    /**
     * 缓存仪态各维度评分（紧张度、认真度、表情处理与综合分）。
     */
    @Override
    public void cacheDemeanorScoreDetails(String sessionId, Integer panicLevel, Integer seriousnessLevel, 
                                          Integer emoticonHandling, Integer compositeScore) {
        try {
            // （1）分别写入四个维度的评分
            String panicKey = "demeanor:panic:" + sessionId;
            String seriousnessKey = "demeanor:seriousness:" + sessionId;
            String emoticonKey = "demeanor:emoticon:" + sessionId;
            String compositeKey = "demeanor:composite:" + sessionId;
            
            stringRedisTemplate.opsForValue().set(panicKey, panicLevel.toString());
            stringRedisTemplate.opsForValue().set(seriousnessKey, seriousnessLevel.toString());
            stringRedisTemplate.opsForValue().set(emoticonKey, emoticonHandling.toString());
            stringRedisTemplate.opsForValue().set(compositeKey, compositeScore.toString());
            
            // 四个维度评分统一设置过期时间。
            stringRedisTemplate.expire(panicKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(seriousnessKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(emoticonKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            stringRedisTemplate.expire(compositeKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            
            log.info("Cached demeanor score details, sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 读取仪态各维度评分，异常时返回全 0 的默认结果。
     *
     * @param sessionId 会话 id
     * @return 仪态评分明细
     */
    @Override
    public DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId) {
        try {
            return interviewScoreService.getSessionDemeanorScoreDetails(sessionId);
        } catch (Exception e) {
            log.error("Failed to get demeanor detail scores, sessionId: {}", sessionId, e);
            DemeanorScoreDTO defaultScore = new DemeanorScoreDTO();
            defaultScore.setPanicLevel(0);
            defaultScore.setSeriousnessLevel(0);
            defaultScore.setEmoticonHandling(0);
            defaultScore.setCompositeScore(0);
            return defaultScore;
        }
    }

    /**
     * 缓存会话的面试方向。
     *
     * @param sessionId 会话 id
     * @param interviewDirection 面试方向
     */
    @Override
    public void cacheInterviewDirection(String sessionId, String interviewDirection) {
        try {
            // （1）写入面试方向并设置过期时间
            String cacheKey = INTERVIEW_DIRECTION_KEY + sessionId;
            stringRedisTemplate.opsForValue().set(cacheKey, interviewDirection);
            // 设置缓存过期时间。
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Interview cache service message", sessionId, interviewDirection);
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * 读取会话的面试方向，缓存未命中时从数据库回源并写回缓存。
     *
     * @param sessionId 会话 id
     * @return 面试方向，无法获取时返回 null
     */
    @Override
    public String getSessionInterviewDirection(String sessionId) {
        try {
            // （1）优先读取缓存中的面试方向
            String cacheKey = INTERVIEW_DIRECTION_KEY + sessionId;
            String direction = stringRedisTemplate.opsForValue().get(cacheKey);
            // （2）缓存未命中时从数据库回源并写回缓存
            if (StrUtil.isBlank(direction)) {
                InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
                if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
                    direction = question.getInterviewType().trim();
                    cacheInterviewDirection(sessionId, direction);
                }
            }
            log.info("Interview cache service message", sessionId, direction);
            return direction;
        } catch (Exception e) {
            log.error("Interview cache service message", sessionId, e.getMessage(), e);
            return null;
        }
    }
    /**
     * 初始化会话的面试流程状态，并切换为提问中。
     *
     * @param sessionId 会话 id
     * @param totalQuestions 题目总数
     */
    @Override
    public void initInterviewFlow(String sessionId, Integer totalQuestions) {
        // （1）参数非法时不初始化流程
        if (StrUtil.isBlank(sessionId) || totalQuestions == null || totalQuestions <= 0) {
            return;
        }
        // （2）构造初始流程状态并写入缓存
        InterviewFlowState state = new InterviewFlowState();
        state.setStatus(FLOW_STATUS_INIT);
        state.setCurrentIndex(0);
        state.setCurrentQuestionNumber("1");
        state.setTotalQuestions(totalQuestions);
        state.setFollowUpCount(0);
        state.setMaxFollowUp(2);
        state.setVersion(1);
        saveFlowState(sessionId, state);
        // （3）将流程状态切换为提问中
        updateInterviewFlowStatus(sessionId, FLOW_STATUS_ASKING);
    }

    /**
     * 读取会话的面试流程状态，无缓存或读取失败时返回 null。
     *
     * @param sessionId 会话 id
     * @return 面试流程状态
     */
    @Override
    public InterviewFlowState getInterviewFlow(String sessionId) {
        // （1）会话 id 为空时直接返回 null
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            // （2）读取 Hash 缓存并逐字段还原流程状态
            String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(cacheKey);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            InterviewFlowState state = new InterviewFlowState();
            state.setStatus(normalizeFlowStatus(asString(entries.get("status"), FLOW_STATUS_INIT)));
            state.setCurrentIndex(asInt(entries.get("currentIndex"), 0));
            state.setCurrentQuestionNumber(asString(entries.get("currentQuestionNumber"), null));
            state.setTotalQuestions(asInt(entries.get("totalQuestions"), 0));
            state.setFollowUpCount(asInt(entries.get("followUpCount"), 0));
            state.setMaxFollowUp(asInt(entries.get("maxFollowUp"), 2));
            state.setVersion(asInt(entries.get("version"), 1));
            return state;
        } catch (Exception e) {
            log.error("Failed to get interview flow state, sessionId: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 更新面试流程状态，参数为空时直接返回。
     *
     * @param sessionId 会话 id
     * @param status 目标流程状态
     */
    @Override
    public void updateInterviewFlowStatus(String sessionId, String status) {
        // （1）参数不完整时不做更新
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(status)) {
            return;
        }
        // （2）通过 CAS 修改流程状态
        mutateFlowState(sessionId, state -> state.setStatus(status));
    }

    /**
     * 追问次数加一，并把流程状态置为追问中。
     *
     * @param sessionId 会话 id
     * @return 更新后的流程状态
     */
    @Override
    public InterviewFlowState incrementFollowUpCount(String sessionId) {
        // （1）在 CAS 更新中累加追问次数并切换状态
        return mutateFlowState(sessionId, state -> {
            state.setFollowUpCount((state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()) + 1);
            state.setStatus(FLOW_STATUS_FOLLOW_UP);
        });
    }

    /**
     * 开始一道追问题：累加追问次数并记录当前追问题号。
     *
     * @param sessionId 会话 id
     * @param questionNumber 追问题号
     * @return 更新后的流程状态，题号为空时返回 null
     */
    @Override
    public InterviewFlowState startFollowUpQuestion(String sessionId, String questionNumber) {
        // （1）题号为空时不推进流程
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        // （2）在 CAS 更新中累加追问次数并记录追问题号
        return mutateFlowState(sessionId, state -> {
            state.setFollowUpCount((state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()) + 1);
            state.setStatus(FLOW_STATUS_FOLLOW_UP);
            state.setCurrentQuestionNumber(questionNumber.trim());
        });
    }

    /**
     * 推进到下一条问题：重置追问次数，越界时标记流程完成。
     *
     * @param sessionId 会话 id
     * @return 更新后的流程状态
     */
    @Override
    public InterviewFlowState advanceToNextQuestion(String sessionId) {
        // （1）在 CAS 更新中计算下一题索引并重置追问次数
        return mutateFlowState(sessionId, state -> {
            int currentIndex = state.getCurrentIndex() == null ? 0 : state.getCurrentIndex();
            int totalQuestions = state.getTotalQuestions() == null ? 0 : state.getTotalQuestions();
            int nextIndex = currentIndex + 1;
            state.setFollowUpCount(0);

            if (totalQuestions <= 0 || nextIndex >= totalQuestions) {
                state.setStatus(FLOW_STATUS_COMPLETED);
                state.setCurrentIndex(Math.max(currentIndex, 0));
                state.setCurrentQuestionNumber(null);
            } else {
                state.setCurrentIndex(nextIndex);
                state.setStatus(FLOW_STATUS_ASKING);
                state.setCurrentQuestionNumber(String.valueOf(nextIndex + 1));
            }
        });
    }

    /**
     * 标记面试流程已完成，清空当前题号与追问次数。
     *
     * @param sessionId 会话 id
     * @return 更新后的流程状态
     */
    @Override
    public InterviewFlowState markInterviewCompleted(String sessionId) {
        // （1）在 CAS 更新中置为完成状态并清空题目信息
        return mutateFlowState(sessionId, state -> {
            state.setStatus(FLOW_STATUS_COMPLETED);
            state.setCurrentQuestionNumber(null);
            state.setFollowUpCount(0);
        });
    }

    /**
     * 用给定快照覆盖恢复会话的流程状态。
     *
     * @param sessionId 会话 id
     * @param flowState 流程状态快照
     */
    @Override
    public void restoreInterviewFlow(String sessionId, InterviewFlowState flowState) {
        // （1）参数不完整时不做恢复
        if (StrUtil.isBlank(sessionId) || flowState == null) {
            return;
        }
        try {
            // （2）直接写入状态快照，失败仅记录日志
            saveFlowState(sessionId, flowState);
        } catch (Exception ex) {
            log.error("Failed to restore interview flow, sessionId: {}", sessionId, ex);
        }
    }

    /**
     * 记录回答请求 id 用于幂等：首次写入返回 true，重复请求返回 false。
     *
     * @param sessionId 会话 id
     * @param requestId 回答请求 id
     * @return 是否为首次处理；参数缺失或异常时按已处理返回 true
     */
    @Override
    public boolean markAnswerRequestProcessed(String sessionId, String requestId) {
        // （1）参数缺失时视为已处理，避免重复流程
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return true;
        }
        try {
            // （2）用 Set 记录请求 id，返回新增数量判断是否首次
            String cacheKey = INTERVIEW_ANSWER_REQUEST_KEY + sessionId;
            Long added = stringRedisTemplate.opsForSet().add(cacheKey, requestId);
            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return added != null && added > 0;
        } catch (Exception e) {
            log.error("Failed to record answer request id, sessionId: {}, requestId: {}", sessionId, requestId, e);
            return true;
        }
    }

    /**
     * 追加一条面试轮次日志。
     *
     * @param sessionId 会话 id
     * @param turnData 轮次日志
     */
    @Override
    public void appendInterviewTurn(String sessionId, InterviewTurnLog turnData) {
        appendInterviewTurnInternal(sessionId, turnData);
    }

    /**
     * 按 requestId 幂等追加轮次日志，重复请求不会重复写入。
     *
     * @param sessionId 会话 id
     * @param turnData 轮次日志
     * @return 是否处理成功或已处理过，参数缺失返回 false
     */
    @Override
    public boolean appendInterviewTurnIfAbsent(String sessionId, InterviewTurnLog turnData) {
        // （1）参数缺失时直接返回 false
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return false;
        }
        String requestId = turnData.getRequestId();
        // （2）无 requestId 时退化为普通追加
        if (StrUtil.isBlank(requestId)) {
            return appendInterviewTurnInternal(sessionId, turnData);
        }
        String requestKey = INTERVIEW_TURN_REQUEST_KEY + sessionId;
        try {
            // （3）先登记 requestId，已存在说明重复请求
            Long added = stringRedisTemplate.opsForSet().add(requestKey, requestId);
            stringRedisTemplate.expire(requestKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            if (added == null || added <= 0) {
                return true;
            }
            // （4）追加成功返回 true，失败则回滚幂等标记
            if (appendInterviewTurnInternal(sessionId, turnData)) {
                return true;
            }
            stringRedisTemplate.opsForSet().remove(requestKey, requestId);
            return false;
        } catch (Exception e) {
            log.error("Failed to append interview turn idempotently, sessionId: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 读取会话的全部轮次日志，单条解析失败时跳过。
     *
     * @param sessionId 会话 id
     * @return 轮次日志列表
     */
    @Override
    public List<InterviewTurnLog> getInterviewTurns(String sessionId) {
        // （1）会话 id 为空时返回空列表
        if (StrUtil.isBlank(sessionId)) {
            return new ArrayList<>();
        }
        try {
            // （2）读取列表缓存并逐条反序列化
            String cacheKey = INTERVIEW_TURNS_KEY + sessionId;
            List<String> rawTurns = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
            if (rawTurns == null || rawTurns.isEmpty()) {
                return new ArrayList<>();
            }

            List<InterviewTurnLog> turns = new ArrayList<>();
            for (String rawTurn : rawTurns) {
                if (StrUtil.isBlank(rawTurn)) {
                    continue;
                }
                try {
                    InterviewTurnLog parsed = JSON.parseObject(rawTurn, new TypeReference<InterviewTurnLog>() {
                    });
                    if (parsed != null) {
                        turns.add(parsed);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to parse interview turn item, sessionId: {}", sessionId, ex);
                }
            }
            return turns;
        } catch (Exception e) {
            log.error("Failed to get interview turns, sessionId: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 向轮次列表追加一条日志，超出上限时裁剪旧数据。
     *
     * @return 是否追加成功
     */
    private boolean appendInterviewTurnInternal(String sessionId, InterviewTurnLog turnData) {
        // （1）参数缺失时不追加
        if (StrUtil.isBlank(sessionId) || turnData == null) {
            return false;
        }
        try {
            // （2）序列化后追加到列表尾部
            String cacheKey = INTERVIEW_TURNS_KEY + sessionId;
            String payload = JSON.toJSONString(turnData);
            stringRedisTemplate.opsForList().rightPush(cacheKey, payload);

            // （3）超出最大条数时裁剪最旧的记录，并刷新过期时间
            Long size = stringRedisTemplate.opsForList().size(cacheKey);
            if (size != null && size > MAX_TURN_LOGS) {
                long start = size - MAX_TURN_LOGS;
                stringRedisTemplate.opsForList().trim(cacheKey, start, -1);
            }

            stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return true;
        } catch (Exception e) {
            log.error("Failed to append interview turn, sessionId: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 以 CAS 方式修改流程状态：读取当前状态、应用变更、按版本号重试写入。
     *
     * @param sessionId 会话 id
     * @param mutator 状态修改回调
     * @return 更新后的流程状态，重试耗尽时返回最新状态
     */
    private InterviewFlowState mutateFlowState(String sessionId, Consumer<InterviewFlowState> mutator) {
        // （1）参数缺失时直接返回 null
        if (StrUtil.isBlank(sessionId) || mutator == null) {
            return null;
        }
        // （2）在最大重试次数内执行读取、修改与 CAS 写入
        for (int retry = 0; retry < FLOW_CAS_MAX_RETRIES; retry++) {
            InterviewFlowState state = getInterviewFlow(sessionId);
            if (state == null) {
                return null;
            }
            Integer expectedVersion = state.getVersion() == null ? 0 : Math.max(state.getVersion(), 0);
            mutator.accept(state);
            state.setVersion(expectedVersion + 1);
            if (saveFlowStateWithCas(sessionId, expectedVersion, state)) {
                return state;
            }
        }
        // （3）重试耗尽时记录告警并返回当前最新状态
        log.warn("Flow CAS update retries exhausted, sessionId={}", sessionId);
        return getInterviewFlow(sessionId);
    }

    /**
     * 直接覆盖写入流程状态（非 CAS）。
     */
    private void saveFlowState(String sessionId, InterviewFlowState state) {
        // （1）构建 Hash 字段并整体写入，同时设置过期时间
        String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
        Map<String, String> payload = buildFlowPayload(state);
        stringRedisTemplate.opsForHash().putAll(cacheKey, payload);
        stringRedisTemplate.expire(cacheKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 按版本号执行 CAS 写入流程状态。
     *
     * @param expectedVersion 期望的当前版本号
     * @return 写入成功返回 true，版本不一致返回 false
     */
    private boolean saveFlowStateWithCas(String sessionId, Integer expectedVersion, InterviewFlowState state) {
        // （1）构建字段载荷并执行 Lua 脚本
        String cacheKey = INTERVIEW_FLOW_KEY + sessionId;
        Map<String, String> payload = buildFlowPayload(state);
        Long result = stringRedisTemplate.execute(
                FLOW_CAS_UPDATE_SCRIPT,
                Collections.singletonList(cacheKey),
                String.valueOf(expectedVersion == null ? 0 : expectedVersion),
                payload.get("status"),
                payload.get("currentIndex"),
                payload.get("currentQuestionNumber"),
                payload.get("totalQuestions"),
                payload.get("followUpCount"),
                payload.get("maxFollowUp"),
                payload.get("version"),
                String.valueOf(TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS))
        );
        return result != null && result > 0;
    }

    /**
     * 将流程状态对象转换为 Redis Hash 字段载荷，空值使用默认值。
     */
    private Map<String, String> buildFlowPayload(InterviewFlowState state) {
        // （1）逐字段转换，并为可空字段兜底默认值
        Map<String, String> payload = new HashMap<>();
        payload.put("status", normalizeFlowStatus(asString(state.getStatus(), FLOW_STATUS_INIT)));
        payload.put("currentIndex", String.valueOf(state.getCurrentIndex() == null ? 0 : state.getCurrentIndex()));
        payload.put("currentQuestionNumber", asString(state.getCurrentQuestionNumber(), ""));
        payload.put("totalQuestions", String.valueOf(state.getTotalQuestions() == null ? 0 : state.getTotalQuestions()));
        payload.put("followUpCount", String.valueOf(state.getFollowUpCount() == null ? 0 : state.getFollowUpCount()));
        payload.put("maxFollowUp", String.valueOf(state.getMaxFollowUp() == null ? 2 : state.getMaxFollowUp()));
        payload.put("version", String.valueOf(state.getVersion() == null ? 1 : state.getVersion()));
        return payload;
    }

    /**
     * 规范化流程状态：去空格转大写，非法值回退为 INIT。
     */
    private String normalizeFlowStatus(String status) {
        // （1）空状态回退为初始化状态
        if (StrUtil.isBlank(status)) {
            return FLOW_STATUS_INIT;
        }
        // （2）仅接受已知状态值，其余回退为初始化状态
        String normalized = status.trim().toUpperCase();
        if (FLOW_STATUS_INIT.equals(normalized)
                || FLOW_STATUS_ASKING.equals(normalized)
                || FLOW_STATUS_EVALUATING.equals(normalized)
                || FLOW_STATUS_FOLLOW_UP.equals(normalized)
                || FLOW_STATUS_COMPLETED.equals(normalized)) {
            return normalized;
        }
        return FLOW_STATUS_INIT;
    }

    /**
     * 初始化流程状态 CAS 更新脚本对象。
     */
    private static DefaultRedisScript<Long> initFlowCasScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(FLOW_CAS_UPDATE_SCRIPT_TEXT);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 将缓存值转换为整数，无法解析时返回默认值。
     */
    private Integer asInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * 将缓存值转换为字符串，空值或空白返回默认值。
     */
    private String asString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString();
        return StrUtil.isBlank(str) ? defaultValue : str;
    }
}
