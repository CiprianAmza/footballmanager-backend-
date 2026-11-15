package com.footballmanagergamesimulator.nameGenerator;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpandedNationNameTest {
    private static final Map<Long, NameStyle> STYLES = Map.of(
            11L, NameStyles.VEYRA, 12L, NameStyles.BRANIK, 13L, NameStyles.SILETH);

    @Test
    void completeSquadsAndStaffReceiveDistinctNationalNamesIncludingUnsavedBatches() {
        for (var entry : STYLES.entrySet()) {
            HumanRepository people = mock(HumanRepository.class);
            YouthPlayerRepository academy = mock(YouthPlayerRepository.class);
            List<String> persisted = new ArrayList<>();
            when(people.findActiveNames()).thenAnswer(call -> new ArrayList<>(persisted));
            when(academy.findAcademyNames()).thenReturn(List.of());
            CompositeNameGenerator names = new CompositeNameGenerator(
                    mock(CompetitionRepository.class), people, academy);
            Set<String> generated = new HashSet<>();
            for (int i = 0; i < 360; i++) { // 12 clubs * (22 players + 7 staff + manager)
                String name = names.generateNameForNation(entry.getKey());
                assertTrue(generated.add(name), "duplicate in the full national allocation: " + name);
                String[] parts = name.split(" ");
                assertEquals(2, parts.length);
                assertTrue(entry.getValue().pool(NameStyles.GIVEN).contains(parts[0]));
                assertTrue(entry.getValue().pool(NameStyles.FAMILY).contains(" " + parts[1]));
                assertFalse(NameStyles.REFERENCE_NAMES.get(entry.getKey()).contains(name));
                if (i % 22 == 21) { // mirrors bulk persistence of an entire squad
                    persisted.clear();
                    persisted.addAll(generated);
                }
            }
            persisted.clear();
            persisted.addAll(generated);
            CompositeNameGenerator restarted = new CompositeNameGenerator(
                    mock(CompetitionRepository.class), people, academy);
            assertFalse(generated.contains(restarted.generateNameForNation(entry.getKey())),
                    "loading a save must not reset uniqueness to an empty in-memory set");
        }
    }

    @Test
    void lastAvailablePairIsFoundAndExhaustionDoesNotLoopOrAddDigits() {
        NameStyle style = NameStyles.VEYRA;
        List<String> occupied = new ArrayList<>();
        for (String first : style.pool(NameStyles.GIVEN)) {
            for (String family : style.pool(NameStyles.FAMILY)) occupied.add(first + family);
        }
        String lastFree = occupied.remove(occupied.size() - 1);
        HumanRepository people = mock(HumanRepository.class);
        when(people.findActiveNames()).thenReturn(occupied.subList(0, occupied.size() - 1));
        YouthPlayerRepository academy = mock(YouthPlayerRepository.class);
        when(academy.findAcademyNames()).thenReturn(List.of("  " + occupied.get(occupied.size() - 1).toUpperCase() + "  "));
        CompositeNameGenerator names = new CompositeNameGenerator(mock(CompetitionRepository.class), people, academy);
        assertEquals(lastFree, names.generateNameForNation(11));
        assertThrows(IllegalStateException.class, () -> names.generateNameForNation(11));
    }

    @Test
    void nationAndCompetitionIdsCannotBeConfusedForTheNewCountries() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        Competition oldLeague = new Competition();
        oldLeague.setNationId(0);
        Competition newLeague = new Competition();
        newLeague.setNationId(11);
        when(competitions.findById(11L)).thenReturn(Optional.of(oldLeague));
        when(competitions.findById(18L)).thenReturn(Optional.of(newLeague));
        CompositeNameGenerator names = new CompositeNameGenerator(competitions);
        assertFalse(names.generateName(11).contains(" "));
        assertTrue(names.generateNameForNation(11).contains(" "));
        assertTrue(names.generateName(18).contains(" "));
    }

    @Test
    void allApprovedExampleNamesAreRepresentableByTheirPattern() {
        assertExamples(NameStyles.VEYRA, "Aven Saloren", "Neris Vaelo", "Maelis Orava", "Lio Mavess", "Teren Ilvar", "Sova Narel");
        assertExamples(NameStyles.BRANIK, "Dorn Vradek", "Odran Tesk", "Dren Volbak", "Irdan Zemrat", "Tovan Drazel", "Udro Belm");
        assertExamples(NameStyles.SILETH, "Saevi Qelith", "Izel Vaes", "Eshai Xorun", "Thesin Ulai", "Orya Shezel", "Nemei Azhar");
    }

    @Test
    void uncommittedNamesStayReservedUntilCommitOrRollbackEvenAfterTheOwnerReadsThem() throws Exception {
        for (int completion : List.of(TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_ROLLED_BACK)) {
            List<String> committed = new ArrayList<>();
            for (String given : NameStyles.VEYRA.pool(NameStyles.GIVEN)) {
                for (String family : NameStyles.VEYRA.pool(NameStyles.FAMILY)) committed.add(given + family);
            }
            Set<String> twoAvailable = Set.of(committed.remove(committed.size() - 1),
                    committed.remove(committed.size() - 1));
            List<String> ownerInserts = new ArrayList<>();
            Thread owner = Thread.currentThread();
            HumanRepository people = mock(HumanRepository.class);
            when(people.findActiveNames()).thenAnswer(call -> {
                List<String> visible = new ArrayList<>(committed);
                if (Thread.currentThread() == owner) visible.addAll(ownerInserts);
                return visible;
            });
            YouthPlayerRepository academy = mock(YouthPlayerRepository.class);
            when(academy.findAcademyNames()).thenReturn(List.of());
            CompositeNameGenerator names = new CompositeNameGenerator(mock(CompetitionRepository.class), people, academy);
            var otherWriter = Executors.newSingleThreadExecutor();
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                ownerInserts.add(names.generateNameForNation(11));
                ownerInserts.add(names.generateNameForNation(11));
                assertEquals(twoAvailable, new HashSet<>(ownerInserts));
                otherWriter.submit(() -> assertThrows(IllegalStateException.class,
                        () -> names.generateNameForNation(11))).get(5, TimeUnit.SECONDS);

                if (completion == TransactionSynchronization.STATUS_COMMITTED) committed.addAll(ownerInserts);
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(callback -> callback.afterCompletion(completion));
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
                ownerInserts.clear();
                if (completion == TransactionSynchronization.STATUS_COMMITTED) {
                    assertThrows(IllegalStateException.class, () -> names.generateNameForNation(11));
                } else {
                    assertTrue(twoAvailable.contains(names.generateNameForNation(11)),
                            "rollback must release the unpersisted names for a retry");
                }
            } finally {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.clearSynchronization();
                }
                TransactionSynchronizationManager.setActualTransactionActive(false);
                otherWriter.shutdownNow();
            }
        }
    }

    private void assertExamples(NameStyle style, String... examples) {
        assertEquals(24, new HashSet<>(style.pool(NameStyles.GIVEN)).size());
        assertEquals(32, new HashSet<>(style.pool(NameStyles.FAMILY)).size());
        for (String example : examples) {
            int space = example.indexOf(' ');
            assertTrue(style.pool(NameStyles.GIVEN).contains(example.substring(0, space)));
            assertTrue(style.pool(NameStyles.FAMILY).contains(example.substring(space)));
        }
    }
}
