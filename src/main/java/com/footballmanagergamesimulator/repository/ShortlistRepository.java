package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Shortlist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ShortlistRepository extends JpaRepository<Shortlist, Long> {
    List<Shortlist> findAllByUserId(long userId);
    Optional<Shortlist> findByUserIdAndPlayerId(long userId, long playerId);
    void deleteByUserIdAndPlayerId(long userId, long playerId);
    boolean existsByUserIdAndPlayerId(long userId, long playerId);
}
