package com.footballmanagergamesimulator.service.press;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.PressFragment;
import com.footballmanagergamesimulator.repository.PressFragmentRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The library of pre-written press fragments (design DESIGN_START_CARIERA §6.1): loaded once from
 * {@code press/appointment-fragments.json} and rotated, least used and longest unused first.
 */
@Service
public class PressFragmentService {

    static final String RESOURCE = "press/appointment-fragments.json";

    private final PressFragmentRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PressFragmentService(PressFragmentRepository repository) {
        this.repository = repository;
    }

    record Line(String category, String bucket, String text) {
    }

    /** Fills the table from the shipped file when it is empty. Returns how many fragments were added. */
    @Transactional
    public int ensureLoaded() {
        if (repository.count() > 0) return 0;
        List<Line> lines;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            lines = objectMapper.readValue(in, new TypeReference<List<Line>>() {});
        } catch (IOException missing) {
            throw new IllegalStateException("Press fragments are missing: " + RESOURCE, missing);
        }
        List<PressFragment> rows = new ArrayList<>();
        for (Line line : lines) {
            PressFragment row = new PressFragment();
            row.setCategory(line.category());
            row.setBucket(line.bucket());
            row.setText(line.text());
            rows.add(row);
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * The next fragment of a kind: among the least used, the one unused for longest; ties broken by {@code rng}.
     * Marks it used today. Empty when the library has none of that kind.
     */
    @Transactional
    public Optional<String> next(String category, String bucket, int today, Random rng) {
        List<PressFragment> candidates = repository.findAllByCategoryAndBucket(category, bucket);
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
}
