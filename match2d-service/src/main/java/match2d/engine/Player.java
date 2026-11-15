package match2d.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Un jucator de pe teren: zona lui, parametrii scosi din ratinguri si starea din timpul meciului. */
public final class Player {
    final Team team;
    final int idx;
    final int uid;
    final int num;
    final String name;
    final String shortName;
    final String pos;
    final String slot;
    double[] deep;
    double[] high;
    final double[] dbox;
    /** zona din faza ofensiva; in timpul unei alergari in careu e {@link #runBox}. */
    double[] abox;
    final double[] abox0;
    /** zona pe care o are cat alearga in careu: pana aproape de linia portii, pe toata latimea careului. */
    final double[] runBox;
    /** om de atac: zona lui ajunge in careu cand echipa ataca (vezi {@link Runs}). */
    boolean forward;
    /** alearga in careu (vezi {@link Runs}); tinta e (runX, runY), in canonicul echipei. */
    boolean running;
    double runX;
    double runY;
    /** cat coboara cand echipa se apara: 0 = deloc, 1 = pana pe linia mijlocasilor (vezi {@link Runs}). */
    double dropK;
    /** marginea de jos a zonei de aparare, cu tot cu coborare (recalculata la fiecare gandire). */
    double dlo;
    /** ratingurile 1..20, in ordinea din {@link A#NAMES}. */
    final int[] r = new int[A.COUNT];
    /** ratingurile normalizate (1..20 -> 0.15..0.85). */
    final double[] n = new double[A.COUNT];

    // ratinguri -> parametri; pantele sunt toate in Wt
    double vmax;
    double acc;
    final double react;
    double jog;
    final double carryK;
    final double tk;
    final double dr;
    final double air;
    final boolean isGk;
    final boolean isDef;
    final boolean special;
    final int specialLvl;
    final double carryBias;

    // oboseala: valorile "odihnit" raman in *0; vmax, acc si jog se schimba in timpul meciului
    final double vmax0;
    final double acc0;
    final double jog0;
    double energy = 1.0;
    double tired = 0.0;
    final double drainK;
    final double restK;
    final double halfK;
    final double track2;
    final double chaseFor;
    final double trailK;
    double effort = 1.0;
    double lostT = -9.0;
    /** pana cand tine scaparea de atentie in care se afla (etapa 4). */
    double lapseUntil = -9.0;
    double dist = 0.0;
    double fast = 0.0;

    // starea din meci
    double x;
    double y;
    double vx;
    double vy;
    double tx;
    double ty;
    List<double[]> path = new ArrayList<>();
    double[] pg;
    double replanAt = 0.0;
    double thinkAt = 0.0;
    int role;
    double rx;
    double ry;
    boolean sprint;
    double stunUntil = -1.0;
    double tackleCd = -1.0;
    double touchCd = -1.0;
    double canActAt = 0.0;
    double decideAt = 0.0;
    double[] carryTo;
    Player lastFrom;
    double lastFromT = -9.0;
    Player assistFrom;
    double safeUntil = -1.0;
    /** momentul in care a primit mingea (NaN = nu se stie), pentru statistica "timp cu mingea". */
    double gainT = Double.NaN;
    /** id-ul din backend (campul {@code id} al jucatorului), sau null daca lipseste. */
    final Long id;
    /** are cartonas galben: iese mai rar la deposedare si faulteaza mai rar (vezi SetPiece). */
    boolean booked;

    @SuppressWarnings("unchecked")
    Player(Team team, int idx, Zones.Spec spec, Map<String, Object> data) {
        this.team = team;
        this.idx = idx;
        this.uid = team.index * 11 + idx;
        this.num = ((Number) data.get("number")).intValue();
        this.name = (String) data.get("name");
        this.id = idOf(data.get("id"));
        this.shortName = "#" + num + " " + name.substring(name.lastIndexOf(' ') + 1);
        this.pos = spec.pos;
        this.slot = spec.slot;
        this.deep = spec.deep.clone();
        this.high = spec.high.clone();
        if (truthy(data.get("stayForward"))) {
            // ramane sus si cand echipa se apara, ca tinta pentru contraatac
            deep = new double[] {0.5 * (deep[0] + high[0]), deep[1]};
        }
        double[] up = Tactics.dutyShift(data.get("duty"));
        if (up[0] != 0.0 || up[1] != 0.0) {
            double[] ab = spec.abox;
            high = new double[] {Geo.clamp(high[0] + up[0], ab[0], ab[1] - 1.0), high[1]};
            deep = new double[] {deep[0] + up[1], deep[1]};
        }
        this.dbox = spec.dbox;
        this.abox = spec.abox;
        this.abox0 = spec.abox;
        this.runBox = new double[] {spec.abox[0], Math.max(spec.abox[1], Geo.L - 4.0),
            Math.min(spec.abox[2], Geo.MID_Y - 18.0), Math.max(spec.abox[3], Geo.MID_Y + 18.0)};
        this.dlo = spec.dbox[0];
        Map<String, Object> ratings = (Map<String, Object>) data.get("ratings");
        for (int k = 0; k < A.COUNT; k++) {
            int v = ((Number) ratings.get(A.NAMES[k])).intValue();
            r[k] = v;
            n[k] = 0.15 + 0.70 * (v - 1) / 19.0;
        }
        vmax = Wt.speed_base + Wt.speed_pace * n[A.pace];
        acc = Wt.accel_base + Wt.accel_acceleration * n[A.acceleration];
        react = Wt.reaction_base - Wt.reaction_anticipation * n[A.anticipation];
        jog = Wt.jog_base + Wt.jog_workRate * n[A.workRate];
        carryK = Wt.carry_speed_base + Wt.carry_speed_dribbling * n[A.dribbling];
        vmax0 = vmax;
        acc0 = acc;
        jog0 = jog;
        drainK = Wt.fatigue_drain * (Wt.fatigue_stamina_base - Wt.fatigue_stamina * n[A.stamina]);
        double fit = Wt.recovery_naturalFitness_base + Wt.recovery_naturalFitness * n[A.naturalFitness];
        restK = Wt.recovery_rest * fit;
        halfK = Wt.recovery_half_time_base + Wt.recovery_naturalFitness_half_time * n[A.naturalFitness];
        double back = Wt.track_back_base - Wt.track_back_workRate * n[A.workRate];
        track2 = back * back;
        chaseFor = Wt.chase_lost_base + Wt.chase_lost_determination * n[A.determination];
        // cand echipa e condusa: peste medie la determinare = ritm mai mare, sub medie = mai mic
        trailK = Wt.trailing_determination * (n[A.determination] - Wt.trailing_determination_from);
        tk = Wt.tackle_tackling * n[A.tackling] + Wt.tackle_ballRecovery * n[A.ballRecovery]
                + Wt.tackle_anticipation * n[A.anticipation]
                + Wt.tackle_strength * n[A.strength] + Wt.tackle_aggression * n[A.aggression];
        dr = Wt.dribble_dribbling * n[A.dribbling] + Wt.dribble_agility * n[A.agility]
                + Wt.dribble_balance * n[A.balance]
                + Wt.dribble_technique * n[A.technique] + Wt.dribble_strength * n[A.strength];
        air = Wt.aerial_heading * n[A.heading] + Wt.aerial_jumpingReach * n[A.jumpingReach]
                + Wt.aerial_strength * n[A.strength]
                + Wt.aerial_bravery * n[A.bravery];
        isGk = pos.equals("GK");
        isDef = Zones.DEFENDERS.contains(pos);
        // 20 la longShots nu se genereaza niciodata: e un sut special, scris de mana in joc
        special = r[A.longShots] >= Wt.special_shot_longShots_from;
        specialLvl = (int) Math.max(0, r[A.longShots] - Wt.special_shot_longShots_from);
        // cat de mult prefera sa conduca mingea in loc sa paseze
        carryBias = Wt.carry_bias_base + Wt.carry_bias_dribbling * n[A.dribbling] * (
                Wt.carry_bias_flair_base + Wt.carry_bias_flair * n[A.flair]);
    }

    /** Un id venit din JSON (numar sau text cu numar), sau null. */
    static Long idOf(Object v) {
        if (v instanceof Number num) {
            return num.longValue();
        }
        if (v != null) {
            try {
                return (long) Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number num) {
            return num.doubleValue() != 0.0;
        }
        return !String.valueOf(v).isEmpty();
    }
}
