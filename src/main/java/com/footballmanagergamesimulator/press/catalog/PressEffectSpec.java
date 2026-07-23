package com.footballmanagergamesimulator.press.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Declarative effect of an answer variant. Data only — application lives in the
 * effects engine (Phase 5). {@code target} ∈ MANAGER, PLAYER, SQUAD, BOARD,
 * OWNER, MEDIA; {@code metric} is a named quantity (e.g. MORALE,
 * MEDIA_REPUTATION, OWNER_ARROGANCE, COACH_HUMILIATION, BOARD_CONFIDENCE).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressEffectSpec {
    private String target;
    private String metric;
    private double value;
}
