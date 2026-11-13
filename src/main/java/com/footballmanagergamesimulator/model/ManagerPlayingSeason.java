package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** One season of a human manager's (optional, self-declared) playing career. */
@Entity
@Data
@Table(name = "manager_playing_season", indexes = @Index(name = "ix_manager_playing_season_manager", columnList = "managerId"))
public class ManagerPlayingSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long managerId;
    /** 1, 2, 3… in the order given. */
    private int seasonOrder;
    private String teamName;
    private int goals;
    private int assists;
}
