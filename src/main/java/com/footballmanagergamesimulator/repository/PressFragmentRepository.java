package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressFragment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PressFragmentRepository extends JpaRepository<PressFragment, Long> {

    List<PressFragment> findAllByEventTypeAndCategoryAndBucket(String eventType, String category, String bucket);

    /** Fragments loaded before articles had an event type: they all belong to the appointment article. */
    List<PressFragment> findAllByEventTypeIsNull();
}
