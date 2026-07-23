package com.footballmanagergamesimulator.squadplanner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SquadPlanSlotRepository extends JpaRepository<SquadPlanSlot, Long> {

    List<SquadPlanSlot> findAllByTeamIdAndSeasonOffset(long teamId, int seasonOffset);

    List<SquadPlanSlot> findAllByUserIdAndTeamIdAndSeasonOffset(int userId, long teamId, int seasonOffset);

    List<SquadPlanSlot> findAllByUserIdAndTeamId(int userId, long teamId);

    List<SquadPlanSlot> findAllByTeamId(long teamId);
}
