package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MeetingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeetingRecordRepository extends JpaRepository<MeetingRecord, Long> {

    Optional<MeetingRecord> findByTeamIdAndSeasonAndType(long teamId, int season, String type);

    List<MeetingRecord> findAllByTeamIdAndTypeAndSeasonGreaterThanEqual(long teamId, String type, int season);

    List<MeetingRecord> findAllByManagerIdAndTypeAndOutcome(long managerId, String type, String outcome);

    List<MeetingRecord> findAllByTypeAndOutcome(String type, String outcome);
}
