package com.footballmanagergamesimulator.dynamics;

/**
 * The situation that frames a monthly team meeting. Derived from real game state
 * (form, objectives, title race, promotion/relegation, morale, minutes, finals,
 * rivalry, a new manager, or an owner intervention).
 */
public enum MeetingContext {
    FORM,
    OBJECTIVES,
    TITLE_RACE,
    PROMOTION_PUSH,
    RELEGATION_BATTLE,
    MORALE,
    PLAYING_TIME,
    BIG_FINAL,
    RIVALRY,
    NEW_MANAGER,
    OWNER_INTERVENTION,
    GENERAL
}
