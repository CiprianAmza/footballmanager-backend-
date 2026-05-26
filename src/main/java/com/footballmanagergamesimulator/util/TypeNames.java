package com.footballmanagergamesimulator.util;

public class TypeNames {

  public static final Long PLAYER_TYPE = 1L;
  public static final Long TEAM_TYPE = 2L;
  public static final Long MATCH_TYPE = 3L;
  public static final Long MANAGER_TYPE = 4L;

  // Staff types
  public static final Long ASSISTANT_MANAGER_TYPE = 5L;
  public static final Long FIRST_TEAM_COACH_TYPE = 6L;
  public static final Long FITNESS_COACH_TYPE = 7L;
  public static final Long GK_COACH_TYPE = 8L;
  public static final Long YOUTH_COACH_TYPE = 9L;
  public static final Long HOYD_TYPE = 10L; // Head of Youth Development

  public static boolean isCoachType(long typeId) {
    return typeId >= 5L && typeId <= 10L;
  }

  public static String coachTypeName(long typeId) {
    return switch ((int) typeId) {
      case 5 -> "Assistant Manager";
      case 6 -> "First Team Coach";
      case 7 -> "Fitness Coach";
      case 8 -> "Goalkeeping Coach";
      case 9 -> "Youth Coach";
      case 10 -> "Head of Youth Development";
      default -> "Coach";
    };
  }
}
