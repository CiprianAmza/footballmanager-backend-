package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="trainingSchedule")
public class TrainingSchedule {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int dayOfWeek;    // 0=Monday to 6=Sunday
    private int sessionSlot;  // 0-2 for 3 sessions per day
    private String sessionType;  // "Physical", "General", "Tactical", "Match", "Rest"
    private String sessionName;  // "Endurance", "Possession", "Set Pieces", etc.
    private int intensity;       // 0-100

}
