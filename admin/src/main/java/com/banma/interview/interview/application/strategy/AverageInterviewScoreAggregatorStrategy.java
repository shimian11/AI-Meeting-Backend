package com.banma.interview.interview.application.strategy;

import com.banma.interview.interview.service.model.InterviewTurnLog;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 面试分数聚合策略：以平均分汇总面试得分，并统一截断到 0-100。
 */
@Component
public class AverageInterviewScoreAggregatorStrategy implements InterviewScoreAggregatorStrategy {

    /**
     * 将单个评分截断到 0-100，空值按 0 处理。
     */
    @Override
    public int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 根据分数总和与答题数计算平均分。
     *
     * @param scoreSum 分数总和
     * @param answerCount 答题数量
     * @return 平均分，无有效数据时返回 0
     */
    @Override
    public int averageFromAggregate(Long scoreSum, Long answerCount) {
        // （1）数据缺失或答题数非正时返回 0
        if (scoreSum == null || answerCount == null || answerCount <= 0) {
            return 0;
        }
        // （2）四舍五入取平均并截断到 0-100
        return clampScore((int) Math.round((double) scoreSum / answerCount));
    }

    /**
     * 从轮次日志中计算平均分，跳过追问轮与无效分数。
     *
     * @param turns 面试轮次日志
     * @return 平均分，无有效评分轮次时返回 null
     */
    @Override
    public Integer averageFromTurns(List<InterviewTurnLog> turns) {
        // （1）轮次为空时无可用数据
        if (turns == null || turns.isEmpty()) {
            return null;
        }

        // （2）累计有效轮次的评分与数量，排除追问轮
        int scoreSum = 0;
        int scoredTurns = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            scoreSum += clampScore(turn.getScore());
            scoredTurns++;
        }

        // （3）无有效轮次返回 null，否则四舍五入求平均
        if (scoredTurns <= 0) {
            return null;
        }
        return clampScore((int) Math.round((double) scoreSum / scoredTurns));
    }
}

