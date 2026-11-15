package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.YouthPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface YouthPlayerRepository extends JpaRepository<YouthPlayer, Long> {

    @Query("select y.name from YouthPlayer y where y.status = 'IN_ACADEMY' and y.name is not null")
    List<String> findAcademyNames();

    List<YouthPlayer> findAllByTeamIdAndStatus(long teamId, String status);

    List<YouthPlayer> findAllByTeamId(long teamId);
}
