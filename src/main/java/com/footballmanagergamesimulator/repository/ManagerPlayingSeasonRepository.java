package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerPlayingSeason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerPlayingSeasonRepository extends JpaRepository<ManagerPlayingSeason, Long> {

    List<ManagerPlayingSeason> findAllByManagerIdOrderBySeasonOrderAsc(long managerId);

    void deleteAllByManagerId(long managerId);
}
