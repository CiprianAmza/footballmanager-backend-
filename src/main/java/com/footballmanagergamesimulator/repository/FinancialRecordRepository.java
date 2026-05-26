package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FinancialRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FinancialRecordRepository extends JpaRepository<FinancialRecord, Long> {

    List<FinancialRecord> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    @Query("SELECT COALESCE(SUM(f.amount), 0) FROM FinancialRecord f WHERE f.teamId = :teamId AND f.seasonNumber = :seasonNumber AND f.category = :category")
    long sumByTeamIdAndSeasonNumberAndCategory(long teamId, int seasonNumber, String category);

    @Query("SELECT COALESCE(SUM(f.amount), 0) FROM FinancialRecord f WHERE f.teamId = :teamId AND f.seasonNumber = :seasonNumber")
    long sumByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
}
