package com.footballmanagergamesimulator.nameGenerator;

/**
 * Legacy static entry point, kept for callers that predate nation-aware naming
 * (staff, retirement regens). Delegates to the default {@link NameStyles#ELEVEN}
 * style; prefer {@link CompositeNameGenerator} for anything tied to a team.
 */
public class NameGenerator {

  private static final FragmentNameGenerator DEFAULT = new FragmentNameGenerator(NameStyles.ELEVEN);

  public static String generateName() {
    return DEFAULT.generateName();
  }
}
