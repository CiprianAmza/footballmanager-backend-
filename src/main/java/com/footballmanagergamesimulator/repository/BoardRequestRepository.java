package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.BoardRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardRequestRepository extends JpaRepository<BoardRequest, Long> {

    List<BoardRequest> findAllByTeamIdAndStatus(long teamId, String status);

    List<BoardRequest> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
}
