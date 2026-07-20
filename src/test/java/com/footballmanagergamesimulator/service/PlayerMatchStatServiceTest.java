package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.PlayerAnalyticsView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faza 2 accumulation tests. Uses a stateful in-memory fake of
 * {@link PlayerSeasonStatRepository} so upsert (findBy + saveAll) round-trips, plus a
 * skills fake so the deterministic synthesis runs end-to-end.
 */
class PlayerMatchStatServiceTest {

    private PlayerMatchStatService statService;
    private PlayerAnalyticsService analyticsService;

    private InMemoryStatRepo statRepo;
    private PlayerSkillsRepository skillsRepo;
    private MatchEngineConfig engineConfig;

    private static final long COMP = 100L;
    private static final int SEASON = 3;

    @BeforeEach
    void setUp() {
        engineConfig = new MatchEngineConfig();
        statRepo = new InMemoryStatRepo();
        skillsRepo = mock(PlayerSkillsRepository.class);

        statService = new PlayerMatchStatService();
        statService.engineConfig = engineConfig;
        statService.playerSeasonStatRepository = statRepo;
        statService.playerSkillsRepository = skillsRepo;

        analyticsService = new PlayerAnalyticsService();
        analyticsService.engineConfig = engineConfig;
        analyticsService.playerSeasonStatRepository = statRepo;
        analyticsService.playerSkillsRepository = skillsRepo;
        analyticsService.scorerRepository = mock(ScorerRepository.class);
        analyticsService.humanRepository = mock(HumanRepository.class);
    }

    private PlayerSkills uniformSkills(long playerId, int value, String position) {
        PlayerSkills s = new PlayerSkills();
        s.setPlayerId(playerId);
        s.setPosition(position);
        for (String attr : PlayerSkillsService.GETTER_MAP.keySet()) {
            PlayerSkillsService.SETTER_MAP.get(attr).accept(s, value);
        }
        return s;
    }

    private PlayerView view(long id, String position) {
        PlayerView pv = new PlayerView();
        pv.setId(id);
        pv.setPosition(position);
        pv.setRating(150);
        return pv;
    }

    private PersonalizedTactic tactic(String pressing) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setPressing(pressing);
        return t;
    }

    @Test
    void recordingAMatch_incrementsAppearancesMinutesAndTallies() {
        PlayerSkills s = uniformSkills(1L, 14, "MC");
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(List.of(s));

        statService.recordRealMatchForTeam(7L, List.of(view(1L, "MC")), tactic("Standard"), COMP, SEASON);

        PlayerSeasonStat row = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(1L, COMP, SEASON).orElseThrow();
        assertThat(row.getAppearances()).isEqualTo(1);
        assertThat(row.getMinutes()).isEqualTo(90);
        assertThat(row.getTeamId()).isEqualTo(7L);
        assertThat(row.getPressures()).isGreaterThan(0.0);
        assertThat(row.getDefensiveActions()).isGreaterThan(0.0);
        assertThat(row.getPassesAttempted()).isGreaterThanOrEqualTo(row.getPassesCompleted());
        assertThat(row.getChancesCreated()).isGreaterThan(0.0);
        assertThat(row.getDribblesCompleted()).isGreaterThan(0.0);

        // Capture the single-match value before the upsert (the in-memory repo returns the SAME
        // mutable row instance, so `row` would otherwise alias the post-increment value).
        double pressuresAfterOne = row.getPressures();

        // A second match upserts (increments) the same row.
        statService.recordRealMatchForTeam(7L, List.of(view(1L, "MC")), tactic("Standard"), COMP, SEASON);
        PlayerSeasonStat after = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(1L, COMP, SEASON).orElseThrow();
        assertThat(after.getAppearances()).isEqualTo(2);
        assertThat(after.getMinutes()).isEqualTo(180);
        assertThat(after.getPressures()).isGreaterThan(pressuresAfterOne);
    }

    @Test
    void highPressing_yieldsMorePressuresThanLow() {
        PlayerSkills s = uniformSkills(2L, 14, "MC");
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(List.of(s));

        statService.recordRealMatchForTeam(1L, List.of(view(2L, "MC")), tactic("High"), COMP, SEASON);
        double highPressures = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(2L, COMP, SEASON)
                .orElseThrow().getPressures();

        statRepo.clear();
        statService.recordRealMatchForTeam(1L, List.of(view(2L, "MC")), tactic("Low"), COMP, SEASON);
        double lowPressures = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(2L, COMP, SEASON)
                .orElseThrow().getPressures();

        assertThat(highPressures).isGreaterThan(lowPressures);
    }

    @Test
    void moreDribblingInstruction_recordsMoreCompletedDribblesThanLess() {
        PlayerSkills skills = uniformSkills(4L, 16, "AML");
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(List.of(skills));
        PersonalizedTactic more = tactic("Standard");
        more.setDribbling("More");
        PersonalizedTactic less = tactic("Standard");
        less.setDribbling("Less");

        statService.recordRealMatchForTeam(1L, List.of(view(4L, "AML")), more, COMP, SEASON);
        double moreValue = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(4L, COMP, SEASON)
                .orElseThrow().getDribblesCompleted();
        statRepo.clear();
        statService.recordRealMatchForTeam(1L, List.of(view(4L, "AML")), less, COMP, SEASON);
        double lessValue = statRepo.findByPlayerIdAndCompetitionIdAndSeasonNumber(4L, COMP, SEASON)
                .orElseThrow().getDribblesCompleted();

        assertThat(moreValue).isGreaterThan(lessValue);
    }

    @Test
    void analytics_prefersAccumulated_whenAppearancesMeetThreshold() {
        int threshold = engineConfig.getAnalytics().getMinAppearances();
        PlayerSkills s = uniformSkills(3L, 14, "MC");
        when(skillsRepo.findAllByPlayerIdIn(any())).thenReturn(List.of(s));
        when(skillsRepo.findPlayerSkillsByPlayerId(3L)).thenReturn(Optional.of(s));
        lenient().when(analyticsService.humanRepository.findById(anyLong()))
                .thenReturn(Optional.of(new Human()));

        // Below threshold → projected (Faza 1).
        statService.recordRealMatchForTeam(1L, List.of(view(3L, "MC")), tactic("Standard"), COMP, SEASON);
        PlayerAnalyticsView projected = analyticsService.getPlayerAnalytics(3L, COMP, SEASON);
        assertThat(projected.isAccumulated()).isFalse();

        // Reach the threshold → accumulated (Faza 2).
        for (int i = 1; i < threshold; i++) {
            statService.recordRealMatchForTeam(1L, List.of(view(3L, "MC")), tactic("Standard"), COMP, SEASON);
        }
        PlayerAnalyticsView accumulated = analyticsService.getPlayerAnalytics(3L, COMP, SEASON);
        assertThat(accumulated.isAccumulated()).isTrue();
        assertThat(accumulated.getSampleAppearances()).isGreaterThanOrEqualTo(threshold);
        assertThat(accumulated.getMetrics()).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Stateful in-memory fake of PlayerSeasonStatRepository
    // ------------------------------------------------------------------

    private static class InMemoryStatRepo implements PlayerSeasonStatRepository {
        private final Map<String, PlayerSeasonStat> store = new LinkedHashMap<>();
        private long seq = 1;

        private String key(long p, long c, int s) { return p + ":" + c + ":" + s; }

        void clear() { store.clear(); }

        @Override
        public Optional<PlayerSeasonStat> findByPlayerIdAndCompetitionIdAndSeasonNumber(long playerId, long competitionId, int seasonNumber) {
            return Optional.ofNullable(store.get(key(playerId, competitionId, seasonNumber)));
        }

        @Override
        public List<PlayerSeasonStat> findAllByCompetitionIdAndSeasonNumber(long competitionId, int seasonNumber) {
            List<PlayerSeasonStat> out = new ArrayList<>();
            for (PlayerSeasonStat s : store.values()) {
                if (s.getCompetitionId() == competitionId && s.getSeasonNumber() == seasonNumber) out.add(s);
            }
            return out;
        }

        @Override
        public List<PlayerSeasonStat> findAllBySeasonNumber(int seasonNumber) {
            return store.values().stream().filter(row -> row.getSeasonNumber() == seasonNumber).toList();
        }

        @Override
        public <S extends PlayerSeasonStat> S save(S entity) {
            if (entity.getId() == 0) entity.setId(seq++);
            store.put(key(entity.getPlayerId(), entity.getCompetitionId(), entity.getSeasonNumber()), entity);
            return entity;
        }

        @Override
        public <S extends PlayerSeasonStat> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>();
            for (S e : entities) out.add(save(e));
            return out;
        }

        // --- Unused JpaRepository surface ---
        @Override public List<PlayerSeasonStat> findAll() { return new ArrayList<>(store.values()); }
        @Override public List<PlayerSeasonStat> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<PlayerSeasonStat> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public List<PlayerSeasonStat> findAllById(Iterable<Long> ids) { return new ArrayList<>(); }
        @Override public Optional<PlayerSeasonStat> findById(Long aLong) { return Optional.empty(); }
        @Override public boolean existsById(Long aLong) { return false; }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(Long aLong) {}
        @Override public void delete(PlayerSeasonStat entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends PlayerSeasonStat> entities) {}
        @Override public void deleteAll() { store.clear(); }
        @Override public void flush() {}
        @Override public <S extends PlayerSeasonStat> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends PlayerSeasonStat> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public void deleteAllInBatch(Iterable<PlayerSeasonStat> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public PlayerSeasonStat getOne(Long aLong) { return null; }
        @Override public PlayerSeasonStat getById(Long aLong) { return null; }
        @Override public PlayerSeasonStat getReferenceById(Long aLong) { return null; }
        @Override public <S extends PlayerSeasonStat> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends PlayerSeasonStat> List<S> findAll(org.springframework.data.domain.Example<S> example) { return new ArrayList<>(); }
        @Override public <S extends PlayerSeasonStat> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return new ArrayList<>(); }
        @Override public <S extends PlayerSeasonStat> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends PlayerSeasonStat> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends PlayerSeasonStat> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends PlayerSeasonStat, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    }
}
