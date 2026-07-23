package com.footballmanagergamesimulator.press.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Declarative eligibility predicate over the context keys present in a session's
 * frozen context snapshot. A missing/empty predicate is always eligible.
 *
 * <ul>
 *   <li>{@code allOf} — every listed key must be present.</li>
 *   <li>{@code anyOf} — at least one listed key must be present.</li>
 *   <li>{@code noneOf} — none of the listed keys may be present.</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressEligibility {
    private List<String> allOf = List.of();
    private List<String> anyOf = List.of();
    private List<String> noneOf = List.of();

    public boolean matches(java.util.Set<String> contextKeys) {
        if (allOf != null && !contextKeys.containsAll(allOf)) return false;
        if (anyOf != null && !anyOf.isEmpty() && anyOf.stream().noneMatch(contextKeys::contains)) return false;
        if (noneOf != null && noneOf.stream().anyMatch(contextKeys::contains)) return false;
        return true;
    }
}
