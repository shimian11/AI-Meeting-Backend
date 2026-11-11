package com.banma.interview.interview.application.strategy;

import org.springframework.stereotype.Component;

/**
 * 仪态评分归一化策略：自动识别 10 分制并统一归一化为百分制。
 */
@Component
public class AdaptiveDemeanorNormalizationStrategy implements DemeanorNormalizationStrategy {

    /**
     * 判断给定评分是否全部落在 0-10 区间，用于识别 10 分制。
     *
     * @param scores 待判断的评分集合
     * @return 全部为 0-10 时返回 true
     */
    @Override
    public boolean isLikelyTenScale(Integer... scores) {
        // （1）空数组或 null 直接判定为非 10 分制
        if (scores == null || scores.length == 0) {
            return false;
        }
        // （2）任一评分为空或超出 0-10 即判定为非 10 分制
        for (Integer score : scores) {
            if (score == null || score < 0 || score > 10) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将单个评分归一化到 0-100，10 分制时放大十倍。
     *
     * @param score 原始评分
     * @param tenScaleDetected 是否识别为 10 分制
     * @return 0-100 区间的评分
     */
    @Override
    public int normalize(Integer score, boolean tenScaleDetected) {
        // （1）空评分按 0 处理
        if (score == null) {
            return 0;
        }
        // （2）10 分制放大十倍，并截断到 0-100
        int normalized = tenScaleDetected ? score * 10 : score;
        return Math.max(0, Math.min(100, normalized));
    }
}

