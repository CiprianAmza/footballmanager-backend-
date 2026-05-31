package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ClubShareholding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClubShareholdingRepository extends JpaRepository<ClubShareholding, Long> {

    List<ClubShareholding> findAllByHumanId(long humanId);

    List<ClubShareholding> findAllByTeamId(long teamId);

    Optional<ClubShareholding> findByHumanIdAndTeamId(long humanId, long teamId);
}
