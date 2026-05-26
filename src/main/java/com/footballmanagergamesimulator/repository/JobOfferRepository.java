package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.JobOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {

    List<JobOffer> findAllByUserIdAndStatus(int userId, String status);

    List<JobOffer> findAllByUserId(int userId);

    List<JobOffer> findAllByStatus(String status);
}
