package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;

public record ClubEquityQuote(BigDecimal referencePrice, BigDecimal marketNoise, BigDecimal quotedPrice,
                              String algorithmVersion) { }
