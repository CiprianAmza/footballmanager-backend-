package com.footballmanagergamesimulator.service.knockout;

/**
 * Whether a knockout tie is decided over one match or two.
 *
 * <ul>
 *   <li>{@link #SINGLE_LEG} — one match. It cannot end level: if tied after
 *       90 minutes it goes to extra time and, if still level, penalties.</li>
 *   <li>{@link #TWO_LEG} — home-and-away. Each leg can end in any result
 *       (a draw is fine). The winner is decided on aggregate; if the aggregate
 *       is level the second leg goes to extra time and, if still level,
 *       penalties. (No away-goals rule — abolished in modern football.)</li>
 * </ul>
 */
public enum LegFormat {
    SINGLE_LEG,
    TWO_LEG
}
