package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.HumanEntityRelation;
import com.footballmanagergamesimulator.model.RelationParty;
import com.footballmanagergamesimulator.repository.HumanEntityRelationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes {@link HumanEntityRelation} rows: 0–100, 50 when nothing happened between the two yet. */
@Service
public class RelationService {

    public static final int NEUTRAL = 50;
    public static final int MIN = 0;
    public static final int MAX = 100;

    @Autowired HumanEntityRelationRepository repository;

    /** One relation change, for {@link #changeAll}. */
    public record Change(RelationParty subjectType, long subjectId, RelationParty targetType, long targetId, int delta) {
    }

    public int relation(RelationParty subjectType, long subjectId, RelationParty targetType, long targetId) {
        return repository.findBySubjectTypeAndSubjectIdAndTargetTypeAndTargetId(subjectType, subjectId, targetType, targetId)
                .map(HumanEntityRelation::getRelation)
                .orElse(NEUTRAL);
    }

    /** Many changes at once (season-end bonds): one read of the rows involved, one save. */
    @Transactional
    public void changeAll(java.util.Collection<Change> changes) {
        if (changes.isEmpty()) return;
        java.util.Set<RelationParty> subjects = java.util.EnumSet.noneOf(RelationParty.class);
        changes.forEach(c -> subjects.add(c.subjectType()));
        java.util.Map<String, HumanEntityRelation> rows = new java.util.HashMap<>();
        for (HumanEntityRelation row : repository.findAllBySubjectTypeIn(subjects)) {
            rows.put(key(row.getSubjectType(), row.getSubjectId(), row.getTargetType(), row.getTargetId()), row);
        }
        for (Change c : changes) {
            HumanEntityRelation row = rows.computeIfAbsent(key(c.subjectType(), c.subjectId(), c.targetType(), c.targetId()), k -> {
                HumanEntityRelation fresh = new HumanEntityRelation();
                fresh.setSubjectType(c.subjectType());
                fresh.setSubjectId(c.subjectId());
                fresh.setTargetType(c.targetType());
                fresh.setTargetId(c.targetId());
                fresh.setRelation(NEUTRAL);
                return fresh;
            });
            row.setRelation(Math.max(MIN, Math.min(MAX, row.getRelation() + c.delta())));
        }
        repository.saveAll(rows.values());
    }

    private static String key(RelationParty subjectType, long subjectId, RelationParty targetType, long targetId) {
        return subjectType + ":" + subjectId + ">" + targetType + ":" + targetId;
    }

    /** Moves the relation by {@code delta}, kept within 0–100; returns the new value. */
    @Transactional
    public int change(RelationParty subjectType, long subjectId, RelationParty targetType, long targetId, int delta) {
        HumanEntityRelation row = repository
                .findBySubjectTypeAndSubjectIdAndTargetTypeAndTargetId(subjectType, subjectId, targetType, targetId)
                .orElseGet(() -> {
                    HumanEntityRelation fresh = new HumanEntityRelation();
                    fresh.setSubjectType(subjectType);
                    fresh.setSubjectId(subjectId);
                    fresh.setTargetType(targetType);
                    fresh.setTargetId(targetId);
                    fresh.setRelation(NEUTRAL);
                    return fresh;
                });
        row.setRelation(Math.max(MIN, Math.min(MAX, row.getRelation() + delta)));
        return repository.save(row).getRelation();
    }
}
