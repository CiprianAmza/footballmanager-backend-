package com.footballmanagergamesimulator.person;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonProfileRepository extends JpaRepository<PersonProfile, Long> {
    Optional<PersonProfile> findByUserId(Integer userId);
    Optional<PersonProfile> findByHumanId(Long humanId);
}
