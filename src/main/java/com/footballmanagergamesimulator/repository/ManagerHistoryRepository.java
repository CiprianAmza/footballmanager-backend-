package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerHistoryRepository extends JpaRepository<ManagerHistory, Long> {

    List<ManagerHistory> findAllByManagerId(long managerId);

    List<ManagerHistory> findAllByTeamId(long teamId);

    List<ManagerHistory> findAllBySeasonNumber(int seasonNumber);
}
