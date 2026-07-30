package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reflective probe for the Faza 2 contract that does not exist yet.
 *
 * <p>The acceptance gates must be RUNNABLE and HONESTLY RED before the production code
 * lands (AI_HANDOFF rev. 8 ask B). They therefore cannot reference
 * {@code AmbientSegmentCompiler} / {@code LiveMatchAdvanceDelta} by type — that would not
 * compile — and they must not be faked with a test-only implementation. Instead every
 * acceptance test starts by resolving the contract by name; when the type or method is
 * missing the test FAILS with a message that says exactly what Faza 2 still owes.
 *
 * <p><b>The names below are the plan's contract</b>
 * ({@code MATCH_2D_ENGINE_PLAN.md} §Faza 2 + {@code AI_HANDOFF.md} rev. 6 answers 1-2 and
 * rev. 8 ask B). If the implementation deliberately picks different names or a different
 * entry point, this file is the single place to update — in the same change, never by
 * loosening an assertion.
 */
final class Faza2ContractProbe {

    private Faza2ContractProbe() {}

    static final String AMBIENT_COMPILER = "com.footballmanagergamesimulator.animation.AmbientSegmentCompiler";
    static final String AMBIENT_SEED = "com.footballmanagergamesimulator.animation.AmbientSeed";
    static final String AMBIENT_SEGMENT_SPEC = "com.footballmanagergamesimulator.animation.AmbientSegmentSpec";
    static final String AMBIENT_SEGMENT_DATA = "com.footballmanagergamesimulator.frontend.AmbientSegmentData";
    static final String ADVANCE_DELTA = "com.footballmanagergamesimulator.frontend.LiveMatchAdvanceDelta";

    private static final String NOT_IMPLEMENTED =
            "FAZA 2 NOT IMPLEMENTED (this red is the specification, not a regression). ";

    /** Resolve a Faza 2 type by name, or fail with what is still owed. */
    static Class<?> requireType(String fqn, String gate, String why) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            return fail(NOT_IMPLEMENTED + gate + ": expected type " + fqn + " — " + why
                    + " See MATCH_2D_ENGINE_PLAN.md §Faza 2. If the implementation chose another "
                    + "name, update Faza2ContractProbe in the same change.");
        }
    }

    /** Assert a DTO exposes the agreed properties (field or getter), or fail listing the gaps. */
    static void requireProperties(Class<?> type, List<String> properties, String gate) {
        TreeSet<String> missing = new TreeSet<>();
        for (String property : properties) {
            if (!hasProperty(type, property)) missing.add(property);
        }
        if (!missing.isEmpty()) {
            fail(NOT_IMPLEMENTED + gate + ": " + type.getName() + " is missing the agreed "
                    + "properties " + missing + ". The DTO contract from AI_HANDOFF rev. 6 answer 1 is "
                    + properties + ".");
        }
    }

    private static boolean hasProperty(Class<?> type, String property) {
        for (var field : type.getDeclaredFields()) {
            if (field.getName().equals(property)) return true;
        }
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getName().equals(property) || m.getName().equals("get" + suffix)
                    || m.getName().equals("is" + suffix)) return true;
        }
        return false;
    }

    /** The single static seed-derivation entry point required to be domain-separated. */
    static Method requireAmbientSeedDerive(String gate) {
        Class<?> seed = requireType(AMBIENT_SEED, gate,
                "ambient seeds must be domain-separated from AnimationSeed (rev. 6 answer 2).");
        for (Method m : seed.getMethods()) {
            if (m.getName().equals("derive") && m.getReturnType() == long.class
                    && m.getParameterCount() == 4) {
                return m;
            }
        }
        return fail(NOT_IMPLEMENTED + gate + ": " + AMBIENT_SEED + " must expose "
                + "static long derive(long planSeed, String fixtureKey, int minute, int ambientVersion); "
                + "found only " + signatures(seed));
    }

    /**
     * Turn ambient generation on/off for a harness. The gate requires a real toggle so the
     * ON-vs-OFF comparison is possible at all.
     */
    static void setAmbientEnabled(MatchEngineConfig config, boolean enabled, String gate) {
        Method accessor = null;
        for (Method m : MatchEngineConfig.class.getMethods()) {
            if (m.getParameterCount() == 0 && m.getName().equals("getAmbient")) accessor = m;
        }
        if (accessor == null) {
            fail(NOT_IMPLEMENTED + gate + ": MatchEngineConfig exposes no ambient toggle. Gate 1 "
                    + "compares the same fixture with ambient generation enabled and disabled, so a "
                    + "real switch (match.engine.ambient.enabled) must exist.");
        }
        try {
            Object holder = accessor.invoke(config);
            holder.getClass().getMethod("setEnabled", boolean.class).invoke(holder, enabled);
        } catch (ReflectiveOperationException e) {
            fail(NOT_IMPLEMENTED + gate + ": MatchEngineConfig.getAmbient() has no setEnabled(boolean): "
                    + e);
        }
    }

    /**
     * Locate the opt-in delta entry point. Per rev. 6 answer 1 the delta is a
     * response-only representation of {@code POST /advance}, so it must be reachable from
     * the live session or the live service without going through the legacy full payload.
     */
    static DeltaEntryPoint requireDeltaEntryPoint(String gate) {
        Class<?> delta = requireType(ADVANCE_DELTA, gate,
                "the versioned delta response DTO of the opt-in /advance representation.");
        for (Method m : LiveMatchSession.class.getMethods()) {
            if (m.getReturnType() == delta && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == int.class) {
                return (harness, session, minute) -> m.invoke(session, minute);
            }
        }
        for (Method m : LiveMatchSimulationService.class.getMethods()) {
            if (m.getReturnType() != delta) continue;
            if (m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class
                    && m.getParameterTypes()[1] == int.class) {
                return (harness, session, minute) ->
                        m.invoke(harness.service, Faza2GateHarness.LIVE_KEY, minute);
            }
        }
        return fail(NOT_IMPLEMENTED + gate + ": no delta entry point. Expected either "
                + "LiveMatchSession#<name>(int untilMinute) or "
                + "LiveMatchSimulationService#<name>(String liveKey, int untilMinute) returning "
                + ADVANCE_DELTA + ". Found on LiveMatchSession: " + signatures(LiveMatchSession.class));
    }

    /** Read a property (field or getter) off a Faza 2 DTO instance. */
    static Object property(Object target, String name) {
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of(name, "get" + suffix, "is" + suffix)) {
            try {
                Method m = target.getClass().getMethod(candidate);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (ReflectiveOperationException ignored) {
                // try the next accessor shape
            }
        }
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            return fail("Faza 2 DTO " + target.getClass().getName() + " exposes no property '"
                    + name + "': " + e);
        }
    }

    static int intProperty(Object target, String name) {
        return ((Number) property(target, name)).intValue();
    }

    @SuppressWarnings("unchecked")
    static List<Object> listProperty(Object target, String name) {
        Object value = property(target, name);
        if (value == null) return List.of();
        if (value instanceof List<?> list) return (List<Object>) list;
        return fail("Faza 2 DTO property '" + name + "' should be a List, was " + value.getClass());
    }

    private static List<String> signatures(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            out.add(m.getReturnType().getSimpleName() + " " + m.getName()
                    + Arrays.toString(Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName).toArray()));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Invokes the opt-in delta advance for a session in a harness. */
    @FunctionalInterface
    interface DeltaEntryPoint {
        Object advance(Faza2GateHarness harness, LiveMatchSession session, int untilMinute)
                throws ReflectiveOperationException;
    }
}
