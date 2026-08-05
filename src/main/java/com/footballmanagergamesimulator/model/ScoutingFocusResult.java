package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** Snapshot returned by a completed broad scouting focus. */
@Entity
@Data
@Table(name = "scouting_focus_result", indexes = {
        @Index(name = "idx_scouting_focus_result_focus", columnList = "focusId"),
        @Index(name = "idx_scouting_focus_result_team", columnList = "teamId")
})
public class ScoutingFocusResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long focusId;
    private long teamId;
    private long playerId;
    private String playerName;
    private String position;
    private int age;
    private long playerTeamId;
    private String playerTeamName;
    private double estimatedRating;
    private double estimatedPotential;
    private long estimatedTransferValue;
    private double fitScore;

    @Column(length = 1000)
    private String matchedAttributes;

    @Column(length = 40)
    private String recommendation;
}
