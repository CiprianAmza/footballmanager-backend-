package com.footballmanagergamesimulator.service.press;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.Analyst;
import com.footballmanagergamesimulator.model.PressFragment;
import com.footballmanagergamesimulator.repository.PressFragmentRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * The library of pre-written press fragments (design DESIGN_START_CARIERA §6.1 and §6.1.1): loaded from the
 * {@code press/} resources and rotated, least used and longest unused first. A fragment belongs to one kind of
 * article ({@link #APPOINTMENT}, {@link #POST_MATCH}, {@link #TRANSFER_RUMOUR}) and is either written in one
 * journalist voice or fits them all.
 */
@Service
public class PressFragmentService {

    public static final String APPOINTMENT = "APPOINTMENT";
    public static final String POST_MATCH = "POST_MATCH";
    public static final String TRANSFER_RUMOUR = "TRANSFER_RUMOUR";

    /** The appointment article: its lines carry no event type and no voice. */
    static final String RESOURCE = "press/appointment-fragments.json";
    /** Fragments that fit every voice (headline kickers, match numbers). */
    static final String COMMON_RESOURCE = "press/common-fragments.json";

    private final PressFragmentRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile List<Line> library;

    public PressFragmentService(PressFragmentRepository repository) {
        this.repository = repository;
    }

    record Line(String eventType, String category, String bucket, String voice, String text) {

        String key() {
            return eventType + '|' + category + '|' + bucket + '|' + voice + '|' + text;
        }
    }

    /** Every shipped file: the appointment article, the common fragments and one file per voice. */
    static List<String> resources() {
        List<String> resources = new ArrayList<>(List.of(RESOURCE, COMMON_RESOURCE));
        for (Analyst.Voice voice : Analyst.Voice.values()) {
            resources.add("press/voices/" + voice.name().toLowerCase(Locale.ROOT) + ".json");
        }
        return resources;
    }

    /** The shipped fragments, read once. A line without an event type belongs to the appointment article. */
    List<Line> library() {
        List<Line> loaded = library;
        if (loaded != null) return loaded;
        List<Line> lines = new ArrayList<>();
        for (String resource : resources()) {
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                for (Line line : objectMapper.readValue(in, new TypeReference<List<Line>>() {})) {
                    lines.add(line.eventType() != null ? line
                            : new Line(APPOINTMENT, line.category(), line.bucket(), line.voice(), line.text()));
                }
            } catch (IOException missing) {
                throw new IllegalStateException("Press fragments are missing: " + resource, missing);
            }
        }
        library = List.copyOf(lines);
        return library;
    }

    /**
     * Adds the shipped fragments the table does not have yet (all of them on a new world; the newer files on a
     * world created before they existed). Returns how many were added.
     */
    @Transactional
    public int ensureLoaded() {
        List<Line> lines = library();
        if (repository.count() >= lines.size()) return 0;
        List<PressFragment> untagged = repository.findAllByEventTypeIsNull();
        if (!untagged.isEmpty()) {
            untagged.forEach(row -> row.setEventType(APPOINTMENT));
            repository.saveAll(untagged);
        }
        Set<String> present = new HashSet<>();
        for (PressFragment row : repository.findAll()) {
            present.add(new Line(row.getEventType(), row.getCategory(), row.getBucket(), row.getVoice(), row.getText()).key());
        }
        List<PressFragment> rows = new ArrayList<>();
        for (Line line : lines) {
            if (!present.add(line.key())) continue;
            PressFragment row = new PressFragment();
            row.setEventType(line.eventType());
            row.setCategory(line.category());
            row.setBucket(line.bucket());
            row.setVoice(line.voice());
            row.setText(line.text());
            rows.add(row);
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /** The next fragment of the appointment article (no voice). */
    @Transactional
    public Optional<String> next(String category, String bucket, int today, Random rng) {
        return next(APPOINTMENT, null, category, bucket, today, rng);
    }

    /**
     * The next fragment of a kind: among the least used, the one unused for longest; ties broken by {@code rng}.
     * Fragments written in {@code voice} come first; without any, the ones that fit every voice.
     * Marks it used today. Empty when the library has none of that kind.
     */
    @Transactional
    public Optional<String> next(String eventType, String voice, String category, String bucket, int today, Random rng) {
        List<PressFragment> candidates = forVoice(
                repository.findAllByEventTypeAndCategoryAndBucket(eventType, category, bucket), voice);
        if (candidates.isEmpty()) return Optional.empty();
        int fewestUses = candidates.stream().mapToInt(PressFragment::getUses).min().orElse(0);
        List<PressFragment> least = candidates.stream().filter(f -> f.getUses() == fewestUses).toList();
        int oldest = least.stream().mapToInt(PressFragment::getLastUsedDay).min().orElse(0);
        List<PressFragment> pool = least.stream().filter(f -> f.getLastUsedDay() == oldest)
                .sorted(Comparator.comparingLong(PressFragment::getId)).toList();
        PressFragment chosen = pool.get(rng.nextInt(pool.size()));
        chosen.setUses(chosen.getUses() + 1);
        chosen.setLastUsedDay(today);
        repository.save(chosen);
        return Optional.of(chosen.getText());
    }

    /** The fragments written in {@code voice}; when there are none, the ones written for every voice. */
    static List<PressFragment> forVoice(List<PressFragment> fragments, String voice) {
        if (voice != null) {
            List<PressFragment> own = fragments.stream().filter(f -> voice.equals(f.getVoice())).toList();
            if (!own.isEmpty()) return own;
        }
        return fragments.stream().filter(f -> f.getVoice() == null).toList();
    }
}
