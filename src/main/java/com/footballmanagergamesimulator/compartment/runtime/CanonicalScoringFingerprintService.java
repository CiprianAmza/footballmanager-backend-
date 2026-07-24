package com.footballmanagergamesimulator.compartment.runtime;

import com.footballmanagergamesimulator.compartment.adapter.CanonicalLineupPlayer;
import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.matchplan.ScoreEngineKind;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, entity-free identity material for persisted canonical decisions. */
@Service
public final class CanonicalScoringFingerprintService {

    public String configFingerprint(CompartmentEngineConfig compartmentConfig,
                                    MatchEngineConfig matchConfig) {
        return sha256(canonical("compartment", compartmentConfig, Set.of("enabled", "shadowEnabled"))
                + canonical("tacticalModel", matchConfig.getTacticalModel(), Set.of()));
    }

    public String inputFingerprint(CanonicalRuntimeScoringService.RuntimeScoringRequest request,
                                   CanonicalRuntimeTeamInput home,
                                   CanonicalRuntimeTeamInput away) {
        StringBuilder material = new StringBuilder();
        material.append("fixtureKey=").append(request.fixtureKey()).append(';')
                .append("competitionId=").append(request.competitionId()).append(';')
                .append("season=").append(request.season()).append(';')
                .append("round=").append(request.round()).append(';')
                .append(canonical("homeTactic", request.homeTactic(), Set.of()))
                .append(canonical("awayTactic", request.awayTactic(), Set.of()))
                .append(canonical("homeInput", home, Set.of()))
                .append(canonical("awayInput", away, Set.of()));
        return sha256(material.toString());
    }

    public String legacyInputFingerprint(String fixtureKey, long competitionId, int season, int round,
                                         long homeTeamId, long awayTeamId, ScoreEngineKind engine) {
        return sha256("legacy|fixture=" + fixtureKey + "|competition=" + competitionId
                + "|season=" + season + "|round=" + round + "|home=" + homeTeamId
                + "|away=" + awayTeamId + "|engine=" + engine);
    }

    private static String canonical(String name, Object value, Set<String> exclusions) {
        return name + '=' + canonicalValue(value, exclusions, new IdentityHashMap<>()) + ';';
    }

    private static String canonicalValue(Object value, Set<String> exclusions,
                                         IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Character || value instanceof Boolean
                || value instanceof Number || value.getClass().isEnum()) {
            return value.getClass().getName() + ':' + String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, String> ordered = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ordered.put(canonicalValue(entry.getKey(), Set.of(), visiting),
                        canonicalValue(entry.getValue(), Set.of(), visiting));
            }
            return ordered.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) items.add(canonicalValue(item, Set.of(), visiting));
            return items.toString();
        }
        if (value.getClass().isArray()) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(canonicalValue(Array.get(value, i), Set.of(), visiting));
            }
            return items.toString();
        }
        if (visiting.put(value, Boolean.TRUE) != null) return "<cycle>";
        try {
            TreeMap<String, String> fields = new TreeMap<>();
            for (Field field : allFields(value.getClass())) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                        || exclusions.contains(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    fields.put(field.getName(), canonicalValue(field.get(value), Set.of(), visiting));
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("Cannot fingerprint " + field, ex);
                }
            }
            return value.getClass().getName() + fields;
        } finally {
            visiting.remove(value);
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        fields.sort(Comparator.comparing(Field::getName));
        return fields;
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
