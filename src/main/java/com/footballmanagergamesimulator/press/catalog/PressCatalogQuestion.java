package com.footballmanagergamesimulator.press.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** A catalog question with its offered answer variants. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressCatalogQuestion {
    private String id;
    /** "PRE_MATCH" or "POST_MATCH". */
    private String type;
    /** Primary context this question belongs to (deduped on during selection). */
    private String contextKey;
    private int weight = 1;
    private PressEligibility eligibility = new PressEligibility();
    private String prompt;
    private List<PressCatalogAnswer> answers = List.of();
}
