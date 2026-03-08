package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Sponsorship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SponsorshipRepository extends JpaRepository<Sponsorship, Long> {

    List<Sponsorship> findAllByTeamIdAndStatus(long teamId, String status);

    List<Sponsorship> findAllByTeamId(long teamId);
}
