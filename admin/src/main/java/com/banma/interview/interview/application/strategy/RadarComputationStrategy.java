package com.banma.interview.interview.application.strategy;

import com.banma.interview.interview.api.io.resp.RadarChartDTO;

public interface RadarComputationStrategy {

    RadarChartDTO compute(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

