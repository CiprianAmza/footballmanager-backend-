package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CalendarEvent;
import com.footballmanagergamesimulator.model.FriendlyEvent;
import com.footballmanagergamesimulator.model.FriendlyMatch;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CalendarEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyEventRepository;
import com.footballmanagergamesimulator.repository.FriendlyMatchRepository;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Domain service for camps, tours and unofficial club-hosted tournaments. */
@Service
public class FriendlyEventService {

    private static final Set<String> TYPES = Set.of("TRAINING_CAMP", "MINI_LEAGUE", "MINI_CUP");
    private static final Set<String> FOCUSES = Set.of("FITNESS", "TACTICAL", "TEAM_BONDING", "COMMERCIAL");

    private final FriendlyEventRepository eventRepository;
    private final FriendlyMatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final GameCalendarRepository gameCalendarRepository;
    private final CalendarService calendarService;
    private final NationService nationService;
    private final FinanceService financeService;

    public FriendlyEventService(FriendlyEventRepository eventRepository,
                                FriendlyMatchRepository matchRepository,
                                TeamRepository teamRepository,
                                CalendarEventRepository calendarEventRepository,
                                GameCalendarRepository gameCalendarRepository,
                                CalendarService calendarService,
                                NationService nationService,
                                FinanceService financeService) {
        this.eventRepository = eventRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.gameCalendarRepository = gameCalendarRepository;
        this.calendarService = calendarService;
        this.nationService = nationService;
        this.financeService = financeService;
    }

    public List<Map<String, Object>> getEvents(long teamId, int season) {
        return eventRepository.findAllBySeasonOrderByStartDayAsc(season).stream()
                .filter(event -> event.getOrganizerTeamId() == teamId || participantIds(event).contains(teamId))
                .map(this::view)
                .toList();
    }

    public List<Map<String, Object>> getWorldEvents(int season) {
        return eventRepository.findAllBySeasonOrderByStartDayAsc(season).stream().map(this::view).toList();
    }

    public Map<String, Object> plannerOptions(long teamId) {
        Team team = requireTeam(teamId);
        long homeNationId = nationService.nationIdForTeam(teamId);
        List<Map<String, Object>> destinations = new ArrayList<>();
        for (long id = 1; id <= 7; id++) {
            NationService.NationInfo nation = nationService.infoFor(id);
            long travelCost = id == homeNationId ? 350_000L : 850_000L + Math.abs(id - homeNationId) * 175_000L;
            destinations.add(Map.of(
                    "nationId", id,
                    "name", nation.name(),
                    "flagCode", nation.flagCode(),
                    "domestic", id == homeNationId,
                    "estimatedBaseCost", travelCost));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teamId", teamId);
        result.put("teamName", team.getName());
        result.put("availableBalance", team.getTotalFinances());
        GameCalendar calendar = currentGameDate();
        result.put("currentSeason", calendar.getSeason());
        result.put("currentDay", calendar.getCurrentDay());
        result.put("currentDate", calendarService.getDateDisplay(calendar.getCurrentDay()));
        result.put("minimumStartDay", calendar.getCurrentDay() + 1);
        result.put("availableSeasons", List.of(calendar.getSeason(), calendar.getSeason() + 1));
        List<Map<String, Object>> dateOptions = new ArrayList<>();
        for (int day = 1; day <= 30; day++) {
            if (day > calendar.getCurrentDay()) dateOptions.add(dateOption(calendar.getSeason(), day, "PRE_SEASON"));
            dateOptions.add(dateOption(calendar.getSeason() + 1, day, "PRE_SEASON"));
        }
        for (int day = 201; day <= 210; day++) {
            if (day > calendar.getCurrentDay()) dateOptions.add(dateOption(calendar.getSeason(), day, "WINTER_BREAK"));
            dateOptions.add(dateOption(calendar.getSeason() + 1, day, "WINTER_BREAK"));
        }
        result.put("dateOptions", dateOptions);
        result.put("destinations", destinations);
        result.put("eventTypes", List.of(
                option("TRAINING_CAMP", "Training camp", "3-14 days · fitness, tactics, cohesion or commercial focus"),
                option("MINI_LEAGUE", "Mini league", "3-6 teams · single round-robin table"),
                option("MINI_CUP", "Mini cup", "4 teams · semi-finals and final")));
        result.put("rulesets", List.of(
                option("STANDARD", "Standard", "Normal senior match rules"),
                option("EXTENDED_BENCH", "Extended bench", "More rotation and lower workload"),
                option("DEVELOPMENT", "Development", "Prioritises prospects and experimentation")));
        return result;
    }

    @Transactional
    public Map<String, Object> createDraft(Map<String, Object> request) {
        long organizerTeamId = number(request, "organizerTeamId").longValue();
        Team organizer = requireTeam(organizerTeamId);
        String eventType = text(request, "eventType", "TRAINING_CAMP").toUpperCase();
        if (!TYPES.contains(eventType)) throw new IllegalArgumentException("Unsupported friendly event type");

        int season = number(request, "season").intValue();
        int startDay = number(request, "startDay").intValue();
        int endDay = number(request, "endDay").intValue();
        validateWindow(startDay, endDay);
        validateFutureDate(season, startDay);

        List<Long> participants = parseParticipantRequest(request.get("participantTeamIds"));
        participants.add(0, organizerTeamId);
        participants = new ArrayList<>(new LinkedHashSet<>(participants));

        if ("MINI_CUP".equals(eventType) && participants.size() != 4) {
            throw new IllegalArgumentException("A mini cup requires exactly 4 teams including the host");
        }
        if ("MINI_LEAGUE".equals(eventType) && (participants.size() < 3 || participants.size() > 6)) {
            throw new IllegalArgumentException("A mini league requires between 3 and 6 teams including the host");
        }
        if (!"TRAINING_CAMP".equals(eventType)) {
            participants.forEach(this::requireTeam);
        } else {
            participants = List.of(organizerTeamId);
        }

        FriendlyEvent event = new FriendlyEvent();
        event.setSeason(season);
        event.setOrganizerTeamId(organizerTeamId);
        event.setName(text(request, "name", organizer.getName() + " Pre-season Event").trim());
        event.setEventType(eventType);
        event.setStatus("DRAFT");
        event.setHostNationId(number(request, "hostNationId", nationService.nationIdForTeam(organizerTeamId)).longValue());
        event.setLocationName(text(request, "locationName", nationService.infoFor(event.getHostNationId()).name()));
        event.setStartDay(startDay);
        event.setEndDay(endDay);
        String focus = text(request, "focus", "FITNESS").toUpperCase();
        event.setFocus(FOCUSES.contains(focus) ? focus : "FITNESS");
        event.setFormat("MINI_CUP".equals(eventType) ? "KNOCKOUT" : "MINI_LEAGUE".equals(eventType) ? "ROUND_ROBIN" : null);
        event.setParticipantTeamIds(joinIds(participants));
        event.setMaxTeams(participants.size());
        event.setParticipationFee(Math.max(0, number(request, "participationFee", 0).longValue()));
        event.setPrizePool(Math.max(0, number(request, "prizePool", 0).longValue()));
        event.setOrganizerCost(Math.max(0, number(request, "organizerCost", 0).longValue()));
        event.setCreatedAt(System.currentTimeMillis());
        configureSeries(event, request);
        return view(eventRepository.save(event));
    }

    /** Catalogue of persistent unofficial competitions, distinct from their seasonal editions. */
    @Transactional
    public List<Map<String, Object>> getFriendlyCompetitions(long teamId) {
        backfillLegacySeries();
        return eventRepository.findAll().stream()
                .filter(this::isTournament)
                .filter(event -> teamId <= 0 || event.getOrganizerTeamId() == teamId || participantIds(event).contains(teamId))
                .collect(Collectors.groupingBy(FriendlyEvent::getSeriesId, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(this::seriesSummary)
                .sorted(Comparator.<Map<String, Object>>comparingInt(value -> (int) value.get("lastSeason")).reversed()
                        .thenComparing(value -> String.valueOf(value.get("name"))))
                .toList();
    }

    @Transactional
    public Map<String, Object> getFriendlyCompetition(String seriesId) {
        backfillLegacySeries();
        List<FriendlyEvent> editions = eventRepository.findAllBySeriesIdOrderBySeasonAscEditionNumberAsc(seriesId);
        if (editions.isEmpty()) throw new IllegalArgumentException("Friendly competition not found");
        Map<String, Object> result = new LinkedHashMap<>(seriesSummary(editions));
        result.put("editions", editions.stream().map(this::view).toList());
        FriendlyEvent latest = editions.get(editions.size() - 1);
        int currentSeason = currentGameDate().getSeason();
        int nextEditionSeason = Math.max(currentSeason, latest.getSeason() + 1);
        result.put("nextEditionSeason", nextEditionSeason);
        result.put("proposalAvailable", nextEditionSeason <= currentSeason + 1);
        result.put("latestEdition", view(latest));
        return result;
    }

    @Transactional
    public Map<String, Object> proposeEdition(String seriesId, Map<String, Object> request) {
        Map<String, Object> editionRequest = new LinkedHashMap<>(request);
        editionRequest.put("seriesId", seriesId);
        return createDraft(editionRequest);
    }

    @Transactional
    public Map<String, Object> confirm(long eventId) {
        FriendlyEvent event = requireEvent(eventId);
        if (!"DRAFT".equals(event.getStatus())) throw new IllegalStateException("Only draft events can be confirmed");
        validateFutureDate(event.getSeason(), event.getStartDay());

        Team organizer = requireTeam(event.getOrganizerTeamId());
        List<Long> participants = participantIds(event);
        long hostCommitment = event.getOrganizerCost() + event.getPrizePool();
        if (organizer.getTotalFinances() < hostCommitment) {
            throw new IllegalStateException("The host does not have enough funds for event costs and prize money");
        }
        for (long participantId : participants) {
            if (participantId == event.getOrganizerTeamId()) continue;
            Team guest = requireTeam(participantId);
            if (guest.getTotalFinances() < event.getParticipationFee()) {
                throw new IllegalStateException(guest.getName() + " cannot afford the participation fee");
            }
        }

        if (event.getOrganizerCost() > 0) {
            financeService.recordExpense(event.getOrganizerTeamId(), event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT", event.getName() + " organisation and logistics", event.getOrganizerCost());
        }
        if (event.getPrizePool() > 0) {
            financeService.recordExpense(event.getOrganizerTeamId(), event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT", event.getName() + " prize fund reserved", event.getPrizePool());
        }
        for (long participantId : participants) {
            if (participantId == event.getOrganizerTeamId() || event.getParticipationFee() == 0) continue;
            Team guest = requireTeam(participantId);
            financeService.recordExpense(participantId, event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT", "Participation fee: " + event.getName(), event.getParticipationFee());
            financeService.recordTransaction(event.getOrganizerTeamId(), event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT", "Participation fee from " + guest.getName(), event.getParticipationFee());
        }

        if ("MINI_LEAGUE".equals(event.getEventType())) scheduleRoundRobin(event, participants);
        if ("MINI_CUP".equals(event.getEventType())) scheduleSemiFinals(event, participants);
        event.setStatus("CONFIRMED");
        return view(eventRepository.save(event));
    }

    @Transactional
    public Map<String, Object> cancel(long eventId) {
        FriendlyEvent event = requireEvent(eventId);
        if ("COMPLETED".equals(event.getStatus())) throw new IllegalStateException("A completed event cannot be cancelled");
        List<FriendlyMatch> eventMatches = matchRepository.findAllByFriendlyEventIdOrderByDayAsc(eventId);
        if (eventMatches.stream().anyMatch(match -> "COMPLETED".equals(match.getStatus()))) {
            throw new IllegalStateException("An event cannot be cancelled after its first match has been played");
        }
        for (FriendlyMatch match : eventMatches) {
            if ("SCHEDULED".equals(match.getStatus())) {
                match.setStatus("CANCELLED");
                if (match.getCalendarEventId() > 0) {
                    calendarEventRepository.findById(match.getCalendarEventId()).ifPresent(calendar -> {
                        calendar.setStatus("SKIPPED");
                        calendarEventRepository.save(calendar);
                    });
                }
            }
        }
        if ("CONFIRMED".equals(event.getStatus())) refundEventCommitments(event);
        event.setStatus("CANCELLED");
        return view(eventRepository.save(event));
    }

    private void refundEventCommitments(FriendlyEvent event) {
        if (event.getPrizePool() > 0) {
            financeService.recordTransaction(event.getOrganizerTeamId(), event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT_REFUND", "Released prize fund: " + event.getName(), event.getPrizePool());
        }
        if (event.getParticipationFee() <= 0) return;
        for (long participantId : participantIds(event)) {
            if (participantId == event.getOrganizerTeamId()) continue;
            Team guest = requireTeam(participantId);
            financeService.recordTransaction(participantId, event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT_REFUND", "Participation fee refund: " + event.getName(), event.getParticipationFee());
            financeService.recordExpense(event.getOrganizerTeamId(), event.getSeason(), event.getStartDay(),
                    "FRIENDLY_EVENT_REFUND", "Participation fee returned to " + guest.getName(), event.getParticipationFee());
        }
    }

    /** Called after all friendlies for the day have been simulated. */
    @Transactional
    public void advanceEventsForDay(int season, int day) {
        for (FriendlyEvent event : eventRepository.findAllBySeasonOrderByStartDayAsc(season)) {
            if (!"CONFIRMED".equals(event.getStatus())) continue;
            List<FriendlyMatch> matches = matchRepository.findAllByFriendlyEventIdOrderByDayAsc(event.getId());
            if ("MINI_CUP".equals(event.getEventType())) advanceCup(event, matches, day);
            if ("MINI_LEAGUE".equals(event.getEventType()) && !matches.isEmpty()
                    && matches.stream().allMatch(match -> "COMPLETED".equals(match.getStatus()))) {
                completeEvent(event, leagueWinner(matches));
            }
        }
    }

    /**
     * Unofficial tournament wins are durable honours too. FriendlyEvent is the
     * source of truth, so old saves gain this cabinet without a schema migration
     * or a second trophy table that could drift out of sync.
     */
    public Map<String, Object> getFriendlyHonours(long teamId) {
        requireTeam(teamId);
        List<Map<String, Object>> honours = eventRepository.findAllByWinnerTeamIdOrderBySeasonDesc(teamId).stream()
                .filter(event -> "COMPLETED".equals(event.getStatus()))
                .filter(event -> "MINI_CUP".equals(event.getEventType()) || "MINI_LEAGUE".equals(event.getEventType()))
                .map(event -> {
                    Map<String, Object> honour = new LinkedHashMap<>();
                    honour.put("eventId", event.getId());
                    honour.put("name", event.getName());
                    honour.put("eventType", event.getEventType());
                    honour.put("season", event.getSeason());
                    honour.put("hostNationName", nationService.infoFor(event.getHostNationId()).name());
                    honour.put("locationName", event.getLocationName());
                    honour.put("organizerTeamId", event.getOrganizerTeamId());
                    honour.put("organizerTeamName", teamRepository.findNameById(event.getOrganizerTeamId()));
                    honour.put("prizePool", event.getPrizePool());
                    return honour;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teamId", teamId);
        result.put("total", honours.size());
        result.put("miniCups", honours.stream().filter(honour -> "MINI_CUP".equals(honour.get("eventType"))).count());
        result.put("miniLeagues", honours.stream().filter(honour -> "MINI_LEAGUE".equals(honour.get("eventType"))).count());
        result.put("honours", honours);
        return result;
    }

    private void advanceCup(FriendlyEvent event, List<FriendlyMatch> matches, int day) {
        List<FriendlyMatch> semis = matches.stream().filter(match -> "SEMI_FINAL".equals(match.getEventStage())).toList();
        boolean finalExists = matches.stream().anyMatch(match -> "FINAL".equals(match.getEventStage()));
        if (!finalExists && semis.size() == 2 && semis.stream().allMatch(match -> "COMPLETED".equals(match.getStatus()))) {
            long finalistOne = winner(semis.get(0));
            long finalistTwo = winner(semis.get(1));
            int finalDay = Math.min(event.getEndDay(), Math.max(day + 2, event.getStartDay() + 2));
            createMatch(event, finalistOne, finalistTwo, finalDay, "FINAL");
            return;
        }
        FriendlyMatch finalMatch = matches.stream().filter(match -> "FINAL".equals(match.getEventStage())).findFirst().orElse(null);
        if (finalMatch != null && "COMPLETED".equals(finalMatch.getStatus())) completeEvent(event, winner(finalMatch));
    }

    private void completeEvent(FriendlyEvent event, long winnerTeamId) {
        Team winner = requireTeam(winnerTeamId);
        event.setWinnerTeamId(winnerTeamId);
        event.setWinnerTeamName(winner.getName());
        event.setStatus("COMPLETED");
        eventRepository.save(event);
        if (event.getPrizePool() > 0) {
            financeService.recordTransaction(winnerTeamId, event.getSeason(), event.getEndDay(),
                    "PRIZE_MONEY", "Winner: " + event.getName(), event.getPrizePool());
        }
    }

    private long leagueWinner(List<FriendlyMatch> matches) {
        Map<Long, int[]> table = new LinkedHashMap<>();
        for (FriendlyMatch match : matches) {
            int[] home = table.computeIfAbsent(match.getHomeTeamId(), ignored -> new int[3]);
            int[] away = table.computeIfAbsent(match.getAwayTeamId(), ignored -> new int[3]);
            home[1] += match.getHomeGoals(); home[2] += match.getAwayGoals();
            away[1] += match.getAwayGoals(); away[2] += match.getHomeGoals();
            if (match.getHomeGoals() > match.getAwayGoals()) home[0] += 3;
            else if (match.getAwayGoals() > match.getHomeGoals()) away[0] += 3;
            else { home[0]++; away[0]++; }
        }
        return table.entrySet().stream()
                .max(Comparator.<Map.Entry<Long, int[]>>comparingInt(entry -> entry.getValue()[0])
                        .thenComparingInt(entry -> entry.getValue()[1] - entry.getValue()[2])
                        .thenComparingInt(entry -> entry.getValue()[1]))
                .orElseThrow().getKey();
    }

    private void scheduleRoundRobin(FriendlyEvent event, List<Long> originalParticipants) {
        List<Long> rotation = new ArrayList<>(originalParticipants);
        if (rotation.size() % 2 != 0) rotation.add(0L);
        int rounds = rotation.size() - 1;
        int requiredEnd = event.getStartDay() + (rounds - 1) * 2;
        if (requiredEnd > event.getEndDay()) throw new IllegalArgumentException("The event window is too short for this mini league");

        for (int round = 0; round < rounds; round++) {
            int day = event.getStartDay() + round * 2;
            for (int i = 0; i < rotation.size() / 2; i++) {
                long home = rotation.get(i);
                long away = rotation.get(rotation.size() - 1 - i);
                if (home != 0 && away != 0) createMatch(event, home, away, day, "ROUND_" + (round + 1));
            }
            Long last = rotation.remove(rotation.size() - 1);
            rotation.add(1, last);
        }
    }

    private void scheduleSemiFinals(FriendlyEvent event, List<Long> participants) {
        if (event.getStartDay() + 2 > event.getEndDay()) {
            throw new IllegalArgumentException("A mini cup needs at least three calendar days");
        }
        createMatch(event, participants.get(0), participants.get(3), event.getStartDay(), "SEMI_FINAL");
        createMatch(event, participants.get(1), participants.get(2), event.getStartDay(), "SEMI_FINAL");
    }

    private FriendlyMatch createMatch(FriendlyEvent event, long homeId, long awayId, int day, String stage) {
        Team home = requireTeam(homeId);
        Team away = requireTeam(awayId);
        FriendlyMatch match = new FriendlyMatch();
        match.setSeason(event.getSeason());
        match.setDay(day);
        match.setHomeTeamId(homeId);
        match.setAwayTeamId(awayId);
        match.setHomeTeamName(home.getName());
        match.setAwayTeamName(away.getName());
        match.setStatus("SCHEDULED");
        match.setScheduledByTeamId(event.getOrganizerTeamId());
        match.setFriendlyEventId(event.getId());
        match.setMatchType(event.getEventType());
        match.setPurpose(event.getFocus());
        match.setRuleset("EXTENDED_BENCH");
        match.setEventStage(stage);
        match.setVenueName(event.getLocationName());

        CalendarEvent calendar = new CalendarEvent();
        calendar.setSeason(event.getSeason());
        calendar.setDay(day);
        calendar.setPhase("EVENING");
        calendar.setEventType("MATCH_FRIENDLY");
        calendar.setStatus("PENDING");
        calendar.setTitle(event.getName() + ": " + home.getName() + " vs " + away.getName());
        calendar.setPriority(1);
        match.setCalendarEventId(calendarEventRepository.save(calendar).getId());
        return matchRepository.save(match);
    }

    private Map<String, Object> view(FriendlyEvent event) {
        List<Long> ids = participantIds(event);
        Map<Long, Team> teams = teamRepository.findAllById(ids).stream().collect(Collectors.toMap(Team::getId, team -> team));
        List<Map<String, Object>> participants = ids.stream().map(id -> {
            Team team = teams.get(id);
            return Map.<String, Object>of("teamId", id, "name", team == null ? "Unknown club" : team.getName(),
                    "organizer", id == event.getOrganizerTeamId());
        }).toList();
        List<FriendlyMatch> matches = matchRepository.findAllByFriendlyEventIdOrderByDayAsc(event.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.getId());
        result.put("season", event.getSeason());
        result.put("organizerTeamId", event.getOrganizerTeamId());
        result.put("organizerTeamName", teamRepository.findNameById(event.getOrganizerTeamId()));
        result.put("name", event.getName());
        result.put("seriesId", event.getSeriesId());
        result.put("seriesName", event.getSeriesName());
        result.put("editionNumber", event.getEditionNumber());
        result.put("eventType", event.getEventType());
        result.put("status", event.getStatus());
        result.put("hostNationId", event.getHostNationId());
        result.put("hostNationName", nationService.infoFor(event.getHostNationId()).name());
        result.put("hostNationFlagCode", nationService.infoFor(event.getHostNationId()).flagCode());
        result.put("locationName", event.getLocationName());
        result.put("startDay", event.getStartDay());
        result.put("endDay", event.getEndDay());
        result.put("startDate", calendarService.getDateDisplay(event.getStartDay()));
        result.put("endDate", calendarService.getDateDisplay(event.getEndDay()));
        result.put("focus", event.getFocus());
        result.put("format", event.getFormat());
        result.put("participants", participants);
        result.put("participationFee", event.getParticipationFee());
        result.put("prizePool", event.getPrizePool());
        result.put("organizerCost", event.getOrganizerCost());
        result.put("projectedFeeIncome", Math.max(0, ids.size() - 1L) * event.getParticipationFee());
        result.put("projectedNetCost", event.getOrganizerCost() + event.getPrizePool()
                - Math.max(0, ids.size() - 1L) * event.getParticipationFee());
        result.put("winnerTeamId", event.getWinnerTeamId());
        result.put("winnerTeamName", event.getWinnerTeamName());
        result.put("matches", matches.stream().map(this::matchView).toList());
        return result;
    }

    private Map<String, Object> matchView(FriendlyMatch match) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("matchId", match.getId()); view.put("day", match.getDay());
        view.put("dateDisplay", calendarService.getDateDisplay(match.getDay()));
        view.put("homeTeamId", match.getHomeTeamId()); view.put("homeTeamName", match.getHomeTeamName());
        view.put("awayTeamId", match.getAwayTeamId()); view.put("awayTeamName", match.getAwayTeamName());
        view.put("stage", match.getEventStage()); view.put("status", match.getStatus());
        if ("COMPLETED".equals(match.getStatus())) view.put("score", match.getHomeGoals() + " - " + match.getAwayGoals());
        return view;
    }

    private Map<String, Object> option(String id, String label, String description) {
        return Map.of("id", id, "label", label, "description", description);
    }

    private void configureSeries(FriendlyEvent event, Map<String, Object> request) {
        if (!isTournament(event)) return;
        String requestedSeriesId = text(request, "seriesId", "").trim();
        if (requestedSeriesId.isBlank()) {
            event.setSeriesId(UUID.randomUUID().toString());
            event.setSeriesName(text(request, "seriesName", event.getName()).trim());
            event.setEditionNumber(1);
            return;
        }
        List<FriendlyEvent> editions = eventRepository.findAllBySeriesIdOrderBySeasonAscEditionNumberAsc(requestedSeriesId);
        if (editions.isEmpty()) throw new IllegalArgumentException("Friendly competition tradition not found");
        FriendlyEvent inaugural = editions.get(0);
        if (!Objects.equals(inaugural.getEventType(), event.getEventType())) {
            throw new IllegalArgumentException("A new edition must keep the competition format");
        }
        boolean duplicateSeason = editions.stream().anyMatch(existing -> existing.getSeason() == event.getSeason()
                && !"CANCELLED".equals(existing.getStatus()));
        if (duplicateSeason) throw new IllegalArgumentException("This friendly competition already has an edition in Season " + event.getSeason());
        event.setSeriesId(requestedSeriesId);
        event.setSeriesName(inaugural.getSeriesName() == null ? inaugural.getName() : inaugural.getSeriesName());
        event.setEditionNumber(editions.stream().mapToInt(FriendlyEvent::getEditionNumber).max().orElse(editions.size()) + 1);
    }

    private boolean isTournament(FriendlyEvent event) {
        return "MINI_CUP".equals(event.getEventType()) || "MINI_LEAGUE".equals(event.getEventType());
    }

    private void backfillLegacySeries() {
        List<FriendlyEvent> legacy = eventRepository.findAll().stream()
                .filter(this::isTournament)
                .filter(event -> event.getSeriesId() == null || event.getSeriesId().isBlank())
                .toList();
        Map<String, List<FriendlyEvent>> groups = legacy.stream().collect(Collectors.groupingBy(event ->
                event.getOrganizerTeamId() + "|" + event.getEventType() + "|" + normalizeSeriesName(event.getName())));
        List<FriendlyEvent> changed = new ArrayList<>();
        for (List<FriendlyEvent> group : groups.values()) {
            group.sort(Comparator.comparingInt(FriendlyEvent::getSeason).thenComparingLong(FriendlyEvent::getId));
            String id = "friendly-series-" + group.get(0).getId();
            String name = group.get(0).getName();
            for (int index = 0; index < group.size(); index++) {
                FriendlyEvent event = group.get(index);
                event.setSeriesId(id);
                event.setSeriesName(name);
                event.setEditionNumber(index + 1);
                changed.add(event);
            }
        }
        if (!changed.isEmpty()) eventRepository.saveAll(changed);
    }

    private String normalizeSeriesName(String name) {
        return (name == null ? "friendly competition" : name).trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    private Map<String, Object> seriesSummary(List<FriendlyEvent> unsortedEditions) {
        List<FriendlyEvent> editions = unsortedEditions.stream()
                .sorted(Comparator.comparingInt(FriendlyEvent::getSeason).thenComparingInt(FriendlyEvent::getEditionNumber))
                .toList();
        FriendlyEvent first = editions.get(0);
        FriendlyEvent latest = editions.get(editions.size() - 1);
        long completed = editions.stream().filter(event -> "COMPLETED".equals(event.getStatus())).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seriesId", first.getSeriesId());
        result.put("name", first.getSeriesName() == null ? first.getName() : first.getSeriesName());
        result.put("eventType", first.getEventType());
        result.put("foundedSeason", first.getSeason());
        result.put("lastSeason", latest.getSeason());
        result.put("editionCount", editions.size());
        result.put("completedEditions", completed);
        result.put("organizerTeamId", latest.getOrganizerTeamId());
        result.put("organizerTeamName", teamRepository.findNameById(latest.getOrganizerTeamId()));
        result.put("latestWinnerTeamId", latest.getWinnerTeamId());
        result.put("latestWinnerTeamName", latest.getWinnerTeamName());
        result.put("latestStatus", latest.getStatus());
        result.put("latestLocationName", latest.getLocationName());
        return result;
    }

    private Map<String, Object> dateOption(int season, int day, String phase) {
        return Map.of("season", season, "day", day, "dateDisplay", calendarService.getDateDisplay(day), "phase", phase);
    }

    private Team requireTeam(long teamId) {
        return teamRepository.findById(teamId).orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    private FriendlyEvent requireEvent(long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> new IllegalArgumentException("Friendly event not found"));
    }

    private List<Long> participantIds(FriendlyEvent event) {
        if (event.getParticipantTeamIds() == null || event.getParticipantTeamIds().isBlank()) return List.of(event.getOrganizerTeamId());
        return List.of(event.getParticipantTeamIds().split(",")).stream().map(String::trim).filter(value -> !value.isBlank())
                .map(Long::parseLong).toList();
    }

    private List<Long> parseParticipantRequest(Object raw) {
        if (!(raw instanceof List<?> values)) return new ArrayList<>();
        return values.stream().filter(Objects::nonNull).map(value -> value instanceof Number number
                ? number.longValue() : Long.parseLong(value.toString())).collect(Collectors.toCollection(ArrayList::new));
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private long winner(FriendlyMatch match) {
        if (match.getHomeGoals() == match.getAwayGoals()) {
            // The canonical friendly engine has no shoot-out; a deterministic seeded tie-break keeps brackets moving.
            return Math.min(match.getHomeTeamId(), match.getAwayTeamId());
        }
        return match.getHomeGoals() > match.getAwayGoals() ? match.getHomeTeamId() : match.getAwayTeamId();
    }

    private void validateWindow(int startDay, int endDay) {
        boolean preSeason = startDay >= 1 && endDay <= 30;
        boolean winterBreak = startDay >= 201 && endDay <= 210;
        if (startDay > endDay || (!preSeason && !winterBreak)) {
            throw new IllegalArgumentException("Events must fit entirely inside pre-season (1-30) or winter break (201-210)");
        }
    }

    private void validateFutureDate(int season, int startDay) {
        GameCalendar calendar = currentGameDate();
        if (season < calendar.getSeason() || season > calendar.getSeason() + 1) {
            throw new IllegalArgumentException("Friendly events can only be created for Season "
                    + calendar.getSeason() + " or Season " + (calendar.getSeason() + 1));
        }
        if (season == calendar.getSeason() && startDay <= calendar.getCurrentDay()) {
            throw new IllegalArgumentException("Friendly events must start after the current day (Day "
                    + calendar.getCurrentDay() + ")");
        }
    }

    private GameCalendar currentGameDate() {
        return gameCalendarRepository.findTopByOrderBySeasonDesc()
                .orElseThrow(() -> new IllegalStateException("The game calendar is not initialized"));
    }

    private Number number(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing numeric field: " + key);
        return number;
    }

    private Number number(Map<String, Object> request, String key, Number fallback) {
        Object value = request.get(key);
        return value instanceof Number number ? number : fallback;
    }

    private String text(Map<String, Object> request, String key, String fallback) {
        Object value = request.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
