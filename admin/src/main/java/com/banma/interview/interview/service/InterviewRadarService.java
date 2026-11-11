package com.banma.interview.interview.service;

import com.banma.interview.interview.api.io.resp.RadarChartDTO;

public interface InterviewRadarService {

    RadarChartDTO buildRadarChart(Integer resumeScore, Integer interviewScore, Integer demeanorScore);
}

