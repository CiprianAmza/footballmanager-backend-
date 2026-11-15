package match2d.engine;

/** Cele 43 de atribute ale unui jucator, cu aceleasi nume ca in {@code PlayerSkills} din backend. */
public final class A {
    public static final String[] NAMES = {
        // tehnice
        "corners", "crossing", "dribbling", "finishing", "firstTouch", "freeKick", "heading",
        "longShots", "longThrows", "marking", "passing", "penaltyTaking", "tackling", "technique",
        "ballRecovery",
        // mentale
        "aggression", "anticipation", "bravery", "composure", "concentration", "decisions",
        "determination", "flair", "leadership", "offTheBall", "positioning", "teamwork", "vision",
        "workRate",
        // fizice
        "acceleration", "agility", "balance", "jumpingReach", "naturalFitness", "pace", "stamina",
        "strength",
        // portar
        "handling", "reflexes", "oneOnOnes", "commandOfArea", "kicking", "throwing",
    };

    public static final int corners = 0, crossing = 1, dribbling = 2, finishing = 3, firstTouch = 4,
            freeKick = 5, heading = 6, longShots = 7, longThrows = 8, marking = 9, passing = 10,
            penaltyTaking = 11, tackling = 12, technique = 13, ballRecovery = 14, aggression = 15,
            anticipation = 16, bravery = 17, composure = 18, concentration = 19, decisions = 20,
            determination = 21, flair = 22, leadership = 23, offTheBall = 24, positioning = 25,
            teamwork = 26, vision = 27, workRate = 28, acceleration = 29, agility = 30,
            balance = 31, jumpingReach = 32, naturalFitness = 33, pace = 34, stamina = 35,
            strength = 36, handling = 37, reflexes = 38, oneOnOnes = 39, commandOfArea = 40,
            kicking = 41, throwing = 42;

    public static final int COUNT = NAMES.length;

    private A() {
    }
}
