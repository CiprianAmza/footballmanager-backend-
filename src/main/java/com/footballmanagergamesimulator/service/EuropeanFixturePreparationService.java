package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormat;
import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes one European draw once every participant in the phase is known.
 * Normal callers are the scheduled {@code EUROPEAN_DRAW} calendar events;
 * matchday simulation retains an idempotent call only as an old-save safety net.
 *
 * <p>The preparation is intentionally idempotent.</p>
 */
@Service
public class EuropeanFixturePreparationService {

    private static final Set<Long> EUROPEAN_TYPE_IDS = Set.of(4L, 5L);

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private CompetitionFormatConfig competitionFormatConfig;
    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private FixtureSchedulingService fixtureSchedulingService;

    /**
     * Prepare the earliest drawable phase for every European competition. Existing
     * group matchdays are skipped, allowing the scan to stop at the first phase
     * whose participants are not known yet.
     */
    @Transactional
    public void prepareNextFixturesForEachCompetition(int season) {
        Set<Long> europeanCompetitionIds = competitionRepository.findAll().stream()
                .filter(c -> EUROPEAN_TYPE_IDS.contains(c.getTypeId()))
                .map(Competition::getId)
                .collect(Collectors.toSet());
        List<CalendarEvent> events = calendarEventRepository
                .findAllBySeasonAndStatus(season, "PENDING").stream()
                .filter(e -> "MATCH_EUROPEAN".equals(e.getEventType())
                        && e.getCompetitionId() != null
                        && europeanCompetitionIds.contains(e.getCompetitionId()))
                .sorted(Comparator.comparingInt(CalendarEvent::getDay)
                        .thenComparingLong(e -> e.getCompetitionId() == null ? Long.MAX_VALUE : e.getCompetitionId())
                        .thenComparingInt(CalendarEvent::getMatchday)
                        .thenComparingInt(CalendarEvent::getLegNumber))
                .toList();

        Set<Long> competitionIds = events.stream()
                .map(CalendarEvent::getCompetitionId)
                .collect(Collectors.toSet());

        for (long competitionId : competitionIds) {
            List<CalendarEvent> competitionEvents = events.stream()
                    .filter(e -> e.getCompetitionId() != null && e.getCompetitionId() == competitionId)
                    .toList();

            int previousMatchday = Integer.MIN_VALUE;
            for (CalendarEvent event : competitionEvents) {
                // A two-leg round has two calendar events but only one fixture draw.
                if (event.getMatchday() == previousMatchday) continue;
                previousMatchday = event.getMatchday();

                Competition competition = competitionRepository.findById(competitionId).orElse(null);
                if (competition == null) break;
                CompetitionFormat format = competitionFormatConfig.get((int) competition.getTypeId());
                int round = format.roundForMatchday(event.getMatchday());

                if (hasFixtures(competitionId, round, season)) continue;

                // Do not jump over a phase that still waits for qualifiers. Later
                // phases depend on precisely those participants.
                prepareMatchday(competitionId, event.getMatchday(), season);
                break;
            }
        }
    }

    /**
     * Prepare one calendar matchday. Returns true when fixtures already existed or
     * were generated, false while the phase is still waiting for participants.
     */
    @Transactional
    public boolean prepareMatchday(long competitionId, int matchday, int season) {
        // Parallel matchday workers can finish related phases at nearly the same
        // time. Lock the competition row, then repeat the existence check below;
        // only one worker is allowed to persist its draw.
        Competition competition = competitionRepository
                .findByIdForFixturePreparation(competitionId).orElse(null);
        if (competition == null || !EUROPEAN_TYPE_IDS.contains(competition.getTypeId())) return false;

        int typeId = (int) competition.getTypeId();
        CompetitionFormat format = competitionFormatConfig.get(typeId);
        int round = format.roundForMatchday(matchday);

        if (hasFixtures(competitionId, round, season)) {
            fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday, season);
            return true;
        }

        if (format.isPreliminaryRound(round)) {
            int participants = participantCount(competitionId, round, season);
            var plan = format.europeanPlan();
            if (plan == null || participants < 2) return false;
            int expected = plan.stageForRound(round).bracketSize();
            if (participants < expected) return false;

            europeanCompetitionService.drawEuropeanPreliminarySeeded(
                    competitionId, round, plan.slots());
            fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday, season);
            return hasFixtures(competitionId, round, season);
        }

        if (format.isGroupRound(round)) {
            if (!format.isGroupDrawRound(round)) return false;

            int groupRound = format.groupStartRound();
            int expectedParticipants = format.groupCount() * format.groupSize();
            if (participantCount(competitionId, groupRound, season) < expectedParticipants) return false;

            // A group draw creates every group matchday in one operation.
            europeanCompetitionService.drawEuropeanGroups(competitionId, groupRound);
            europeanCompetitionService.resetEuropeanStats(competitionId);
            europeanCompetitionService.generateGroupStageFixtures(competitionId);
            for (int md = matchday; md < matchday + format.groupMatchdayCount(); md++) {
                fixtureSchedulingService.assignMatchDayForNewRound(competitionId, md, season);
            }
            return hasFixtures(competitionId, round, season);
        }

        int participants = participantCount(competitionId, round, season);
        if (!knockoutFieldIsReady(typeId, format, round, participants)) return false;

        fixtureSchedulingService.getFixturesForRound(
                String.valueOf(competitionId), String.valueOf(round), season);
        fixtureSchedulingService.assignMatchDayForNewRound(competitionId, matchday, season);
        return hasFixtures(competitionId, round, season);
    }

    private boolean knockoutFieldIsReady(int typeId, CompetitionFormat format,
                                         int round, int participants) {
        if (participants < 2) return false;

        // Stars Cup playoff must wait for BOTH pots: its own group runners-up and
        // the League of Champions third-place teams. Drawing after only one pot is
        // available produces valid-looking but incorrect pairings.
        if (typeId == 5 && round == format.playoffRound()) {
            int expected = format.groupCount() * format.playoffQualifyPerGroup();
            CompetitionFormat loc = competitionFormatConfig.get(4);
            if (loc.thirdPlaceDropTypeId() == typeId && loc.thirdPlaceDropRound() == round) {
                expected += loc.groupCount();
            }
            return participants >= expected;
        }

        return true;
    }

    private int participantCount(long competitionId, int round, int season) {
        return (int) competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(round, competitionId, season).stream()
                .map(cti -> cti.getTeamId())
                .distinct()
                .count();
    }

    private boolean hasFixtures(long competitionId, int round, int season) {
        return !competitionTeamInfoMatchRepository
                .findAllByCompetitionIdAndRoundAndSeasonNumber(
                        competitionId, round, String.valueOf(season))
                .isEmpty();
    }

}
