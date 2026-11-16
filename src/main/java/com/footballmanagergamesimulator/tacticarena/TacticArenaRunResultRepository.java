package com.footballmanagergamesimulator.tacticarena;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TacticArenaRunResultRepository extends JpaRepository<TacticArenaRunResult, Long> {
    @Transactional
    void deleteAllByTestId(long testId);
}
