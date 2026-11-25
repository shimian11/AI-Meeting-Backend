package com.banma.interview.interview.service.strategy;

import com.banma.interview.interview.service.model.InterviewTurnLog;

import java.util.List;

public interface InterviewScoreAggregatorStrategy {

    int clampScore(Integer score);

    int averageFromAggregate(Long scoreSum, Long answerCount);

    Integer averageFromTurns(List<InterviewTurnLog> turns);
}

