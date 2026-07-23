package com.footballmanagergamesimulator.press.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** Root of a versioned press-conference question/answer catalog. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PressCatalog {
    /** Catalog version; also used as the session's generatorVersion. */
    private String version;
    private List<PressCatalogQuestion> questions = List.of();
}
