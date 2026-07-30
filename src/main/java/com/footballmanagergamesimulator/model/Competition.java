package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competition")
public class Competition {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long nationId;
  private long prizesId;
  private long typeId;

  /**
   * Level within its own nation and kind: 1 = top flight / premier cup, 2 = the
   * division or secondary competition below it, and so on.
   *
   * <p>{@link #typeId} conflates kind with level — 1 is "league" but really means
   * "top-flight league", and 3 is a second division rather than a distinct kind of
   * competition. Everything that needs to reason about levels therefore hardcodes
   * the pair: whether a club can be relegated, which league a second division
   * belongs under, which champion enters the Super Cup. None of that survives a
   * third division being added.
   *
   * <p>This field carries the level on its own so those questions become lookups
   * (<em>is there a league in this nation at tier + 1?</em>) instead of constants.
   * {@code typeId} is untouched for now and still decides kind; the two are kept in
   * step by {@link #tierForTypeId(long)}.
   */
  private int tier = 1;

  /**
   * General Information
   */
  private String name;

  /** A domestic league of any level. Second divisions are {@code LEAGUE} on tier 2. */
  public static final long LEAGUE = 1;
  public static final long CUP = 2;
  public static final long LEAGUE_OF_CHAMPIONS = 4;
  public static final long STARS_CUP = 5;
  public static final long SUPER_CUP = 6;

  /** True for a domestic league at any level — what {@code typeId == 1 || == 3} used to ask. */
  public boolean isLeague() {
    return typeId == LEAGUE;
  }

  /** True for a nation's top flight — what a bare {@code typeId == 1} used to mean. */
  public boolean isTopFlight() {
    return typeId == LEAGUE && tier == 1;
  }

  /** True for a division below the top flight — what {@code typeId == 3} used to mean. */
  public boolean isBelowTopFlight() {
    return typeId == LEAGUE && tier > 1;
  }

}
