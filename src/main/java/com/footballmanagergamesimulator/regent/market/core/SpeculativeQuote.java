package com.footballmanagergamesimulator.regent.market.core;

import java.math.BigDecimal;

public record SpeculativeQuote(DailyReturn dailyReturn, BigDecimal closingPrice) { }
