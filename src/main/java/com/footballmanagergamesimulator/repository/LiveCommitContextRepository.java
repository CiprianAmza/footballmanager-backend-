package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.LiveCommitContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LiveCommitContextRepository extends JpaRepository<LiveCommitContext, Long> {
    Optional<LiveCommitContext> findByLiveKey(String liveKey);
}
