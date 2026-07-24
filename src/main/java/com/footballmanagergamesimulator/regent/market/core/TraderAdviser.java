package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Immutable hireable adviser terms; hiring and salary payment are intentionally outside this phase. */
public record TraderAdviser(String adviserId, int skill, int reputation, BigDecimal salaryPerDay,
                            LocalDate contractStart, LocalDate contractEnd, String modelVersion) {
    public TraderAdviser {
        adviserId = SafeCompanyProfile.requireText(adviserId, "adviserId");
        if (skill < 0 || skill > 100 || reputation < 0 || reputation > 100) {
            throw new IllegalArgumentException("skill and reputation must be in [0, 100]");
        }
        salaryPerDay = Objects.requireNonNull(salaryPerDay, "salaryPerDay");
        if (salaryPerDay.signum() < 0) throw new IllegalArgumentException("salaryPerDay must not be negative");
        contractStart = Objects.requireNonNull(contractStart, "contractStart");
        contractEnd = Objects.requireNonNull(contractEnd, "contractEnd");
        if (contractEnd.isBefore(contractStart)) throw new IllegalArgumentException("contractEnd must not precede contractStart");
        modelVersion = SafeCompanyProfile.requireText(modelVersion, "modelVersion");
    }

    public boolean activeOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(contractStart) && !date.isAfter(contractEnd);
    }
}
