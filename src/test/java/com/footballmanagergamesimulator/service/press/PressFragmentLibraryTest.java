package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import com.footballmanagergamesimulator.model.PressFragment;
import com.footballmanagergamesimulator.repository.PressFragmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PressFragmentLibraryTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern MANAGER_OPENS_SENTENCE = Pattern.compile("(^|[.!?:;]\\s+)\\{manager}");

    private static final Set<String> MATCH_VALUES = Set.of("team", "opponent", "score", "competition", "manager");
    private static final Set<String> NUMBER_VALUES = Set.of("team", "opponent", "possession", "shots", "shotsOnTarget",
            "opponentShots", "opponentShotsOnTarget");
    private static final Set<String> RUMOUR_VALUES = Set.of("player", "buyer", "seller", "fee");

    private final List<PressFragmentService.Line> library = new PressFragmentService(null).library();

    @Test
    @DisplayName("""
            GIVEN the shipped press files (appointment, common, one per voice)
            WHEN the library is read
            THEN it holds 1,897 fragments, 985 of them for the appointment article (lines without an event type);
             every journalist voice has, for the post-match article, a headline and a verdict for each of the 6
             results and a talking point for each outcome and tone, and for the rumour a headline and a body for
             both stages; the kickers and the match numbers fit every voice
            """)
    void everyVoiceCanWriteEveryArticle() {
        assertEquals(1_897, library.size());
        assertEquals(985, library.stream().filter(l -> PressFragmentService.APPOINTMENT.equals(l.eventType())).count());

        for (Analyst.Voice voice : Analyst.Voice.values()) {
            for (MatchReactionArticle.Result result : MatchReactionArticle.Result.values()) {
                assertEquals(6, count(PressFragmentService.POST_MATCH, voice.name(), "HEADLINE", result.name()));
                assertEquals(8, count(PressFragmentService.POST_MATCH, voice.name(), "VERDICT", result.name()));
                for (Analyst.Temperament tone : Analyst.Temperament.values()) {
                    assertEquals(4, count(PressFragmentService.POST_MATCH, voice.name(), "TALKING_POINT",
                            MatchReactionArticle.talkingPointBucket(result, tone)));
                }
            }
            for (String stage : List.of(TransferRumourArticle.STRONG, TransferRumourArticle.EARLY)) {
                assertEquals(4, count(PressFragmentService.TRANSFER_RUMOUR, voice.name(), "HEADLINE", stage));
                assertEquals(5, count(PressFragmentService.TRANSFER_RUMOUR, voice.name(), "BODY", stage));
            }
        }
        for (MatchReactionArticle.Result result : MatchReactionArticle.Result.values()) {
            assertEquals(8, count(PressFragmentService.POST_MATCH, null, "KICKER", result.name()));
            for (String side : List.of("_ON_TOP", "_SECOND_BEST")) {
                assertEquals(6, count(PressFragmentService.POST_MATCH, null, "STATS", result.outcome() + side));
            }
        }
    }

    @Test
    @DisplayName("""
            GIVEN every post-match and rumour fragment
            WHEN its text is checked
            THEN it uses only the values the press desk fills in for that section, never opens a sentence with the
             manager's name (it may read "the manager"), has no control character or doubled space, fits the
             1,000-character column, and its voice is one the game knows
            """)
    void fragmentsOnlyUseWhatTheDeskCanFill() {
        Set<String> voices = new java.util.HashSet<>();
        for (Analyst.Voice voice : Analyst.Voice.values()) voices.add(voice.name());

        for (PressFragmentService.Line line : library) {
            if (PressFragmentService.APPOINTMENT.equals(line.eventType())) continue;
            Set<String> allowed = PressFragmentService.TRANSFER_RUMOUR.equals(line.eventType()) ? RUMOUR_VALUES
                    : "STATS".equals(line.category()) ? NUMBER_VALUES : MATCH_VALUES;
            Matcher used = PLACEHOLDER.matcher(line.text());
            while (used.find()) assertTrue(allowed.contains(used.group(1)), line.text());
            assertFalse(MANAGER_OPENS_SENTENCE.matcher(line.text()).find(), line.text());
            assertTrue(line.text().chars().noneMatch(Character::isISOControl), line.text());
            assertFalse(line.text().contains("  "), line.text());
            assertTrue(line.text().length() <= 1_000, line.text());
            assertTrue(line.voice() == null || voices.contains(line.voice()), line.voice());
        }
    }

    @Test
    @DisplayName("""
            GIVEN a world created before reporters: 985 appointment fragments in the table, none with an event type
            WHEN the library is loaded, then loaded again once the table is complete
            THEN the old rows are marked APPOINTMENT and only the 912 new fragments are added; the second load adds
             nothing
            """)
    void olderWorldsReceiveOnlyTheNewFragments() {
        PressFragmentRepository repository = mock(PressFragmentRepository.class);
        PressFragmentService service = new PressFragmentService(repository);
        List<PressFragment> legacy = new ArrayList<>();
        for (PressFragmentService.Line line : library) {
            if (!PressFragmentService.APPOINTMENT.equals(line.eventType())) continue;
            PressFragment row = new PressFragment();
            row.setCategory(line.category());
            row.setBucket(line.bucket());
            row.setText(line.text());
            legacy.add(row);
        }
        when(repository.count()).thenReturn(985L);
        when(repository.findAllByEventTypeIsNull()).thenReturn(legacy);
        when(repository.findAll()).thenReturn(legacy);

        assertEquals(912, service.ensureLoaded());

        assertTrue(legacy.stream().allMatch(row -> PressFragmentService.APPOINTMENT.equals(row.getEventType())));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PressFragment>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveAll(saved.capture());
        List<PressFragment> added = saved.getAllValues().get(1);
        assertEquals(912, added.size());
        assertTrue(added.stream().noneMatch(row -> PressFragmentService.APPOINTMENT.equals(row.getEventType())));

        PressFragmentRepository complete = mock(PressFragmentRepository.class);
        when(complete.count()).thenReturn(1_897L);
        assertEquals(0, new PressFragmentService(complete).ensureLoaded());
        verify(complete, never()).saveAll(any());
    }

    @Test
    @DisplayName("""
            GIVEN a bucket holding two tabloid fragments (one already used), one agency fragment and one written
             for every voice
            WHEN a tabloid and then an insider journalist ask for the next fragment
            THEN the tabloid man gets his unused one, marked used today; the insider, who has none of his own,
             gets the one written for every voice; a bucket with nothing for him gives nothing
            """)
    void aJournalistWritesFromHisOwnVoiceFirst() {
        PressFragmentRepository repository = mock(PressFragmentRepository.class);
        PressFragmentService service = new PressFragmentService(repository);
        PressFragment usedTabloid = fragment(1, "TABLOID", "used tabloid", 1);
        PressFragment freshTabloid = fragment(2, "TABLOID", "fresh tabloid", 0);
        PressFragment agency = fragment(3, "AGENCY", "agency", 0);
        PressFragment common = fragment(4, null, "for everyone", 0);
        when(repository.findAllByEventTypeAndCategoryAndBucket("POST_MATCH", "VERDICT", "WIN"))
                .thenReturn(List.of(usedTabloid, freshTabloid, agency, common));
        when(repository.findAllByEventTypeAndCategoryAndBucket("POST_MATCH", "VERDICT", "LOSS"))
                .thenReturn(List.of(agency));

        Optional<String> tabloid = service.next("POST_MATCH", "TABLOID", "VERDICT", "WIN", 1_107, new Random(1));
        Optional<String> insider = service.next("POST_MATCH", "INSIDER", "VERDICT", "WIN", 1_107, new Random(1));
        Optional<String> nothing = service.next("POST_MATCH", "INSIDER", "VERDICT", "LOSS", 1_107, new Random(1));

        assertEquals(Optional.of("fresh tabloid"), tabloid);
        assertEquals(1, freshTabloid.getUses());
        assertEquals(1_107, freshTabloid.getLastUsedDay());
        assertEquals(Optional.of("for everyone"), insider);
        assertEquals(Optional.empty(), nothing);
        assertEquals(0, agency.getUses());
    }

    private long count(String eventType, String voice, String category, String bucket) {
        return library.stream()
                .filter(l -> eventType.equals(l.eventType()) && category.equals(l.category()) && bucket.equals(l.bucket()))
                .filter(l -> voice == null ? l.voice() == null : voice.equals(l.voice()))
                .count();
    }

    private static PressFragment fragment(long id, String voice, String text, int uses) {
        PressFragment fragment = new PressFragment();
        fragment.setId(id);
        fragment.setEventType("POST_MATCH");
        fragment.setCategory("VERDICT");
        fragment.setBucket("WIN");
        fragment.setVoice(voice);
        fragment.setText(text);
        fragment.setUses(uses);
        return fragment;
    }
}
