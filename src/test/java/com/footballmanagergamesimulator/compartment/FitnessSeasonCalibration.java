package com.footballmanagergamesimulator.compartment;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.service.FitnessEconomy;
import com.footballmanagergamesimulator.service.TacticService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Season-level calibration of the fitness economy on the REAL Tactic Arena base world (not a unit
 * test; run its {@code main}).
 *
 * <p>Why the first version was wrong: it used synthetic squads whose backups were 1.2 attribute
 * levels (about 9%) below the starters, judged a tempo only against itself without fatigue (its own
 * scoring bonus never entered the comparison), and gave every club one match a week. Zero's real
 * reserves are within a few percent of the starters, so rotating at Much Higher cost it almost
 * nothing, and Much Higher's own scoring bonus paid for the rest; while a club on the real
 * 70-match Diamond League calendar played every 3-4 days and could not keep up.
 *
 * <p>This version reads, from {@code src/test/resources/fitness-calibration/}, the real squads
 * (rating, natural position, every attribute, stamina and natural fitness) of the Eleven League and
 * the Diamond League clubs, their saved tactics, and the real season-1 fixture list, all exported
 * from {@code baseline/world.zip}. Every club plays its own calendar (cup rounds after the first are
 * added on the game's cup days for Zero and Inazuma, against a mid-table club). Every day without a
 * match recovers by the default schedule; every match drains the eleven; the eleven is picked like
 * the engine does ({@code slotAptness × fitness factor}, base-family slots, the formation's slots in
 * display order). Scoring uses the production adapter and the production in-match rule. Points are
 * expected league points (xPts over the 38 Eleven League matches).
 *
 * <p>Baseline "no fitness": every player at 100 for every club, no in-match effect (the game as it was
 * before the fitness economy).
 *
 * <p>Arguments: {@code daily=}, {@code rest=}, {@code drain=}, {@code share=}, {@code fromFresh=true|false},
 * {@code Higher=} / {@code MuchHigher=} / {@code Lower=} / {@code MuchLower=} (tempo drain multipliers).
 */
public final class FitnessSeasonCalibration {

    static final long ZERO = 88, INAZUMA = 87;
    /** Mid-table opponents for the cup rounds the export does not contain yet (one each: a club plays once a day). */
    static final Map<Long, Long> CUP_OPPONENT = Map.of(88L, 95L, 87L, 96L);
    static final int LEAGUE = 16, CUP = 17, LAST_DAY = 332;
    static final int[] LATER_CUP_DAYS = {154, 231, 280};

    record Player(long id, long teamId, String natural, double rating,
                  Map<PlayerAttribute, Integer> attributes, int stamina, int naturalFitness) {}

    record Fixture(int competition, int day, long home, long away) {}

    record Tactic(String formation, Map<String, String> axes) {}

    record Result(double leagueXpts, int matches, double avgFactor, int playersUsed, int topApps) {}

    private final CompartmentEngineConfig compartment;
    private final MatchEngineConfig engine;
    private final CanonicalMatchEvaluationAdapter adapter;
    /** The game before the fitness economy: no in-match effect (every player is at 100 there). */
    private final CanonicalMatchEvaluationAdapter adapterWithoutFitness;
    private final CanonicalScoreSampler sampler;
    private final TacticService tactics = new TacticService();
    private final Map<Long, List<Player>> squads = new HashMap<>();
    private final Map<Long, Tactic> savedTactics = new HashMap<>();
    private final List<Fixture> fixtures = new ArrayList<>();

    FitnessSeasonCalibration(CompartmentEngineConfig compartment, MatchEngineConfig engine) {
        this.compartment = compartment;
        this.engine = engine;
        this.adapter = new CanonicalMatchEvaluationAdapter(compartment, engine);
        MatchEngineConfig off = loadEngine();
        off.getFitness().setInMatchShare(0.0);
        this.adapterWithoutFitness = new CanonicalMatchEvaluationAdapter(compartment, off);
        this.sampler = new CanonicalScoreSampler(compartment);
        loadSquads();
        loadTactics();
        loadFixtures();
    }

    public static void main(String[] args) {
        CompartmentEngineConfig compartment = CompartmentConfigFixture.load();
        MatchEngineConfig engine = loadEngine();
        for (String arg : args) {
            String[] kv = arg.split("=", 2);
            switch (kv[0]) {
                case "daily" -> engine.getFitness().setDaily(Double.parseDouble(kv[1]));
                case "rest" -> engine.getFitness().setRestDaily(Double.parseDouble(kv[1]));
                case "drain" -> engine.getStamina().setBatchMatchFitnessDrain(Double.parseDouble(kv[1]));
                case "share" -> engine.getFitness().setInMatchShare(Double.parseDouble(kv[1]));
                case "fromFresh" -> engine.getFitness().setInMatchFromFresh(Boolean.parseBoolean(kv[1]));
                case "MuchHigher" -> engine.getFitness().getTempoDrain().put("Much Higher", Double.parseDouble(kv[1]));
                case "MuchLower" -> engine.getFitness().getTempoDrain().put("Much Lower", Double.parseDouble(kv[1]));
                default -> engine.getFitness().getTempoDrain().put(kv[0], Double.parseDouble(kv[1]));
            }
        }
        MatchEngineConfig.Fitness f = engine.getFitness();
        System.out.printf(Locale.ROOT, "daily=%.2f rest=%.2f drain=%.1f tempo=%s in-match share=%.2f fromFresh=%s%n",
                f.getDaily(), f.getRestDaily(), engine.getStamina().getBatchMatchFitnessDrain(), f.getTempoDrain(),
                f.getInMatchShare(), f.isInMatchFromFresh());
        new FitnessSeasonCalibration(compartment, engine).report();
    }

    static MatchEngineConfig loadEngine() {
        try {
            MutablePropertySources properties = new MutablePropertySources();
            for (String file : List.of("compartment-scoring-weights-v1.yml", "application.yml")) {
                for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
                    properties.addLast(source);
                }
            }
            return new Binder(ConfigurationPropertySources.from(properties))
                    .bind("match.engine", Bindable.of(MatchEngineConfig.class))
                    .orElseThrow(() -> new IllegalStateException("match.engine is not bound"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load the yml", e);
        }
    }

    // ------------------------------------------------------------------ report

    void report() {
        String[] tempos = {"Much Lower", "Lower", "Standard", "Higher", "Much Higher"};
        System.out.printf(Locale.ROOT, "%nZero (42 matches), base 4-4-2 all standard but tempo, auto eleven re-picked every match%n");
        System.out.printf(Locale.ROOT, "%-44s | %7s %7s %7s | %6s | %5s %5s%n", "tempo", "xPts", "noFit", "vsStd",
                "avgF", "used", "top");
        Result standard = season(ZERO, Map.of(), true);
        for (String tempo : tempos) {
            Map<String, String> settings = Map.of("tempo", tempo);
            Result with = season(ZERO, settings, true);
            Result without = season(ZERO, settings, false);
            System.out.printf(Locale.ROOT, "%-44s | %7.2f %7.2f %+7.2f | %6.3f | %5d %5d%n", tempo, with.leagueXpts(),
                    without.leagueXpts(), with.leagueXpts() - standard.leagueXpts(), with.avgFactor(),
                    with.playersUsed(), with.topApps());
        }
        Map<String, String> gegen = TacticCombinationCalibration.TOPS.get("TOP GEGENPRESS+ALL_OUT_ATTACK+WINGS");
        Result g = season(ZERO, gegen, true);
        Result g0 = season(ZERO, gegen, false);
        System.out.printf(Locale.ROOT, "%-44s | %7.2f %7.2f %+7.2f | %6.3f | %5d %5d%n",
                "Gegenpress + All-out attack + Wings", g.leagueXpts(), g0.leagueXpts(),
                g.leagueXpts() - standard.leagueXpts(), g.avgFactor(), g.playersUsed(), g.topApps());

        Result inaz = season(INAZUMA, null, true);
        Result inaz0 = season(INAZUMA, null, false);
        Result zero0 = season(ZERO, Map.of(), false);
        System.out.printf(Locale.ROOT, "%nInazuma Japan (%d matches, own tactic): league xPts %.2f with fitness, %.2f without (%+.2f); avgF %.3f, used %d, top apps %d%n",
                inaz.matches(), inaz.leagueXpts(), inaz0.leagueXpts(), inaz.leagueXpts() - inaz0.leagueXpts(),
                inaz.avgFactor(), inaz.playersUsed(), inaz.topApps());
        System.out.printf(Locale.ROOT, "Zero default (%d matches): league xPts %.2f with fitness, %.2f without (%+.2f)%n",
                standard.matches(), standard.leagueXpts(), zero0.leagueXpts(), standard.leagueXpts() - zero0.leagueXpts());
    }

    // ------------------------------------------------------------------ season

    /**
     * One season of the whole world; returns the focus club's figures. {@code focusSettings}: the
     * focus club's tactic axes (base 4-4-2 with these on top), or null to keep its saved tactic.
     */
    Result season(long focus, Map<String, String> focusSettings, boolean fitnessOn) {
        Map<Long, Double> fitness = new HashMap<>();
        for (List<Player> squad : squads.values()) for (Player p : squad) fitness.put(p.id(), fitnessOn ? 80.0 : 100.0);
        Map<Long, Tactic> tacticByTeam = new HashMap<>(savedTactics);
        if (focusSettings != null) tacticByTeam.put(focus, new Tactic("442", focusSettings));

        Map<Integer, List<Fixture>> byDay = new HashMap<>();
        for (Fixture fx : fixtures) byDay.computeIfAbsent(fx.day(), d -> new ArrayList<>()).add(fx);
        for (long club : List.of(ZERO, INAZUMA)) {
            for (int day : LATER_CUP_DAYS) byDay.computeIfAbsent(day, d -> new ArrayList<>())
                    .add(new Fixture(CUP, day, club, CUP_OPPONENT.get(club)));
        }

        double leaguePoints = 0, factorSum = 0;
        int matches = 0;
        Map<Long, Integer> apps = new HashMap<>();
        for (int day = 1; day <= LAST_DAY; day++) {
            Set<Long> played = new HashSet<>();
            for (Fixture fx : byDay.getOrDefault(day, List.of())) {
                if (played.contains(fx.home()) || played.contains(fx.away())) continue; // one match a day
                boolean homeKnown = squads.containsKey(fx.home());
                boolean awayKnown = squads.containsKey(fx.away());
                List<Player> homeXi = homeKnown ? pick(fx.home(), tacticByTeam, fitness, fitnessOn) : null;
                List<Player> awayXi = awayKnown ? pick(fx.away(), tacticByTeam, fitness, fitnessOn) : null;
                if ((fx.home() == focus || fx.away() == focus) && homeKnown && awayKnown) {
                    boolean home = fx.home() == focus;
                    CanonicalMatchEvaluationAdapter scorer = fitnessOn ? adapter : adapterWithoutFitness;
                    CanonicalTeamEvaluation h = scorer.evaluateTeam(team(homeXi, fx.home(), tacticByTeam, fitness));
                    CanonicalTeamEvaluation a = scorer.evaluateTeam(team(awayXi, fx.away(), tacticByTeam, fitness));
                    CanonicalScoreSampler.MatchExpectation e = sampler.expect(scorer.evaluate(h, a, MatchVenue.HOME));
                    if (fx.competition() == LEAGUE) leaguePoints += home ? e.homeXpts() : e.awayXpts();
                    List<Player> own = home ? homeXi : awayXi;
                    double f = 0;
                    for (Player p : own) {
                        f += fitnessOn ? FitnessFactor.of(scoringFitness(p, p.teamId(), tacticByTeam, fitness), compartment) : 1.0;
                        apps.merge(p.id(), 1, Integer::sum);
                    }
                    factorSum += f / 11;
                    matches++;
                }
                if (fitnessOn) {
                    if (homeXi != null) drain(homeXi, tempo(fx.home(), tacticByTeam), fitness);
                    if (awayXi != null) drain(awayXi, tempo(fx.away(), tacticByTeam), fitness);
                }
                played.add(fx.home());
                played.add(fx.away());
            }
            if (fitnessOn) {
                FitnessEconomy.DayPlan plan = FitnessEconomy.defaultDayPlan(FitnessEconomy.weekday(day));
                for (Map.Entry<Long, List<Player>> entry : squads.entrySet()) {
                    if (played.contains(entry.getKey())) continue;
                    for (Player p : entry.getValue()) {
                        fitness.put(p.id(), FitnessEconomy.recover(engine.getFitness(), fitness.get(p.id()), plan, p.naturalFitness()));
                    }
                }
            }
        }
        int top = apps.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new Result(leaguePoints, matches, factorSum / Math.max(1, matches), apps.size(), top);
    }

    private double scoringFitness(Player p, long teamId, Map<Long, Tactic> tacticByTeam, Map<Long, Double> fitness) {
        return FitnessFactor.inMatch(engine, fitness.get(p.id()), tempo(teamId, tacticByTeam), p.stamina(),
                compartment.getRating().getFitnessFresh());
    }

    private void drain(List<Player> xi, String tempo, Map<Long, Double> fitness) {
        for (Player p : xi) fitness.put(p.id(), FitnessEconomy.afterMatch(engine, fitness.get(p.id()), tempo, p.stamina()));
    }

    private static String tempo(long teamId, Map<Long, Tactic> tacticByTeam) {
        Tactic t = tacticByTeam.get(teamId);
        return t == null ? "Standard" : t.axes().getOrDefault("tempo", "Standard");
    }

    /** The engine's automatic eleven: each slot, in display order, takes the best remaining slotAptness × factor. */
    private List<Player> pick(long teamId, Map<Long, Tactic> tacticByTeam, Map<Long, Double> fitness, boolean fatigueAware) {
        Tactic t = tacticByTeam.get(teamId);
        String formation = t == null ? "442" : t.formation();
        List<String> slots = new ArrayList<>();
        tactics.getRoomInTeamByTactic(formation).entrySet().stream()
                .sorted(Comparator.comparingInt(e -> tactics.getValueForTacticDisplay(e.getKey())))
                .forEach(e -> { for (int i = 0; i < e.getValue(); i++) slots.add(e.getKey()); });
        List<Player> left = new ArrayList<>(squads.get(teamId));
        List<Player> xi = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (String slot : slots) {
            Player best = null;
            double bestScore = -1;
            for (Player p : left) {
                double score = slotAptness(p, slot)
                        * (fatigueAware ? FitnessFactor.of(fitness.get(p.id()), compartment) : 1.0);
                if (score > bestScore) { bestScore = score; best = p; }
            }
            if (best == null) break;
            left.remove(best);
            xi.add(best);
            used.add(slot);
        }
        slotsUsed.put(teamId, used);
        return xi;
    }

    private final Map<Long, List<String>> slotsUsed = new HashMap<>();

    private double slotAptness(Player p, String slot) {
        String a = TacticService.getBasePosition(p.natural());
        String b = TacticService.getBasePosition(slot);
        if (a != null && a.equals(b)) return p.rating();
        return p.rating() * engine.getPlayerValue().familiarity(p.natural(), slot);
    }

    private CanonicalRuntimeTeamInput team(List<Player> xi, long teamId, Map<Long, Tactic> tacticByTeam,
                                           Map<Long, Double> fitness) {
        Tactic t = tacticByTeam.get(teamId);
        TacticalContextInput context = TacticCombinationCalibration.context(t == null ? Map.of() : t.axes());
        List<String> used = slotsUsed.get(teamId);
        List<CanonicalLineupPlayer> lineup = new ArrayList<>();
        Map<Long, TacticalContextInput> contexts = new LinkedHashMap<>();
        Map<PlayerPosition, Integer> occurrences = new EnumMap<>(PlayerPosition.class);
        for (int i = 0; i < xi.size(); i++) {
            Player p = xi.get(i);
            PlayerPosition slot = PlayerPosition.require(used.get(i));
            PlayerPosition natural = PlayerPosition.require(p.natural());
            int occurrence = occurrences.merge(slot, 1, Integer::sum);
            Map<PlayerPosition, Integer> familiarity = new EnumMap<>(PlayerPosition.class);
            familiarity.put(natural, 20);
            familiarity.put(slot, (int) Math.max(1, Math.round(20 * slotAptness(p, used.get(i)) / p.rating())));
            PlayerCapabilitySnapshot capability = new PlayerCapabilitySnapshot(p.id(), natural, familiarity, Map.of(),
                    10, 10, false, false, false);
            // Pre-match fitness: the production adapter applies the in-match rule from the tempo.
            lineup.add(new CanonicalLineupPlayer(p.id(), slot, occurrence, null, Duty.SUPPORT, p.attributes(),
                    fitness.get(p.id()), 70, capability, 50.0, Set.of(), ForwardInstruction.DEFAULT, p.rating()));
            contexts.put(p.id(), context);
        }
        return new CanonicalRuntimeTeamInput(TacticCombinationCalibration.mentality(context.mentality()), lineup, contexts);
    }

    // ------------------------------------------------------------------ data

    private static List<String[]> csv(String name) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("fitness-calibration/" + name).getInputStream(), StandardCharsets.UTF_8))) {
            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String[] cells = line.split(",", -1);
                for (int i = 0; i < cells.length; i++) cells[i] = cells[i].replace("\"", "").trim();
                rows.add(cells);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    private static int column(String[] header, String name) {
        for (int i = 0; i < header.length; i++) if (header[i].equals(name)) return i;
        return -1;
    }

    private void loadSquads() {
        List<String[]> rows = csv("squads.csv");
        String[] h = rows.get(0);
        for (String[] r : rows.subList(1, rows.size())) {
            Map<PlayerAttribute, Integer> attributes = new EnumMap<>(PlayerAttribute.class);
            for (PlayerAttribute a : PlayerAttribute.values()) {
                int c = column(h, a.name());
                attributes.put(a, c < 0 ? 10 : Math.max(1, Math.min(20, Integer.parseInt(r[c]))));
            }
            Player p = new Player(Long.parseLong(r[column(h, "ID")]), Long.parseLong(r[column(h, "TEAM_ID")]),
                    r[column(h, "NATURAL_POSITION")], Double.parseDouble(r[column(h, "RATING")]), attributes,
                    Integer.parseInt(r[column(h, "STAMINA")]), Integer.parseInt(r[column(h, "NATURAL_FITNESS")]));
            squads.computeIfAbsent(p.teamId(), k -> new ArrayList<>()).add(p);
        }
    }

    private void loadTactics() {
        List<String[]> rows = csv("tactics.csv");
        String[] h = rows.get(0);
        for (String[] r : rows.subList(1, rows.size())) {
            Map<String, String> axes = new HashMap<>();
            axes.put("mentality", r[column(h, "MENTALITY")]);
            axes.put("tempo", r[column(h, "TEMPO")]);
            axes.put("passingType", r[column(h, "PASSING_TYPE")]);
            axes.put("defensiveLine", r[column(h, "DEFENSIVE_LINE")]);
            axes.put("pressing", r[column(h, "PRESSING")]);
            axes.put("width", r[column(h, "WIDTH")]);
            axes.put("recovery", r[column(h, "RECOVERY")]);
            savedTactics.put(Long.parseLong(r[column(h, "TEAM_ID")]), new Tactic(r[column(h, "TACTIC")], axes));
        }
    }

    private void loadFixtures() {
        List<String[]> rows = csv("fixtures.csv");
        for (String[] r : rows.subList(1, rows.size())) {
            fixtures.add(new Fixture(Integer.parseInt(r[0]), Integer.parseInt(r[1]), Long.parseLong(r[2]), Long.parseLong(r[3])));
        }
    }
}
