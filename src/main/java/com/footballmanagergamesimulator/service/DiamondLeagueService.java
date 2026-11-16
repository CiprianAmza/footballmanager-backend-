package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.algorithms.DiamondLeagueScheduleGenerator;
import com.footballmanagergamesimulator.algorithms.DiamondLeagueScheduleGenerator.Fixture;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Diamond League: an invitational competition (type {@link Competition#DIAMOND_LEAGUE})
 * between a fixed list of clubs from different leagues. Every pair meets four
 * times, twice at each ground, and the table uses the usual 3/1/0 points.
 *
 * <p>Each season's edition is entered and drawn here (entries, empty table rows,
 * every fixture); {@link FixtureSchedulingService#generateSeasonCalendar(int)} then
 * puts its rounds on days its clubs keep free, and the matches are played by the
 * ordinary matchday pipeline. It qualifies, relegates and pays nobody.
 *
 * <p>Settings: {@code diamond-league.enabled} (default {@code true}),
 * {@code diamond-league.teams} (club names) and {@code diamond-league.seed}
 * (mixed into each season's draw). If a configured club is not in the world the
 * competition is not created and a warning names the missing club.
 */
@Service
public class DiamondLeagueService {

    private static final Logger log = LoggerFactory.getLogger(DiamondLeagueService.class);

    public static final String NAME = "Diamond League";
    static final int REPUTATION = 8_500;

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamInfoRepository entryRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final TeamCompetitionDetailRepository standingsRepository;
    private final TeamRepository teamRepository;

    @Value("${diamond-league.enabled:true}")
    private boolean enabled;

    @Value("${diamond-league.teams:Inazuma Japan,Tik Tok,FC San Marino,No Merci,Shadows,Ligthnings,Xenon,Athletic Sohatu}")
    private List<String> teamNames;

    @Value("${diamond-league.seed:0}")
    private long seed;

    public DiamondLeagueService(CompetitionRepository competitionRepository,
                                CompetitionTeamInfoRepository entryRepository,
                                CompetitionTeamInfoMatchRepository fixtureRepository,
                                TeamCompetitionDetailRepository standingsRepository,
                                TeamRepository teamRepository) {
        this.competitionRepository = competitionRepository;
        this.entryRepository = entryRepository;
        this.fixtureRepository = fixtureRepository;
        this.standingsRepository = standingsRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * Enters the configured clubs for {@code season} and draws its schedule. Creates
     * the competition on first use. Idempotent: entries, table rows and fixtures that
     * already exist for the season are kept.
     */
    @Transactional
    public void prepareSeason(int season) {
        Optional<Competition> competition = ensureCompetition();
        if (competition.isEmpty()) return;
        List<Long> clubs = resolveClubs();
        if (clubs == null) return;
        long competitionId = competition.get().getId();

        Set<Long> entered = entryRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, season).stream()
                .map(CompetitionTeamInfo::getTeamId).collect(Collectors.toSet());
        List<CompetitionTeamInfo> entries = new ArrayList<>();
        for (long club : clubs) {
            if (entered.contains(club)) continue;
            CompetitionTeamInfo entry = new CompetitionTeamInfo();
            entry.setCompetitionId(competitionId);
            entry.setTeamId(club);
            entry.setSeasonNumber(season);
            entry.setRound(1);
            entries.add(entry);
        }
        if (!entries.isEmpty()) entryRepository.saveAll(entries);

        Set<Long> withRow = standingsRepository.findAllByCompetitionId(competitionId).stream()
                .map(TeamCompetitionDetail::getTeamId).collect(Collectors.toSet());
        List<TeamCompetitionDetail> rows = new ArrayList<>();
        for (long club : clubs) {
            if (withRow.contains(club)) continue;
            TeamCompetitionDetail row = new TeamCompetitionDetail();
            row.setCompetitionId(competitionId);
            row.setTeamId(club);
            row.setForm("");
            rows.add(row);
        }
        if (!rows.isEmpty()) standingsRepository.saveAll(rows);

        String seasonNumber = String.valueOf(season);
        if (!fixtureRepository.findDistinctRoundsByCompetitionIdAndSeasonNumber(competitionId, seasonNumber).isEmpty()) {
            return;
        }
        List<List<Fixture>> rounds = DiamondLeagueScheduleGenerator.generate(
                clubs, CompetitionFormatConfig.DIAMOND_LEAGUE_ENCOUNTERS, drawSeed(season, competitionId, clubs));
        List<CompetitionTeamInfoMatch> fixtures = new ArrayList<>();
        for (int r = 0; r < rounds.size(); r++) {
            List<Fixture> round = rounds.get(r);
            for (int i = 0; i < round.size(); i++) {
                CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
                match.setCompetitionId(competitionId);
                match.setSeasonNumber(seasonNumber);
                match.setRound(r + 1);
                match.setMatchIndex(i + 1);
                match.setTeam1Id(round.get(i).homeTeamId());
                match.setTeam2Id(round.get(i).awayTeamId());
                fixtures.add(match);
            }
        }
        fixtureRepository.saveAll(fixtures);
        log.info("Diamond League season {}: {} clubs, {} rounds, {} fixtures drawn",
                season, clubs.size(), rounds.size(), fixtures.size());
    }

    /** The Diamond League competition, created when enabled and every configured club exists. */
    @Transactional
    public Optional<Competition> ensureCompetition() {
        if (!enabled) return Optional.empty();
        Optional<Competition> existing = competitionRepository.findAll().stream()
                .filter(Competition::isDiamondLeague).findFirst();
        if (existing.isPresent()) return existing;
        if (resolveClubs() == null) return Optional.empty();

        Competition competition = new Competition();
        competition.setName(NAME);
        competition.setTypeId(Competition.DIAMOND_LEAGUE);
        competition.setNationId(0);   // not tied to a nation, like the continental competitions
        competition.setPrizesId(0);   // no prize table: the competition pays nothing
        competition.setTier(1);
        competition.setReputation(REPUTATION);
        Competition saved = competitionRepository.save(competition);
        log.info("Diamond League created (competition {}) for {}", saved.getId(), teamNames);
        return Optional.of(saved);
    }

    /**
     * Team ids of the configured clubs, in configuration order, or {@code null}
     * (with a warning) when one is missing, listed twice, or the field is not an even
     * number of at least two clubs.
     */
    List<Long> resolveClubs() {
        List<String> names = teamNames == null ? List.of() : teamNames.stream()
                .map(String::trim).filter(name -> !name.isEmpty()).toList();
        if (names.size() < 2 || names.size() % 2 != 0 || new HashSet<>(names).size() != names.size()) {
            log.warn("Diamond League not created: diamond-league.teams must list an even number (at least two) "
                    + "of different clubs, got {}", names);
            return null;
        }
        Map<String, List<Long>> idsByName = new HashMap<>();
        for (Team team : teamRepository.findAll()) {
            idsByName.computeIfAbsent(team.getName(), ignored -> new ArrayList<>()).add(team.getId());
        }
        Map<String, Long> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            List<Long> ids = idsByName.get(name);
            if (ids == null || ids.isEmpty()) missing.add(name);
            else resolved.put(name, ids.get(0));
        }
        if (!missing.isEmpty()) {
            log.warn("Diamond League not created: no club named {} in this world (diamond-league.teams = {})",
                    missing, names);
            return null;
        }
        return new ArrayList<>(resolved.values());
    }

    /** Deterministic per world (club ids), competition and season, plus the configured salt. */
    private long drawSeed(int season, long competitionId, List<Long> clubs) {
        long value = mix(0x9E3779B97F4A7C15L ^ seed);
        value = mix(value ^ season);
        value = mix(value ^ competitionId);
        for (long club : clubs.stream().sorted().toList()) value = mix(value ^ club);
        return value;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
