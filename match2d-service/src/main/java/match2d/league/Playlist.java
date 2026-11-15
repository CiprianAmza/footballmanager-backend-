package match2d.league;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ceasul transmisiei, portat din {@code highlights.py}: lista de bucati pe care o parcurge liga
 * (meciul intreg sau doar fazele, cu reluari dupa gol), durata reala, locul in lista si reluarea
 * de la progres. Fazele importante vin din cronologie (cheia {@code highlights}, calculata de
 * {@link match2d.engine.Highlights}).
 */
final class Playlist {
    private Playlist() {
    }

    // reluarea golului: dupa ce meciul ajunge la t + REPLAY_AT se arata [t - 6, t + 1] cu incetinitorul
    static final double REPLAY_BEFORE = 6.0;
    static final double REPLAY_AFTER = 1.0;
    static final double REPLAY_AT = 3.0;
    static final double REPLAY_RATE = 0.5;
    static final String REPLAY_LABEL = "Reluare";
    static final List<String> MODES = List.of("full", "highlights");

    /** O bucata: secundele de joc [from, to] cu {@code rate} secunde de joc pe secunda reala. */
    static final class Piece {
        final double from;
        final double to;
        final double rate;
        final String kind;
        final Object label;
        final Integer segment;

        Piece(double from, double to, double rate, String kind, Object label, Integer segment) {
            this.from = from;
            this.to = to;
            this.rate = rate;
            this.kind = kind;
            this.label = label;
            this.segment = segment;
        }
    }

    /** (indexul bucatii, pozitia afisata, progresul). */
    record Loc(int index, double pos, double progress) {
    }

    /** (indexul bucatii, offset in secunde reale). */
    record Resume(int index, double offset) {
    }

    /** Durata meciului (secunde de joc): din cadre, altfel din ultimul eveniment. */
    static double duration(Map<String, Object> timeline) {
        Object frames = timeline.get("frames");
        Object hz = timeline.get("hz");
        if (Py.truthy(frames) && Py.truthy(hz)) {
            return (((List<?>) frames).size() - 1) / Py.num(hz);
        }
        Double best = null;
        Object events = timeline.get("events");
        if (Py.truthy(events)) {
            for (Object o : (List<?>) events) {
                Object t = ((Map<?, ?>) o).get("t");
                double v = Py.truthy(t) ? Py.num(t) : 0.0;
                if (best == null || v > best) {
                    best = v;
                }
            }
        }
        return best == null ? 0.0 : best;
    }

    /** Fazele importante, calculate de motor: [{from, to, label, kind}]. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> segments(Map<String, Object> timeline) {
        Object segs = timeline == null ? null : timeline.get("highlights");
        if (segs == null) {
            throw new IllegalStateException("arhiva motorului e prea veche: cronologia nu are \"highlights\" "
                    + "(recompileaza match-engine-java cu build.sh; motorul Python nu le calculeaza)");
        }
        return (List<Map<String, Object>>) segs;
    }

    private static List<Double> goalTimes(Map<String, Object> timeline) {
        List<Double> out = new ArrayList<>();
        Object events = timeline.get("events");
        if (Py.truthy(events)) {
            for (Object o : (List<?>) events) {
                Map<?, ?> e = (Map<?, ?>) o;
                if ("GOAL".equals(e.get("type")) && Py.isNum(e.get("t"))) {
                    out.add(Py.num(e.get("t")));
                }
            }
        }
        Collections.sort(out, Py::cmp);
        return out;
    }

    /** Bucatile pe care le parcurge ceasul (vezi {@code highlights.playlist}). */
    static List<Piece> playlist(Map<String, Object> timeline, String mode, boolean replays, double speed,
                                double hlSpeed) {
        double dur = duration(timeline);
        List<Object[]> ranges = new ArrayList<>();
        double rate;
        if ("highlights".equals(mode)) {
            List<Map<String, Object>> segs = segments(timeline);
            for (int i = 0; i < segs.size(); i++) {
                Map<String, Object> s = segs.get(i);
                ranges.add(new Object[] {Py.num(s.get("from")), Py.num(s.get("to")), s.get("label"), i});
            }
            rate = hlSpeed;
        } else {
            ranges.add(new Object[] {0.0, dur, null, null});
            rate = speed;
        }
        List<Double> goals = replays ? goalTimes(timeline) : List.of();
        List<Piece> pieces = new ArrayList<>();
        for (Object[] r : ranges) {
            double a = (Double) r[0];
            double b = (Double) r[1];
            Object label = r[2];
            Integer seg = (Integer) r[3];
            double cur = a;
            for (double t : goals) {
                if (!(a <= t && t <= b)) {
                    continue;
                }
                double at = Py.min(t + REPLAY_AT, b);
                if (at < cur) {
                    continue;
                }
                if (at > cur) {
                    pieces.add(new Piece(cur, at, rate, "live", label, seg));
                }
                pieces.add(new Piece(Py.max(0.0, t - REPLAY_BEFORE), Py.min(dur, t + REPLAY_AFTER), REPLAY_RATE,
                        "replay", REPLAY_LABEL, seg));
                cur = at;
            }
            if (b > cur) {
                pieces.add(new Piece(cur, b, rate, "live", label, seg));
            }
        }
        return pieces;
    }

    /** Cat tine o bucata in secunde reale. */
    static double pieceSeconds(Piece p) {
        return p.rate > 0 ? (p.to - p.from) / p.rate : 0.0;
    }

    /** Durata reala a listei, de la bucata {@code start} (cu {@code offset} secunde reale deja trecute). */
    static double totalSeconds(List<Piece> pieces, int start, double offset) {
        double sum = 0.0;
        for (int i = Math.max(0, start); i < pieces.size(); i++) {
            sum += pieceSeconds(pieces.get(i));
        }
        return Py.max(0.0, sum - offset);
    }

    private static double progressBefore(List<Piece> pieces, int i) {
        Double best = null;
        for (int k = 0; k < Math.min(i, pieces.size()); k++) {
            Piece p = pieces.get(k);
            if ("live".equals(p.kind) && (best == null || p.to > best)) {
                best = p.to;
            }
        }
        return best == null ? 0.0 : best;
    }

    static double endProgress(List<Piece> pieces) {
        return progressBefore(pieces, pieces.size());
    }

    /** Unde e transmisia dupa {@code w} secunde reale de la bucata {@code i}. */
    static Loc locate(List<Piece> pieces, int i, double offset, double w) {
        double rest = offset + Py.max(0.0, w);
        while (i < pieces.size()) {
            Piece p = pieces.get(i);
            double secs = pieceSeconds(p);
            if (rest < secs) {
                double pos = p.from + rest * p.rate;
                double before = progressBefore(pieces, i);
                return new Loc(i, pos, "live".equals(p.kind) ? Py.max(before, pos) : before);
            }
            rest -= secs;
            i++;
        }
        double last = pieces.isEmpty() ? 0.0 : pieces.get(pieces.size() - 1).to;
        return new Loc(pieces.size(), last, endProgress(pieces));
    }

    /** De unde continui ca progresul sa nu mearga inapoi. */
    static Resume resume(List<Piece> pieces, double progress) {
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (!"live".equals(p.kind) || p.to <= progress) {
                continue;
            }
            if (p.from <= progress) {
                return new Resume(i, (progress - p.from) / p.rate);
            }
            return new Resume(i, 0.0);
        }
        return new Resume(pieces.size(), 0.0);
    }
}
