package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ClubBoard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClubBoardRepository extends JpaRepository<ClubBoard, Long> {

    Optional<ClubBoard> findByTeamId(long teamId);
}
