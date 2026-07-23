package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapServiceStayForwardTest {

    @Test
    void warmSaveBackfillMarksOnlyCanonicalStayForwardPlayersAndIsIdempotent() {
        HumanRepository humanRepository = mock(HumanRepository.class);
        BootstrapService service = new BootstrapService();
        ReflectionTestUtils.setField(service, "humanRepository", humanRepository);

        Human kvekrpur = player("Kvekrpur", false);
        Human dostoievski = player("Dostoievski", false);
        Human shakespeare = player("Shakespeare", false);
        Human kabutov = player("Kabutov", false);
        Human alreadyMarked = player("Kvekrpur", true);
        List<Human> players = List.of(kvekrpur, dostoievski, shakespeare, kabutov, alreadyMarked);

        when(humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)).thenReturn(players);

        assertThat(service.ensureSpecialPlayersStayForward()).isEqualTo(3);
        assertThat(kvekrpur.isStayForward()).isTrue();
        assertThat(dostoievski.isStayForward()).isTrue();
        assertThat(shakespeare.isStayForward()).isTrue();
        assertThat(alreadyMarked.isStayForward()).isTrue();
        assertThat(kabutov.isStayForward()).isFalse();

        assertThat(service.ensureSpecialPlayersStayForward()).isZero();
        verify(humanRepository, times(1)).saveAll(List.of(kvekrpur, dostoievski, shakespeare));
    }

    private Human player(String name, boolean stayForward) {
        Human player = new Human();
        player.setName(name);
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setStayForward(stayForward);
        return player;
    }
}
