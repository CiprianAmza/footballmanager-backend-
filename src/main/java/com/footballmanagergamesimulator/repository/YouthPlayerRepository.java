package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.YouthPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface YouthPlayerRepository extends JpaRepository<YouthPlayer, Long> {

    List<YouthPlayer> findAllByTeamIdAndStatus(long teamId, String status);

    List<YouthPlayer> findAllByTeamId(long teamId);
}
