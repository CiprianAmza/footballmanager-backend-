package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * The head coach on paper at a club whose manager lacks the licence (design DESIGN_START_CARIERA §4, decided
 * 2026-11-13). Proud or mercenary; below 30 loyalty he revolts and fields his own team sheet; the secret that he
 * is only a front can leak to the press once.
 */
@Entity
@Data
@Table(name = "front_coach", uniqueConstraints = @UniqueConstraint(columnNames = {"teamId"}))
public class FrontCoach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long coachId;
    /** Proud (losses hurt him twice) or mercenary (losses hurt him half). */
    private boolean proud;
    private boolean revolting;
    /** The press found out he is only a front. */
    private boolean discovered;
    /** The manager's own team sheet and formation, kept while the front coach fields his. */
    @Column(length = 10000)
    private String savedFirst11;
    private String savedTactic;
}
