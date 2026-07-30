package com.footballmanagergamesimulator.nameGenerator;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Picks a per-nation phonetic style. Callers historically pass a competitionId
 * (e.g. {@code team.getCompetitionId()}), so the id is first resolved to its
 * nation via {@link Competition#getNationId()}; an id with no competition row is
 * treated as a nationId directly. Nation ids follow the bootstrap seed mirrored
 * in {@code NationService}: 1=Gallactick, 2=Dong, 3=Khess, 6=Literature.
 */
@Component
public class CompositeNameGenerator implements NameGeneratorStrategy {

    private static final long GALLACTICK_NATION_ID = 1L;
    private static final long DONG_NATION_ID = 2L;
    private static final long KHESS_NATION_ID = 3L;
    private static final long LITERATURE_NATION_ID = 6L;

    private final CompetitionRepository competitionRepository;
    private final Map<Long, NameGeneratorStrategy> strategiesByNation;
    private final NameGeneratorStrategy defaultStrategy;

    public CompositeNameGenerator(CompetitionRepository competitionRepository) {
        this.competitionRepository = competitionRepository;
        this.defaultStrategy = new ElevenNameGenerator();
        this.strategiesByNation = Map.of(
                GALLACTICK_NATION_ID, defaultStrategy,
                DONG_NATION_ID, new VardNameGenerator(),
                KHESS_NATION_ID, new KessNameGenerator(),
                LITERATURE_NATION_ID, new LiraNameGenerator());
    }

    @Override
    public String generateName(long competitionOrNationId) {
        long nationId = resolveNationId(competitionOrNationId);
        return strategyFor(nationId).generateName(nationId);
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
