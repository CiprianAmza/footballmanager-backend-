package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** What a human manager told about himself when he created his career (design DESIGN_START_CARIERA §2.2). */
@Entity
@Data
@Table(name = "manager_background", indexes = @Index(name = "ux_manager_background_manager", columnList = "managerId", unique = true))
public class ManagerBackground {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long managerId;
    /** 0 = not given. */
    private int birthDay;
    private int birthMonth;
    /** Nation of birth (NationService ids); -1 = not given. */
    private long birthNationId = -1;
    /** 0 = none. */
    private long favoriteTeamId;
    /** PlayingCareer name. */
    private String playingCareer;
    /** CoachingLicence name. */
    private String coachingLicence;
}
