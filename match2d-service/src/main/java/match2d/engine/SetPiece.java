package match2d.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Fazele fixe (etapa A): faulturile din dueluri, cartonasele galbene, lovitura libera directa si
 * penalty-ul, plus cadrul de asezare la faza fixa. Tot ce e aici ruleaza doar pe drumul nou
 * ({@code !Sim.LEGACY}); cu {@code -Dmatch2d.legacy=true} motorul nu ajunge in clasa asta.
 *
 * <p><b>Asezarea.</b> La o repunere de tip lovitura libera directa sau penalty (in etapa B si
 * corner, lovitura libera centrata) se calculeaza un loc-tinta (coordonate de lume) pentru o parte
 * din jucatori, pe {@code uid}. {@link Movement#think} ii trimite acolo cat mingea e moarta, iar
 * {@link Sim#tickDead} asteapta pana ajung toti la cel mult {@code setpiece_near} m (dar cel mult
 * {@code setpiece_wait_max} s). La executie structura se sterge. Cine nu are loc se aseaza ca azi.
 *
 * <p><b>Cartonasele.</b> Exista doar galbenul: cine are deja galben nu mai primeste altul si nu
 * exista eliminare (motorul joaca mereu 11 la 11). Jucatorul cu galben devine prudent: iese mai
 * rar la deposedare ({@code booked_eager}) si faulteaza mai rar ({@code foul_booked}).
 */
final class SetPiece {
    /** felul fazei fixe: "fk_shot", "penalty" (in etapa B si "corner", "fk_cross"). */
    final String kind;
    /** locurile-tinta, pe uid (lume); {@code has[uid]} = jucatorul are loc. */
    final double[] x = new double[22];
    final double[] y = new double[22];
    final boolean[] has = new boolean[22];
    /** oamenii din zid (lovitura libera directa), in ordine pe latime. */
    Player[] wall = new Player[0];
    /** tintele centrarii (corner, lovitura libera centrata): atacantii buni la jocul aerian. */
    Player[] targets = new Player[0];

    private SetPiece(String kind) {
        this.kind = kind;
    }

    private void put(Player p, double wx, double wy) {
        x[p.uid] = Geo.clamp(wx, 0.3, Geo.L - 0.3);
        y[p.uid] = Geo.clamp(wy, 0.3, Geo.W - 0.3);
        has[p.uid] = true;
    }

    /** Toti cei cu loc au ajuns la cel mult {@code setpiece_near} m de el. */
    boolean placed(Sim s) {
        double near2 = Wt.setpiece_near * Wt.setpiece_near;
        for (Player p : s.all) {
            if (has[p.uid]) {
                double dx = x[p.uid] - p.x;
                double dy = y[p.uid] - p.y;
                if (dx * dx + dy * dy > near2) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean inWall(Player p) {
        for (Player q : wall) {
            if (q == p) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ faultul
    /**
     * Tragerea la sorti pentru fault, inaintea rezolvarii duelului dintre {@code o} (apararea) si
     * purtatorul {@code c}. {@code front} e cel din {@link BallPlay#doTackle}: negativ = atacat din
     * spate. Intoarce true daca a fost fault (jocul s-a oprit si urmeaza repunerea).
     */
    static boolean foul(Sim s, Player o, Player c, double front) {
        Team ot = o.team;
        boolean behind = front < 0.0;
        // locul faultului, in canonicul celui care faulteaza (poarta lui e la x = 0)
        double fcx = Geo.cx(c.x, ot.dir);
        double fcy = Geo.cy(c.y, ot.dir);
        boolean box = fcx <= 16.5 && Math.abs(fcy - Geo.MID_Y) <= 20.16;
        double pf = Wt.foul_base * (1.0 + Wt.foul_aggression * (o.n[A.aggression] - 0.5)
                - Wt.foul_tackling * (o.n[A.tackling] - 0.5)) * ot.foulFreq
                * (behind ? Wt.foul_behind : 1.0) * (box ? Wt.foul_box : 1.0) * (o.booked ? Wt.foul_booked : 1.0);
        pf = Geo.clamp(pf, 0.0, Wt.foul_max);
        if (pf <= 0.0 || s.rng.random() >= pf) {
            return false;
        }
        commit(s, o, c, behind, false);
        return true;
    }

    /** {@code p} e in careul propriu (locul ({@code x}, {@code y}), in canonicul echipei lui). */
    private static boolean inOwnBoxAt(Team team, double x, double y) {
        double cx = Geo.cx(x, team.dir);
        double cy = Geo.cy(y, team.dir);
        return cx <= 16.5 && Math.abs(cy - Geo.MID_Y) <= 20.16;
    }

    /** Sansa ca {@code p} sa faulteze la duelul aerian. */
    private static double aerialFoulProb(Player p) {
        double pf = Wt.aerial_foul_base * (1.0 + Wt.aerial_foul_aggression * (p.n[A.aggression] - 0.5))
                * p.team.foulFreq * (inOwnBoxAt(p.team, p.x, p.y) ? Wt.foul_box : 1.0)
                * (p.booked ? Wt.foul_booked : 1.0);
        return Geo.clamp(pf, 0.0, Wt.foul_max);
    }

    /**
     * Faultul la duelul aerian dintre primitorul {@code q} si {@code rival} (impingere la
     * saritura): o singura tragere la sorti; daca iese, faulteaza unul dintre ei, cu sansa lui.
     * Intoarce true daca a fost fault.
     */
    static boolean aerialFoul(Sim s, Player q, Player rival) {
        double pq = aerialFoulProb(q);
        double pr = aerialFoulProb(rival);
        if (pq + pr <= 0.0) {
            return false;
        }
        double r = s.rng.random();
        if (r >= pq + pr) {
            return false;
        }
        Player o = r < pq ? q : rival;
        commit(s, o, o == q ? rival : q, false, true);
        return true;
    }

    /**
     * Faultul s-a produs: eveniment, poate galben, apoi penalty (locul e in careul celui care a
     * faultat) sau lovitura libera pentru cel faultat, din locul lui.
     */
    private static void commit(Sim s, Player o, Player c, boolean behind, boolean aerial) {
        Team ot = o.team;
        double fcx = Geo.cx(c.x, ot.dir);
        double fcy = Geo.cy(c.y, ot.dir);
        boolean box = fcx <= 16.5 && Math.abs(fcy - Geo.MID_Y) <= 20.16;
        ot.fouls++;
        Map<String, Object> e = s.ev("FOUL", ot, o, c, "Fault: " + o.shortName + " asupra lui " + c.shortName,
                c.x, c.y, Double.NaN, Double.NaN);
        e.put("behind", behind);
        if (aerial) {
            e.put("aerial", true);
        }
        if (!o.booked) {
            boolean danger = Sim.hyp(fcx, fcy - Geo.MID_Y) < Wt.card_danger_dist
                    && Math.abs(fcy - Geo.MID_Y) < Wt.card_danger_width;
            double pc = Wt.card_base * ot.foulHard * (1.0 + Wt.card_aggression * (o.n[A.aggression] - 0.5))
                    * (behind ? Wt.card_behind : 1.0) * (danger ? Wt.card_danger : 1.0)
                    * (aerial ? Wt.aerial_card : 1.0);
            if (s.rng.random() < pc) {
                // doar galben: al doilea nu se mai da, nu exista eliminare (mereu 11 la 11)
                o.booked = true;
                ot.yellowCards++;
                Map<String, Object> ce = s.ev("CARD", ot, o, null, "Cartonaș galben: " + o.shortName);
                ce.put("card", "yellow");
            }
        }
        Team fouled = c.team;
        if (box) {
            double spotX = Geo.cx(Geo.L - Wt.pen_spot, fouled.dir);
            s.setRestart("penalty", fouled, spotX, Geo.MID_Y);
        } else {
            s.setRestart("free_kick", fouled, c.x, c.y);
        }
    }

    /**
     * Mingea a iesit peste linia portii de pe un aparator (parada respinsa, blocaj, respingere cu
     * capul): corner pentru {@code attacking}, pe partea pe care e mingea ({@code y}).
     */
    static void toCorner(Sim s, Team attacking, double y) {
        double gx = attacking.dir == 1 ? Geo.L : 0.0;
        attacking.corners++;
        s.ev("CORNER", attacking, null, null, "Corner pentru " + attacking.name);
        s.setRestart("corner", attacking, gx, y < Geo.MID_Y ? 0.0 : Geo.W);
    }

    // ------------------------------------------------------------------ repunerea
    /** Felul loviturii libere dupa loc, in canonicul echipei care bate (poarta adversa la x = L). */
    static String fkKind(Team team, double x, double y) {
        double cx = Geo.cx(x, team.dir);
        double cy = Geo.cy(y, team.dir);
        double dx = Geo.L - cx;
        double dy = Math.abs(cy - Geo.MID_Y);
        if (dx > 0.0 && Sim.hyp(dx, dy) <= Wt.fk_shot_range
                && Math.toDegrees(Math.atan2(dy, dx)) <= Wt.fk_shot_angle) {
            return "shot";
        }
        if (cx >= Wt.fk_cross_from) {
            return "cross";         // etapa A: se joaca la fel ca "play"
        }
        return "play";
    }

    /** Din {@link Sim#setRestart}: evenimentul fazei fixe, statisticile si locurile jucatorilor. */
    static void onRestart(Sim s, String kind, Team team) {
        Ball b = s.ball;
        if (kind.equals("free_kick")) {
            team.freeKicks++;
            Map<String, Object> e = s.ev("FREE_KICK", team, s.taker, null, "Lovitură liberă pentru " + team.name,
                    b.x, b.y, Double.NaN, Double.NaN);
            e.put("kind", s.fkKind);
            if ("shot".equals(s.fkKind)) {
                s.sp = planFreeKick(s, team);
            } else if ("cross".equals(s.fkKind)) {
                s.sp = planFkCross(s, team);
            }
        } else if (kind.equals("corner")) {
            s.sp = planCorner(s, team);
        } else if (kind.equals("penalty")) {
            team.penalties++;
            s.ev("PENALTY", team, s.taker, null, "Penalty pentru " + team.name + "!", b.x, b.y,
                    Double.NaN, Double.NaN);
            s.sp = planPenalty(s, team);
        }
    }

    /**
     * Zidul la lovitura libera directa: 3 oameni (4 sub {@code fk_wall_close} m) ai echipei care
     * se apara, la {@code fk_wall_dist} m de minge, pe linia spre poarta mutata ca zidul sa acopere
     * coltul scurt; sunt jucatorii de camp cei mai apropiati de locurile zidului.
     */
    static SetPiece planFreeKick(Sim s, Team att) {
        SetPiece sp = new SetPiece("fk_shot");
        Team def = s.opp(att);
        Ball b = s.ball;
        double gx = att.dir == 1 ? Geo.L : 0.0;
        double dist = Sim.hyp(gx - b.x, Geo.MID_Y - b.y);
        int n = (int) Math.round(dist < Wt.fk_wall_close ? Wt.fk_wall_n_close : Wt.fk_wall_n);
        double gap = Wt.fk_wall_gap;
        double side = b.y < Geo.MID_Y ? -1.0 : 1.0;      // partea barei apropiate (lume)
        double halfSpan = (n - 1) * gap / 2.0 + 0.35;
        double f = Math.max(1.0, dist / Wt.fk_wall_dist);
        // marginea zidului, prelungita pana la poarta, cade putin in afara barei apropiate
        double aimY = Geo.MID_Y + side * (Geo.GOAL_HALF + 0.3 - halfSpan * f);
        double ax = gx - b.x;
        double ay = aimY - b.y;
        double al = Sim.hyp(ax, ay);
        if (al < 1e-6) {
            al = 1.0;
        }
        double ux = ax / al;
        double uy = ay / al;
        double cxw = b.x + ux * Wt.fk_wall_dist;
        double cyw = b.y + uy * Wt.fk_wall_dist;
        List<Player> free = new ArrayList<>();
        for (Player q : def.players) {
            if (!q.isGk) {
                free.add(q);
            }
        }
        Player[] wall = new Player[n];
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * gap;
            double wx = cxw - uy * off;
            double wy = cyw + ux * off;
            Player best = null;
            double bd = 0.0;
            for (Player q : free) {
                double dd = Sim.hyp(q.x - wx, q.y - wy);
                if (best == null || dd < bd) {
                    best = q;
                    bd = dd;
                }
            }
            free.remove(best);
            wall[i] = best;
            sp.put(best, wx, wy);
        }
        sp.wall = wall;
        return sp;
    }

    /**
     * Penalty-ul: portarul pe linia portii; jucatorii de camp ai apararii si cei mai avansati 4
     * atacanti (fara executant) se insira, amestecati, pe o linie la {@code pen_line} m de linia
     * portii, intre -{@code pen_line_half} si +{@code pen_line_half} m de ax. Restul atacantilor
     * stau ca de obicei, scosi din careu (vezi {@link Movement#think}).
     */
    static SetPiece planPenalty(Sim s, Team att) {
        SetPiece sp = new SetPiece("penalty");
        Team def = s.opp(att);
        Player tk = s.taker;
        sp.put(def.gk, Geo.cx(Geo.L - 0.3, att.dir), Geo.MID_Y);
        List<Player> line = new ArrayList<>();
        for (Player q : def.players) {
            if (!q.isGk) {
                line.add(q);
            }
        }
        List<Player> fwd = new ArrayList<>();
        for (Player q : att.players) {
            if (!q.isGk && q != tk) {
                fwd.add(q);
            }
        }
        fwd.sort((a, c) -> Double.compare(Geo.cx(c.x, att.dir), Geo.cx(a.x, att.dir)));
        line.addAll(fwd.subList(0, Math.min(4, fwd.size())));
        Player[] order = line.toArray(new Player[0]);
        Arrays.sort(order, (a, c) -> Double.compare(a.y, c.y));       // amestecati, dupa latime
        double lx = Geo.cx(Geo.L - Wt.pen_line, att.dir);
        int n = order.length;
        for (int i = 0; i < n; i++) {
            double wy = Geo.MID_Y - Wt.pen_line_half + (n > 1 ? 2.0 * Wt.pen_line_half * i / (n - 1) : Wt.pen_line_half);
            sp.put(order[i], lx, wy);
        }
        return sp;
    }

    /** Pune {@code p} la locul dat in canonicul echipei {@code att} (poarta atacata la x = L). */
    private void putC(Player p, Team att, double cx, double cy) {
        put(p, Geo.cx(cx, att.dir), Geo.cy(cy, att.dir));
    }

    /** Jucatorii de camp ai echipei, fara {@code skip}, cei mai buni la jocul aerian primii. */
    private static List<Player> byAir(Team team, Player skip) {
        List<Player> out = new ArrayList<>();
        for (Player q : team.players) {
            if (!q.isGk && q != skip) {
                out.add(q);
            }
        }
        out.sort((a, c) -> Double.compare(c.air, a.air));
        return out;
    }

    /**
     * Cornerul. Atacul: cei mai buni 5 la jocul aerian in careu (marginea careului mic, punctul de
     * la 11 m, bara indepartata, bara apropiata, spatele careului), unul la marginea careului mare
     * pentru respingeri, ceilalti in urma, langa centru. Apararea: portarul pe linie, cate un om pe
     * fiecare atacant din careu (cel mai bun la jocul aerian pe cel mai bun), intre el si poarta;
     * unul la bara apropiata, unul la marginea careului, cel mai avansat ramane sus.
     */
    static SetPiece planCorner(Sim s, Team att) {
        SetPiece sp = new SetPiece("corner");
        Team def = s.opp(att);
        Ball b = s.ball;
        double L = Geo.L;
        double mid = Geo.MID_Y;
        double sgn = Geo.cy(b.y, att.dir) < mid ? -1.0 : 1.0;      // partea cornerului (canonic)
        double[][] boxSpots = {
            {L - 6.0, mid - sgn * 0.5},     // marginea careului mic
            {L - 11.0, mid},                // punctul de la 11 m
            {L - 4.5, mid - sgn * 3.5},     // bara indepartata
            {L - 4.0, mid + sgn * 2.5},     // bara apropiata
            {L - 13.0, mid - sgn * 8.0},    // spatele careului
        };
        List<Player> atk = byAir(att, s.taker);
        int nBox = Math.min(boxSpots.length, atk.size());
        Player[] targets = new Player[nBox];
        for (int i = 0; i < nBox; i++) {
            targets[i] = atk.get(i);
            sp.putC(targets[i], att, boxSpots[i][0], boxSpots[i][1]);
        }
        sp.targets = targets;
        List<Player> rest = new ArrayList<>(atk.subList(nBox, atk.size()));
        if (!rest.isEmpty()) {
            // la marginea careului mare: cel mai bun la sutul de departe
            Player edge = rest.get(0);
            for (Player q : rest) {
                if (q.n[A.longShots] > edge.n[A.longShots]) {
                    edge = q;
                }
            }
            rest.remove(edge);
            sp.putC(edge, att, L - 20.0, mid + sgn * 2.0);
        }
        for (int i = 0; i < rest.size(); i++) {
            sp.putC(rest.get(i), att, 55.0 - 3.0 * (i / 3), mid + 14.0 * ((i % 3) - 1));
        }
        // apararea (tot in canonicul atacului)
        sp.putC(def.gk, att, L - 0.5, mid);
        List<Player> dfs = byAir(def, null);
        Player up = null;
        for (Player q : dfs) {
            if (up == null || Geo.cx(q.x, att.dir) < Geo.cx(up.x, att.dir)) {
                up = q;
            }
        }
        dfs.remove(up);
        sp.putC(up, att, L - 35.0, mid - sgn * 6.0);
        int k = 0;
        for (int i = 0; i < nBox && k < dfs.size(); i++, k++) {
            int ti = i;
            double tx = boxSpots[ti][0];
            double ty = boxSpots[ti][1];
            double gl = Sim.hyp(L - tx, mid - ty);
            sp.putC(dfs.get(k), att, tx + (L - tx) / gl * 1.2, ty + (mid - ty) / gl * 1.2);
        }
        double[][] zone = {
            {L - 0.8, mid + sgn * 3.2},     // bara apropiata
            {L - 16.5, mid},                // marginea careului
            {L - 8.5, mid + sgn * 6.0},
            {L - 12.0, mid - sgn * 2.0},
        };
        for (int z = 0; k < dfs.size(); k++, z++) {
            double[] at = zone[Math.min(z, zone.length - 1)];
            sp.putC(dfs.get(k), att, at[0], at[1] + (z >= zone.length ? 2.0 * (z - zone.length + 1) : 0.0));
        }
        return sp;
    }

    /**
     * Lovitura libera centrata: cei mai buni 5 la jocul aerian pe o linie la marginea careului
     * (cu un pas in urma liniei apararii, deci nu in ofsaid), apararea cu cate un om intre fiecare
     * dintre ei si poarta, plus unul in fata portii. Restul se aseaza ca de obicei.
     */
    static SetPiece planFkCross(Sim s, Team att) {
        SetPiece sp = new SetPiece("fk_cross");
        Team def = s.opp(att);
        double L = Geo.L;
        double mid = Geo.MID_Y;
        double line = L - 17.0;
        double[] offY = {-5.0, 5.0, 0.0, -10.0, 10.0};
        List<Player> atk = byAir(att, s.taker);
        int n = Math.min(offY.length, atk.size());
        Player[] targets = new Player[n];
        for (int i = 0; i < n; i++) {
            targets[i] = atk.get(i);
            sp.putC(targets[i], att, line - 1.5, mid + offY[i]);
        }
        sp.targets = targets;
        List<Player> dfs = byAir(def, null);
        int k = 0;
        for (int i = 0; i < n && k < dfs.size(); i++, k++) {
            sp.putC(dfs.get(k), att, line - 0.3, mid + offY[i]);       // intre atacant si poarta
        }
        if (k < dfs.size()) {
            sp.putC(dfs.get(k), att, L - 6.0, mid);
        }
        return sp;
    }

    /** Alege tinta centrarii dintre atacantii din careu, cu sanse mai mari pentru cei buni la cap. */
    private static Player pickTarget(Sim s, SetPiece plan) {
        Player[] ts = plan.targets;
        double tot = 0.0;
        double[] w = new double[ts.length];
        for (int i = 0; i < ts.length; i++) {
            w[i] = Math.exp(Wt.corner_pick_air * (ts[i].air - 0.5));
            tot += w[i];
        }
        double r = s.rng.random() * tot;
        for (int i = 0; i < ts.length; i++) {
            r -= w[i];
            if (r < 0.0) {
                return ts[i];
            }
        }
        return ts[ts.length - 1];
    }

    /** Cornerul: minge inalta spre un atacant din careu (fara ofsaid). */
    static void takeCorner(Sim s, Player tk, SetPiece plan) {
        start(s, tk);
        s.restartKind = "corner";
        Player m = pickTarget(s, plan);
        double dq = Sim.hyp(m.x - tk.x, m.y - tk.y);
        double dur = 0.75 + dq / 24.0;
        Decision.doPass(s, tk, m, m.x, m.y, true, dur, false, 0.5, 0.0, 2);
    }

    /** Lovitura libera centrata: mingea la ~9.5 m de linia portii, in dreptul atacantului ales. */
    static void takeFkCross(Sim s, Player tk, SetPiece plan) {
        start(s, tk);
        s.restartKind = "free_kick";        // pasa de dupa lovitura libera: ofsaidul se verifica
        Player m = pickTarget(s, plan);
        Team att = tk.team;
        double cy = Geo.clamp(Geo.cy(m.y, att.dir), Geo.MID_Y - 10.0, Geo.MID_Y + 10.0);
        double qx = Geo.cx(Geo.L - Wt.fk_cross_depth, att.dir);
        double qy = Geo.cy(cy, att.dir);
        double dq = Sim.hyp(qx - tk.x, qy - tk.y);
        double dur = 0.75 + dq / 24.0;
        Decision.doPass(s, tk, m, qx, qy, true, dur, false, 0.5, 0.0, 3);
    }

    // ------------------------------------------------------------------ executia
    /** Mingea moarta devine vie la executant (ca in {@link Sim#tickDead}), fara decizie. */
    private static void start(Sim s, Player tk) {
        Ball b = s.ball;
        s.state = Sim.LIVE;
        s.restartKind = null;
        s.poss = tk.team;
        s.gkHold = false;
        b.lastTeam = tk.team;
        // executantul bate din locul mingii
        tk.x = b.x;
        tk.y = b.y;
        tk.vx = 0.0;
        tk.vy = 0.0;
        tk.gainT = Double.NaN;
        tk.assistFrom = null;
        tk.carryTo = null;
        tk.path = new ArrayList<>();
        tk.tx = tk.x;
        tk.ty = tk.y;
    }

    /** Sansa sutului sa ramana in zid, dupa freeKick. */
    static double wallProb(Player p) {
        return Geo.clamp(Wt.fk_wall_base - Wt.fk_wall_freeKick * (p.n[A.freeKick] - 0.5), 0.0, 0.95);
    }

    /**
     * Executia loviturii libere directe: precizia (unghi si inaltime) vine din freeKick, puterea
     * din technique si strength ca la sut. Tinteste coltul mai departat de portar.
     */
    static Shooting.Kick fkKick(Sim s, Player p, Mt rng) {
        Team team = p.team;
        double[] n = p.n;
        double px = p.x;
        double py = p.y;
        double gx = team.dir == 1 ? Geo.L : 0.0;
        Player gk = s.opp(team).gk;
        double distG = Sim.hyp(gx - px, Geo.MID_Y - py);
        double side = gk.y < Geo.MID_Y ? 1.0 : -1.0;
        if (rng.random() < 0.25) {
            side = -side;
        }
        double aimY = Geo.MID_Y + side * (Geo.GOAL_HALF - Wt.fk_aim_margin);
        double sigma = Math.max(Wt.fk_error_floor, Wt.fk_error_base - Wt.fk_error_freeKick * (n[A.freeKick] - 0.5));
        double ang = PMath.atan2(aimY - py, gx - px) + rng.gauss(0.0, sigma);
        double speed = Wt.fk_power * (Wt.shot_power_base + Wt.shot_power_technique * n[A.technique]
                + Wt.shot_power_strength * n[A.strength]) + rng.gauss(0.0, 1.0);
        double zsd = Math.max(Wt.fk_height_sd_floor, Wt.fk_height_sd - Wt.fk_height_freeKick * (n[A.freeKick] - 0.5));
        double zEnd = Math.max(0.0, rng.gauss(Wt.fk_height_base, zsd));
        return Shooting.kickAlong(px, py, gx, ang, speed, zEnd, distG);
    }

    /** Sansa de gol a loviturii libere directe (ca {@link Shooting#shotXg}, pe {@code s.xrng}). */
    static double fkXg(Sim s, Player p, SetPiece plan, double pWall) {
        final int samples = 16;
        Team opp = s.opp(p.team);
        Player gk = opp.gk;
        double total = 0.0;
        for (int i = 0; i < samples; i++) {
            Shooting.Kick k = fkKick(s, p, s.xrng);
            if (!k.onTarget) {
                continue;
            }
            double free = plan.wall.length > 0 ? 1.0 - pWall : 1.0;
            for (Player o : opp.players) {
                if (o.isGk || plan.inWall(o)) {
                    continue;
                }
                double pb = Shooting.blockProb(p, o, k);
                if (pb >= 0.0) {
                    free *= 1.0 - pb;
                }
            }
            double gu = Geo.clamp(Geo.segU(gk.x, gk.y, p.x, p.y, k.ex, k.ey), 0.05, 1.0);
            double dlat = Geo.segDist(gk.x, gk.y, p.x, p.y, k.ex, k.ey);
            total += free * (1.0 - Shooting.saveProb(s, p, k, false, gu, dlat, Wt.fk_gk_late));
        }
        return total / samples;
    }

    /** Lovitura libera directa: un sut din locul mingii, cu zidul verificat o singura data. */
    static void takeFreeKick(Sim s, Player tk, SetPiece plan) {
        start(s, tk);
        Mt rng = s.rng;
        Team team = tk.team;
        Team opp = s.opp(team);
        double pWall = wallProb(tk);
        team.mXg += fkXg(s, tk, plan, pWall);
        s.played(tk);
        Shooting.Kick k = fkKick(s, tk, rng);
        team.shots++;
        team.fkShots++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mShotsLate++;
        }
        Map<String, Object> e = s.ev("SHOT", team, tk, null,
                "Lovitură liberă: șut " + tk.shortName + " de la " + (long) Math.rint(k.distG) + " m",
                Double.NaN, Double.NaN, k.ex, Geo.clamp(k.ey, -3.0, Geo.W + 3.0));
        e.put("special", false);
        e.put("freeKick", true);
        Player blocker = null;
        double bu = 2.0;
        boolean wall = false;
        if (plan.wall.length > 0 && rng.random() < pWall) {
            // in zid: il opreste omul din zid cel mai apropiat de traiectorie
            double bd = Double.POSITIVE_INFINITY;
            for (Player o : plan.wall) {
                double dd = Geo.segDist(o.x, o.y, tk.x, tk.y, k.ex, k.ey);
                if (dd < bd) {
                    bd = dd;
                    blocker = o;
                }
            }
            bu = Geo.clamp(Geo.segU(blocker.x, blocker.y, tk.x, tk.y, k.ex, k.ey), 0.05, 0.95);
            wall = true;
        } else {
            // ceilalti (nu cei din zid) pot bloca obisnuit
            for (Player o : opp.players) {
                if (o.isGk || plan.inWall(o)) {
                    continue;
                }
                double pb = Shooting.blockProb(tk, o, k);
                if (pb >= 0.0) {
                    double u = Geo.segU(o.x, o.y, tk.x, tk.y, k.ex, k.ey);
                    if (rng.random() < pb && u < bu) {
                        bu = u;
                        blocker = o;
                    }
                }
            }
        }
        Sim.ShotState sh = Shooting.launch(s, tk, k, blocker, bu, false, -1, 2);
        sh.wall = wall;
    }

    /**
     * Sansa de gol a penalty-ului (si a loviturii de departajare): penaltyTaking si composure ale
     * executantului contra portarului ((reflexes + oneOnOnes) / 2), intre 0.3 si 0.97.
     */
    static double penGoalProb(Player tk, Player gk) {
        double[] n = tk.n;
        double[] g = gk.n;
        double pg = Wt.pen_goal_base + Wt.pen_penaltyTaking * (n[A.penaltyTaking] - 0.5)
                + Wt.pen_composure * (n[A.composure] - 0.5)
                - Wt.pen_gk * ((g[A.reflexes] + g[A.oneOnOnes]) / 2.0 - 0.5);
        return Geo.clamp(pg, 0.3, 0.97);
    }

    /** Deznodamantul penalty-ului din tragerea {@code r}: gol (pg), pe langa (pm), restul parada. */
    static int penOutcome(double r, double pg, double pm) {
        return r < pg ? Sim.ShotState.GOAL : r < pg + pm ? Sim.ShotState.MISS : Sim.ShotState.SAVE;
    }

    /** Tinta penalty-ului dupa deznodamant: {aimY, inaltimea la poarta} (lume, poarta pe linia ei). */
    static double[] penAim(Mt rng, int outcome) {
        double side = rng.random() < 0.5 ? -1.0 : 1.0;
        double aimY;
        double zEnd;
        if (outcome == Sim.ShotState.MISS) {
            if (rng.random() < 0.6) {
                aimY = Geo.MID_Y + side * (Geo.GOAL_HALF + 0.3 + 1.2 * rng.random());     // pe langa bara
                zEnd = 0.3 + rng.random();
            } else {
                aimY = Geo.MID_Y + side * (Geo.GOAL_HALF - 0.5) * rng.random();          // peste poarta
                zEnd = Geo.GOAL_H + 0.3 + rng.random();
            }
        } else {
            aimY = Geo.MID_Y + side * (0.8 + (Geo.GOAL_HALF - 1.4) * rng.random());
            zEnd = 0.2 + 1.8 * rng.random();
        }
        return new double[] {aimY, zEnd};
    }

    /** Viteza mingii la penalty (technique, strength, plus zgomot). */
    static double penSpeed(Player tk, Mt rng) {
        double[] n = tk.n;
        return Wt.shot_power_base + Wt.shot_power_technique * n[A.technique]
                + Wt.shot_power_strength * n[A.strength] + rng.gauss(0.0, 1.0);
    }

    /**
     * Penalty-ul: sansa de gol din penaltyTaking si composure contra portarului
     * ((reflexes + oneOnOnes) / 2); o parte mica e ratare pe langa, restul parada (prinsa sau
     * respinsa, iar respingerea ramane in joc). Mingea zboara apoi pana la poarta.
     */
    static void takePenalty(Sim s, Player tk) {
        start(s, tk);
        Mt rng = s.rng;
        Team team = tk.team;
        double pg = penGoalProb(tk, s.opp(team).gk);
        double pm = Math.min(Wt.pen_miss_base, 1.0 - pg);
        team.mXg += pg;
        s.played(tk);
        team.shots++;
        if (s.t >= s.halfLen * 4.0 / 3.0) {
            team.mShotsLate++;
        }
        if (s.target != null) {
            // scor tinta: sansa de gol condusa (Sim.guideGoal); ce se pierde din ea devine parada
            pg = s.guideGoal(team, pg);
            pm = Math.min(Wt.pen_miss_base, 1.0 - pg);
        }
        int outcome = penOutcome(rng.random(), pg, pm);
        double[] aim = penAim(rng, outcome);
        double aimY = aim[0];
        double zEnd = aim[1];
        double gx = team.dir == 1 ? Geo.L : 0.0;
        double speed = penSpeed(tk, rng);
        double ang = PMath.atan2(aimY - tk.y, gx - tk.x);
        Shooting.Kick k = Shooting.kickAlong(tk.x, tk.y, gx, ang, speed, zEnd, Sim.hyp(gx - tk.x, Geo.MID_Y - tk.y));
        Map<String, Object> e = s.ev("SHOT", team, tk, null, "Penalty: execută " + tk.shortName,
                Double.NaN, Double.NaN, k.ex, Geo.clamp(k.ey, -3.0, Geo.W + 3.0));
        e.put("special", false);
        e.put("penalty", true);
        Shooting.launch(s, tk, k, null, 2.0, false, outcome, 1);
    }
}
