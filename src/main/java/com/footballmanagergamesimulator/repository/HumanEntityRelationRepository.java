package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.HumanEntityRelation;
import com.footballmanagergamesimulator.model.RelationParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HumanEntityRelationRepository extends JpaRepository<HumanEntityRelation, Long> {

    Optional<HumanEntityRelation> findBySubjectTypeAndSubjectIdAndTargetTypeAndTargetId(
            RelationParty subjectType, long subjectId, RelationParty targetType, long targetId);

    List<HumanEntityRelation> findAllBySubjectTypeIn(Collection<RelationParty> subjectTypes);
}
