package com.footballmanagergamesimulator.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") int userId);

    Optional<User> findByUsername(String userName);

    Optional<User> findByUsernameIgnoreCase(String userName);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String userName);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findById(int ID);

    List<User> findAllByTeamId(Long teamId);

}
