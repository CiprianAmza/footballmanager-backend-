package com.footballmanagergamesimulator.tacticarena;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TacticArenaRunRepository extends JpaRepository<TacticArenaRun, Long> {
    List<TacticArenaRun> findAllByTestIdOrderByRunIndexAsc(long testId);

    List<TacticArenaRun> findAllByTestIdIn(List<Long> testIds);

    @Transactional
    void deleteAllByTestId(long testId);
}
