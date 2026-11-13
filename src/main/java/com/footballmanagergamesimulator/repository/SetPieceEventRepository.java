package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.SetPieceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SetPieceEventRepository extends JpaRepository<SetPieceEvent, Long> {
    List<SetPieceEvent> findAllByMatchStatsIdOrderByTeamIdAscEventIndexAsc(long matchStatsId);
    void deleteAllByMatchStatsId(long matchStatsId);

    /** Detail retention: drops the rows of seasons before {@code season}. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from SetPieceEvent e where e.seasonNumber < :season")
    int deleteSeasonsBefore(@org.springframework.data.repository.query.Param("season") int season);
}
