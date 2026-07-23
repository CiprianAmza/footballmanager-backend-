package com.footballmanagergamesimulator.training;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositories for the FORGE training extensions (units, coach assignment,
 * mentoring). Grouped in one file to keep the feature self-contained.
 */
public class TrainingExtraRepositories {

    public interface PlayerUnitAssignmentRepository extends JpaRepository<PlayerUnitAssignment, Long> {
        List<PlayerUnitAssignment> findAllByTeamId(long teamId);
        PlayerUnitAssignment findByTeamIdAndPlayerId(long teamId, long playerId);
    }

    public interface UnitCoachAssignmentRepository extends JpaRepository<UnitCoachAssignment, Long> {
        List<UnitCoachAssignment> findAllByTeamId(long teamId);
        List<UnitCoachAssignment> findAllByTeamIdAndUnit(long teamId, String unit);
    }

    public interface MentoringGroupRepository extends JpaRepository<MentoringGroup, Long> {
        List<MentoringGroup> findAllByTeamId(long teamId);
    }

    public interface MentoringGroupMemberRepository extends JpaRepository<MentoringGroupMember, Long> {
        List<MentoringGroupMember> findAllByTeamId(long teamId);
        List<MentoringGroupMember> findAllByGroupId(long groupId);
        void deleteAllByGroupId(long groupId);
    }
}
