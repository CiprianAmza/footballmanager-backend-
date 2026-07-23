package com.footballmanagergamesimulator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process, dependency-free observability for the Phase 0 provenance layer.
 *
 * <p>The project has no Micrometer/Actuator dependency at base {@code 90cf15b};
 * introducing one is out of Phase 0 scope. These atomic counters mirror the
 * plan's required signals — stamps per source kind, reconciliation mismatches,
 * duplicates prevented, fixture-key collisions resolved, and canonical vs legacy
 * read routes — without a new dependency. It is silent at rest: callers only
 * touch it on the provenance-v2 path, so a flag-OFF runtime records nothing and
 * logs nothing.
 */
@Component
public class AnalyticsProvenanceMetrics {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsProvenanceMetrics.class);

    private final Map<String, LongAdder> stampsBySourceKind = new ConcurrentHashMap<>();
    private final LongAdder duplicatesPrevented = new LongAdder();
    private final LongAdder reconciliationMismatches = new LongAdder();
    private final LongAdder fixtureKeyCollisions = new LongAdder();
    private final LongAdder canonicalReads = new LongAdder();
    private final LongAdder legacyReads = new LongAdder();

    public void recordStamp(SourceKind kind) {
        stampsBySourceKind.computeIfAbsent(kind.name(), k -> new LongAdder()).increment();
        log.debug("provenance stamp recorded kind={}", kind);
    }

    public void recordDuplicatePrevented(String fixtureKey) {
        duplicatesPrevented.increment();
        log.debug("provenance duplicate prevented fixtureKey={}", fixtureKey);
    }

    public void recordReconciliationMismatch(String fixtureKey, String detail) {
        reconciliationMismatches.increment();
        log.warn("provenance reconciliation mismatch fixtureKey={} detail={}", fixtureKey, detail);
    }

    public void recordFixtureKeyCollision(String fixtureKey) {
        fixtureKeyCollisions.increment();
        log.debug("provenance fixture-key collision resolved fixtureKey={}", fixtureKey);
    }

    public void recordCanonicalRead() {
        canonicalReads.increment();
    }

    public void recordLegacyRead() {
        legacyReads.increment();
    }

    public long stamps(SourceKind kind) {
        LongAdder adder = stampsBySourceKind.get(kind.name());
        return adder == null ? 0L : adder.sum();
    }

    public long duplicatesPrevented() {
        return duplicatesPrevented.sum();
    }

    public long reconciliationMismatches() {
        return reconciliationMismatches.sum();
    }

    public long fixtureKeyCollisions() {
        return fixtureKeyCollisions.sum();
    }

    public long canonicalReads() {
        return canonicalReads.sum();
    }

    public long legacyReads() {
        return legacyReads.sum();
    }

    /** Read-only view for tests/diagnostics. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        stampsBySourceKind.forEach((k, v) -> out.put("stamp." + k, v.sum()));
        out.put("duplicatesPrevented", duplicatesPrevented.sum());
        out.put("reconciliationMismatches", reconciliationMismatches.sum());
        out.put("fixtureKeyCollisions", fixtureKeyCollisions.sum());
        out.put("canonicalReads", canonicalReads.sum());
        out.put("legacyReads", legacyReads.sum());
        return out;
    }
}
