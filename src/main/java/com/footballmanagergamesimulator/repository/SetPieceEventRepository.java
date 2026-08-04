package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.SetPieceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SetPieceEventRepository extends JpaRepository<SetPieceEvent, Long> {
    List<SetPieceEvent> findAllByMatchStatsIdOrderByTeamIdAscEventIndexAsc(long matchStatsId);
    void deleteAllByMatchStatsId(long matchStatsId);
}
