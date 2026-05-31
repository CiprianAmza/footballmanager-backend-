package com.footballmanagergamesimulator.frontend;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class PersonalizedTacticView {

    private List<FormationData> formationDataList;
    private long teamId;
    private String tactic;

    private String mentality;
    private String timeWasting;
    private String inPossession;
    private String passingType;
    private String tempo;

    // Strat-2 tactical axes (null ⇒ neutral)
    private String defensiveLine;
    private String pressing;
    private String width;

    // Faza 2 team-level instructions (null ⇒ neutral)
    private String dribbling;
    private String foulFrequency;
    private String foulHardness;
    private String tempoFragmentation;
    private String widePlay;
    private String transition;

    // Set piece takers
    private Long penaltyTakerId;
    private Long freeKickTakerId;
    private Long cornerTakerLeftId;
    private Long cornerTakerRightId;
}
