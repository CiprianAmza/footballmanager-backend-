package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressConferenceAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PressConferenceAnswerRepository extends JpaRepository<PressConferenceAnswer, Long> {

    List<PressConferenceAnswer> findAllBySessionId(long sessionId);

    /** At most one answer per question — the guard that makes answering idempotent. */
    Optional<PressConferenceAnswer> findByQuestionId(long questionId);
}
