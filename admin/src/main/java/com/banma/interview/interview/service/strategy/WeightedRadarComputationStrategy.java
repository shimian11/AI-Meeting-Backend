package com.banma.interview.interview.service.strategy;

import com.banma.interview.interview.api.io.resp.RadarChartDTO;
import org.springframework.stereotype.Component;

/**
 * 雷达图计算策略：按简历、面试表现与仪态评分加权计算各维度得分。
 */
@Component
public class WeightedRadarComputationStrategy implements RadarComputationStrategy {

    /**
     * 潜力指数中简历评分权重。
     */
    private static final double RESUME_WEIGHT = 0.25D;
    /**
     * 潜力指数中面试表现权重。
     */
    private static final double INTERVIEW_WEIGHT = 0.55D;
    /**
     * 潜力指数中仪态评分权重。
     */
    private static final double DEMEANOR_WEIGHT = 0.20D;
    /**
     * 专业能力中面试表现权重。
     */
    private static final double PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT = 0.70D;
    /**
     * 专业能力中简历评分权重。
     */
    private static final double PROFESSIONAL_SKILLS_RESUME_WEIGHT = 0.30D;

    /**
     * 计算雷达图数据，缺失的维度按 0 处理，并按可用权重归一化。
     *
     * @param resumeScore 简历评分
     * @param interviewScore 面试表现分
     * @param demeanorScore 仪态评分
     * @return 雷达图各维度得分
     */
    @Override
    public RadarChartDTO compute(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        // （1）将各维度评分截断到 0-100，缺失值保持 null
        Integer resume = clampNullableScore(resumeScore);
        Integer interview = clampNullableScore(interviewScore);
        Integer demeanor = clampNullableScore(demeanorScore);

        // （2）填充基础维度，缺失值按 0 展示
        RadarChartDTO radarChart = new RadarChartDTO();
        radarChart.setResumeScore(defaultScore(resume));
        radarChart.setInterviewPerformance(defaultScore(interview));
        radarChart.setDemeanorEvaluation(defaultScore(demeanor));
        // （3）按权重计算专业能力与潜力指数
        radarChart.setProfessionalSkills(calculateProfessionalSkills(resume, interview));
        radarChart.setPotentialIndex(calculateWeightedComposite(resume, interview, demeanor));
        return radarChart;
    }

    /**
     * 按简历与面试表现加权计算专业能力得分，仅对存在的维度计入权重。
     */
    private int calculateProfessionalSkills(Integer resumeScore, Integer interviewScore) {
        // （1）累加可用维度的加权分与权重和
        double weightedScore = 0D;
        double totalWeight = 0D;
        if (resumeScore != null) {
            weightedScore += clampScore(resumeScore) * PROFESSIONAL_SKILLS_RESUME_WEIGHT;
            totalWeight += PROFESSIONAL_SKILLS_RESUME_WEIGHT;
        }
        if (interviewScore != null) {
            weightedScore += clampScore(interviewScore) * PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT;
            totalWeight += PROFESSIONAL_SKILLS_INTERVIEW_WEIGHT;
        }
        // （2）无可用权重返回 0，否则按权重和归一化
        if (totalWeight <= 0D) {
            return 0;
        }
        return clampScore((int) Math.round(weightedScore / totalWeight));
    }

    /**
     * 按简历、面试表现与仪态三类权重计算潜力指数，仅对存在的维度计入权重。
     */
    private int calculateWeightedComposite(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        // （1）累加可用维度的加权分与权重和
        double weightedScore = 0D;
        double totalWeight = 0D;
        if (resumeScore != null) {
            weightedScore += clampScore(resumeScore) * RESUME_WEIGHT;
            totalWeight += RESUME_WEIGHT;
        }
        if (interviewScore != null) {
            weightedScore += clampScore(interviewScore) * INTERVIEW_WEIGHT;
            totalWeight += INTERVIEW_WEIGHT;
        }
        if (demeanorScore != null) {
            weightedScore += clampScore(demeanorScore) * DEMEANOR_WEIGHT;
            totalWeight += DEMEANOR_WEIGHT;
        }
        // （2）无可用权重返回 0，否则按权重和归一化
        if (totalWeight <= 0D) {
            return 0;
        }
        return clampScore((int) Math.round(weightedScore / totalWeight));
    }

    /**
     * 将可空评分转为展示用分数，null 按 0 处理。
     */
    private int defaultScore(Integer score) {
        return score == null ? 0 : clampScore(score);
    }

    /**
     * 截断可空评分，null 保持 null。
     */
    private Integer clampNullableScore(Integer score) {
        if (score == null) {
            return null;
        }
        return clampScore(score);
    }

    /**
     * 将评分截断到 0-100，null 按 0 处理。
     */
    private int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }
}

