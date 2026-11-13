package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * "The chairman would like..." passed on by the general director (design DESIGN_START_CARIERA §5.1, decided
 * 2026-11-13). Whose wish it really is stays hidden unless the manager asks the chairman.
 */
@Entity
@Data
@Table(name = "director_wish")
public class DirectorWish {

    public static final String FORMATION = "FORMATION";
    public static final String PLAY_YOUNG = "PLAY_YOUNG";
    public static final String SELL = "SELL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int season;
    private String kind;
    /** The formation wanted (FORMATION). */
    private String formation;
    /** The player concerned (PLAY_YOUNG, SELL). */
    private long playerId;
    /** His season appearances when the wish was made (PLAY_YOUNG). */
    private int appearancesAtStart;
    private String text;
    /** Hidden: the chairman's own wish, or the director's agenda. */
    private boolean fromChairman;
    /** The manager asked the chairman whose wish it was. */
    private boolean revealed;
    private boolean settled;
    private boolean obeyed;
}
