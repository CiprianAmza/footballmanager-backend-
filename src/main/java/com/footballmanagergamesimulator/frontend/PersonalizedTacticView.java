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
}
