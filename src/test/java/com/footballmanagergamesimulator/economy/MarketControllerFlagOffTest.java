package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketControllerFlagOffTest {

    @Test
    void marketEndpointsReturnTypedFlagOffErrorWhileRegentIsDisabled() throws Exception {
        RegentEconomyProperties properties = new RegentEconomyProperties();
        properties.setEnabled(false);
        MarketController controller = new MarketController(
                mock(CurrentUserService.class),
                mock(PersonProfileService.class),
                mock(PersonalAccountingService.class),
                mock(MarketTradingService.class),
                mock(MarketQueryService.class),
                mock(TraderAdviserService.class),
                properties,
                mock(GameCalendarRepository.class)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new EconomyApiExceptionHandler())
                .build();

        mockMvc.perform(get("/api/market/instruments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGENT_FEATURE_DISABLED"));
    }
}
