package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The meeting with the squad, objectives part (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): for each
 * competition of the season the manager names a target, the players say what they expect (league: the press's
 * predicted finish; cups: the club's reputation rank among the entrants), and the gap moves every player's
 * relation with the manager. Held once per season.
 */
@Service
public class SquadMeetingService {

    private final CompetitionTeamInfoRepository membershipRepository;
    private final CompetitionRepository competitionRepository;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final SquadObjectiveRepository objectiveRepository;
    private final GameCalendarRepository calendarRepository;
    private final RelationService relationService;
    private final LeaguePredictionService predictionService;
    @org.springframework.beans.factory.annotation.Autowired
    private MeetingRecordRepository meetingRecordRepository;

    /** Refusing the squad meeting (design §6.4, decided 2026-11-12): every player's relation and morale -5. */
    public static final int REFUSAL_RELATION = -5;
    public static final double REFUSAL_MORALE = -5;

    public SquadMeetingService(CompetitionTeamInfoRepository membershipRepository,
                               CompetitionRepository competitionRepository, TeamRepository teamRepository,
                               HumanRepository humanRepository, SquadObjectiveRepository objectiveRepository,
                               GameCalendarRepository calendarRepository, RelationService relationService,
                               LeaguePredictionService predictionService) {
        this.membershipRepository = membershipRepository;
        this.competitionRepository = competitionRepository;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.objectiveRepository = objectiveRepository;
        this.calendarRepository = calendarRepository;
        this.relationService = relationService;
        this.predictionService = predictionService;
    }

    /** One competition on the agenda. {@code managerTarget}/{@code relationDelta} are set once the meeting was held. */
    public record Item(long competitionId, String competitionName, boolean league, List<String> ladder,
                       String playersWish, String managerTarget, Integer relationDelta, String bonusLevel,
                       Long bonusPaid) {}

    public record Agenda(int season, boolean held, List<Item> items, boolean refused) {}

    public int currentSeason() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }

    public Agenda agenda(long teamId) {
        int season = currentSeason();
        Map<Long, SquadObjective> held = new HashMap<>();
        objectiveRepository.findAllByTeamIdAndSeason(teamId, season).forEach(o -> held.put(o.getCompetitionId(), o));
        List<Item> items = new ArrayList<>();
        for (Competition competition : competitionsOf(teamId, season)) {
            boolean league = competition.isLeague();
            SquadObjective done = held.get(competition.getId());
            items.add(new Item(competition.getId(), competition.getName(), league, SquadObjectives.ladder(league),
                    done != null ? done.getPlayersWish() : playersWish(competition, teamId, season),
                    done == null ? null : done.getManagerTarget(), done == null ? null : done.getRelationDelta(),
                    done == null ? null : done.getBonusLevel(),
                    done == null || !done.isBonusSettled() ? null : done.getBonusPaid()));
        }
        boolean refused = meetingRecordRepository != null && meetingRecordRepository
                .findByTeamIdAndSeasonAndType(teamId, season, MeetingRecord.SQUAD)
                .map(r -> MeetingRecord.REFUSED.equals(r.getOutcome())).orElse(false);
        return new Agenda(season, !held.isEmpty() || refused, items, refused);
    }

    /**
     * Holds the meeting: stores each target and moves every player's relation with the manager by the sum of the
     * per-competition changes. A competition without a target from the manager counts as the players' wish.
     */
    @Transactional
    public Agenda hold(long teamId, Map<Long, String> targets) {
        return hold(teamId, targets, Map.of());
    }

    /** As above, with the bonus level (MIN/MID/MAX, default MID) per competition; it moves the squad's morale now. */
    @Transactional
    public Agenda hold(long teamId, Map<Long, String> targets, Map<Long, String> bonuses) {
        int season = currentSeason();
        if (agenda(teamId).held()) {
            throw new IllegalStateException("The squad meeting has already taken place (or been refused) this season.");
        }
        List<SquadObjective> rows = new ArrayList<>();
        int total = 0;
        double morale = 0;
        for (Item item : agenda(teamId).items()) {
            String target = targets.getOrDefault(item.competitionId(), item.playersWish());
            if (!item.ladder().contains(target)) {
                throw new IllegalArgumentException("Unknown target " + target + " for " + item.competitionName());
            }
            int delta = SquadObjectives.relationDelta(item.ladder(), target, item.playersWish());
            SquadObjective row = new SquadObjective();
            row.setTeamId(teamId);
            row.setSeason(season);
            row.setCompetitionId(item.competitionId());
            row.setCompetitionName(item.competitionName());
            row.setManagerTarget(target);
            row.setPlayersWish(item.playersWish());
            row.setRelationDelta(delta);
            SquadObjectives.Bonus bonus = SquadObjectives.Bonus.valueOf(
                    bonuses.getOrDefault(item.competitionId(), SquadObjectives.Bonus.MID.name()));
            row.setBonusLevel(bonus.name());
            morale += bonus.morale;
            rows.add(row);
            total += delta;
        }
        objectiveRepository.saveAll(rows);
        long managerId = managerOf(teamId);
        record(teamId, managerId, season, MeetingRecord.HELD);
        double moraleChange = Math.max(-SquadObjectives.MAX_MEETING_MORALE, Math.min(SquadObjectives.MAX_MEETING_MORALE, morale));
        if (moraleChange != 0) {
            List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player : squad) {
                if (player.isRetired()) continue;
                player.setMorale(Math.max(0, Math.min(100, player.getMorale() + moraleChange)));
            }
            humanRepository.saveAll(squad);
        }
        if (managerId > 0 && total != 0) {
            List<RelationService.Change> changes = new ArrayList<>();
            for (Human player : humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)) {
                if (player.isRetired()) continue;
                changes.add(new RelationService.Change(RelationParty.PLAYER, player.getId(),
                        RelationParty.MANAGER, managerId, total));
            }
            relationService.changeAll(changes);
        }
        return agenda(teamId);
    }

    /**
     * The manager refuses the squad meeting: no targets and no bonuses this season, and every player's relation
     * with him and morale drop by 5.
     */
    @Transactional
    public Agenda refuse(long teamId) {
        int season = currentSeason();
        if (agenda(teamId).held()) {
            throw new IllegalStateException("The squad meeting has already taken place (or been refused) this season.");
        }
        long managerId = managerOf(teamId);
        record(teamId, managerId, season, MeetingRecord.REFUSED);
        List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired()).toList();
        for (Human player : squad) player.setMorale(Math.max(0, player.getMorale() + REFUSAL_MORALE));
        humanRepository.saveAll(squad);
        if (managerId > 0) {
            relationService.changeAll(squad.stream().map(p -> new RelationService.Change(RelationParty.PLAYER,
                    p.getId(), RelationParty.MANAGER, managerId, REFUSAL_RELATION)).toList());
        }
        return agenda(teamId);
    }

    private long managerOf(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired()).map(Human::getId).min(Long::compare).orElse(0L);
    }

    private void record(long teamId, long managerId, int season, String outcome) {
        if (meetingRecordRepository == null) return;
        MeetingRecord record = new MeetingRecord();
        record.setTeamId(teamId);
        record.setManagerId(managerId);
        record.setSeason(season);
        record.setType(MeetingRecord.SQUAD);
        record.setOutcome(outcome);
        meetingRecordRepository.save(record);
    }

    private List<Competition> competitionsOf(long teamId, int season) {
        Set<Long> ids = new TreeSet<>();
        membershipRepository.findAllByTeamIdAndSeasonNumber(teamId, season)
                .forEach(m -> ids.add(m.getCompetitionId()));
        return competitionRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing((Competition c) -> !c.isLeague()).thenComparingLong(Competition::getId))
                .toList();
    }

    String playersWish(Competition competition, long teamId, int season) {
        if (competition.isLeague()) {
            int[] finish = predictionService.predictedFinish(competition.getId(), teamId);
            return finish[0] == 0 ? "TOP_HALF" : SquadObjectives.leagueWish(finish[0], finish[1]);
        }
        List<Long> entrants = membershipRepository.findAllByCompetitionIdAndSeasonNumber(competition.getId(), season)
                .stream().map(CompetitionTeamInfo::getTeamId).distinct().toList();
        Map<Long, Integer> reputation = new HashMap<>();
        teamRepository.findAllById(entrants).forEach(t -> reputation.put(t.getId(), t.getReputation()));
        int mine = reputation.getOrDefault(teamId, 0);
        int rank = 1 + (int) reputation.entrySet().stream()
                .filter(e -> e.getKey() != teamId && e.getValue() > mine).count();
        return SquadObjectives.cupWish(rank);
    }
}
