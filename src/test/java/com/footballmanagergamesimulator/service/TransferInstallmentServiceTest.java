package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferInstallmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
@Import(TransferInstallmentService.class)
class TransferInstallmentServiceTest {

    @Autowired private TransferInstallmentService installments;
    @Autowired private TransferInstallmentRepository rows;
    @Autowired private TeamRepository teams;
    @MockBean private FinanceService financeService;

    @Test
    @DisplayName("""
            GIVEN a transfer in season 3 with 2 instalments of 300,000 from my club to the seller
            WHEN seasons 4 and 5 start (and season 4 again)
            THEN one instalment is paid at each season start, never twice: budgets move by 300,000 each time
            """)
    void instalmentsArePaidOneASeason() {
        long buyer = teams.save(team(1_000_000)).getId();
        long seller = teams.save(team(0)).getId();
        installments.schedule(7, "Kvekrpur", buyer, seller, 2, 300_000, 3);

        assertEquals(1, installments.payDue(4));
        assertEquals(0, installments.payDue(4));
        assertEquals(700_000, teams.findById(buyer).orElseThrow().getTransferBudget());
        assertEquals(300_000, teams.findById(seller).orElseThrow().getTransferBudget());

        assertEquals(1, installments.payDue(5));
        assertEquals(400_000, teams.findById(buyer).orElseThrow().getTransferBudget());
        verify(financeService, times(2)).recordExpense(eq(buyer), anyInt(), eq(1), eq("TRANSFER_INSTALMENT"),
                anyString(), eq(300_000L));
        assertEquals(2, rows.findAll().stream().filter(r -> r.isPaid()).count());
    }

    private static Team team(long budget) {
        Team t = new Team();
        t.setName("T");
        t.setTransferBudget(budget);
        return t;
    }
}
