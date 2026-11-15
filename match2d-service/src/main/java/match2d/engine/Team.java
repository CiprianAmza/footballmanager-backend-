package match2d.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** O echipa din meci: jucatorii, tactica tradusa in parametri de comportament si statisticile. */
public final class Team {
    final int index;
    final String name;
    final String color;
    final String gkColor;
    final String formation;
    /** 1 = ataca spre dreapta ecranului, -1 = spre stanga; se schimba la pauza. */
    int dir;
    int goals;

    // statistici
    int poss, passes, passesOk, through, throughOk, longBalls, longOk, dribblesWon, dribblesLost,
            tackles, interceptions, aerialsWon, shots, onTarget, blocked, offTarget, saves,
            offsides, corners, carries;

    // tactica -> parametri de comportament (tabelul e in Tactics)
    final Map<String, String> tactic;
    final List<String> tacticUnknown;
    final double block;         // cat urca sau coboara tot blocul
    final int pressExtra;       // oameni in plus la minge
    final double pressMargin;   // de la ce distanta de zona lor ies
    final double pressEager;    // cat de des ataca mingea
    final double shape;         // cat de strans tine marcajul
    final double lineShift;     // cat de sus sta linia de fund
    final double widthAtt;
    final double widthDef;
    final double tempoTime;     // cat tine mingea pana decide
    final double tempoError;    // cat de imprecisa e executia
    final double tempoMove;     // cat de repede se misca fara minge, in atac
    final double passShort;
    final double passLong;
    final double passThrough;
    final double risk;          // cat costa pierderea mingii
    final double forward;       // preferinta pentru pasa inainte
    final double dribble;

    // masuratori pentru calibrare (nu intra in joc)
    double mPassLen, mWidth, mLine, mBallTime, mXg, mEnergyHalf = 100.0;
    int mFwd, mWidthN, mLineN, mBallN, mRegains, mChaseRegains, mGoalsLate, mShotsLate;
    /** scapari de atentie si mingi libere castigate (etapa 4). */
    int mLapses, mLooseWon;
    /** cand a pierdut ultima oara posesia. */
    double lostAt = -9.0;
    /** faze fixe (etapa A): faulturi comise, galbene, lovituri libere si penalty-uri primite. */
    int fouls, yellowCards, freeKicks, penalties, penaltyGoals, fkShots, fkGoals;
    /** etapa B: centrari (toate), ajunse la un coechipier, lovituri de cap la poarta, goluri. */
    int crosses, crossesOk, headers, headerGoals, cornerGoals;

    // faulturi, din tactica (foulFrequency, foulHardness)
    final double foulFreq;      // cat de des faulteaza
    final double foulHard;      // inmultitorul cartonaselor
    final double tackleEdge;    // termen adunat in logitul duelului

    /**
     * Executantii fazelor fixe: cei din tactica (penaltyTakerId, freeKickTakerId,
     * cornerTakerLeftId, cornerTakerRightId), daca sunt in primul 11; altfel penalty = cel mai mare
     * penaltyTaking (la egalitate composure), lovitura libera = cel mai mare freeKick. Cornerul fara
     * executant ales (null) foloseste regula veche din {@link Sim#pickTaker}.
     */
    Player penTaker;
    Player fkTaker;
    Player cornerLeft;
    Player cornerRight;

    final Player[] players = new Player[11];
    /** cati oameni de atac ii lipsesc formatiei (vezi {@link Runs#attackLine}). */
    final double runMissing;
    /** cate alergari in careu are voie in acelasi timp. */
    final int runNeed;
    final Player gk;
    /** capitanul: jucatorul cu cel mai mare leadership (la egalitate, primul din lista). */
    final Player captain;

    @SuppressWarnings("unchecked")
    Team(int index, Map<String, Object> data) {
        this.index = index;
        this.name = (String) data.get("name");
        Object c = data.get("color");
        this.color = c != null ? (String) c : (index == 0 ? "#d62828" : "#1d4ed8");
        Object g = data.get("gkColor");
        this.gkColor = g != null ? (String) g : (index == 0 ? "#f4d35e" : "#22c55e");
        this.formation = (String) data.get("formation");
        this.dir = index == 0 ? 1 : -1;
        Tactics.Resolved res = Tactics.resolve((Map<String, Object>) data.get("tactic"));
        this.tactic = res.chosen;
        this.tacticUnknown = res.unknown;
        Map<String, Double> p = res.params;
        block = p.get("block");
        pressExtra = (int) Math.round(p.get("press_extra"));
        pressMargin = p.get("press_margin");
        pressEager = p.get("press_eager");
        shape = p.get("shape");
        lineShift = p.get("line_shift");
        widthAtt = p.get("width_att");
        widthDef = p.get("width_def");
        tempoTime = p.get("tempo_time");
        tempoError = p.get("tempo_error");
        tempoMove = p.get("tempo_move");
        passShort = p.get("pass_short");
        passLong = p.get("pass_long");
        passThrough = p.get("pass_through");
        risk = p.get("risk");
        forward = p.get("forward");
        dribble = p.get("dribble");
        foulFreq = p.getOrDefault("foul_freq", 1.0);
        foulHard = p.getOrDefault("foul_hard", 1.0);
        tackleEdge = p.getOrDefault("tackle_edge", 0.0);
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("players");
        List<String> positions = new ArrayList<>();
        for (Map<String, Object> pl : list) {
            positions.add((String) pl.get("position"));
        }
        List<Zones.Spec> slots = Zones.buildSlotsFromPositions(positions);
        if (!Sim.LEGACY) {
            Zones.supportAttack(slots);
        }
        for (int i = 0; i < 11; i++) {
            players[i] = new Player(this, i, slots.get(i), list.get(i));
        }
        gk = players[0];
        if (!Sim.LEGACY) {
            runMissing = Runs.attackLine(players);
            Runs.dropShares(players);
        } else {
            runMissing = 0.0;
        }
        runNeed = (int) Math.ceil(runMissing - 1e-9);
        Player lead = players[0];
        for (Player q : players) {
            if (q.r[A.leadership] > lead.r[A.leadership]) {
                lead = q;
            }
        }
        captain = lead;
        resolveTakers((Map<String, Object>) data.get("tactic"));
    }

    /** Jucatorul din primul 11 cu id-ul din tactica la cheia {@code key} (orice scriere), sau null. */
    private Player byTacticId(Map<String, Object> tactic, String key) {
        if (tactic == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : tactic.entrySet()) {
            if (Tactics.norm(e.getKey()).equals(key)) {
                Long id = Player.idOf(e.getValue());
                if (id == null) {
                    return null;
                }
                for (Player q : players) {
                    if (id.equals(q.id)) {
                        return q;
                    }
                }
                return null;
            }
        }
        return null;
    }

    private void resolveTakers(Map<String, Object> tactic) {
        penTaker = byTacticId(tactic, "penaltytakerid");
        if (penTaker == null) {
            for (Player q : players) {
                if (q.isGk) {
                    continue;
                }
                if (penTaker == null || q.r[A.penaltyTaking] > penTaker.r[A.penaltyTaking]
                        || (q.r[A.penaltyTaking] == penTaker.r[A.penaltyTaking]
                        && q.r[A.composure] > penTaker.r[A.composure])) {
                    penTaker = q;
                }
            }
        }
        fkTaker = byTacticId(tactic, "freekicktakerid");
        if (fkTaker == null) {
            for (Player q : players) {
                if (!q.isGk && (fkTaker == null || q.r[A.freeKick] > fkTaker.r[A.freeKick])) {
                    fkTaker = q;
                }
            }
        }
        cornerLeft = byTacticId(tactic, "cornertakerleftid");
        cornerRight = byTacticId(tactic, "cornertakerrightid");
    }
}
