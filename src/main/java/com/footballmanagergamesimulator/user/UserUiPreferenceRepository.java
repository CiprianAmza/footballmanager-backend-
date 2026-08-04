package com.footballmanagergamesimulator.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserUiPreferenceRepository extends JpaRepository<UserUiPreference, Long> {
    Optional<UserUiPreference> findByUserIdAndPreferenceKey(int userId, String preferenceKey);

    void deleteByUserIdAndPreferenceKey(int userId, String preferenceKey);
}
