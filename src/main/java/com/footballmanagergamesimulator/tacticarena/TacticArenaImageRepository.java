package com.footballmanagergamesimulator.tacticarena;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TacticArenaImageRepository extends JpaRepository<TacticArenaImage, Long> {
    Optional<TacticArenaImage> findByRunIdAndKind(long runId, String kind);

    @Query("select i.runId, i.kind from TacticArenaImage i where i.testId = ?1")
    List<Object[]> kindsForTest(long testId);

    List<TacticArenaImage> findAllByTestId(long testId);

    @Transactional
    void deleteAllByTestId(long testId);
}
