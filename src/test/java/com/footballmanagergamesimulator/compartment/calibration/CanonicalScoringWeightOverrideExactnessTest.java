package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoringWeightOverrideExactnessTest {
    @Test
    void everyNumericOverrideChangesOnlyItsRequestedConfigurationField() {
        var profile = CalibrationConfigFixture.load();
        var catalog = CanonicalScoringWeightCatalog.from(profile.compartment(), profile.match());

        for (var leaf : catalog.leafWeights()) {
            var baseline = CanonicalScoringWeightSet.baseline(profile.compartment(), profile.match());
            var tested = baseline.override(catalog,
                    new CanonicalScoringWeightOverride(leaf.path(), CanonicalWeightPerturbation.validAlternative(leaf)));
            var changed = changed(numericSnapshot(baseline.compartment(), baseline.match()),
                    numericSnapshot(tested.compartment(), tested.match()));
            boolean constrainedPair = leaf.path().matches("compartment\\.mentalities\\.[^.]+\\.midfield-to-attack");
            assertThat(changed).as("override %s", leaf.path())
                    .hasSize(constrainedPair ? 2 : 1);
            if (constrainedPair) {
                assertThat(changed.keySet()).anyMatch(path -> path.endsWith("midfieldToAttack"));
                assertThat(changed.keySet()).anyMatch(path -> path.endsWith("midfieldToDefense"));
            } else {
                String expectedField = normalize(leaf.path().substring(leaf.path().lastIndexOf('.') + 1));
                assertThat(changed.keySet()).anyMatch(path -> normalize(path.substring(path.lastIndexOf('.') + 1))
                        .equals(expectedField));
            }
        }
    }

    @Test
    void snapshotDetectsAControlledSecondaryMutation() {
        var profile = CalibrationConfigFixture.load();
        var set = CanonicalScoringWeightSet.baseline(profile.compartment(), profile.match());
        var before = numericSnapshot(set.compartment(), set.match());
        var rule = set.compartment().getMentalities().get(com.footballmanagergamesimulator.compartment.Mentality.BALANCED);
        rule.setMidfieldToDefense(rule.getMidfieldToDefense() + 0.17);

        var changed = changed(before, numericSnapshot(set.compartment(), set.match()));
        assertThat(changed).containsKey("compartment.mentalities.BALANCED.midfieldToDefense");
        assertThat(changed).hasSize(1);
    }

    private static String normalize(String value) {
        return value.replace("-", "").replace("_", "").replace(" ", "").toUpperCase();
    }

    private static Map<String, Double> changed(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Double> changed = new LinkedHashMap<>();
        for (String path : before.keySet()) {
            if (!java.util.Objects.equals(before.get(path), after.get(path))) changed.put(path, after.get(path));
        }
        return changed;
    }

    private static Map<String, Double> numericSnapshot(CompartmentEngineConfig compartment, MatchEngineConfig match) {
        Map<String, Double> snapshot = new TreeMap<>();
        visit("compartment", compartment, snapshot, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        visit("match", match, snapshot, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        return snapshot;
    }

    private static void visit(String path, Object value, Map<String, Double> output, Set<Object> active) {
        if (value == null || value instanceof String || value instanceof Boolean || value.getClass().isEnum()) return;
        if (value instanceof Number number) {
            output.put(path, number.doubleValue());
            return;
        }
        if (!active.add(value)) throw new IllegalStateException("configuration cycle at " + path);
        if (value instanceof Map<?, ?> map) {
            map.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(String::valueOf)))
                    .forEach(entry -> visit(path + "." + entry.getKey(), entry.getValue(), output, active));
        } else if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) visit(path + "[" + index++ + "]", item, output, active);
        } else {
            for (Field field : fields(value.getClass())) {
                try {
                    field.setAccessible(true);
                    visit(path + "." + field.getName(), field.get(value), output, active);
                } catch (IllegalAccessException exception) {
                    throw new AssertionError("cannot snapshot " + path, exception);
                }
            }
        }
        active.remove(value);
    }

    private static java.util.List<Field> fields(Class<?> type) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) fields.add(field);
            }
        }
        fields.sort(java.util.Comparator.comparing(Field::getName));
        return fields;
    }
}
