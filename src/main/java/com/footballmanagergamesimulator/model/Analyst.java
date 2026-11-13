package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A press voice of the world (design DESIGN_START_CARIERA §6.1, decided 2026-11-12; style parameters §6.1.1,
 * decided 2026-11-13): a fixed character.
 * <ul>
 *   <li><b>Pundits</b> are quoted inside articles: each league has three (one per temperament); two international
 *       pundits comment only on the biggest clubs.</li>
 *   <li><b>Reporters</b> write and sign articles: each league has its own, one {@link Beat} each.</li>
 * </ul>
 * Some support a club, which colours what they say about it and its rivals. The style columns are plain values so
 * a journalist can be re-tuned straight in the database.
 */
@Entity
@Data
@Table(name = "analyst")
public class Analyst {

    public enum Temperament {
        CRITIC, BALANCED, SUPPORTIVE;

        /** The tier a 0–100 harshness falls in: 67+ critic, 34–66 balanced, below that supportive. */
        public static Temperament ofHarshness(int harshness) {
            if (harshness >= 67) return CRITIC;
            return harshness >= 34 ? BALANCED : SUPPORTIVE;
        }
    }

    public enum Role { PUNDIT, REPORTER }

    /** The set of pre-written fragments he writes from. */
    public enum Voice { TABLOID, ANALYTICAL, OLD_SCHOOL, POLEMICIST, INSIDER, AGENCY }

    /** What a reporter covers. */
    public enum Beat { MATCHES, TRANSFERS, BOARDROOM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String name;
    /** The league he covers; 0 = international pundit. */
    private long competitionId;
    private long nationId;

    /** The tier he was created with. {@link #tone()} is what the game reads. */
    @Enumerated(EnumType.STRING)
    private Temperament temperament;

    /** The club he supports; 0 = neutral. */
    private long favoriteTeamId;

    /** Null on rows created before reporters existed = pundit. */
    @Enumerated(EnumType.STRING)
    private Role role;

    /** Null = not styled yet. */
    @Enumerated(EnumType.STRING)
    private Voice voice;

    /** Reporters only. */
    @Enumerated(EnumType.STRING)
    private Beat beat;

    /** 0–100, how hard he is on poor results; null = use {@link #temperament}. */
    private Integer harshness;

    /** 0–100, the chance his headline gets a loud kicker ("MELTDOWN: …"). */
    @Column(columnDefinition = "integer default 50 not null")
    private int sensationalism = 50;

    /** 0–100, the chance his article carries a paragraph of match numbers. */
    @Column(columnDefinition = "integer default 50 not null")
    private int statsFocus = 50;

    /** 1–3, how many paragraphs he writes. */
    @Column(columnDefinition = "integer default 2 not null")
    private int verbosity = 2;

    /** 0–100, how close his transfer stories are to the truth (the credibility shown, the fee he quotes). */
    @Column(columnDefinition = "integer default 50 not null")
    private int reliability = 50;

    /** 0–100, how much it takes to change his mind about a manager (see {@code AnalystStance#step}). */
    @Column(columnDefinition = "integer default 50 not null")
    private int stubbornness = 50;

    /**
     * His own tone before any manager or club colours it: from {@link #harshness} when set, otherwise the
     * temperament he was created with; balanced when the row has neither.
     */
    public Temperament tone() {
        if (harshness != null) return Temperament.ofHarshness(harshness);
        return temperament == null ? Temperament.BALANCED : temperament;
    }

    public boolean isReporter() {
        return role == Role.REPORTER;
    }
}
