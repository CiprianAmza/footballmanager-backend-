package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Ownership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OwnershipRepository extends JpaRepository<Ownership, Long> {

    List<Ownership> findAllByHumanId(long humanId);

    List<Ownership> findAllByTeamId(long teamId);

    Optional<Ownership> findByHumanIdAndTeamId(long humanId, long teamId);
}
