package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.model.Competition;

/** Human-readable elimination stage for a cup's internal round number. */
final class CupStageLabels {
    private CupStageLabels() {}

    static String forRound(Competition competition, CompetitionFormat format, long round, long finalRound) {
        if (format.isGroupRound(round)) return "Group Stage";
        if (format.isPreliminaryRound(round)) return "Qualifying Round " + (round + 1);
        if (competition.getTypeId() == Competition.STARS_CUP && round == format.playoffRound())
            return "Knockout Playoff";
        long distance = finalRound - round;
        if (distance == 0) return "Final";
        if (distance == 1) return "Semi-final";
        if (distance == 2) return "Quarter-final";
        if (distance >= 3 && distance <= 59) return "Last " + (1L << (distance + 1));
        return "Knockout Stage";
    }
}
