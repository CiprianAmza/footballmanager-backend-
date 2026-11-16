package com.footballmanagergamesimulator.tacticarena;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TacticArenaTestRepository extends JpaRepository<TacticArenaTest, Long> {
    List<TacticArenaTest> findAllByOrderByIdDesc();

    List<TacticArenaTest> findAllByStatusOrderByIdAsc(String status);

    Optional<TacticArenaTest> findByUid(String uid);
}
