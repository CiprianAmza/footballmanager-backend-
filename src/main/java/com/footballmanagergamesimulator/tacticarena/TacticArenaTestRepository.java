package com.footballmanagergamesimulator.tacticarena;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TacticArenaTestRepository extends JpaRepository<TacticArenaTest, Long> {
    List<TacticArenaTest> findAllByOrderByIdDesc();

    List<TacticArenaTest> findAllByStatusOrderByIdAsc(String status);

    List<TacticArenaTest> findAllByStatusInOrderByIdAsc(List<String> statuses);

    /** The head of the queue (running tests take at most one slot each, so 50 covers any sane limit). */
    List<TacticArenaTest> findTop50ByStatusInOrderByIdAsc(List<String> statuses);

    Optional<TacticArenaTest> findByUid(String uid);
}
