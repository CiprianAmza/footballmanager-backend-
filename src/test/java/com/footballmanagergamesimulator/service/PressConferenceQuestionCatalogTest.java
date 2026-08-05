package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PressConference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PressConferenceQuestionCatalogTest {

    @Test
    void providesLargeDistinctQuestionPoolsForEveryMatchContext() {
        assertCatalog("PRE_MATCH", 25);
        assertCatalog("WIN", 20);
        assertCatalog("DRAW", 20);
        assertCatalog("LOSS", 20);
    }

    @Test
    void coversRecurringRomanianFootballMediaThemes() {
        String allQuestions = String.join(" ", List.of("PRE_MATCH", "WIN", "DRAW", "LOSS").stream()
                        .flatMap(phase -> PressConferenceService.questionCatalog(phase).stream())
                        .toList())
                .toLowerCase(Locale.ROOT);

        assertThat(allQuestions).contains(
                "referee", "transfer market", "dressing room", "board",
                "supporters", "starting eleven", "resigning", "tactical");
    }

    @Test
    void selectsStableQuestionsAndStoresOnlyOneWithinTheDatabaseColumnLimit() {
        List<String> questions = PressConferenceService.questionCatalog("LOSS");
        String first = PressConferenceService.selectQuestion(questions, 86, 8, 12, 4, "POST_MATCH:LOSS");
        String again = PressConferenceService.selectQuestion(questions, 86, 8, 12, 4, "POST_MATCH:LOSS");

        assertThat(first).isEqualTo(again);
        assertThat("POST_MATCH:LOSS|" + first).hasSizeLessThan(256);
    }

    @Test
    void extractsTheDisplayedQuestionFromPreAndPostMatchTopics() {
        PressConferenceService service = new PressConferenceService();
        PressConference preMatch = new PressConference();
        preMatch.setTopic("PRE_MATCH:Are you satisfied with recent form?");
        PressConference postMatch = new PressConference();
        postMatch.setTopic("POST_MATCH:LOSS|Do you still have the backing of the board?");
        PressConference legacySave = new PressConference();
        legacySave.setTopic("PRE_MATCH:First saved question?|Second saved question?");

        assertThat(service.questionFor(preMatch)).isEqualTo("Are you satisfied with recent form?");
        assertThat(service.questionFor(postMatch)).isEqualTo("Do you still have the backing of the board?");
        assertThat(service.questionFor(legacySave)).isEqualTo("First saved question?");
    }

    private void assertCatalog(String phase, int minimumSize) {
        List<String> questions = PressConferenceService.questionCatalog(phase);
        assertThat(questions).hasSizeGreaterThanOrEqualTo(minimumSize);
        assertThat(questions).doesNotHaveDuplicates();
        assertThat(questions).allSatisfy(question -> {
            assertThat(question).isNotBlank();
            assertThat(question.length()).isLessThan(220);
            assertThat(question).endsWith("?");
        });
    }
}
