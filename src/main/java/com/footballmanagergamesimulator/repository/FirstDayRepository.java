package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FirstDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FirstDayRepository extends JpaRepository<FirstDay, Long> {

    Optional<FirstDay> findTopByTeamIdAndDoneFalseOrderByIdDesc(long teamId);

    List<FirstDay> findAllByTeamIdAndDoneFalse(long teamId);
}
