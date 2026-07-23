package com.footballmanagergamesimulator.dynamics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerConversationRepository extends JpaRepository<PlayerConversation, Long> {

    Optional<PlayerConversation> findByPlayerIdAndSeasonAndMonthIndex(long playerId, int season, int monthIndex);

    boolean existsByPlayerIdAndSeasonAndMonthIndex(long playerId, int season, int monthIndex);

    List<PlayerConversation> findByTeamIdOrderBySeasonDescMonthIndexDesc(long teamId);

    List<PlayerConversation> findByPlayerIdOrderBySeasonDescMonthIndexDesc(long playerId);
}
