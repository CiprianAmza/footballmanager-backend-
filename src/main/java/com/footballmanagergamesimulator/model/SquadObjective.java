package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** A competition target agreed at the meeting with the squad: the manager's and the players' (one per season). */
@Entity
@Data
@Table(name = "squad_objective", uniqueConstraints = @UniqueConstraint(columnNames = {"teamId", "season", "competitionId"}))
public class SquadObjective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int season;
    private long competitionId;
    private String competitionName;
    private String managerTarget;
    private String playersWish;
    /** Change applied to every player's relation with the manager. */
    private int relationDelta;
    /** MIN, MID or MAX: the bonus the club pays the squad if the target is reached. */
    @Column(columnDefinition = "varchar(8) default 'MID'")
    private String bonusLevel = "MID";
    @Column(columnDefinition = "boolean default false not null")
    private boolean bonusSettled;
    /** Paid at season end; 0 when the target was missed. */
    @Column(columnDefinition = "bigint default 0 not null")
    private long bonusPaid;
}
