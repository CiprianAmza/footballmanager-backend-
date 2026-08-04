package com.footballmanagergamesimulator.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUiPreferenceServiceTest {

    private final UserUiPreferenceRepository repository = mock(UserUiPreferenceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserUiPreferenceService service = new UserUiPreferenceService(repository, objectMapper);

    @Test
    void savesPreferencesForTheAuthenticatedUserScope() {
        ObjectNode value = objectMapper.createObjectNode().put("position", "GK");
        when(repository.findByUserIdAndPreferenceKey(7, "transfers.market"))
                .thenReturn(Optional.empty());
        when(repository.save(any(UserUiPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserUiPreferenceService.PreferenceView result = service.save(7, "transfers.market", value);

        assertEquals("GK", result.value().get("position").asText());
        verify(repository).save(any(UserUiPreference.class));
    }

    @Test
    void rejectsInvalidKeysAndNonObjectValues() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(1, "../another-user", objectMapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(1, "transfers.market", objectMapper.createArrayNode()));
    }
}
