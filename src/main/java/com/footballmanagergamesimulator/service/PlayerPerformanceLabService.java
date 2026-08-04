package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.DefensivePressure;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Season-wide player lab with explicit provenance for observed, derived and modelled values. */
@Service
public class PlayerPerformanceLabService {
    private final HumanRepository humans;
    private final PlayerSkillsRepository skillsRepository;
    private final PlayerSeasonStatRepository seasonStats;
    private final ScorerRepository scorers;
    private final MatchStatsRepository matches;
    private final ShotEventService shotEvents;
    private final DefensivePressureLedgerService pressureLedger;

    public PlayerPerformanceLabService(HumanRepository humans, PlayerSkillsRepository skillsRepository,
                                       PlayerSeasonStatRepository seasonStats, ScorerRepository scorers,
                                       MatchStatsRepository matches, ShotEventService shotEvents,
                                       DefensivePressureLedgerService pressureLedger) {
        this.humans = humans;
        this.skillsRepository = skillsRepository;
        this.seasonStats = seasonStats;
        this.scorers = scorers;
        this.matches = matches;
        this.shotEvents = shotEvents;
        this.pressureLedger = pressureLedger;
    }

    public PerformanceLab performance(long playerId, int season) {
        Human player = humans.findById(playerId).orElseThrow();
        PlayerSkills skills = skillsRepository.findPlayerSkillsByPlayerId(playerId).orElseGet(PlayerSkills::new);
        List<PlayerSeasonStat> allRows = seasonStats.findAllBySeasonNumber(season);
        Totals totals = aggregate(allRows.stream().filter(row -> row.getPlayerId() == playerId).toList());
        List<Scorer> appearances = valid(scorers.findByPlayerIdAndSeasonNumber(playerId, season));
        List<MatchStats> seasonMatches = matches.findAllBySeasonNumber(season);
        String group = group(position(player, skills));
        List<Totals> peers = peerTotals(allRows, group);

        List<Metric> metrics = metrics(totals, skills, peers);
        List<FormMatch> form = appearances.stream().sorted(Comparator.comparingLong(Scorer::getId).reversed()).limit(5)
                .map(this::form).toList();
        Split home = split(appearances, seasonMatches, true);
        Split away = split(appearances, seasonMatches, false);
        Split starters = splitBySubstitute(appearances, false);
        Split substitutes = splitBySubstitute(appearances, true);
        TeamImpact impact = teamImpact(playerId, player.getTeamId(), season, appearances);
        List<Chemistry> chemistry = chemistry(player, season, appearances);
        List<SimilarPlayer> similar = similarPlayers(player, skills);
        List<RoleSuitability> roles = roleSuitability(group, skills);

        String systemMessage = "Historical tactics are not persisted per appearance; role output uses the recorded position and current skill profile.";
        return new PerformanceLab(playerId, player.getName(), position(player, skills), group, season,
                totals.appearances, totals.minutes, peers.size(), metrics, form,
                new Context(home, away, starters, substitutes, impact,
                        new RoleSystemContext(position(player, skills), averageRating(appearances), "INFERRED", systemMessage)),
                chemistry, similar, roles, quality());
    }

    public GoalkeeperHub goalkeeper(long playerId, int season) {
        Human player = humans.findById(playerId).orElseThrow();
        PlayerSkills skills = skillsRepository.findPlayerSkillsByPlayerId(playerId).orElseGet(PlayerSkills::new);
        String position = position(player, skills);
        if (!"GK".equals(group(position))) {
            return new GoalkeeperHub(playerId, player.getName(), season, false,
                    "Goalkeeper Hub is not applicable to this player's position.", 0, 0, List.of(), quality());
        }
        if (player.getTeamId() == null) {
            return new GoalkeeperHub(playerId, player.getName(), season, true,
                    "No club match sample is available for this free-agent goalkeeper.", 0, 0, List.of(), quality());
        }

        List<Scorer> apps = valid(scorers.findByPlayerIdAndSeasonNumber(playerId, season));
        List<MatchStats> fixtures = matchIndex(season).values().stream()
                .filter(match -> apps.stream().anyMatch(app -> sameFixture(app, match))).toList();
        int minutes = aggregate(seasonStats.findAllBySeasonNumber(season).stream()
                .filter(row -> row.getPlayerId() == playerId).toList()).minutes;
        if (minutes <= 0) minutes = apps.size() * 90;

        int shotsOnTarget = 0, goals = 0, saves = 0, penaltiesFaced = 0, penaltySaves = 0;
        int opponentCrosses = 0, passes = 0;
        double xgot = 0, lineHeight = 0;
        for (MatchStats match : fixtures) {
            boolean home = match.getTeam1Id() == player.getTeamId();
            shotsOnTarget += home ? match.getAwayShotsOnTarget() : match.getHomeShotsOnTarget();
            goals += home ? match.getAwayGoals() : match.getHomeGoals();
            saves += home ? match.getHomeSaves() : match.getAwaySaves();
            opponentCrosses += home ? match.getAwayCrossesAccurate() : match.getHomeCrossesAccurate();
            passes += home ? match.getHomePasses() : match.getAwayPasses();
            for (ShotEvent shot : shotEvents.eventsForMatch(match)) {
                if (shot.getTeamId() == player.getTeamId()) continue;
                xgot += shot.getXgot() / 10_000.0;
                if ("PENALTY".equals(shot.getSituation())) {
                    penaltiesFaced++;
                    if ("SAVED".equals(shot.getOutcome())) penaltySaves++;
                }
            }
            lineHeight += pressureLedger.rowsForMatch(match).stream()
                    .filter(row -> row.getTeamId() == player.getTeamId())
                    .mapToDouble(DefensivePressure::getDefensiveLineHeightMeters).findFirst().orElse(43);
        }
        double games = Math.max(1, fixtures.size());
        double savePct = shotsOnTarget == 0 ? 0 : saves * 100.0 / shotsOnTarget;
        double prevented = xgot - goals;
        double claimRate = clamp((skills.getHandling() + skills.getCommandOfArea() + skills.getAnticipation()) / 60.0, .25, .88);
        double crossesClaimed = opponentCrosses * claimRate;
        double sweeperFactor = clamp((skills.getPace() + skills.getAcceleration() + skills.getAnticipation()
                + skills.getOneOnOnes()) / 80.0, .2, .95);
        double avgLine = lineHeight / games;
        double avgPosition = clamp(8.5 + (avgLine - 35) * .28 + sweeperFactor * 4.2, 8, 22);
        double sweeping = games * (.45 + sweeperFactor * 1.45);
        double outsideBox = sweeping * .62;
        double goalkeeperPasses = passes * clamp(.07 + skills.getPassing() / 200.0, .08, .18);
        double longShare = clamp(.58 - (skills.getPassing() + skills.getThrowing()) / 100.0, .22, .58);
        double longPasses = goalkeeperPasses * longShare;
        double shortPasses = goalkeeperPasses - longPasses;
        double progression = longPasses * (.32 + skills.getKicking() / 100.0) + shortPasses * .08;
        double errors = goals * clamp((32.0 - skills.getConcentration() - skills.getDecisions()) / 70.0, .02, .18);

        List<GkMetric> metrics = List.of(
                metric("Save percentage", savePct, "%", "OBSERVED"),
                metric("xGOT faced", xgot, "xG", "DERIVED_FROM_SHOTS"),
                metric("Goals prevented", prevented, "goals", "DERIVED"),
                metric("Goals prevented per 90", per90(prevented, minutes), "per 90", "DERIVED"),
                metric("Penalty saves", penaltySaves, "saves", "OBSERVED"),
                metric("Penalty save rate", penaltiesFaced == 0 ? 0 : penaltySaves * 100.0 / penaltiesFaced, "%", "OBSERVED"),
                metric("Crosses claimed", crossesClaimed, "claims", "MODELED"),
                metric("Sweeping actions", sweeping, "actions", "MODELED"),
                metric("Actions outside box", outsideBox, "actions", "MODELED"),
                metric("Short distribution", shortPasses, "passes", "MODELED_SPLIT"),
                metric("Long distribution", longPasses, "passes", "MODELED_SPLIT"),
                metric("Progression from passes", progression, "progressive passes", "MODELED"),
                metric("Errors", errors, "errors", "MODELED"),
                metric("Average position height", avgPosition, "metres", "MODELED"),
                metric("Distance to defensive line", Math.max(0, avgLine - avgPosition), "metres", "MODELED")
        );
        return new GoalkeeperHub(playerId, player.getName(), season, true, "", fixtures.size(), minutes, metrics, quality());
    }

    private List<Metric> metrics(Totals t, PlayerSkills s, List<Totals> peers) {
        double progPass = t.passesCompleted * clamp(.10 + s.getVision() / 160.0, .1, .24);
        double progCarry = t.dribbles * (1.05 + s.getDribbling() / 40.0);
        double npXg = t.xg * .94;
        double xa = t.chances * (.22 + s.getPassing() / 100.0);
        double touchesBox = t.xg * 4.2 + t.chances * 1.25 + t.dribbles * .6;
        double interceptions = t.defensiveActions * .34;
        double turnovers = Math.max(0, t.passesAttempted - t.passesCompleted) + t.dribbles * .42;
        double possessionValue = progPass * .012 + progCarry * .018 + xa * .30 + npXg * .22 - turnovers * .006;
        return List.of(
                labMetric("xG", t.xg, t, peers, p -> p.xg, "OBSERVED_ACCUMULATOR"),
                labMetric("Non-penalty xG", npXg, t, peers, p -> p.xg * .94, "MODELED_PENALTY_SPLIT"),
                labMetric("xA", xa, t, peers, p -> p.chances * .32, "MODELED"),
                labMetric("Key passes", t.chances, t, peers, p -> p.chances, "OBSERVED_ACCUMULATOR"),
                labMetric("Progressive passes", progPass, t, peers, p -> p.passesCompleted * .16, "MODELED"),
                labMetric("Progressive carries", progCarry, t, peers, p -> p.dribbles * 1.3, "MODELED"),
                labMetric("Touches in box", touchesBox, t, peers, p -> p.xg * 4.2 + p.chances * 1.25 + p.dribbles * .6, "MODELED"),
                labMetric("Pressures", t.pressures, t, peers, p -> p.pressures, "OBSERVED_ACCUMULATOR"),
                labMetric("Counterpressures", t.counterpressures, t, peers, p -> p.counterpressures, "OBSERVED_ACCUMULATOR"),
                labMetric("Tackles", t.tackles, t, peers, p -> p.tackles, "OBSERVED_ACCUMULATOR"),
                labMetric("Interceptions", interceptions, t, peers, p -> p.defensiveActions * .34, "MODELED_SPLIT"),
                labMetric("Turnovers", turnovers, t, peers, p -> Math.max(0, p.passesAttempted - p.passesCompleted) + p.dribbles * .42, "DERIVED"),
                labMetric("Possession value", possessionValue, t, peers,
                        p -> p.passesCompleted * .16 * .012 + p.dribbles * 1.3 * .018 + p.chances * .096 + p.xg * .207, "MODELED")
        );
    }

    private Metric labMetric(String label, double raw, Totals t, List<Totals> peers,
                             ToDoubleFunction<Totals> extractor, String quality) {
        double value = per90(raw, t.minutes);
        List<Double> pool = peers.stream().map(p -> per90(extractor.applyAsDouble(p), p.minutes)).sorted().toList();
        long below = pool.stream().filter(peer -> peer <= value).count();
        double percentile = pool.isEmpty() ? 50 : below * 100.0 / pool.size();
        return new Metric(label, round(value), round(percentile), quality);
    }

    private List<Totals> peerTotals(List<PlayerSeasonStat> rows, String group) {
        Map<Long, List<PlayerSeasonStat>> byPlayer = rows.stream().collect(Collectors.groupingBy(PlayerSeasonStat::getPlayerId));
        Map<Long, Human> playerById = humans.findAllById(byPlayer.keySet()).stream().collect(Collectors.toMap(Human::getId, p -> p));
        Map<Long, PlayerSkills> skillsById = skillsRepository.findAllByPlayerIdIn(byPlayer.keySet()).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, p -> p, (a, b) -> a));
        return byPlayer.entrySet().stream().filter(entry -> {
                    Human human = playerById.get(entry.getKey());
                    if (human == null) return false;
                    return group.equals(group(position(human, skillsById.get(entry.getKey()))));
                }).map(entry -> aggregate(entry.getValue())).filter(t -> t.minutes > 0).toList();
    }

    private Totals aggregate(List<PlayerSeasonStat> rows) {
        Totals t = new Totals();
        for (PlayerSeasonStat row : rows) {
            t.appearances += row.getAppearances(); t.minutes += row.getMinutes();
            t.defensiveActions += row.getDefensiveActions(); t.pressures += row.getPressures();
            t.counterpressures += row.getCounterpressures(); t.tackles += row.getTackles(); t.xg += row.getShots();
            t.passesAttempted += row.getPassesAttempted(); t.passesCompleted += row.getPassesCompleted();
            t.chances += row.getChancesCreated(); t.dribbles += row.getDribblesCompleted();
        }
        return t;
    }

    private TeamImpact teamImpact(long playerId, Long teamId, int season, List<Scorer> playerApps) {
        if (teamId == null) return new TeamImpact(0, 0, 0, 0, "No current club");
        List<Scorer> teamRows = valid(scorers.findAllByTeamIdAndSeasonNumber(teamId, season));
        Map<String, List<Scorer>> fixtures = teamRows.stream().collect(Collectors.groupingBy(this::fixtureKey));
        Set<String> played = playerApps.stream().map(this::fixtureKey).collect(Collectors.toSet());
        double withPoints = 0, withoutPoints = 0; int withGames = 0, withoutGames = 0;
        for (Map.Entry<String, List<Scorer>> entry : fixtures.entrySet()) {
            Scorer sample = entry.getValue().get(0);
            double points = sample.getTeamScore() > sample.getOpponentScore() ? 3 : sample.getTeamScore() == sample.getOpponentScore() ? 1 : 0;
            if (played.contains(entry.getKey())) { withGames++; withPoints += points; }
            else { withoutGames++; withoutPoints += points; }
        }
        return new TeamImpact(withGames, round(withPoints / Math.max(1, withGames)), withoutGames,
                round(withoutPoints / Math.max(1, withoutGames)), "DERIVED_FROM_LINEUPS");
    }

    private List<Chemistry> chemistry(Human player, int season, List<Scorer> playerApps) {
        if (player.getTeamId() == null) return List.of();
        Set<String> fixtures = playerApps.stream().map(this::fixtureKey).collect(Collectors.toSet());
        Map<Long, List<Scorer>> mates = valid(scorers.findAllByTeamIdAndSeasonNumber(player.getTeamId(), season)).stream()
                .filter(row -> row.getPlayerId() != player.getId() && fixtures.contains(fixtureKey(row)))
                .collect(Collectors.groupingBy(Scorer::getPlayerId));
        Map<Long, Human> names = humans.findAllById(mates.keySet()).stream().collect(Collectors.toMap(Human::getId, h -> h));
        return mates.entrySet().stream().map(entry -> {
                    Human mate = names.get(entry.getKey());
                    double score = clamp(45 + entry.getValue().size() * 2.2 + averageRating(entry.getValue()) * 3.2, 0, 100);
                    return new Chemistry(entry.getKey(), mate == null ? "Unknown" : mate.getName(), entry.getValue().size(), round(score), "MODELED_FROM_COAPPEARANCES");
                }).sorted(Comparator.comparingDouble(Chemistry::score).reversed()).limit(8).toList();
    }

    private List<SimilarPlayer> similarPlayers(Human player, PlayerSkills source) {
        if (player.getTeamId() == null) return List.of();
        List<Human> candidates = new ArrayList<>(humans
                .findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(1, player.getTeamId(), player.getPosition(),
                        PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "currentAbility"))).getContent());
        candidates.addAll(humans.findAllByTeamIdAndTypeId(player.getTeamId(), 1));
        Map<Long, PlayerSkills> candidateSkills = skillsRepository.findAllByPlayerIdIn(candidates.stream().map(Human::getId).toList()).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, p -> p, (a, b) -> a));
        return candidates.stream().filter(candidate -> candidate.getId() != player.getId()).distinct().map(candidate -> {
                    PlayerSkills other = candidateSkills.get(candidate.getId());
                    double similarity = other == null ? 0 : skillSimilarity(source, other);
                    return new SimilarPlayer(candidate.getId(), candidate.getName(), candidate.getTeamId(), candidate.getPosition(), round(similarity));
                }).sorted(Comparator.comparingDouble(SimilarPlayer::similarity).reversed()).limit(6).toList();
    }

    private double skillSimilarity(PlayerSkills a, PlayerSkills b) {
        int[] av = {a.getPassing(), a.getVision(), a.getDribbling(), a.getFinishing(), a.getTackling(), a.getPositioning(), a.getPace(), a.getStrength(), a.getWorkRate(), a.getAnticipation()};
        int[] bv = {b.getPassing(), b.getVision(), b.getDribbling(), b.getFinishing(), b.getTackling(), b.getPositioning(), b.getPace(), b.getStrength(), b.getWorkRate(), b.getAnticipation()};
        double distance = 0;
        for (int i = 0; i < av.length; i++) distance += Math.pow(av[i] - bv[i], 2);
        return clamp(100 - Math.sqrt(distance) * 2.2, 0, 100);
    }

    private List<RoleSuitability> roleSuitability(String group, PlayerSkills s) {
        return switch (group) {
            case "GK" -> List.of(role("Sweeper Keeper", avg(s.getReflexes(), s.getOneOnOnes(), s.getPassing(), s.getAnticipation(), s.getPace())), role("Goalkeeper", avg(s.getHandling(), s.getReflexes(), s.getCommandOfArea(), s.getConcentration())));
            case "DEF" -> List.of(role("Ball-playing Defender", avg(s.getTackling(), s.getPositioning(), s.getPassing(), s.getComposure())), role("Central Defender", avg(s.getTackling(), s.getMarking(), s.getHeading(), s.getStrength())), role("Wing-back", avg(s.getCrossing(), s.getPace(), s.getStamina(), s.getWorkRate())));
            case "FWD" -> List.of(role("Advanced Forward", avg(s.getFinishing(), s.getOffTheBall(), s.getPace(), s.getComposure())), role("Inside Forward", avg(s.getDribbling(), s.getFinishing(), s.getAcceleration(), s.getFlair())), role("Target Forward", avg(s.getHeading(), s.getStrength(), s.getBravery(), s.getFinishing())));
            default -> List.of(role("Advanced Playmaker", avg(s.getPassing(), s.getVision(), s.getTechnique(), s.getDecisions())), role("Box-to-box Midfielder", avg(s.getStamina(), s.getWorkRate(), s.getPassing(), s.getTackling())), role("Ball-winning Midfielder", avg(s.getTackling(), s.getAggression(), s.getPositioning(), s.getWorkRate())));
        };
    }

    private RoleSuitability role(String name, double attributeAverage) { return new RoleSuitability(name, round(clamp(attributeAverage * 5, 0, 100)), "MODELED_FROM_ATTRIBUTES"); }
    private double avg(int... values) { double sum = 0; for (int value : values) sum += value; return values.length == 0 ? 0 : sum / values.length; }

    private Split split(List<Scorer> apps, List<MatchStats> seasonMatches, boolean home) {
        List<Scorer> selected = apps.stream().filter(app -> {
            MatchStats match = seasonMatches.stream().filter(candidate -> sameFixture(app, candidate)).findFirst().orElse(null);
            return match != null && (match.getTeam1Id() == app.getTeamId()) == home;
        }).toList();
        return summary(home ? "Home" : "Away", selected, "OBSERVED");
    }

    private Split splitBySubstitute(List<Scorer> apps, boolean substitute) {
        return summary(substitute ? "Substitute" : "Starter", apps.stream().filter(app -> app.isSubstitute() == substitute).toList(), "OBSERVED");
    }

    private Split summary(String label, List<Scorer> apps, String quality) {
        return new Split(label, apps.size(), apps.stream().mapToInt(Scorer::getGoals).sum(), apps.stream().mapToInt(Scorer::getAssists).sum(), round(averageRating(apps)), quality);
    }

    private FormMatch form(Scorer app) { return new FormMatch(app.getOpponentTeamId(), app.getOpponentTeamName(), app.getTeamScore(), app.getOpponentScore(), app.getGoals(), app.getAssists(), round(app.getRating()), app.isSubstitute()); }
    private boolean sameFixture(Scorer app, MatchStats match) { return app.getCompetitionId() == match.getCompetitionId() && app.getSeasonNumber() == match.getSeasonNumber() && app.getRoundNumber() == match.getRoundNumber() && ((match.getTeam1Id() == app.getTeamId() && match.getTeam2Id() == app.getOpponentTeamId()) || (match.getTeam2Id() == app.getTeamId() && match.getTeam1Id() == app.getOpponentTeamId())); }
    private Map<Long, MatchStats> matchIndex(int season) { return matches.findAllBySeasonNumber(season).stream().collect(Collectors.toMap(MatchStats::getId, match -> match, (a, b) -> a)); }
    private List<Scorer> valid(List<Scorer> rows) { return rows.stream().filter(row -> row.getRoundNumber() >= 0 && row.getOpponentTeamId() >= 0 && row.getTeamScore() >= 0).toList(); }
    private String fixtureKey(Scorer row) { return row.getCompetitionId() + ":" + row.getSeasonNumber() + ":" + row.getRoundNumber() + ":" + Math.min(row.getTeamId(), row.getOpponentTeamId()) + ":" + Math.max(row.getTeamId(), row.getOpponentTeamId()); }
    private double averageRating(List<Scorer> rows) { return rows.stream().filter(row -> row.getRating() > 0).mapToDouble(Scorer::getRating).average().orElse(0); }
    private String position(Human human, PlayerSkills skills) { if (skills != null && skills.getPosition() != null && !skills.getPosition().isBlank()) return skills.getPosition(); return human.getPosition() == null ? "Unknown" : human.getPosition(); }
    private String group(String position) { String p = position.toUpperCase(Locale.ROOT); if (p.contains("GK")) return "GK"; if (p.contains("CB") || p.contains("LB") || p.contains("RB") || p.contains("DEF")) return "DEF"; if (p.contains("ST") || p.contains("FW") || p.contains("LW") || p.contains("RW")) return "FWD"; return "MID"; }
    private double per90(double value, int minutes) { return minutes <= 0 ? 0 : value * 90.0 / minutes; }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private GkMetric metric(String label, double value, String unit, String quality) { return new GkMetric(label, round(value), unit, quality); }
    private Map<String, String> quality() { Map<String, String> q = new LinkedHashMap<>(); q.put("OBSERVED", "Persisted match or player event"); q.put("DERIVED", "Calculated from observed inputs"); q.put("MODELED", "Estimated because individual tracking is not persisted"); return q; }

    private static final class Totals { int appearances, minutes; double defensiveActions, pressures, counterpressures, tackles, xg, passesAttempted, passesCompleted, chances, dribbles; }

    public record PerformanceLab(long playerId, String playerName, String position, String positionGroup, int seasonNumber,
                                 int appearances, int minutes, int peerCount, List<Metric> metrics, List<FormMatch> lastFive,
                                 Context contexts, List<Chemistry> chemistry, List<SimilarPlayer> similarPlayers,
                                 List<RoleSuitability> roleSuitability, Map<String, String> qualityLegend) {}
    public record Metric(String label, double valuePer90, double percentile, String quality) {}
    public record FormMatch(long opponentTeamId, String opponent, int goalsFor, int goalsAgainst, int goals, int assists, double rating, boolean substitute) {}
    public record Context(Split home, Split away, Split starter, Split substitute, TeamImpact teamImpact, RoleSystemContext roleAndSystem) {}
    public record Split(String label, int appearances, int goals, int assists, double averageRating, String quality) {}
    public record TeamImpact(int gamesWith, double pointsPerGameWith, int gamesWithout, double pointsPerGameWithout, String quality) {}
    public record RoleSystemContext(String recordedRole, double averageRating, String quality, String note) {}
    public record Chemistry(long playerId, String playerName, int coAppearances, double score, String quality) {}
    public record SimilarPlayer(long playerId, String playerName, Long teamId, String position, double similarity) {}
    public record RoleSuitability(String role, double score, String quality) {}
    public record GoalkeeperHub(long playerId, String playerName, int seasonNumber, boolean eligible, String message,
                                int matches, int minutes, List<GkMetric> metrics, Map<String, String> qualityLegend) {}
    public record GkMetric(String label, double value, String unit, String quality) {}
}
