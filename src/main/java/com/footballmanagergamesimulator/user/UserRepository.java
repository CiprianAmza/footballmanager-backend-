package com.footballmanagergamesimulator.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUsername(String userName);

    Optional<User> findByUsernameIgnoreCase(String userName);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String userName);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findById(int ID);

    List<User> findAllByTeamId(Long teamId);

}
