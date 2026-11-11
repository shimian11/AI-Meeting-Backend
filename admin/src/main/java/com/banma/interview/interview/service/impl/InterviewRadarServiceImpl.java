package com.banma.interview.interview.service.impl;

import com.banma.interview.interview.api.io.resp.RadarChartDTO;
import com.banma.interview.interview.application.strategy.RadarComputationStrategy;
import com.banma.interview.interview.service.InterviewRadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 雷达图服务实现：委托计算策略生成雷达图，异常时返回全 0 的默认数据。
 */
@Service
@RequiredArgsConstructor
public class InterviewRadarServiceImpl implements InterviewRadarService {

    /** 雷达图分值计算策略 */
    private final RadarComputationStrategy radarComputationStrategy;

    /**
     * 根据三维评分构建雷达图数据，计算异常时降级为全 0 结果。
     *
     * @param resumeScore 简历评分
     * @param interviewScore 面试表现分
     * @param demeanorScore 仪态评分
     * @return 雷达图数据
     */
    @Override
    public RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore, Integer demeanorScore) {
        try {
            // （1）委托计算策略生成各维度得分
            return radarComputationStrategy.compute(resumeScore, interviewScore, demeanorScore);
        } catch (Exception ex) {
            // （2）计算失败时返回全 0 的默认图表
            RadarChartDTO defaultChart = new RadarChartDTO();
            defaultChart.setResumeScore(0);
            defaultChart.setInterviewPerformance(0);
            defaultChart.setDemeanorEvaluation(0);
            defaultChart.setProfessionalSkills(0);
            defaultChart.setPotentialIndex(0);
            return defaultChart;
        }
    }
}
