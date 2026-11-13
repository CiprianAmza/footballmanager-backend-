package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.CalendarEntryView;
import com.footballmanagergamesimulator.frontend.ScheduleView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MatchService {

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    CalendarService calendarService;
    @Autowired
    FriendlyMatchRepository friendlyMatchRepository;
    @Autowired
    FriendlyEventRepository friendlyEventRepository;
    @Autowired
    CompetitionProgressService competitionProgressService;

    /**
     * Club schedule for one season: scheduled fixtures, played results and friendlies.
     *
     * <p>Results ({@link CompetitionTeamInfoDetail}) are the durable source. Fixture rows
     * are wiped at every new season, so a past season is rebuilt from its results alone;
     * the current season merges both so upcoming matches still appear.
     */
    public List<ScheduleView> getScheduleViewsFromCompetitionTeamInfoMatchesAndTeamId(List<CompetitionTeamInfoMatch> competitionTeamInfoMatches, long teamId, long seasonNumber) {

        List<ScheduleView> scheduleViews = new ArrayList<>();
        List<CompetitionTeamInfoDetail> seasonResults =
                competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(seasonNumber, teamId);
        ScheduleLookups lookups = new ScheduleLookups(seasonNumber, seasonResults);

        Map<String, CompetitionTeamInfoDetail> resultsByFixture = new LinkedHashMap<>();
        for (CompetitionTeamInfoDetail result : seasonResults) {
            resultsByFixture.putIfAbsent(fixtureKey(result.getCompetitionId(), result.getRoundId(),
                    result.getTeam1Id(), result.getTeam2Id()), result);
        }

        Set<String> matchedResults = new HashSet<>();
        for (CompetitionTeamInfoMatch fixture : competitionTeamInfoMatches) {
            String key = fixtureKey(fixture.getCompetitionId(), fixture.getRound(),
                    fixture.getTeam1Id(), fixture.getTeam2Id());
            CompetitionTeamInfoDetail result = resultsByFixture.get(key);
            if (result != null) matchedResults.add(key);
            int day = fixture.getDay() > 0 || result == null ? fixture.getDay() : result.getDay();
            int legNumber = fixture.getLegNumber() > 0 || result == null ? fixture.getLegNumber() : result.getLegNumber();
            long tieId = fixture.getTieId() != 0 || result == null ? fixture.getTieId() : result.getTieId();
            scheduleViews.add(competitiveScheduleView(teamId, seasonNumber, fixture.getCompetitionId(),
                    fixture.getRound(), fixture.getTeam1Id(), fixture.getTeam2Id(), day, legNumber, tieId,
                    result, lookups));
        }

        // Results whose fixture row is gone: every past season, and re-drawn cup slots.
        resultsByFixture.forEach((key, result) -> {
            if (matchedResults.contains(key)) return;
            scheduleViews.add(competitiveScheduleView(teamId, seasonNumber, result.getCompetitionId(),
                    result.getRoundId(), result.getTeam1Id(), result.getTeam2Id(), result.getDay(),
                    result.getLegNumber(), result.getTieId(), result, lookups));
        });

        appendFriendlySchedule(scheduleViews, teamId, (int) seasonNumber, lookups);

        // Chronological across competitions; old saves without calendar days fall back to round order.
        scheduleViews.sort(Comparator.comparingInt(ScheduleView::getDay)
                .thenComparingInt(ScheduleView::getRoundNumber)
                .thenComparingLong(ScheduleView::getCompetitionId)
                .thenComparingInt(ScheduleView::getLegNumber));

        return scheduleViews;
    }

    private ScheduleView competitiveScheduleView(long teamId, long seasonNumber, long competitionId, long round,
                                                 long team1Id, long team2Id, int day, int legNumber, long tieId,
                                                 CompetitionTeamInfoDetail result, ScheduleLookups lookups) {
        ScheduleView view = new ScheduleView();
        boolean isHome = team1Id == teamId;
        long opponentTeamId = isHome ? team2Id : team1Id;
        String opponentTeamName = lookups.teamName(opponentTeamId,
                result == null ? null : isHome ? result.getTeamName2() : result.getTeamName1());
        String ownTeamName = lookups.teamName(teamId,
                result == null ? null : isHome ? result.getTeamName1() : result.getTeamName2());

        view.setOpponentTeam(opponentTeamName);
        view.setOpponentTeamId(opponentTeamId);
        view.setHomeOrAway(isHome ? "H" : "A");
        view.setHomeTeamAbbr(abbreviateTeamName(isHome ? ownTeamName : opponentTeamName));
        view.setAwayTeamAbbr(abbreviateTeamName(isHome ? opponentTeamName : ownTeamName));
        view.setCompetitionId(competitionId);
        view.setCompetitionName(lookups.competitionName(competitionId));
        view.setCompetitionType(lookups.competitionType(competitionId));
        if (view.getCompetitionType() == null) view.getUnknownFields().add("competitionType");
        view.setRoundLabel(lookups.roundLabel(competitionId, round));
        view.setSeasonNumber((int) seasonNumber);
        view.setRoundNumber((int) round);
        view.setTeamId1(team1Id);
        view.setTeamId2(team2Id);
        view.setVenueName(lookups.stadiumName(team1Id));
        if (view.getVenueName() == null) view.getUnknownFields().add("venueName");
        applyDay(view, day, "Matchday " + round);

        if (result == null) {
            view.setScore("-");
            view.setStatus("upcoming");
            view.setLegNumber(legNumber);
            view.setTieId(tieId);
            if (legNumber > 0 && tieId == 0) view.getUnknownFields().add("tieId");
            return view;
        }

        view.setStatus("played");
        view.setWinnerTeamId(result.getWinnerTeamId());
        view.setDecidedBy(result.getDecidedBy());
        view.setReportAvailable(result.getScore() != null);

        ResultReading reading = readResult(result, legNumber, tieId, lookups);
        view.setLegNumber(reading.legNumber());
        view.setTieId(tieId);
        if (reading.legNumber() > 0 && tieId == 0) view.getUnknownFields().add("tieId");

        if (reading.scoreReadable()) {
            view.setTeamGoals(isHome ? reading.team1Goals() : reading.team2Goals());
            view.setOpponentGoals(isHome ? reading.team2Goals() : reading.team1Goals());
        } else {
            view.getUnknownFields().add("teamGoals");
            view.getUnknownFields().add("opponentGoals");
        }

        if (reading.aggregateKnown()) {
            view.setAggregateTeam1Score(reading.aggregate1());
            view.setAggregateTeam2Score(reading.aggregate2());
        } else if (reading.legNumber() == 2) {
            view.getUnknownFields().add("aggregateTeam1Score");
            view.getUnknownFields().add("aggregateTeam2Score");
        }

        if (reading.penaltiesKnown()) {
            view.setPenaltyTeam1Score(reading.penalties1());
            view.setPenaltyTeam2Score(reading.penalties2());
        } else if (reading.shootout()) {
            // Older saves stored only " (pens)" with no shootout score.
            view.getUnknownFields().add("penaltyTeam1Score");
            view.getUnknownFields().add("penaltyTeam2Score");
        }

        if (!reading.scoreReadable()) {
            view.setScore(result.getScore() == null ? "-" : result.getScore());
            if (result.getScore() == null) view.getUnknownFields().add("score");
            view.getUnknownFields().add("resultOutcome");
            return view;
        }
        view.setScore(scoreText(reading, isHome));

        int own = view.getTeamGoals();
        int opponent = view.getOpponentGoals();
        if (own != opponent) view.setResultOutcome(own > opponent ? "W" : "L");
        else if ((reading.shootout() || reading.extraTime()) && result.getWinnerTeamId() != null)
            // Settled in this match after a level score (old saves also keep extra-time goals out of the score).
            view.setResultOutcome(result.getWinnerTeamId() == teamId ? "W" : "L");
        else view.setResultOutcome("D");
        return view;
    }

    /**
     * Result score from the home side (team1 - team2) for the match summary and report,
     * with the leg/aggregate/shootout note rebuilt the same way as Schedule rows. Falls
     * back to the stored text when it cannot be read.
     */
    public String homeScoreText(CompetitionTeamInfoDetail result) {
        if (result == null || result.getScore() == null) return null;
        String score = result.getScore();
        // The first leg is only needed to sum an aggregate the row does not store.
        boolean mayNeedFirstLeg = result.getLegNumber() == 2 || "AGGREGATE".equals(result.getDecidedBy())
                || AGGREGATE_SUFFIX.matcher(score).find();
        List<CompetitionTeamInfoDetail> seasonResults = mayNeedFirstLeg
                ? competitionTeamInfoDetailRepository.findAllBySeasonNumberAndTeamId(
                        result.getSeasonNumber(), result.getTeam1Id())
                : List.of();
        ScheduleLookups lookups = new ScheduleLookups(result.getSeasonNumber(), seasonResults);
        String text = scoreText(readResult(result, result.getLegNumber(), result.getTieId(), lookups), true);
        return text != null ? text : score;
    }

    /** One stored result, read the same way for Schedule rows and the match summary. */
    private record ResultReading(boolean scoreReadable, Integer team1Goals, Integer team2Goals, int legNumber,
                                 Integer aggregate1, Integer aggregate2, Integer penalties1, Integer penalties2,
                                 boolean shootout, boolean extraTime, String storedSuffix) {
        boolean aggregateKnown() {
            return aggregate1 != null && aggregate2 != null;
        }

        boolean penaltiesKnown() {
            return penalties1 != null && penalties2 != null;
        }
    }

    private ResultReading readResult(CompetitionTeamInfoDetail result, int legNumber, long tieId,
                                     ScheduleLookups lookups) {
        Matcher score = result.getScore() == null ? null : SCORE_PATTERN.matcher(result.getScore());
        boolean scoreReadable = score != null && score.matches();
        String storedSuffix = scoreReadable ? score.group(3) : "";
        String decidedBy = result.getDecidedBy();
        boolean shootout = "PENALTIES".equals(decidedBy);
        boolean extraTime = "EXTRA_TIME".equals(decidedBy);

        // Saves written before leg numbers existed still carry the leg in decidedBy/score text.
        if (legNumber == 0) {
            if ("FIRST_LEG".equals(decidedBy) || FIRST_LEG_SUFFIX.matcher(storedSuffix).find()) legNumber = 1;
            else if ("AGGREGATE".equals(decidedBy) || AGGREGATE_SUFFIX.matcher(storedSuffix).find()) legNumber = 2;
        }

        Integer team1Goals = scoreReadable ? Integer.valueOf(score.group(1)) : null;
        Integer team2Goals = scoreReadable ? Integer.valueOf(score.group(2)) : null;

        // Aggregate: structured columns, else the sum of both legs. The "(agg N-N)" text is not
        // used: older saves wrote it leg-1-home first on AGGREGATE rows, newer ones team1 first.
        Integer aggregate1 = result.getAggregateTeam1Score();
        Integer aggregate2 = result.getAggregateTeam2Score();
        if ((aggregate1 == null || aggregate2 == null) && legNumber == 2 && scoreReadable) {
            aggregate1 = null;
            aggregate2 = null;
            int[] firstLeg = lookups.firstLegGoals(result.getCompetitionId(), result.getTeam1Id(),
                    result.getTeam2Id(), tieId);
            if (firstLeg != null) {
                // Leg 1 was hosted by this row's team2.
                aggregate1 = team1Goals + firstLeg[1];
                aggregate2 = team2Goals + firstLeg[0];
                // Old saves kept extra-time goals out of the stored score; a level sum would contradict the decision.
                if (extraTime && aggregate1.equals(aggregate2)) aggregate1 = aggregate2 = null;
            }
        }

        // Shootout: structured columns, else "(pens N-N)" text, which is team1 first on single matches.
        Integer penalties1 = result.getPenaltyTeam1Score();
        Integer penalties2 = result.getPenaltyTeam2Score();
        Matcher penaltyText = PENALTY_SUFFIX.matcher(storedSuffix);
        if (shootout && (penalties1 == null || penalties2 == null) && legNumber != 2 && penaltyText.find()) {
            penalties1 = Integer.valueOf(penaltyText.group(1));
            penalties2 = Integer.valueOf(penaltyText.group(2));
        }
        return new ResultReading(scoreReadable, team1Goals, team2Goals, legNumber, aggregate1, aggregate2,
                penalties1, penalties2, shootout, extraTime, storedSuffix);
    }

    /** Score in the simulator's own text shapes, from team1's side or team2's; null when unreadable. */
    private static String scoreText(ResultReading reading, boolean team1Side) {
        if (!reading.scoreReadable()) return null;
        String suffix = reading.storedSuffix();
        if (reading.legNumber() == 1) {
            suffix = " (1st leg)";
        } else if (reading.shootout() || reading.extraTime() || reading.aggregateKnown()) {
            String decision = reading.shootout()
                    ? (reading.penaltiesKnown()
                        ? "pens " + pair(team1Side, reading.penalties1(), reading.penalties2()) : "pens")
                    : reading.extraTime() ? "a.e.t." : null;
            if (reading.aggregateKnown()) {
                suffix = " (agg " + pair(team1Side, reading.aggregate1(), reading.aggregate2())
                        + (decision == null ? "" : ", " + decision) + ")";
            } else {
                suffix = decision == null ? "" : " (" + decision + ")";
            }
        }
        int first = team1Side ? reading.team1Goals() : reading.team2Goals();
        int second = team1Side ? reading.team2Goals() : reading.team1Goals();
        return first + " - " + second + suffix;
    }

    private static String pair(boolean team1Side, int team1Value, int team2Value) {
        return team1Side ? team1Value + "-" + team2Value : team2Value + "-" + team1Value;
    }

    private void applyDay(ScheduleView view, int day, String fallbackDate) {
        view.setDay(day);
        if (day > 0) {
            view.setDate(calendarService.getDateDisplay(day));
        } else {
            // Saves from before calendar days were stored: no real date exists.
            view.setDate(fallbackDate);
            view.getUnknownFields().add("day");
            view.getUnknownFields().add("date");
        }
    }

    private static String fixtureKey(long competitionId, long round, long team1Id, long team2Id) {
        return competitionId + ":" + round + ":" + team1Id + ":" + team2Id;
    }

    /** Competition and team lookups shared by every row of one schedule request. */
    private final class ScheduleLookups {
        private final long seasonNumber;
        private final Map<Long, String> teamNames = new HashMap<>();
        private final Map<Long, Optional<String>> stadiumNames = new HashMap<>();
        private final Map<Long, String> competitionNames = new HashMap<>();
        private final Map<Long, Optional<String>> competitionTypes = new HashMap<>();
        private final Map<String, String> roundLabels = new HashMap<>();
        private final List<CompetitionTeamInfoDetail> seasonResults;

        private ScheduleLookups(long seasonNumber, List<CompetitionTeamInfoDetail> seasonResults) {
            this.seasonNumber = seasonNumber;
            this.seasonResults = seasonResults;
        }

        /**
         * Home/away goals of the first leg paired with a second leg (team1 vs team2): the
         * same tie with the venues swapped. Null when the first leg is not on record.
         */
        int[] firstLegGoals(long competitionId, long leg2Team1Id, long leg2Team2Id, long tieId) {
            for (CompetitionTeamInfoDetail candidate : seasonResults) {
                if (candidate.getCompetitionId() != competitionId
                        || candidate.getTeam1Id() != leg2Team2Id || candidate.getTeam2Id() != leg2Team1Id) continue;
                boolean firstLeg = candidate.getLegNumber() == 1 || "FIRST_LEG".equals(candidate.getDecidedBy());
                if (!firstLeg || (tieId != 0 && candidate.getTieId() != 0 && candidate.getTieId() != tieId)) continue;
                Matcher goals = candidate.getScore() == null ? null : SCORE_PATTERN.matcher(candidate.getScore());
                if (goals == null || !goals.matches()) return null;
                return new int[]{Integer.parseInt(goals.group(1)), Integer.parseInt(goals.group(2))};
            }
            return null;
        }

        String teamName(long teamId, String storedName) {
            String name = teamNames.computeIfAbsent(teamId, teamRepository::findNameById);
            return name != null ? name : storedName;
        }

        String stadiumName(long teamId) {
            return stadiumNames.computeIfAbsent(teamId,
                    id -> Optional.ofNullable(teamRepository.findStadiumNameById(id))).orElse(null);
        }

        String competitionName(long competitionId) {
            return competitionNames.computeIfAbsent(competitionId, competitionRepository::findNameById);
        }

        String competitionType(long competitionId) {
            return competitionTypes.computeIfAbsent(competitionId,
                    id -> Optional.ofNullable(scheduleCompetitionType(competitionRepository.findTypeIdById(id))))
                    .orElse(null);
        }

        String roundLabel(long competitionId, long round) {
            return roundLabels.computeIfAbsent(competitionId + ":" + round,
                    key -> competitionProgressService.roundLabel(competitionId, round, seasonNumber));
        }
    }

    static String scheduleCompetitionType(Long typeId) {
        if (typeId == null) return null;
        int type = typeId.intValue();
        if (type == Competition.LEAGUE || type == LEGACY_SECOND_LEAGUE_TYPE) return "League";
        if (type == Competition.CUP || type == Competition.SUPER_CUP) return "Cup";
        if (type == Competition.LEAGUE_OF_CHAMPIONS || type == Competition.STARS_CUP) return "Continental";
        return null;
    }

    public List<CalendarEntryView> getCalendarEntries(List<CompetitionTeamInfoMatch> matches, long teamId, long seasonNumber) {

        List<CalendarEntryView> entries = new ArrayList<>();

        for (CompetitionTeamInfoMatch match : matches) {

            CalendarEntryView entry = new CalendarEntryView();

            entry.setRoundNumber((int) match.getRound());
            entry.setCompetitionId(match.getCompetitionId());
            entry.setSeasonNumber((int) seasonNumber);
            entry.setTeamId1(match.getTeam1Id());
            entry.setTeamId2(match.getTeam2Id());

            // Competition name and type
            String competitionName = competitionRepository.findNameById(match.getCompetitionId());
            entry.setCompetitionName(competitionName);

            Long typeId = competitionRepository.findTypeIdById(match.getCompetitionId());
            entry.setCompetitionType(mapCompetitionType(typeId != null ? typeId : 0L));

            // Opponent
            long opponentTeamId = match.getTeam1Id() == teamId ? match.getTeam2Id() : match.getTeam1Id();
            String opponentTeamName = teamRepository.findNameById(opponentTeamId);
            entry.setOpponentTeamName(opponentTeamName);
            entry.setOpponentTeamId(opponentTeamId);

            // Home/Away
            boolean isHomeCalendar = match.getTeam1Id() == teamId;
            entry.setHomeOrAway(isHomeCalendar ? "H" : "A");

            // Team abbreviations
            String ownTeamNameCal = teamRepository.findNameById(teamId);
            if (isHomeCalendar) {
                entry.setHomeTeamAbbr(abbreviateTeamName(ownTeamNameCal));
                entry.setAwayTeamAbbr(abbreviateTeamName(opponentTeamName));
            } else {
                entry.setHomeTeamAbbr(abbreviateTeamName(opponentTeamName));
                entry.setAwayTeamAbbr(abbreviateTeamName(ownTeamNameCal));
            }

            // Calendar date display
            int matchDayCal = match.getDay();
            entry.setDay(matchDayCal);
            if (matchDayCal > 0) {
                entry.setDateDisplay(calendarService.getDateDisplay(matchDayCal));
            }

            // Score and result
            CompetitionTeamInfoDetail detail = competitionTeamInfoDetailRepository
                    .findAllByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(
                            match.getCompetitionId(), match.getRound(),
                            match.getTeam1Id(), match.getTeam2Id(), seasonNumber).stream().findFirst().orElse(null);

            if (detail != null && detail.getScore() != null && !detail.getScore().equals("-")) {
                String score = detail.getScore();
                String adjustedScore = score;

                // Adjust score so it's always from our team's perspective
                if (entry.getHomeOrAway().equals("A")) {
                    adjustedScore = reverseScore(score);
                }

                entry.setScore(adjustedScore);
                entry.setStatus("played");

                // Determine W/D/L
                Matcher scoreMatcher = SCORE_PATTERN.matcher(adjustedScore);
                int teamGoals = scoreMatcher.matches() ? Integer.parseInt(scoreMatcher.group(1)) : 0;
                int oppGoals = scoreMatcher.matches() ? Integer.parseInt(scoreMatcher.group(2)) : 0;
                if (detail.getWinnerTeamId() != null) {
                    entry.setResultOutcome(detail.getWinnerTeamId() == teamId ? "W" : "L");
                } else if (teamGoals > oppGoals) entry.setResultOutcome("W");
                else if (teamGoals < oppGoals) entry.setResultOutcome("L");
                else entry.setResultOutcome("D");
            } else {
                entry.setScore("-");
                entry.setStatus("upcoming");
                entry.setResultOutcome(null);
            }

            entries.add(entry);
        }

        appendFriendlyCalendar(entries, teamId, (int) seasonNumber);

        // Sort by calendar day (chronological order across all competitions)
        entries.sort(Comparator.comparingInt(CalendarEntryView::getDay));

        return entries;
    }

    private void appendFriendlySchedule(List<ScheduleView> schedule, long teamId, int season, ScheduleLookups lookups) {
        List<FriendlyMatch> friendlies = teamFriendlies(teamId, season);
        Map<Long, FriendlyEvent> events = friendlyEvents(friendlies);
        for (FriendlyMatch match : friendlies) {
            boolean home = match.getHomeTeamId() == teamId;
            boolean completed = "COMPLETED".equals(match.getStatus());
            String opponentName = home ? match.getAwayTeamName() : match.getHomeTeamName();
            ScheduleView view = new ScheduleView();
            view.setOpponentTeam(opponentName);
            view.setOpponentTeamId(home ? match.getAwayTeamId() : match.getHomeTeamId());
            view.setHomeOrAway(home ? "H" : "A");
            view.setHomeTeamAbbr(abbreviateTeamName(match.getHomeTeamName()));
            view.setAwayTeamAbbr(abbreviateTeamName(match.getAwayTeamName()));
            view.setCompetitionName(friendlyCompetitionName(match, events));
            view.setCompetitionType("Friendly");
            view.setRoundLabel(friendlyRoundLabel(match, events));
            view.setScore(friendlyScore(match, home));
            view.setCompetitionId(friendlyCompetitionId(match));
            view.setSeasonNumber(season);
            view.setRoundNumber(safeMatchId(match.getId()));
            view.setTeamId1(match.getHomeTeamId());
            view.setTeamId2(match.getAwayTeamId());
            applyDay(view, match.getDay(), calendarService.getDateDisplay(match.getDay()));
            view.setVenueName(match.getVenueName() != null ? match.getVenueName()
                    : lookups.stadiumName(match.getHomeTeamId()));
            if (view.getVenueName() == null) view.getUnknownFields().add("venueName");
            if (completed && match.getHomeGoals() != match.getAwayGoals()) {
                view.setWinnerTeamId(match.getHomeGoals() > match.getAwayGoals()
                        ? match.getHomeTeamId() : match.getAwayTeamId());
            }
            view.setDecidedBy(completed ? "FRIENDLY" : null);
            view.setStatus(completed ? "played" : "upcoming");
            if (completed) {
                int own = home ? match.getHomeGoals() : match.getAwayGoals();
                int opponent = home ? match.getAwayGoals() : match.getHomeGoals();
                view.setTeamGoals(own);
                view.setOpponentGoals(opponent);
                view.setResultOutcome(own > opponent ? "W" : own < opponent ? "L" : "D");
            }
            // Friendly stats are keyed (0, season, day, home, away) and have no result row,
            // so /match/report cannot be built from this row's identifiers.
            view.setReportAvailable(false);
            schedule.add(view);
        }
    }

    private String friendlyRoundLabel(FriendlyMatch match, Map<Long, FriendlyEvent> events) {
        boolean eventMatch = match.getFriendlyEventId() != null && events.containsKey(match.getFriendlyEventId());
        if (!eventMatch || match.getEventStage() == null || "FRIENDLY".equals(match.getEventStage())) return "Friendly";
        StringBuilder label = new StringBuilder();
        for (String word : match.getEventStage().toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private void appendFriendlyCalendar(List<CalendarEntryView> entries, long teamId, int season) {
        List<FriendlyMatch> friendlies = teamFriendlies(teamId, season);
        Map<Long, FriendlyEvent> events = friendlyEvents(friendlies);
        for (FriendlyMatch match : friendlies) {
            boolean home = match.getHomeTeamId() == teamId;
            String opponentName = home ? match.getAwayTeamName() : match.getHomeTeamName();
            CalendarEntryView entry = new CalendarEntryView();
            entry.setRoundNumber(safeMatchId(match.getId()));
            entry.setCompetitionName(friendlyCompetitionName(match, events));
            entry.setCompetitionId(friendlyCompetitionId(match));
            entry.setCompetitionType("Friendly");
            entry.setOpponentTeamName(opponentName);
            entry.setOpponentTeamId(home ? match.getAwayTeamId() : match.getHomeTeamId());
            entry.setHomeOrAway(home ? "H" : "A");
            entry.setHomeTeamAbbr(abbreviateTeamName(match.getHomeTeamName()));
            entry.setAwayTeamAbbr(abbreviateTeamName(match.getAwayTeamName()));
            entry.setDateDisplay(calendarService.getDateDisplay(match.getDay()));
            entry.setTeamId1(match.getHomeTeamId());
            entry.setTeamId2(match.getAwayTeamId());
            entry.setSeasonNumber(season);
            entry.setDay(match.getDay());
            entry.setScore(friendlyScore(match, home));
            if ("COMPLETED".equals(match.getStatus())) {
                entry.setStatus("played");
                int ownGoals = home ? match.getHomeGoals() : match.getAwayGoals();
                int opponentGoals = home ? match.getAwayGoals() : match.getHomeGoals();
                entry.setResultOutcome(ownGoals > opponentGoals ? "W" : ownGoals < opponentGoals ? "L" : "D");
            } else {
                entry.setStatus("upcoming");
                entry.setResultOutcome(null);
            }
            entries.add(entry);
        }
    }

    private List<FriendlyMatch> teamFriendlies(long teamId, int season) {
        return friendlyMatchRepository
                .findAllBySeasonAndHomeTeamIdOrSeasonAndAwayTeamId(season, teamId, season, teamId).stream()
                .filter(match -> !"CANCELLED".equals(match.getStatus()))
                .toList();
    }

    private Map<Long, FriendlyEvent> friendlyEvents(List<FriendlyMatch> matches) {
        List<Long> ids = matches.stream().map(FriendlyMatch::getFriendlyEventId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, FriendlyEvent> events = new HashMap<>();
        friendlyEventRepository.findAllById(ids).forEach(event -> events.put(event.getId(), event));
        return events;
    }

    private String friendlyCompetitionName(FriendlyMatch match, Map<Long, FriendlyEvent> events) {
        FriendlyEvent event = match.getFriendlyEventId() == null ? null : events.get(match.getFriendlyEventId());
        if (event == null) return "Friendly";
        String stage = match.getEventStage() == null ? "" : " · " + match.getEventStage().replace('_', ' ');
        return event.getName() + stage;
    }

    private long friendlyCompetitionId(FriendlyMatch match) {
        return match.getFriendlyEventId() == null ? -1L : -Math.max(1L, match.getFriendlyEventId());
    }

    private int safeMatchId(long matchId) {
        return matchId > Integer.MAX_VALUE ? (int) (matchId % Integer.MAX_VALUE) : (int) matchId;
    }

    private String friendlyScore(FriendlyMatch match, boolean home) {
        if (!"COMPLETED".equals(match.getStatus())) return "-";
        return home ? match.getHomeGoals() + " - " + match.getAwayGoals()
                : match.getAwayGoals() + " - " + match.getHomeGoals();
    }

    private String abbreviateTeamName(String name) {
        if (name == null || name.isEmpty()) return "???";
        // Take first 3 characters, uppercase
        return name.substring(0, Math.min(3, name.length())).toUpperCase();
    }

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("^\\s*(\\d+)\\s*-\\s*(\\d+)(.*)$");

    private static final Pattern AGGREGATE_SUFFIX = Pattern.compile("agg\\s*\\d+\\s*-\\s*\\d+");
    private static final Pattern PENALTY_SUFFIX = Pattern.compile("pens\\s*(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern FIRST_LEG_SUFFIX = Pattern.compile("1st leg");
    /** Second divisions were their own type before Competition.tier existed. */
    private static final int LEGACY_SECOND_LEAGUE_TYPE = 3;

    private String reverseScore(String score) {
        Matcher matcher = SCORE_PATTERN.matcher(score);
        if (!matcher.matches()) return score;
        return matcher.group(2) + " - " + matcher.group(1) + matcher.group(3);
    }

    private String mapCompetitionType(long typeId) {
        // typeId 1 = League, 2 = Cup, 3 = Second League (treat as League), 4+ = European
        if (typeId == 1 || typeId == 3) return "League";
        if (typeId == 2) return "Cup";
        return "European";
    }
}
