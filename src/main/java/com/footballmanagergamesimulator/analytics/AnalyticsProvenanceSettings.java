package com.footballmanagergamesimulator.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feature flag for Analytics Option B, Phase 0 (canonical identity, provenance
 * and parity gate). Mirrors the {@code AnimationV3Settings} precedent exactly.
 *
 * <p>Default OFF and mandatory to keep OFF for this phase. While OFF: no
 * provenance rows are written on any commit path, the v2 read API reports every
 * fixture as {@code LEGACY_UNVERSIONED}, and no legacy (v1) response changes. The
 * Java initializer mirrors the {@code @Value} default so the component is usable
 * without Spring wiring in a plain unit test.
 */
@Component
public class AnalyticsProvenanceSettings {

    @Value("${match.analytics.provenance-v2.enabled:false}")
    private boolean enabled = false;

    public boolean enabled() {
        return enabled;
    }
}
