package com.footballmanagergamesimulator.nameGenerator;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Picks a per-nation phonetic style. Callers historically pass a competitionId
 * (e.g. {@code team.getCompetitionId()}), so the id is first resolved to its
 * nation via {@link Competition#getNationId()}; an id with no competition row is
 * treated as a nationId directly. Nation ids follow the bootstrap seed mirrored
 * in {@code NationService}: 1=Gallactick, 2=Dong, 3=Khess, 6=Literature, 8=Mareval, 9=Tervala, 10=Altzair, 11=Veyra, 12=Branik, 13=Sileth.
 */
@Component
public class CompositeNameGenerator implements NameGeneratorStrategy {

    private static final long GALLACTICK_NATION_ID = 1L;
    private static final long DONG_NATION_ID = 2L;
    private static final long KHESS_NATION_ID = 3L;
    private static final long LITERATURE_NATION_ID = 6L;
    private static final long MAREVAL_NATION_ID = 8L;
    private static final long TERVALA_NATION_ID = 9L;
    private static final long ALTZAIR_NATION_ID = 10L;

    private final CompetitionRepository competitionRepository;
    private final Map<Long, NameGeneratorStrategy> strategiesByNation;
    private final NameGeneratorStrategy defaultStrategy;
    private final HumanRepository humanRepository;
    private final YouthPlayerRepository youthPlayerRepository;
    private final Set<String> pendingNames = new HashSet<>();
    private static final Map<Long, NameStyle> FULL_NAME_STYLES = Map.of(
            11L, NameStyles.VEYRA, 12L, NameStyles.BRANIK, 13L, NameStyles.SILETH);

    public CompositeNameGenerator(CompetitionRepository competitionRepository) {
        this(competitionRepository, null, null);
    }

    @Autowired
    public CompositeNameGenerator(CompetitionRepository competitionRepository,
                                  HumanRepository humanRepository, YouthPlayerRepository youthPlayerRepository) {
        this.competitionRepository = competitionRepository;
        this.humanRepository = humanRepository;
        this.youthPlayerRepository = youthPlayerRepository;
        this.defaultStrategy = new ElevenNameGenerator();
        this.strategiesByNation = Map.of(
                GALLACTICK_NATION_ID, defaultStrategy,
                DONG_NATION_ID, new VardNameGenerator(),
                KHESS_NATION_ID, new KessNameGenerator(),
                LITERATURE_NATION_ID, new LiraNameGenerator(),
                MAREVAL_NATION_ID, new MarevalNameGenerator(),
                TERVALA_NATION_ID, new TervalaNameGenerator(),
                ALTZAIR_NATION_ID, new AltzairNameGenerator(),
                11L, new FragmentNameGenerator(NameStyles.VEYRA),
                12L, new FragmentNameGenerator(NameStyles.BRANIK),
                13L, new FragmentNameGenerator(NameStyles.SILETH));
    }

    @Override
    public String generateName(long competitionOrNationId) {
        long nationId = resolveNationId(competitionOrNationId);
        return generateNameForNation(nationId);
    }

    /** Name for a nation id that must not be read as a competition id (e.g. agents). */
    public synchronized String generateNameForNation(long nationId) {
        NameStyle style = FULL_NAME_STYLES.get(nationId);
        if (style == null) return strategyFor(nationId).generateName(nationId);

        // Refresh from persistence so save loads, academy promotions and retired people do not leave
        // a stale lifetime-wide registry. Reservations cover callers that save a whole batch later.
        Set<String> unavailable = new HashSet<>();
        if (humanRepository != null) humanRepository.findActiveNames().forEach(name -> unavailable.add(normalize(name)));
        if (youthPlayerRepository != null) youthPlayerRepository.findAcademyNames().forEach(name -> unavailable.add(normalize(name)));
        // A transaction can see its own inserts before other writers can. Keep those names
        // reserved until completion; otherwise a concurrent allocator could reuse one.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            pendingNames.removeAll(unavailable);
        }
        unavailable.addAll(pendingNames);
        NameStyles.REFERENCE_NAMES.getOrDefault(nationId, java.util.List.of())
                .forEach(name -> unavailable.add(normalize(name)));

        for (int attempt = 0; attempt < 64; attempt++) {
            String candidate = strategyFor(nationId).generateName(nationId);
            if (!unavailable.contains(normalize(candidate))) return reserve(candidate);
        }
        // Bounded fallback guarantees progress even when only one of the 768 pairs is still free.
        for (String given : style.pool(NameStyles.GIVEN)) {
            for (String family : style.pool(NameStyles.FAMILY)) {
                String candidate = given + family;
                if (!unavailable.contains(normalize(candidate))) return reserve(candidate);
            }
        }
        throw new IllegalStateException("No unused player name remains for nation " + nationId
                + "; expand the given/family pools before generating more people");
    }

    private String reserve(String candidate) {
        String normalized = normalize(candidate);
        pendingNames.add(normalized);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    synchronized (CompositeNameGenerator.this) {
                        // Commit makes the persisted row visible; rollback frees the unused pair.
                        pendingNames.remove(normalized);
                    }
                }
            });
        }
        return candidate;
    }

    /** Call only at a serialized world-load/import boundary, after outstanding batches end. */
    public synchronized void clearPendingReservations() {
        pendingNames.clear();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    long resolveNationId(long competitionOrNationId) {
        return competitionRepository.findById(competitionOrNationId)
                .map(Competition::getNationId)
                .orElse(competitionOrNationId);
    }

    NameGeneratorStrategy strategyFor(long nationId) {
        return strategiesByNation.getOrDefault(nationId, defaultStrategy);
    }
}
