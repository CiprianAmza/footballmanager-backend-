package com.footballmanagergamesimulator.press.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** A catalog answer variant offered for a {@link PressCatalogQuestion}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressCatalogAnswer {
    private String id;
    /** Response code shared with the legacy modal / boardroom vocabulary. */
    private String code;
    private String tone;
    private String stance;
    private int weight = 1;
    private PressEligibility eligibility = new PressEligibility();
    private List<PressEffectSpec> effects = List.of();
}
