package com.footballmanagergamesimulator.compartment.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative audit: no production source <em>outside</em> the {@code compartment} package may reference
 * the pure adapter or the pure calculators/formulas. This guarantees Phase&nbsp;2 adds no runtime
 * wiring into any match/scoring path &mdash; the adapter remains reachable only from tests.
 */
class CompartmentAdapterRuntimeIsolationTest {

    private static final List<String> GUARDED_TYPES = List.of(
            "CompartmentDomainAdapter",
            "DomainSnapshotFactory",
            "DomainPlayerSnapshot",
            "PlayerAttributeMapping",
            "ContextCoefficientMapper",
            "TacticalContextInput",
            "ContextCoefficientMapping",
            "ContextualPlayerRatingCalculator",
            "DefensiveExposureFormula",
            "GoalProbabilityFormula",
            "CompartmentMath");

    @Test
    void noRuntimeCallsiteReferencesTheAdapterOrPureCalculators() {
        Path root = Path.of("src", "main", "java");
        assertThat(root).exists();

        Map<String, List<String>> offenders = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/compartment/"))
                    .forEach(p -> {
                        String content = read(p);
                        for (String type : GUARDED_TYPES) {
                            if (containsIdentifier(content, type)) {
                                offenders.computeIfAbsent(root.relativize(p).toString(), k -> new java.util.ArrayList<>())
                                        .add(type);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(offenders)
                .as("no production file outside the compartment package may reference the adapter/calculators")
                .isEmpty();
    }

    private static boolean containsIdentifier(String content, String identifier) {
        int from = 0;
        while (true) {
            int idx = content.indexOf(identifier, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isIdentifierPart(content.charAt(idx - 1));
            int end = idx + identifier.length();
            boolean rightOk = end >= content.length() || !isIdentifierPart(content.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
