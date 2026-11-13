package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One line of a club's code of conduct (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): the fine, in weeks
 * of wages, for an offence. No row = the standard fine.
 */
@Entity
@Data
@Table(name = "conduct_rule", uniqueConstraints = @UniqueConstraint(columnNames = {"teamId", "offence"}))
public class ConductRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    /** ConductOffence name. */
    private String offence;
    private double fineWeeks;
}
