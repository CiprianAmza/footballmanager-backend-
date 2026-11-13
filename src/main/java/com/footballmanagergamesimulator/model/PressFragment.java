package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One reusable piece of press text (design DESIGN_START_CARIERA §6.1, decided 2026-11-12): pre-written by agents,
 * shipped in the {@code press/} resources, loaded into this table and rotated (least used first) so articles do
 * not repeat. Placeholders such as {@code {club}} are filled when an article is written.
 */
@Entity
@Data
@Table(name = "press_fragment", indexes = @Index(name = "ix_press_fragment_bucket", columnList = "category, bucket"))
public class PressFragment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** The kind of article it belongs to: APPOINTMENT, POST_MATCH, TRANSFER_RUMOUR. */
    private String eventType;
    /** The journalist voice it is written in (an {@link Analyst.Voice} name); null = fits any voice. */
    private String voice;
    /** The section of the article, e.g. HEADLINE, WHO, ANALYST, VERDICT, TALKING_POINT, BODY. */
    private String category;
    /** The situation it fits, e.g. INTERNATIONAL, SURPRISE, CRITIC_BELOW_RIVAL. */
    private String bucket;
    @Column(length = 1000)
    private String text;
    @Column(columnDefinition = "integer default 0 not null")
    private int uses;
    /** Absolute game day it was last used (0 = never). */
    @Column(columnDefinition = "integer default 0 not null")
    private int lastUsedDay;
}
