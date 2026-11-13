package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.TrainingDelegation;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TrainingDelegationRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who coaches each training type. A human-managed team delegates a staff member per type;
 * a type with no valid delegation (nobody chosen yet, e.g. a team just taken over or an old
 * save, or the delegated coach left the club) falls back to the club's best coach for it.
 * An AI-managed team's staff is always delegated optimally: the best coach of the club for each type.
 */
@Service
public class TrainingDelegationService {

    /** The staff types that can coach. */
    static final List<Long> COACH_TYPES = List.of(
            TypeNames.ASSISTANT_MANAGER_TYPE, TypeNames.FIRST_TEAM_COACH_TYPE,
            TypeNames.FITNESS_COACH_TYPE, TypeNames.GK_COACH_TYPE,
            TypeNames.YOUTH_COACH_TYPE, TypeNames.HOYD_TYPE);

    /** The coach level of a type nobody at the club can coach. */
    static final double NO_COACH = 1.0;

    @Autowired TrainingDelegationRepository delegationRepository;
    @Autowired HumanRepository humanRepository;

    /** Type → rating of the coach delegated to it (else the club's best coach), for a human-managed team. */
    public Map<TrainingType, Double> humanCoachRatings(long teamId) {
        Map<Long, Human> staff = new HashMap<>();
        for (Human coach : humanRepository.findAllByTeamIdInAndTypeIdIn(List.of(teamId), COACH_TYPES)) {
            staff.put(coach.getId(), coach);
        }
        Map<TrainingType, Double> ratings = optimalCoachRatings(List.of(teamId)).get(teamId);
        for (TrainingDelegation delegation : delegationRepository.findAllByTeamId(teamId)) {
            Human coach = staff.get(delegation.getStaffId());
            TrainingType type = typeOrNull(delegation.getTrainingType());
            if (coach != null && type != null) ratings.put(type, CoachRatings.rating(coach, type));
        }
        return ratings;
    }

    /** Team → type → rating of the club's best coach for it, for AI-managed teams. */
    public Map<Long, Map<TrainingType, Double>> optimalCoachRatings(Collection<Long> teamIds) {
        Map<Long, Map<TrainingType, Double>> byTeam = new HashMap<>();
        for (Long teamId : teamIds) {
            Map<TrainingType, Double> ratings = new EnumMap<>(TrainingType.class);
            for (TrainingType type : TrainingType.values()) ratings.put(type, NO_COACH);
            byTeam.put(teamId, ratings);
        }
        if (teamIds.isEmpty()) return byTeam;
        for (Human coach : humanRepository.findAllByTeamIdInAndTypeIdIn(teamIds, COACH_TYPES)) {
            Map<TrainingType, Double> ratings = byTeam.get(coach.getTeamId());
            if (ratings == null) continue;
            for (TrainingType type : TrainingType.values()) {
                ratings.merge(type, CoachRatings.rating(coach, type), Math::max);
            }
        }
        return byTeam;
    }

    /** One coach and his rating on a training type. */
    public record CoachOption(long staffId, String name, long typeId, double rating) {}

    /**
     * One training type as the training screen shows it: who is delegated (null: nobody),
     * who actually coaches it (the delegate, else the club's best coach — {@code automatic}),
     * and every coach of the club with his rating on it, best first.
     */
    public record TypeOverview(TrainingType type, List<String> attributes, List<String> coachAttributes,
                               Long delegatedStaffId, Long effectiveStaffId, double effectiveRating,
                               boolean automatic, List<CoachOption> candidates) {}

    /** Every training type of a team, for the training screen. */
    public List<TypeOverview> overview(long teamId) {
        List<Human> coaches = humanRepository.findAllByTeamIdInAndTypeIdIn(List.of(teamId), COACH_TYPES);
        Map<Long, Human> byId = new HashMap<>();
        coaches.forEach(coach -> byId.put(coach.getId(), coach));
        Map<TrainingType, Long> delegated = delegations(teamId);
        List<TypeOverview> result = new java.util.ArrayList<>();
        for (TrainingType type : TrainingType.values()) {
            List<CoachOption> candidates = coaches.stream()
                    .map(coach -> new CoachOption(coach.getId(), coach.getName(), coach.getTypeId(),
                            CoachRatings.rating(coach, type)))
                    .sorted(java.util.Comparator.comparingDouble(CoachOption::rating).reversed()
                            .thenComparingLong(CoachOption::staffId))
                    .toList();
            Long delegate = delegated.get(type);
            boolean valid = delegate != null && byId.containsKey(delegate);
            CoachOption effective = valid
                    ? candidates.stream().filter(c -> c.staffId() == delegate).findFirst().orElse(null)
                    : candidates.isEmpty() ? null : candidates.get(0);
            result.add(new TypeOverview(type, type.attributes().stream().sorted().toList(), type.coachAttributes(),
                    valid ? delegate : null, effective == null ? null : effective.staffId(),
                    effective == null ? NO_COACH : effective.rating(), !valid, candidates));
        }
        return result;
    }

    /** The current delegations of a team: type → staff id. */
    public Map<TrainingType, Long> delegations(long teamId) {
        Map<TrainingType, Long> result = new EnumMap<>(TrainingType.class);
        for (TrainingDelegation delegation : delegationRepository.findAllByTeamId(teamId)) {
            TrainingType type = typeOrNull(delegation.getTrainingType());
            if (type != null) result.put(type, delegation.getStaffId());
        }
        return result;
    }

    /** Delegates {@code staffId} (a coach of this team) to {@code type}; null staff removes the delegation. */
    @Transactional
    public void delegate(long teamId, TrainingType type, Long staffId) {
        TrainingDelegation existing = delegationRepository
                .findByTeamIdAndTrainingType(teamId, type.name()).orElse(null);
        if (staffId == null) {
            if (existing != null) delegationRepository.delete(existing);
            return;
        }
        Human coach = humanRepository.findById(staffId).orElse(null);
        if (coach == null || coach.getTeamId() == null || coach.getTeamId() != teamId
                || !COACH_TYPES.contains(coach.getTypeId())) {
            throw new IllegalArgumentException("Staff " + staffId + " is not a coach of team " + teamId);
        }
        TrainingDelegation delegation = existing != null ? existing : new TrainingDelegation();
        delegation.setTeamId(teamId);
        delegation.setTrainingType(type.name());
        delegation.setStaffId(staffId);
        delegationRepository.save(delegation);
    }

    private static TrainingType typeOrNull(String name) {
        try {
            return TrainingType.valueOf(name);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
