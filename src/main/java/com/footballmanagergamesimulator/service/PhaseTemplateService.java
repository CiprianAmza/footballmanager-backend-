package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.MatchPhaseData;
import com.footballmanagergamesimulator.frontend.MatchPhaseData.PhaseAction;
import com.footballmanagergamesimulator.frontend.LiveMatchData.LiveMatchMinute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Authored buildup patterns. The live score and terminal shot outcome remain authoritative. */
@Service
public class PhaseTemplateService {
    private static final String TOP_DOWN = "TOP_DOWN";
    public record Point(double x, double y) {}
    /** moves[i] describes how touch i reaches touch i+1. Null means legacy passes. */
    public record Draft(String name, List<Integer> slots, List<Point> points, List<String> moves) {
        public Draft(String name, List<Integer> slots, List<Point> points) {
            this(name, slots, points, null);
        }
    }
    public record Template(String id, String name, List<Integer> slots, List<Point> points,
                           List<String> moves, String yAxis) {}

    private final ObjectMapper mapper;
    private final Path store;
    private final List<Template> templates = new ArrayList<>();

    public PhaseTemplateService(ObjectMapper mapper,
            @Value("${phaselab.templates.path:data/phase-templates.json}") String path) {
        this.mapper = mapper;
        this.store = Path.of(path);
    }

    @PostConstruct
    public synchronized void load() throws IOException {
        templates.clear();
        if (Files.exists(store)) {
            List<Template> loaded = mapper.readValue(store.toFile(), new TypeReference<List<Template>>() {});
            boolean migrated = false;
            for (Template template : loaded) {
                if (TOP_DOWN.equals(template.yAxis()) && template.moves() != null) {
                    templates.add(template);
                    continue;
                }
                if (template.yAxis() != null && !TOP_DOWN.equals(template.yAxis()))
                    throw new IOException("Unknown phase template coordinate system: " + template.yAxis());
                List<Point> points = template.yAxis() == null
                        ? template.points().stream().map(point -> new Point(point.x(), 100 - point.y())).toList()
                        : template.points();
                templates.add(new Template(template.id(), template.name(), template.slots(), points,
                        normalizedMoves(template.slots(), template.moves()), TOP_DOWN));
                migrated = true;
            }
            if (migrated) persist();
        }
    }

    public synchronized List<Template> all() {
        return List.copyOf(templates);
    }

    public synchronized Template find(String id) {
        return templates.stream().filter(template -> template.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown phase template"));
    }

    public synchronized Template save(Draft draft) throws IOException {
        validate(draft);
        Template result = new Template(UUID.randomUUID().toString(), draft.name().trim(),
                List.copyOf(draft.slots()), List.copyOf(draft.points()),
                normalizedMoves(draft.slots(), draft.moves()), TOP_DOWN);
        templates.add(result);
        try {
            persist();
        } catch (IOException error) {
            templates.remove(result);
            throw error;
        }
        return result;
    }

    public Template draft(Draft value) {
        validate(value);
        return new Template("draft", value.name().trim(), List.copyOf(value.slots()),
                List.copyOf(value.points()), normalizedMoves(value.slots(), value.moves()), TOP_DOWN);
    }

    public synchronized void delete(String id) throws IOException {
        Template template = find(id);
        templates.remove(template);
        try {
            persist();
        } catch (IOException error) {
            templates.add(template);
            throw error;
        }
    }

    private void persist() throws IOException {
        Path parent = store.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "phase-templates-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), templates);
            try {
                Files.move(temp, store, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, store, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void validate(Draft draft) {
        if (draft == null || draft.name() == null || draft.name().isBlank()
                || draft.name().length() > 80 || draft.slots() == null || draft.points() == null
                || draft.slots().size() < 2 || draft.slots().size() > 20
                || draft.points().size() != draft.slots().size())
            throw new IllegalArgumentException("A phase needs a name and 2 to 20 touches with pitch points");
        if (draft.slots().stream().anyMatch(slot -> slot == null || slot < 0 || slot > 10))
            throw new IllegalArgumentException("Choose players from the starting XI");
        List<String> moves = normalizedMoves(draft.slots(), draft.moves());
        for (int i = 1; i < draft.slots().size(); i++) {
            String move = moves.get(i - 1);
            if (!List.of("PASS", "DRIBBLE", "SPRINT_DRIBBLE").contains(move))
                throw new IllegalArgumentException("Move must be a pass, dribble or run with the ball");
            boolean samePlayer = draft.slots().get(i).equals(draft.slots().get(i - 1));
            if ("PASS".equals(move) && samePlayer)
                throw new IllegalArgumentException("A pass needs a different receiver");
            if (!"PASS".equals(move) && !samePlayer)
                throw new IllegalArgumentException("A dribble or run keeps the ball with the same player");
        }
        for (Point point : draft.points()) {
            if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())
                    || point.x() < 3 || point.x() > 94 || point.y() < 5 || point.y() > 95)
                throw new IllegalArgumentException("Every point must be inside the pitch");
        }
        if (draft.points().get(draft.points().size() - 1).x() < 55)
            throw new IllegalArgumentException("The shooter must be in the attacking half");
    }

    private static List<String> normalizedMoves(List<Integer> slots, List<String> moves) {
        if (moves == null) return java.util.Collections.nCopies(slots.size() - 1, "PASS");
        if (moves.size() != slots.size() - 1)
            throw new IllegalArgumentException("A move is needed between each pair of touches");
        return List.copyOf(moves);
    }

    public MatchPhaseData preview(Template template, List<MatchPhaseEngine.PhasePlayer> attack,
            List<MatchPhaseEngine.PhasePlayer> defence, String outcome, long seed) {
        return render(template, attack, defence, 1, 2, true, 30, outcome, seed, 0);
    }

    /** Only one settled shot from the possession side may use an authored pattern. */
    public MatchPhaseData live(MatchPhaseEngine.MinuteContext ctx) {
        List<Template> available = all();
        if (available.isEmpty() || ctx.kickoff() || ctx.minuteEvents().size() != 1) return null;
        LiveMatchMinute terminal = ctx.minuteEvents().get(0);
        if (!isShotOutcome(terminal.getEventType())) return null;
        long possessionTeam = ctx.team1HasBall() ? ctx.teamId1() : ctx.teamId2();
        if (terminal.getTeamId() != possessionTeam) return null;
        Random choice = new Random(ctx.seed() * 0x9E3779B97F4A7C15L ^ 0x5A17E15L);
        if (choice.nextDouble() >= 0.35) return null;
        List<MatchPhaseEngine.PhasePlayer> attack = ctx.team1HasBall()
                ? ctx.team1OnPitch() : ctx.team2OnPitch();
        List<MatchPhaseEngine.PhasePlayer> defence = ctx.team1HasBall()
                ? ctx.team2OnPitch() : ctx.team1OnPitch();
        int first = choice.nextInt(available.size());
        for (int offset = 0; offset < available.size(); offset++) {
            Template template = available.get((first + offset) % available.size());
            MatchPhaseData phase = render(template, attack, defence, possessionTeam,
                    ctx.team1HasBall() ? ctx.teamId2() : ctx.teamId1(),
                    ctx.team1HasBall() == ctx.homeAttacksRight(), ctx.minute(),
                    terminal.getEventType(), ctx.seed(), terminal.getPlayerId());
            if (phase != null) return phase;
        }
        return null;
    }

    private static boolean isShotOutcome(String outcome) {
        return "goal".equals(outcome) || "shot_saved".equals(outcome)
                || "shot_wide".equals(outcome) || "shot_blocked".equals(outcome);
    }

    private MatchPhaseData render(Template template, List<MatchPhaseEngine.PhasePlayer> attack,
            List<MatchPhaseEngine.PhasePlayer> defence, long attackingTeam, long defendingTeam,
            boolean attackingRight, int minute, String outcome, long seed, long scorerId) {
        if (!isShotOutcome(outcome) || defence.isEmpty()
                || attack.size() < template.slots().stream().distinct().count()) return null;
        int touches = template.slots().size();
        MatchPhaseEngine.PhasePlayer[] actors = new MatchPhaseEngine.PhasePlayer[touches];
        Map<Integer, MatchPhaseEngine.PhasePlayer> assigned = new HashMap<>();
        Set<Long> used = new HashSet<>();
        if (scorerId > 0) {
            MatchPhaseEngine.PhasePlayer scorer = attack.stream().filter(player -> player.id() == scorerId)
                    .findFirst().orElse(null);
            if (scorer == null) return null;
            assigned.put(template.slots().get(touches - 1), scorer);
            used.add(scorerId);
        }
        for (int i = 0; i < touches; i++) {
            int slot = template.slots().get(i);
            if (assigned.containsKey(slot)) {
                actors[i] = assigned.get(slot);
                continue;
            }
            String role = PhaseLabGenerator.ROLES[slot];
            MatchPhaseEngine.PhasePlayer player = attack.stream()
                    .filter(candidate -> !used.contains(candidate.id()))
                    .filter(candidate -> !"GK".equals(candidate.role()) || "GK".equals(role))
                    .min(Comparator.comparingInt((MatchPhaseEngine.PhasePlayer candidate) ->
                            roleDistance(role, candidate.role())).thenComparingLong(MatchPhaseEngine.PhasePlayer::id))
                    .orElse(null);
            if (player == null) return null;
            actors[i] = player;
            assigned.put(slot, player);
            used.add(player.id());
        }
        Random rng = new Random(seed ^ template.id().hashCode());
        Point[] points = template.points().toArray(Point[]::new);
        MatchPhaseData phase = new MatchPhaseData();
        phase.setMinute(minute);
        phase.setTeamId(attackingTeam);
        phase.setAttackingRight(attackingRight);
        phase.setStrategy("AUTHORED");
        phase.setScenario(template.name());
        phase.setEndEvent(outcome);
        for (int i = 0; i < touches - 1; i++) {
            Point from = points[i], to = points[i + 1];
            double distance = Math.hypot(to.x() - from.x(), to.y() - from.y());
            String move = template.moves().get(i);
            boolean pass = "PASS".equals(move);
            long duration = pass ? Math.round(380 + distance * 22)
                    : Math.round(350 + distance * ("DRIBBLE".equals(move) ? 145 : 105));
            PhaseAction action = action(move, actors[i], pass ? actors[i + 1] : null,
                    attackingTeam, from, to, attackingRight, duration);
            if (pass && distance > 30) action.setPeakHeight(1.2);
            phase.getActions().add(action);
        }
        Point shotFrom = points[touches - 1];
        double goalY = clamp(50 + rng.nextDouble() * 12 - 6, 38, 62);
        Point target = new Point("shot_blocked".equals(outcome) ? 88 : 99,
                "shot_wide".equals(outcome) ? goalY + 10 : goalY);
        PhaseAction shot = action("SHOT", actors[touches - 1], null, attackingTeam,
                shotFrom, target, attackingRight, 720);
        shot.setPeakHeight(1.4 + rng.nextDouble());
        phase.getActions().add(shot);
        String terminalType = switch (outcome) {
            case "goal" -> "GOAL";
            case "shot_saved" -> "SAVE";
            case "shot_wide" -> "MISS";
            default -> "BLOCK";
        };
        MatchPhaseEngine.PhasePlayer terminalActor = "shot_saved".equals(outcome)
                ? defence.stream().filter(player -> "GK".equals(player.role())).findFirst().orElse(defence.get(0))
                : actors[touches - 1];
        long terminalTeam = "shot_saved".equals(outcome) ? defendingTeam : attackingTeam;
        phase.getActions().add(action(terminalType, terminalActor, null, terminalTeam,
                target, target, attackingRight, 300));
        return phase;
    }

    private static int roleDistance(String requested, String actual) {
        if (requested.equals(actual)) return 0;
        if ("GK".equals(requested) || "GK".equals(actual)) return 100;
        int score = requested.charAt(0) == actual.charAt(0) ? 2 : 8;
        char requestedLane = requested.charAt(requested.length() - 1);
        char actualLane = actual.charAt(actual.length() - 1);
        if (requestedLane == actualLane) score -= 1;
        else if ((requestedLane == 'L' && actualLane == 'R')
                || (requestedLane == 'R' && actualLane == 'L')) score += 5;
        return score;
    }

    private static PhaseAction action(String type, MatchPhaseEngine.PhasePlayer actor,
            MatchPhaseEngine.PhasePlayer receiver, long team, Point from, Point to,
            boolean attackingRight, long duration) {
        PhaseAction action = new PhaseAction();
        action.setType(type);
        action.setPlayerId(actor.id());
        action.setPlayerName(actor.name());
        action.setTargetPlayerId(receiver == null ? 0 : receiver.id());
        action.setTargetPlayerName(receiver == null ? null : receiver.name());
        action.setTeamId(team);
        action.setX(attackingRight ? from.x() : 100 - from.x());
        action.setY(from.y());
        action.setEndX(attackingRight ? to.x() : 100 - to.x());
        action.setEndY(to.y());
        action.setDurationMs((int) duration);
        return action;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
