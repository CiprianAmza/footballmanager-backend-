package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every player of every club recovers on every day his club did not play
 * ({@link FitnessEconomy#recover}); it replaces the "+0.5..1.5 per human training session" that left
 * AI squads without any recovery during a season.
 *
 * <p>Called once per calendar day, when the calendar moves past it ({@code GameAdvanceService}).
 * AI clubs (and free agents) follow the default weekly schedule and are updated in bulk: one
 * UPDATE per natural-fitness value (≈20 statements a day, no rows loaded). Human-managed clubs are
 * few and recover by their own schedule, row by row.
 */
@Service
public class FitnessRecoveryService {

    private static final Set<String> MATCH_TYPES = Set.of("MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN");

    @Autowired MatchEngineConfig engineConfig;
    @Autowired HumanRepository humanRepository;
    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired CalendarEventRepository calendarEventRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionTeamInfoMatchRepository fixtureRepository;
    @Autowired CompetitionFormatConfig competitionFormat;
    @Autowired UserContext userContext;
    @Autowired TrainingLoadService trainingLoadService;

    /** The day {@code day} of {@code season} is over: everyone whose club did not play it recovers. */
    @Transactional
    public void recoverDay(int season, int day) {
        Set<Long> played = clubsThatPlayed(season, day);
        Set<Long> humanTeams = new HashSet<>(userContext.getAllHumanTeamIds());
        MatchEngineConfig.Fitness cfg = engineConfig.getFitness();

        // Human-managed clubs: their own schedule decides rest or training and how hard.
        for (long teamId : humanTeams) {
            if (played.contains(teamId)) continue;
            recoverTeam(teamId, trainingLoadService.planFor(teamId, day), cfg);
        }

        // Everyone else in bulk, on the default schedule's day.
        Set<Long> excluded = new HashSet<>(played);
        excluded.addAll(humanTeams);
        excluded.add(-1L); // never an empty NOT IN list
        double gain = FitnessEconomy.dayGain(cfg, FitnessEconomy.defaultDayPlan(FitnessEconomy.weekday(day)));
        if (gain <= 0) return;
        for (Integer naturalFitness : playerSkillsRepository.findDistinctNaturalFitness()) {
            if (naturalFitness == null) continue;
            humanRepository.recoverFitnessByNaturalFitness(
                    gain * FitnessEconomy.naturalFitnessFactor(cfg, naturalFitness), excluded, naturalFitness);
        }
        humanRepository.recoverFitnessWithoutSkills(gain * FitnessEconomy.naturalFitnessFactor(cfg, 10), excluded);
    }

    private void recoverTeam(long teamId, FitnessEconomy.DayPlan plan, MatchEngineConfig.Fitness cfg) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired() && player.getFitness() < 100.0)
                .toList();
        if (players.isEmpty()) return;
        Map<Long, Integer> naturalFitness = new HashMap<>();
        for (PlayerSkills skills : playerSkillsRepository.findAllByPlayerIdIn(players.stream().map(Human::getId).toList())) {
            naturalFitness.put(skills.getPlayerId(), skills.getNaturalFitness());
        }
        List<Human> changed = new ArrayList<>();
        for (Human player : players) {
            player.setFitness(FitnessEconomy.recover(cfg, player.getFitness(), plan,
                    naturalFitness.getOrDefault(player.getId(), 10)));
            changed.add(player);
        }
        humanRepository.saveAll(changed);
    }

    /** Clubs with a competitive fixture on this day (league, cup, European), from that day's match events. */
    Set<Long> clubsThatPlayed(int season, int day) {
        Set<Long> teams = new HashSet<>();
        for (CalendarEvent event : calendarEventRepository.findAllBySeasonAndDay(season, day)) {
            if (!MATCH_TYPES.contains(event.getEventType())) continue;
            if (event.getCompetitionId() == null || event.getMatchday() <= 0) continue;
            int typeId = competitionRepository.findById(event.getCompetitionId())
                    .map(c -> (int) c.getTypeId()).orElse(1);
            int round = competitionFormat.get(typeId).roundForMatchday(event.getMatchday());
            for (CompetitionTeamInfoMatch fixture : fixtureRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(
                    event.getCompetitionId(), round, String.valueOf(season))) {
                if (event.getLegNumber() != 0 && fixture.getLegNumber() != event.getLegNumber()) continue;
                teams.add(fixture.getTeam1Id());
                teams.add(fixture.getTeam2Id());
            }
        }
        return teams;
    }
}
