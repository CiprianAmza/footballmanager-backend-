package com.footballmanagergamesimulator.press;

import com.footballmanagergamesimulator.model.PressConferenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3 — deterministic generator + catalog. Pure unit test, no Spring context. */
class PressConferenceGeneratorTest {

    private PressConferenceGenerator generator;

    @BeforeEach
    void setup() {
        PressConferenceCatalogService catalogService = new PressConferenceCatalogService();
        catalogService.load(); // loads press/press-conference-catalog-v1.json from the classpath
        generator = new PressConferenceGenerator();
        generator.catalog = catalogService;
    }

    private PressContext preMatchContext() {
        PressContext c = new PressContext();
        c.setOpponentName("Rivals FC");
        c.addKey("HOME");
        c.addKey("SQUAD_VALUE_FAVOURITE");
        c.addKey("FORM_POOR");
        c.addKey("DERBY");
        c.addKey("TABLE_TOP");
        return c;
    }

    @Test
    void sameSeedAndContextProduceIdenticalQuestions() {
        PressContext c = preMatchContext();
        long seed = PressConferenceGenerator.deterministicSeed(
                "pc-v1", PressConferenceType.PRE_MATCH, "1:15:20:100-200", 100, 200, 15, 20);

        List<PressGeneratedQuestion> first = generator.generate(PressConferenceType.PRE_MATCH, c, seed);
        List<PressGeneratedQuestion> second = generator.generate(PressConferenceType.PRE_MATCH, c, seed);

        assertThat(ids(first)).isEqualTo(ids(second));
        assertThat(prompts(first)).isEqualTo(prompts(second));
        assertThat(answerIds(first)).isEqualTo(answerIds(second));
    }

    @Test
    void countIsBetweenThreeAndSixWithNoDuplicates() {
        PressContext c = preMatchContext();
        long seed = PressConferenceGenerator.deterministicSeed(
                "pc-v1", PressConferenceType.PRE_MATCH, "fx-A", 1, 2, 3, 4);

        List<PressGeneratedQuestion> qs = generator.generate(PressConferenceType.PRE_MATCH, c, seed);

        assertThat(qs.size()).isBetween(3, 6);
        assertThat(ids(qs)).doesNotHaveDuplicates();
        assertThat(qs.stream().map(PressGeneratedQuestion::getContextKey).collect(Collectors.toList()))
                .doesNotHaveDuplicates();
    }

    @Test
    void eligibilityGatesContextSpecificQuestions() {
        // Without DERBY the derby question can never appear.
        PressContext noDerby = new PressContext();
        noDerby.addKey("HOME");
        noDerby.addKey("SQUAD_VALUE_UNDERDOG");
        noDerby.addKey("FORM_POOR");
        for (long s = 0; s < 50; s++) {
            List<PressGeneratedQuestion> qs = generator.generate(PressConferenceType.PRE_MATCH, noDerby, s);
            assertThat(ids(qs)).doesNotContain("PRE_DERBY_TENSION");
        }
    }

    @Test
    void differentFixtureKeyChangesSeed() {
        long a = PressConferenceGenerator.deterministicSeed("pc-v1", PressConferenceType.PRE_MATCH, "fx-A", 1, 2, 3, 4);
        long b = PressConferenceGenerator.deterministicSeed("pc-v1", PressConferenceType.PRE_MATCH, "fx-B", 1, 2, 3, 4);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void postMatchWinContextTemplatesAndSelects() {
        PressContext c = new PressContext();
        c.setTeamScore(3);
        c.setOpponentScore(1);
        c.addKey("RESULT_WIN");
        c.addKey("POSSESSION_DOMINANT");
        c.addKey("HOME");
        long seed = PressConferenceGenerator.deterministicSeed(
                "pc-v1", PressConferenceType.POST_MATCH, "1:15:20:100-200", 100, 200, 15, 20);

        List<PressGeneratedQuestion> qs = generator.generate(PressConferenceType.POST_MATCH, c, seed);
        assertThat(qs).isNotEmpty();
        // POSSESSION_DOMINANT question is gated noneOf RESULT_WIN, so must be absent here.
        assertThat(ids(qs)).doesNotContain("POST_POSSESSION_DOMINANT_NO_WIN");
    }

    private List<String> ids(List<PressGeneratedQuestion> qs) {
        return qs.stream().map(PressGeneratedQuestion::getCatalogQuestionId).collect(Collectors.toList());
    }

    private List<String> prompts(List<PressGeneratedQuestion> qs) {
        return qs.stream().map(PressGeneratedQuestion::getPromptText).collect(Collectors.toList());
    }

    private List<String> answerIds(List<PressGeneratedQuestion> qs) {
        return qs.stream()
                .flatMap(q -> q.getAnswers().stream().map(PressGeneratedAnswer::getCatalogAnswerId))
                .collect(Collectors.toList());
    }
}
