package com.banma.interview.interview.service.impl;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.api.io.req.DemeanorScoreDTO;
import com.banma.interview.interview.service.strategy.DemeanorNormalizationStrategy;
import com.banma.interview.interview.service.strategy.InterviewScoreAggregatorStrategy;
import com.banma.interview.interview.service.InterviewScoreService;
import com.banma.interview.interview.service.cache.InterviewCacheKeys;
import com.banma.interview.interview.service.cache.InterviewCacheStore;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 面试评分服务实现：维护会话分数聚合缓存，并归一化仪态各维度评分。
 */
@Service
@RequiredArgsConstructor
public class InterviewScoreServiceImpl implements InterviewScoreService {

    /**
     * 分数缓存过期时间（小时）。
     */
    private static final long CACHE_EXPIRE_HOURS = 24L;
    // 用 Lua 一次完成 sum/count/avg 更新，降低多 key 分步写入造成的数据漂移窗口。
    private static final String SCORE_AGGREGATE_UPDATE_SCRIPT_TEXT =
            "local sum = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1])) "
                    + "local cnt = redis.call('INCRBY', KEYS[2], 1) "
                    + "local avg = math.floor((sum / cnt) + 0.5) "
                    + "redis.call('SET', KEYS[3], tostring(avg)) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "
                    + "redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2])) "
                    + "redis.call('EXPIRE', KEYS[3], tonumber(ARGV[2])) "
                    + "return {sum, cnt, avg}";
    private static final DefaultRedisScript<List> SCORE_AGGREGATE_UPDATE_SCRIPT = initScoreAggregateScript();

    /** 缓存存储抽象 */
    private final InterviewCacheStore interviewCacheStore;
    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 总分聚合策略 */
    private final InterviewScoreAggregatorStrategy scoreAggregatorStrategy;
    /** 神态分值归一化策略（0-10 与 0-100 互转） */
    private final DemeanorNormalizationStrategy demeanorNormalizationStrategy;

    /**
     * 获取会话面试的平均分：优先读缓存，缓存缺失时按轮次日志推导。
     *
     * @param sessionId 会话 id
     * @param turns 面试轮次日志
     * @return 面试平均分，无数据时返回 0
     */
    @Override
    public Integer getSessionTotalScore(String sessionId, List<InterviewTurnLog> turns) {
        // （1）会话 id 为空时返回 0
        if (StrUtil.isBlank(sessionId)) {
            return 0;
        }

        // （2）优先读取缓存中的平均分
        Integer cachedAverage = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.sessionScore(sessionId)));
        if (cachedAverage != null) {
            return scoreAggregatorStrategy.clampScore(cachedAverage);
        }

        // （3）缓存缺失时按轮次日志推导平均分
        Integer derivedAverage = scoreAggregatorStrategy.averageFromTurns(turns);
        return derivedAverage != null ? derivedAverage : 0;
    }

    /**
     * 累加一次答题分数并返回最新平均分：优先走 Lua 原子脚本，失败时退化到分步写入。
     *
     * @param sessionId 会话 id
     * @param score 本次得分
     * @return 更新后的平均分
     */
    @Override
    public Integer addSessionScore(String sessionId, Integer score) {
        // （1）会话 id 为空时返回 0，并先截断本次分数
        if (StrUtil.isBlank(sessionId)) {
            return 0;
        }

        int safeScore = scoreAggregatorStrategy.clampScore(score == null ? 0 : score);
        String scoreSumKey = InterviewCacheKeys.sessionScoreSum(sessionId);
        String scoreCountKey = InterviewCacheKeys.sessionScoreCount(sessionId);
        String scoreKey = InterviewCacheKeys.sessionScore(sessionId);
        long ttlSeconds = TimeUnit.HOURS.toSeconds(CACHE_EXPIRE_HOURS);

        try {
            // 主路径：原子更新聚合分数。
            List<?> scriptResult = stringRedisTemplate.execute(
                    SCORE_AGGREGATE_UPDATE_SCRIPT,
                    List.of(scoreSumKey, scoreCountKey, scoreKey),
                    String.valueOf(safeScore),
                    String.valueOf(ttlSeconds)
            );
            Integer averageFromScript = parseAverageFromScriptResult(scriptResult);
            if (averageFromScript != null) {
                return scoreAggregatorStrategy.clampScore(averageFromScript);
            }
        } catch (Exception ignored) {
            // 兼容路径：脚本不可用时退化到旧逻辑，优先保证可用性。
        }

        // 旧逻辑兜底：不是强原子，但可在脚本异常时继续服务。
        Long scoreSum = interviewCacheStore.increment(scoreSumKey, safeScore);
        Long answerCount = interviewCacheStore.increment(scoreCountKey, 1L);
        int averagedScore = scoreAggregatorStrategy.averageFromAggregate(scoreSum, answerCount);
        interviewCacheStore.setValue(scoreKey, String.valueOf(averagedScore), CACHE_EXPIRE_HOURS);
        interviewCacheStore.expire(scoreSumKey, CACHE_EXPIRE_HOURS);
        interviewCacheStore.expire(scoreCountKey, CACHE_EXPIRE_HOURS);
        return scoreAggregatorStrategy.clampScore(averagedScore);
    }

    /**
     * 重置会话的面试分数缓存（平均分、总分与答题数）。
     *
     * @param sessionId 会话 id
     */
    @Override
    public void resetSessionScore(String sessionId) {
        // （1）会话 id 为空时不做处理
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        // （2）删除三个关联的分数缓存键
        interviewCacheStore.deleteKeys(List.of(
                InterviewCacheKeys.sessionScore(sessionId),
                InterviewCacheKeys.sessionScoreSum(sessionId),
                InterviewCacheKeys.sessionScoreCount(sessionId)
        ));
    }

    /**
     * 获取会话的仪态综合分，并根据各维度分制自动归一化到百分制。
     *
     * @param sessionId 会话 id
     * @return 归一化后的仪态分，无缓存时返回 null
     */
    @Override
    public Integer getSessionDemeanorScore(String sessionId) {
        // （1）会话 id 为空时返回 null
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }

        // （2）读取原始综合分，缺失则直接返回 null
        Integer rawScore = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorScore(sessionId)));
        if (rawScore == null) {
            return null;
        }

        // （3）读取各维度分判断是否 10 分制，并归一化综合分
        Integer panic = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorPanic(sessionId)));
        Integer seriousness = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorSeriousness(sessionId)));
        Integer emoticon = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorEmoticon(sessionId)));
        boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(panic, seriousness, emoticon, rawScore);
        return demeanorNormalizationStrategy.normalize(rawScore, tenScaleDetected);
    }

    /**
     * 获取仪态各维度评分明细，并统一归一化到百分制。
     *
     * @param sessionId 会话 id
     * @return 仪态评分明细，无数据时各维度为 0
     */
    @Override
    public DemeanorScoreDTO getSessionDemeanorScoreDetails(String sessionId) {
        // （1）初始化返回对象，会话 id 为空时直接返回
        DemeanorScoreDTO detail = new DemeanorScoreDTO();
        if (StrUtil.isBlank(sessionId)) {
            return detail;
        }

        // （2）读取各维度原始评分并判断是否为 10 分制
        Integer panicLevel = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorPanic(sessionId)));
        Integer seriousnessLevel = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorSeriousness(sessionId)));
        Integer emoticonHandling = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorEmoticon(sessionId)));
        Integer compositeScore = parseInteger(interviewCacheStore.getValue(InterviewCacheKeys.demeanorComposite(sessionId)));
        boolean tenScaleDetected = demeanorNormalizationStrategy.isLikelyTenScale(
                panicLevel,
                seriousnessLevel,
                emoticonHandling,
                compositeScore
        );
        // （3）逐个维度归一化后写入明细
        detail.setPanicLevel(demeanorNormalizationStrategy.normalize(panicLevel, tenScaleDetected));
        detail.setSeriousnessLevel(demeanorNormalizationStrategy.normalize(seriousnessLevel, tenScaleDetected));
        detail.setEmoticonHandling(demeanorNormalizationStrategy.normalize(emoticonHandling, tenScaleDetected));
        detail.setCompositeScore(demeanorNormalizationStrategy.normalize(compositeScore, tenScaleDetected));
        return detail;
    }

    /**
     * 将缓存字符串解析为整数，空白或非法时返回 null。
     */
    private Integer parseInteger(String value) {
        // （1）空白值返回 null
        if (StrUtil.isBlank(value)) {
            return null;
        }
        // （2）解析失败返回 null
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从 Lua 脚本返回值中提取平均分（第三个元素），无法解析时返回 null。
     */
    private Integer parseAverageFromScriptResult(List<?> scriptResult) {
        // （1）返回值结构不完整时返回 null
        if (scriptResult == null || scriptResult.size() < 3) {
            return null;
        }
        Object avg = scriptResult.get(2);
        if (avg == null) {
            return null;
        }
        // （2）解析平均分，失败返回 null
        try {
            return Integer.parseInt(String.valueOf(avg));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 初始化分数聚合更新脚本对象。
     */
    private static DefaultRedisScript<List> initScoreAggregateScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(SCORE_AGGREGATE_UPDATE_SCRIPT_TEXT);
        script.setResultType(List.class);
        return script;
    }
}
