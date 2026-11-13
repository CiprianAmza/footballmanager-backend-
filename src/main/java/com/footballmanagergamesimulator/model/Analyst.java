package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A football pundit (design DESIGN_START_CARIERA §6.1, decided 2026-11-12): a fixed character of the world. Each
 * league has three (one per temperament); two international pundits comment only on the biggest clubs. Some
 * support a club, which colours what they say about it and its rivals.
 */
@Entity
@Data
@Table(name = "analyst")
public class Analyst {

    public enum Temperament { CRITIC, BALANCED, SUPPORTIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String name;
    /** The league he covers; 0 = international pundit. */
    private long competitionId;
    private long nationId;

    @Enumerated(EnumType.STRING)
    private Temperament temperament;

    /** The club he supports; 0 = neutral. */
    private long favoriteTeamId;
}
