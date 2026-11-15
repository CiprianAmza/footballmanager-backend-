package match2d.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zonele pe pozitii si formatiile, in coordonate canonice (poarta proprie la x=0, y=0 = tusa din
 * stanga echipei).
 *
 * <ul>
 * <li>deep - unde sta cand echipa se apara cu mingea langa poarta proprie</li>
 * <li>high - unde sta cand echipa ataca cu mingea langa poarta adversa</li>
 * <li>dbox - zona din faza defensiva (x_min, x_max, y_min, y_max): nu iese din ea</li>
 * <li>abox - zona din faza ofensiva: cat de sus si cat de lateral poate urca</li>
 * </ul>
 */
public final class Zones {
    /** Zona unei pozitii; pentru un slot concret are si numele slotului (de exemplu DCL). */
    public static final class Spec {
        public String pos;
        public String slot;
        public final double[] deep;
        public final double[] high;
        public final double[] dbox;
        public final double[] abox;

        Spec(double[] deep, double[] high, double[] dbox, double[] abox) {
            this.deep = deep;
            this.high = high;
            this.dbox = dbox;
            this.abox = abox;
        }

        Spec copy() {
            return new Spec(deep.clone(), high.clone(), dbox.clone(), abox.clone());
        }
    }

    public static final Map<String, Spec> POS = new LinkedHashMap<>();
    public static final Map<String, List<String>> FORMATIONS = new LinkedHashMap<>();
    public static final Set<String> DEFENDERS = Set.of("DL", "DC", "DR", "WBL", "WBR");

    // cand o pozitie centrala apare de 2-4 ori, o impartim pe latime
    private static final double[][] OFFSETS = {{}, {0.0}, {-8.5, 8.5}, {-13.0, 0.0, 13.0}, {-18.0, -6.0, 6.0, 18.0}};
    private static final String[][] SUFFIX = {{}, {""}, {"L", "R"}, {"L", "C", "R"}, {"L", "CL", "CR", "R"}};

    static {
        put("GK", 3.5, 34, 17, 34, 0.5, 18, 20, 48, 0.5, 28, 16, 52);
        put("DL", 14, 12, 56, 8, 4, 50, 1, 28, 10, 82, 1, 27);
        put("DC", 11, 34, 46, 34, 3, 46, 13, 55, 8, 66, 11, 57);
        put("WBL", 16, 8, 72, 6, 5, 56, 1, 24, 12, 95, 1, 24);
        put("DM", 17, 34, 58, 34, 10, 56, 11, 57, 18, 78, 9, 59);
        put("ML", 21, 14, 80, 7, 14, 66, 1, 29, 22, 100, 1, 31);
        put("MC", 20, 34, 72, 34, 14, 64, 9, 59, 22, 92, 7, 61);
        put("AML", 30, 15, 89, 12, 24, 76, 1, 33, 32, 103, 1, 43);
        put("AMC", 28, 34, 85, 34, 26, 76, 11, 57, 34, 100, 9, 59);
        put("ST", 38, 34, 93, 34, 32, 88, 11, 57, 40, 104, 7, 61);
        String[][] mirrors = {{"DL", "DR"}, {"WBL", "WBR"}, {"ML", "MR"}, {"AML", "AMR"}};
        for (String[] m : mirrors) {
            POS.put(m[1], mirror(POS.get(m[0])));
        }
        FORMATIONS.put("4-4-2", List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST"));
        FORMATIONS.put("4-3-3", List.of("GK", "DL", "DC", "DC", "DR", "DM", "MC", "MC", "AML", "AMR", "ST"));
        FORMATIONS.put("4-2-3-1", List.of("GK", "DL", "DC", "DC", "DR", "DM", "DM", "AML", "AMC", "AMR", "ST"));
        FORMATIONS.put("3-5-2", List.of("GK", "DC", "DC", "DC", "WBL", "WBR", "DM", "MC", "MC", "ST", "ST"));
        FORMATIONS.put("4-1-4-1", List.of("GK", "DL", "DC", "DC", "DR", "DM", "ML", "MC", "MC", "MR", "ST"));
        FORMATIONS.put("4-4-1-1", List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "AMC", "ST"));
        FORMATIONS.put("4-2-2-2", List.of("GK", "DL", "DC", "DC", "DR", "DM", "DM", "AML", "AMR", "ST", "ST"));
        FORMATIONS.put("3-5-1-1", List.of("GK", "DC", "DC", "DC", "WBL", "WBR", "MC", "MC", "MC", "AMC", "ST"));
        FORMATIONS.put("3-1-4-1-1", List.of("GK", "DC", "DC", "DC", "DM", "ML", "MC", "MC", "MR", "AMC", "ST"));
    }

    private Zones() {
    }

    private static void put(String pos, double d0, double d1, double h0, double h1,
                            double b0, double b1, double b2, double b3,
                            double a0, double a1, double a2, double a3) {
        POS.put(pos, new Spec(new double[] {d0, d1}, new double[] {h0, h1},
                new double[] {b0, b1, b2, b3}, new double[] {a0, a1, a2, a3}));
    }

    private static Spec mirror(Spec s) {
        double w = Geo.W;
        return new Spec(new double[] {s.deep[0], w - s.deep[1]}, new double[] {s.high[0], w - s.high[1]},
                new double[] {s.dbox[0], s.dbox[1], w - s.dbox[3], w - s.dbox[2]},
                new double[] {s.abox[0], s.abox[1], w - s.abox[3], w - s.abox[2]});
    }

    private static double side(double v, double off) {
        return Math.min(Geo.W - 1.0, Math.max(1.0, v + off));
    }

    private static Spec shift(Spec s, double off) {
        if (off == 0.0) {
            return s.copy();
        }
        double k = 0.7;
        return new Spec(new double[] {s.deep[0], side(s.deep[1], off)},
                new double[] {s.high[0], side(s.high[1], off)},
                new double[] {s.dbox[0], s.dbox[1], side(s.dbox[2] + off * k - off, off), side(s.dbox[3] + off * k - off, off)},
                new double[] {s.abox[0], s.abox[1], side(s.abox[2] + off * k - off, off), side(s.abox[3] + off * k - off, off)});
    }

    // de unde incolo un jucator "ajunge in careu" (om de atac) sau "e sus pe banda" cand echipa ataca
    static final double ARRIVE_X = 85.0;
    static final double WIDE_X = 78.0;
    static final double CENTRAL_Y = 14.0;

    /**
     * Formatiile fara jucatori de banda in fata (3-5-2, 3-5-1-1): pe o banda goala urca cel mai
     * avansat jucator de pe ea (fundasul lateral), ca atacul sa aiba latime. Se schimba doar unde
     * sta cand echipa ataca ({@code high}, {@code abox}); in aparare ramane la locul lui. Oamenii
     * care lipsesc din careu vin din mers, prin alergarile din {@link Runs}.
     */
    static void supportAttack(List<Spec> slots) {
        for (int side = 0; side < 2; side++) {
            boolean covered = false;
            Spec best = null;
            for (Spec s : slots) {
                boolean onSide = side == 0 ? s.high[1] < Geo.MID_Y - CENTRAL_Y : s.high[1] > Geo.MID_Y + CENTRAL_Y;
                if (!onSide || s.pos.equals("GK")) {
                    continue;
                }
                if (s.high[0] >= WIDE_X) {
                    covered = true;
                }
                if (best == null || s.high[0] > best.high[0]) {
                    best = s;
                }
            }
            if (!covered && best != null) {
                best.high[0] = WIDE_X + 2.0;
                best.abox[1] = Math.max(best.abox[1], 100.0);
            }
        }
    }

    /**
     * Lista de sloturi pentru orice lista de pozitii, in ordinea primita. Asa poate juca si o
     * formatie pe care motorul nu o are in lista.
     */
    public static List<Spec> buildSlotsFromPositions(List<String> names) {
        Map<String, Integer> count = new HashMap<>();
        for (String n : names) {
            count.merge(n, 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Spec> out = new ArrayList<>();
        for (String n : names) {
            int k = seen.getOrDefault(n, 0);
            seen.put(n, k + 1);
            int c = count.get(n);
            Spec spec = shift(POS.get(n), OFFSETS[c][k]);
            spec.pos = n;
            spec.slot = n + SUFFIX[c][k];
            out.add(spec);
        }
        return out;
    }
}
