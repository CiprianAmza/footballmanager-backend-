package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.SetPieceEvent;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.SetPieceEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Creates a deterministic set-piece ledger while preserving observed MatchStats totals. */
@Service
public class SetPieceEventService {
    public static final String QUALITY = "MODELED_DELIVERY_FROM_MATCH_STATS";
    private final SetPieceEventRepository repository;
    private final ShotEventService shotEvents;

    public SetPieceEventService(SetPieceEventRepository repository, ShotEventService shotEvents) {
        this.repository = repository; this.shotEvents = shotEvents;
    }
    @Transactional public void replaceForMatch(MatchStats stats) {
        if (stats == null || stats.getId() <= 0) return;
        repository.deleteAllByMatchStatsId(stats.getId()); repository.saveAll(generate(stats));
    }
    public List<SetPieceEvent> eventsForMatch(MatchStats stats) {
        List<SetPieceEvent> stored = repository.findAllByMatchStatsIdOrderByTeamIdAscEventIndexAsc(stats.getId());
        return stored == null || stored.isEmpty() ? generate(stats) : stored;
    }
    public List<SetPieceEvent> generate(MatchStats stats) {
        List<ShotEvent> shots = shotEvents.eventsForMatch(stats);
        List<SetPieceEvent> result = new ArrayList<>();
        result.addAll(team(stats, true, shots)); result.addAll(team(stats, false, shots)); return result;
    }
    private List<SetPieceEvent> team(MatchStats stats, boolean home, List<ShotEvent> shots) {
        long team = home ? stats.getTeam1Id() : stats.getTeam2Id();
        int corners = Math.max(0, home ? stats.getHomeCorners() : stats.getAwayCorners());
        int freeKicks = Math.max(0, home ? stats.getHomeFreeKicks() : stats.getAwayFreeKicks());
        int crosses = Math.max(0, home ? stats.getHomeCrosses() : stats.getAwayCrosses());
        List<ShotEvent> cornerShots = shots.stream().filter(s -> s.getTeamId() == team && "CORNER".equals(s.getSituation())).toList();
        List<ShotEvent> freeKickShots = shots.stream().filter(s -> s.getTeamId() == team && "FREE_KICK".equals(s.getSituation())).toList();
        List<ShotEvent> penalties = shots.stream().filter(s -> s.getTeamId() == team && "PENALTY".equals(s.getSituation())).toList();
        int direct = Math.min(freeKicks, Math.max(freeKickShots.size(), (int) Math.round(freeKicks * .13)));
        int indirect = Math.max(0, freeKicks - direct);
        int longThrows = Math.max(0, (int) Math.round(crosses * .08));
        List<SetPieceEvent> result = new ArrayList<>(); int index = 0;
        for (int i = 0; i < corners; i++) result.add(event(stats, team, index++, "CORNER", i, cornerShots));
        for (int i = 0; i < direct; i++) result.add(event(stats, team, index++, "DIRECT_FREE_KICK", i, freeKickShots));
        for (int i = 0; i < indirect; i++) result.add(event(stats, team, index++, "INDIRECT_FREE_KICK", i + direct, freeKickShots));
        for (int i = 0; i < penalties.size(); i++) result.add(event(stats, team, index++, "PENALTY", i, penalties));
        for (int i = 0; i < longThrows; i++) result.add(event(stats, team, index++, "LONG_THROW", i, List.of()));
        return result;
    }
    private SetPieceEvent event(MatchStats stats, long team, int index, String type, int typeIndex, List<ShotEvent> shots) {
        long seed = stats.getId() * 31 + team * 17 + index * 13L; Random rng = new Random(seed);
        ShotEvent shot = typeIndex < shots.size() ? shots.get(typeIndex) : null;
        SetPieceEvent e = new SetPieceEvent(); e.setMatchStatsId(stats.getId()); e.setEventIndex(index);
        e.setCompetitionId(stats.getCompetitionId()); e.setSeasonNumber(stats.getSeasonNumber()); e.setRoundNumber(stats.getRoundNumber());
        e.setTeamId(team); e.setOpponentTeamId(team == stats.getTeam1Id() ? stats.getTeam2Id() : stats.getTeam1Id()); e.setType(type);
        e.setDeliveryStyle("PENALTY".equals(type) || "DIRECT_FREE_KICK".equals(type) ? "DIRECT" : pick(rng, "SHORT", "INSWINGING", "OUTSWINGING"));
        e.setDeliveryZone("PENALTY".equals(type) ? "CENTRE" : pick(rng, "NEAR_POST", "CENTRE", "FAR_POST", "EDGE_OF_BOX"));
        e.setFirstContact(shot == null ? pick(rng, "DEFENDER", "NONE") : pick(rng, "ATTACKER", "ATTACKER", "DEFENDER"));
        e.setSecondBallRecovery(pick(rng, "ATTACKING", "DEFENDING", "NONE"));
        e.setOutcome(shot == null ? "NO_SHOT" : "GOAL".equals(shot.getOutcome()) ? "GOAL" : "SHOT");
        e.setXg(shot == null ? 0 : shot.getXg()); e.setDataQuality(QUALITY); return e;
    }
    private String pick(Random rng, String... values) { return values[rng.nextInt(values.length)]; }
}
