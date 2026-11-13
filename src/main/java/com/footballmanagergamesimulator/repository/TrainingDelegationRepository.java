package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TrainingDelegation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrainingDelegationRepository extends JpaRepository<TrainingDelegation, Long> {

    List<TrainingDelegation> findAllByTeamId(long teamId);

    Optional<TrainingDelegation> findByTeamIdAndTrainingType(long teamId, String trainingType);
}
