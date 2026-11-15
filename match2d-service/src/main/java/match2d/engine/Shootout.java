package match2d.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loviturile de departajare ale meciului eliminatoriu, jucate dupa prelungiri (din {@link Sim#run}).
 *
 * <p>Cine trage primul: la sorti, din generatorul meciului. Cate 5 de fiecare, alternativ, cu oprire
 * cand rezultatul e decis; apoi cate una de fiecare pana la departajare. Executantii: intai
 * executantul de penalty al echipei (din tactica, altfel cel mai bun la penaltyTaking), apoi ceilalti
 * dupa penaltyTaking descrescator (la egalitate composure), portarul ultimul; dupa toti 11, ordinea
 * se reia. Sansa fiecarei lovituri e cea a penalty-ului din joc ({@link SetPiece#penGoalProb}, cu
 * aceeasi ratare pe langa {@code pen_miss_base} si aceeasi tinta {@link SetPiece#penAim}).
 *
 * <p>Loviturile nu sunt goluri: nu intra in scor, in statistici sau in note. Fiecare are evenimentul
 * {@code SHOOTOUT_KICK} si cadrele ei (executantul vine la punctul de la 11 m, trage, portarul
 * apara sau nu), toate la aceeasi poarta (x = L). Cadrele se scriu direct, fara fizica meciului.
 */
final class Shootout {
    static final double WALK = 3.0;          // executantul vine de la linia coechipierilor
    static final double RUN_UP = 0.6;        // elanul
    static final double AFTER = 1.8;         // dupa lovitura
    static final double LEAD = 2.0;          // asezarea, inainte de prima lovitura
    static final double TAIL = 2.0;          // dupa ultima
    static final double WALK_SPEED = 7.0;

    private Shootout() {
    }

    /** Ordinea executantilor echipei: executantul de penalty, ceilalti dupa penaltyTaking / composure, portarul ultimul. */
    static List<Player> order(Team team) {
        List<Player> rest = new ArrayList<>();
        for (Player p : team.players) {
            if (p != team.penTaker && !p.isGk) {
                rest.add(p);
            }
        }
        rest.sort((a, b) -> a.r[A.penaltyTaking] != b.r[A.penaltyTaking]
                ? Double.compare(b.r[A.penaltyTaking], a.r[A.penaltyTaking])
                : Double.compare(b.r[A.composure], a.r[A.composure]));
        List<Player> out = new ArrayList<>();
        if (team.penTaker != null) {
            out.add(team.penTaker);
        }
        out.addAll(rest);
        for (Player p : team.players) {
            if (p.isGk && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Rezultatul e decis dupa {@code taken} lovituri si {@code scored} goluri ale fiecarei echipe. */
    static boolean decided(int[] taken, int[] scored) {
        if (taken[0] <= 5 && taken[1] <= 5) {
            return scored[0] + (5 - taken[0]) < scored[1] || scored[1] + (5 - taken[1]) < scored[0];
        }
        return taken[0] == taken[1] && scored[0] != scored[1];
    }

    // ------------------------------------------------------------------ scena
    private static final class Scene {
        final Sim s;
        final double[] tx = new double[22];
        final double[] ty = new double[22];
        final double[] speed = new double[22];

        Scene(Sim s) {
            this.s = s;
        }

        void target(Player p, double x, double y, double v) {
            tx[p.uid] = x;
            ty[p.uid] = y;
            speed[p.uid] = v;
        }

        /** Un pas de 0.1 s: fiecare jucator merge spre tinta lui; cadrul se scrie la cadenta meciului. */
        void step() {
            s.t += Sim.DT;
            s.tickNo++;
            for (Player p : s.all) {
                double dx = tx[p.uid] - p.x;
                double dy = ty[p.uid] - p.y;
                double d = Sim.hyp(dx, dy);
                double m = speed[p.uid] * Sim.DT;
                if (d <= m || d < 1e-9) {
                    p.x = tx[p.uid];
                    p.y = ty[p.uid];
                    p.vx = 0.0;
                    p.vy = 0.0;
                } else {
                    p.vx = dx / d * speed[p.uid];
                    p.vy = dy / d * speed[p.uid];
                    p.x += dx / d * m;
                    p.y += dy / d * m;
                }
            }
            if (s.tickNo % s.recEvery == 0) {
                s.record();
            }
        }

        void wait(double seconds) {
            int n = (int) Math.round(seconds / Sim.DT);
            for (int i = 0; i < n; i++) {
                step();
            }
        }
    }

    /** Locul de asteptare al jucatorului (linia echipei lui, la marginea terenului de joc al departajarii). */
    private static double[] home(Player p, int slot) {
        if (p.isGk) {
            return new double[] {Geo.L - 18.0, Geo.MID_Y + 24.0 + 3.0 * p.team.index};
        }
        double x = Geo.L - 31.0 - 2.0 * p.team.index;
        double y = Geo.MID_Y - 13.5 + 3.0 * slot;
        return new double[] {x, y};
    }

    /** Joaca departajarea; pune {@code s.shootout} si {@code s.penalties}. */
    static void play(Sim s) {
        Mt rng = s.rng;
        Ball b = s.ball;
        s.state = Sim.DEAD;
        s.rkind = "shootout";
        s.shot = null;
        s.pending = null;
        s.sp = null;
        b.holder = null;
        b.kind = Ball.DEAD;
        b.v = 0.0;
        b.z = 0.0;
        double gx = Geo.L;
        double spotX = Geo.L - Wt.pen_spot;
        int first = rng.random() < 0.5 ? 0 : 1;
        Scene sc = new Scene(s);
        // asezarea: fiecare la locul lui (taietura in cadre, ca la pauza)
        double[][] homes = new double[22][];
        for (Team team : s.teams) {
            int slot = 0;
            for (Player p : team.players) {
                homes[p.uid] = home(p, p.isGk ? 0 : slot++);
                p.x = homes[p.uid][0];
                p.y = homes[p.uid][1];
                p.vx = 0.0;
                p.vy = 0.0;
                p.role = Sim.R_NONE;
                sc.target(p, p.x, p.y, 0.0);
            }
        }
        b.x = spotX;
        b.y = Geo.MID_Y;
        b.px = b.x;
        b.py = b.y;
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("frame", s.frameNo);
        h.put("dirs", List.of(s.teams[0].dir, s.teams[1].dir));
        h.put("period", "penalties");
        s.halves.add(h);
        Map<String, Object> start = s.ev("SHOOTOUT", s.teams[first], null, null,
                "Lovituri de departajare: începe " + s.teams[first].name);
        start.put("first", first);
        sc.wait(LEAD);

        List<List<Player>> orders = List.of(order(s.teams[0]), order(s.teams[1]));
        int[] next = new int[2];
        int[] taken = new int[2];
        int[] scored = new int[2];
        List<Map<String, Object>> log = new ArrayList<>();
        Player prev = null;
        int kickNo = 0;
        while (!decided(taken, scored)) {
            int ti = taken[0] == taken[1] ? first : 1 - first;
            Team team = s.teams[ti];
            Player gk = s.opp(team).gk;
            List<Player> ord = orders.get(ti);
            Player tk = ord.get(next[ti] % ord.size());
            next[ti]++;
            kickNo++;
            // asezarea: executantul vine la minge, cel de dinainte se intoarce, portarii la locul lor
            for (Player p : s.all) {
                sc.target(p, homes[p.uid][0], homes[p.uid][1], WALK_SPEED);
            }
            sc.target(gk, gx - 0.3, Geo.MID_Y, WALK_SPEED);
            double rx = spotX - 3.0;
            double ry = Geo.MID_Y + 1.2;
            sc.target(tk, rx, ry, Math.max(WALK_SPEED, Sim.hyp(rx - tk.x, ry - tk.y) / WALK));
            b.x = spotX;
            b.y = Geo.MID_Y;
            b.z = 0.0;
            sc.wait(WALK);
            sc.target(tk, spotX - 0.5, Geo.MID_Y, Sim.hyp(spotX - 0.5 - tk.x, Geo.MID_Y - tk.y) / RUN_UP);
            sc.wait(RUN_UP);
            // lovitura: sansa si tinta ca la penalty-ul din joc
            double pg = SetPiece.penGoalProb(tk, gk);
            double pm = Math.min(Wt.pen_miss_base, 1.0 - pg);
            int outcome = SetPiece.penOutcome(rng.random(), pg, pm);
            double[] aim = SetPiece.penAim(rng, outcome);
            double kickSpeed = Math.max(8.0, SetPiece.penSpeed(tk, rng));
            boolean goal = outcome == Sim.ShotState.GOAL;
            double ex = goal ? gx + 1.2 : outcome == Sim.ShotState.MISS ? gx + 1.5 : gx - 0.6;
            double ey = aim[0];
            double zEnd = outcome == Sim.ShotState.SAVE ? Math.min(aim[1], 1.6) : aim[1];
            double dist = Sim.hyp(ex - spotX, ey - Geo.MID_Y);
            double flight = Geo.clamp(dist / kickSpeed, 0.3, 1.0);
            taken[ti]++;
            if (goal) {
                scored[ti]++;
            }
            String text = goal ? "Departajare: " + tk.shortName + " transformă"
                    : outcome == Sim.ShotState.SAVE ? "Departajare: " + gk.shortName + " apără lovitura lui " + tk.shortName
                    : "Departajare: " + tk.shortName + " ratează";
            Map<String, Object> e = s.ev("SHOOTOUT_KICK", team, tk, gk, text + " (" + scored[0] + " - " + scored[1] + ")",
                    spotX, Geo.MID_Y, Geo.clamp(ex, 0.0, Geo.L), Geo.clamp(ey, -3.0, Geo.W + 3.0));
            e.put("scored", goal);
            e.put("kick", kickNo);
            e.put("round", taken[ti]);
            e.put("score", List.of(scored[0], scored[1]));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team", ti);
            row.put("uid", tk.uid);
            row.put("playerId", tk.id);
            row.put("player", tk.name);
            row.put("scored", goal);
            log.add(row);
            // portarul: la parada ajunge la minge, la gol se arunca in partea cealalta
            double side = ey < Geo.MID_Y ? -1.0 : 1.0;
            double kx = gx - 0.5;
            double ky = outcome == Sim.ShotState.SAVE ? ey : Geo.MID_Y - side * 2.2;
            sc.target(gk, kx, ky, Math.max(1.0, Sim.hyp(kx - gk.x, ky - gk.y) / flight));
            int n = Math.max(1, (int) Math.round(flight / Sim.DT));
            for (int i = 1; i <= n; i++) {
                double u = (double) i / n;
                b.x = spotX + (ex - spotX) * u;
                b.y = Geo.MID_Y + (ey - Geo.MID_Y) * u;
                b.z = zEnd * u;
                sc.step();
            }
            if (!goal && outcome == Sim.ShotState.SAVE) {
                b.z = 0.0;
            }
            sc.target(tk, tk.x, tk.y, 0.0);
            sc.wait(AFTER);
            b.z = 0.0;
            prev = tk;
        }
        if (prev != null) {
            for (Player p : s.all) {
                sc.target(p, homes[p.uid][0], homes[p.uid][1], WALK_SPEED);
            }
            b.x = spotX;
            b.y = Geo.MID_Y;
            sc.wait(TAIL);
        }
        s.shootout = log;
        s.penalties = scored;
    }
}
