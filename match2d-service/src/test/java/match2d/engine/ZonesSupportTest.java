package match2d.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teste pentru {@link Zones#supportAttack}: pe o banda fara jucator sus urca cel mai avansat om de
 * pe ea (fundasul lateral); unde banda e ocupata nu se schimba nimic, iar partea centrala veche
 * (impingerea unui om in careu) nu mai exista. Fara biblioteci de test:
 *
 * <pre>   ./build.sh &amp;&amp; ./test.sh ZonesSupportTest</pre>
 */
public final class ZonesSupportTest {
    private static int checks;
    private static final List<String> FAILED = new ArrayList<>();

    private ZonesSupportTest() {
    }

    public static void main(String[] args) {
        wingBacksGoUpOnEmptyFlanks();
        coveredFlanksAreLeftAlone();
        onlyTheEmptyFlankChanges();
        theMostAdvancedManOnTheFlankGoesUp();
        nothingCentralMovesAnyMore();
        defensiveShapeNeverChanges();
        teamsUseItOnTheNewPath();
        System.out.printf("%d verificari, %d esuate%n", checks, FAILED.size());
        for (String name : FAILED) {
            System.out.println("  ESUAT: " + name);
        }
        System.exit(FAILED.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ ajutoare
    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            FAILED.add(name);
        }
    }

    private static List<Zones.Spec> slots(List<String> positions) {
        return Zones.buildSlotsFromPositions(positions);
    }

    private static List<Zones.Spec> copy(List<Zones.Spec> list) {
        List<Zones.Spec> out = new ArrayList<>();
        for (Zones.Spec s : list) {
            Zones.Spec c = s.copy();
            c.pos = s.pos;
            c.slot = s.slot;
            out.add(c);
        }
        return out;
    }

    private static boolean same(Zones.Spec a, Zones.Spec b) {
        return Arrays.equals(a.deep, b.deep) && Arrays.equals(a.high, b.high) && Arrays.equals(a.dbox, b.dbox)
                && Arrays.equals(a.abox, b.abox);
    }

    /** -1 = banda stanga, 1 = banda dreapta, 0 = centru (dupa unde sta cand echipa ataca). */
    private static int flank(Zones.Spec s) {
        if (s.high[1] < Geo.MID_Y - Zones.CENTRAL_Y) {
            return -1;
        }
        if (s.high[1] > Geo.MID_Y + Zones.CENTRAL_Y) {
            return 1;
        }
        return 0;
    }

    private static boolean covered(List<Zones.Spec> list, int side) {
        for (Zones.Spec s : list) {
            if (!s.pos.equals("GK") && flank(s) == side && s.high[0] >= Zones.WIDE_X) {
                return true;
            }
        }
        return false;
    }

    /** Toate formatiile cunoscute plus cateva fara banda acoperita sau fara atacanti. */
    private static Map<String, List<String>> allFormations() {
        Map<String, List<String>> out = new LinkedHashMap<>(Zones.FORMATIONS);
        out.put("fara banda dreapta", List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MC", "ST", "ST"));
        out.put("doi pe stanga", List.of("GK", "DL", "DC", "DC", "DR", "WBL", "MC", "MC", "MC", "ST", "ST"));
        out.put("fara atacanti", List.of("GK", "DL", "DC", "DC", "DR", "DM", "MC", "MC", "MC", "ML", "MR"));
        out.put("fara benzi", List.of("GK", "DC", "DC", "DC", "DM", "DM", "MC", "MC", "MC", "ST", "ST"));
        return out;
    }

    // ------------------------------------------------------------------ banda goala
    private static void wingBacksGoUpOnEmptyFlanks() {
        for (String f : new String[] {"3-5-2", "3-5-1-1"}) {
            List<Zones.Spec> before = slots(Zones.FORMATIONS.get(f));
            List<Zones.Spec> after = copy(before);
            check(f + ": inainte, benzile sunt goale", !covered(before, -1) && !covered(before, 1));
            Zones.supportAttack(after);
            for (int i = 0; i < 11; i++) {
                Zones.Spec b = before.get(i);
                Zones.Spec a = after.get(i);
                if (b.pos.startsWith("WB")) {
                    check(f + ": " + b.slot + " urca pe banda goala (" + b.high[0] + " -> " + a.high[0] + ")",
                            a.high[0] >= Zones.WIDE_X && a.high[0] > b.high[0]);
                    check(f + ": " + b.slot + " are voie mai sus in zona de atac",
                            a.abox[1] >= b.abox[1] && a.abox[1] >= a.high[0]);
                    check(f + ": " + b.slot + " ramane pe banda lui", a.high[1] == b.high[1]
                            && a.abox[2] == b.abox[2] && a.abox[3] == b.abox[3] && a.abox[0] == b.abox[0]);
                }
            }
            check(f + ": dupa, ambele benzi sunt acoperite", covered(after, -1) && covered(after, 1));
        }
    }

    private static void coveredFlanksAreLeftAlone() {
        for (String f : new String[] {"4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "4-4-1-1", "4-2-2-2", "3-1-4-1-1"}) {
            List<Zones.Spec> before = slots(Zones.FORMATIONS.get(f));
            List<Zones.Spec> after = copy(before);
            Zones.supportAttack(after);
            boolean all = true;
            for (int i = 0; i < 11; i++) {
                all &= same(before.get(i), after.get(i));
            }
            check(f + ": benzile sunt ocupate, nimeni nu se muta (nici fundasii laterali)",
                    covered(before, -1) && covered(before, 1) && all);
        }
    }

    private static void onlyTheEmptyFlankChanges() {
        List<Zones.Spec> before = slots(allFormations().get("fara banda dreapta"));
        List<Zones.Spec> after = copy(before);
        Zones.supportAttack(after);
        check("banda stanga are mijlocas sus, cea dreapta nu", covered(before, -1) && !covered(before, 1));
        check("fundasul stang (banda ocupata) nu urca", same(before.get(1), after.get(1)));
        check("fundasul drept (banda goala) urca", after.get(4).high[0] >= Zones.WIDE_X
                && after.get(4).high[0] > before.get(4).high[0]);
        int changed = 0;
        for (int i = 0; i < 11; i++) {
            changed += same(before.get(i), after.get(i)) ? 0 : 1;
        }
        check("se muta un singur om", changed == 1);
    }

    private static void theMostAdvancedManOnTheFlankGoesUp() {
        List<Zones.Spec> before = slots(allFormations().get("doi pe stanga"));
        List<Zones.Spec> after = copy(before);
        Zones.supportAttack(after);
        Zones.Spec dl = after.get(1);
        Zones.Spec wbl = after.get(5);
        check("pe banda stanga sunt doi oameni si niciunul sus", flank(before.get(1)) == -1 && flank(before.get(5)) == -1
                && !covered(before, -1));
        check("urca cel mai avansat dintre ei (WBL), nu fundasul", wbl.high[0] >= Zones.WIDE_X && same(before.get(1), dl));
        check("pe banda dreapta urca fundasul drept", after.get(4).high[0] >= Zones.WIDE_X);
    }

    // ------------------------------------------------------------------ fara partea centrala
    private static void nothingCentralMovesAnyMore() {
        for (Map.Entry<String, List<String>> e : allFormations().entrySet()) {
            List<Zones.Spec> before = slots(e.getValue());
            List<Zones.Spec> after = copy(before);
            Zones.supportAttack(after);
            boolean central = true;
            int perSide0 = 0;
            int perSide1 = 0;
            for (int i = 0; i < 11; i++) {
                Zones.Spec b = before.get(i);
                if (same(b, after.get(i))) {
                    continue;
                }
                if (flank(b) == 0 || b.pos.equals("GK")) {
                    central = false;
                } else if (flank(b) < 0) {
                    perSide0++;
                } else {
                    perSide1++;
                }
            }
            check(e.getKey() + ": niciun jucator central nu mai e mutat", central);
            check(e.getKey() + ": cel mult un om pe fiecare banda", perSide0 <= 1 && perSide1 <= 1);
            check(e.getKey() + ": pe banda acoperita nu se muta nimeni",
                    (!covered(before, -1) || perSide0 == 0) && (!covered(before, 1) || perSide1 == 0));
        }
        List<Zones.Spec> none = slots(allFormations().get("fara atacanti"));
        List<Zones.Spec> after = copy(none);
        Zones.supportAttack(after);
        boolean nobodyInBox = true;
        for (Zones.Spec s : after) {
            nobodyInBox &= s.high[0] < Zones.ARRIVE_X;
        }
        check("fara atacanti, nimeni nu e impins in careu", nobodyInBox);
    }

    private static void defensiveShapeNeverChanges() {
        boolean ok = true;
        for (List<String> positions : allFormations().values()) {
            List<Zones.Spec> before = slots(positions);
            List<Zones.Spec> after = copy(before);
            Zones.supportAttack(after);
            for (int i = 0; i < 11; i++) {
                ok &= Arrays.equals(before.get(i).deep, after.get(i).deep)
                        && Arrays.equals(before.get(i).dbox, after.get(i).dbox);
            }
        }
        check("in aparare fiecare ramane la locul lui (deep si dbox neschimbate)", ok);
    }

    private static void teamsUseItOnTheNewPath() {
        Map<String, Object> home = RunsTest.teamOf("Rosii", 12, "3-5-2");
        Sim s = new Sim(home, GoalRulesTest.team("Albii", 12), 1, 10.0, 10, false);
        Team team = s.teams[0];
        boolean up = true;
        boolean defence = true;
        for (Player p : team.players) {
            if (p.pos.startsWith("WB")) {
                up &= p.high[0] >= Zones.WIDE_X && p.abox0[1] >= p.high[0];
                Zones.Spec raw = Zones.POS.get(p.pos);
                defence &= Arrays.equals(p.dbox, raw.dbox) && p.deep[0] == raw.deep[0];
            }
        }
        check("in meci (drumul nou) fundasii laterali din 3-5-2 urca pe banda", up);
        check("in meci zona lor de aparare e cea a pozitiei", defence);
    }
}
