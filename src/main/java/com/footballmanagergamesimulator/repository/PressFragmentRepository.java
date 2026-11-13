package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressFragment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PressFragmentRepository extends JpaRepository<PressFragment, Long> {

    List<PressFragment> findAllByCategoryAndBucket(String category, String bucket);
}
