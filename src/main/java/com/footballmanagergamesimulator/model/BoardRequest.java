package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "board_request")
public class BoardRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int seasonNumber;
    @Column(name = "request_day")
    private int day;
    private String requestType; // "IMPROVE_POSITION", "REDUCE_WAGES", "DEVELOP_YOUTH", "WIN_TROPHY", "INCREASE_REVENUE"
    private String description;
    private int deadline; // day by which it must be met
    @Column(name = "request_status")
    private String status; // "ACTIVE", "MET", "FAILED", "EXPIRED"
    private int reputationPenalty; // penalty if failed
}
