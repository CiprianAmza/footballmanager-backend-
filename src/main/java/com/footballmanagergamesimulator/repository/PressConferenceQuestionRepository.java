package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressConferenceQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PressConferenceQuestionRepository extends JpaRepository<PressConferenceQuestion, Long> {

    List<PressConferenceQuestion> findAllBySessionIdOrderByOrderIndexAsc(long sessionId);
}
