package com.footballmanagergamesimulator.press;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.press.catalog.PressCatalog;
import com.footballmanagergamesimulator.press.catalog.PressCatalogQuestion;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads the versioned press-conference catalog from the classpath once at
 * startup and exposes it read-only. No LLM/runtime generation — everything is
 * static, versioned content authored in {@code resources/press/*.json}.
 */
@Service
public class PressConferenceCatalogService {

    /** Active generator/catalog version. Frozen into each session. */
    public static final String CURRENT_GENERATOR_VERSION = "pc-v1";

    private static final String CATALOG_PATH = "press/press-conference-catalog-v1.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PressCatalog catalog;

    @PostConstruct
    void load() {
        this.catalog = loadFrom(CATALOG_PATH);
    }

    PressCatalog loadFrom(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            PressCatalog loaded = objectMapper.readValue(in, PressCatalog.class);
            validate(loaded);
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load press catalog: " + path, e);
        }
    }

    private void validate(PressCatalog loaded) {
        if (loaded.getVersion() == null || loaded.getVersion().isBlank()) {
            throw new IllegalStateException("Press catalog is missing a version");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PressCatalogQuestion q : loaded.getQuestions()) {
            if (q.getId() == null || q.getPrompt() == null || q.getType() == null) {
                throw new IllegalStateException("Press catalog question missing id/prompt/type");
            }
            if (!ids.add(q.getId())) {
                throw new IllegalStateException("Duplicate press catalog question id: " + q.getId());
            }
            if (q.getAnswers() == null || q.getAnswers().size() < 3) {
                throw new IllegalStateException("Question " + q.getId() + " must offer at least 3 answers");
            }
            java.util.Set<String> answerIds = new java.util.HashSet<>();
            for (var a : q.getAnswers()) {
                if (a.getId() == null || !answerIds.add(a.getId())) {
                    throw new IllegalStateException("Duplicate/missing answer id in question " + q.getId());
                }
            }
        }
    }

    public String version() {
        return catalog.getVersion();
    }

    /** All questions of a given type ("PRE_MATCH"/"POST_MATCH"), in stable catalog order. */
    public List<PressCatalogQuestion> questionsOfType(String type) {
        return catalog.getQuestions().stream()
                .filter(q -> type.equalsIgnoreCase(q.getType()))
                .collect(Collectors.toList());
    }

    public PressCatalogQuestion questionById(String id) {
        return catalog.getQuestions().stream()
                .filter(q -> q.getId().equals(id))
                .findFirst().orElse(null);
    }
}
