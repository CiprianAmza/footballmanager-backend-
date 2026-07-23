package com.footballmanagergamesimulator.economy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "regent")
public class RegentEconomyProperties {

    private boolean enabled;
    private final Economy economy = new Economy();
    private final Club club = new Club();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Economy getEconomy() {
        return economy;
    }

    public Club getClub() {
        return club;
    }

    public static class Economy {
        private String currency = "EUR";
        private long managerStartingWealth;
        private long chairmanStartingWealthDefault = 10_000_000L;
        private long chairmanStartingWealthMin = 1_000_000L;
        private long chairmanStartingWealthMax = 10_000_000_000L;

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public long getManagerStartingWealth() { return managerStartingWealth; }
        public void setManagerStartingWealth(long value) { this.managerStartingWealth = value; }
        public long getChairmanStartingWealthDefault() { return chairmanStartingWealthDefault; }
        public void setChairmanStartingWealthDefault(long value) { this.chairmanStartingWealthDefault = value; }
        public long getChairmanStartingWealthMin() { return chairmanStartingWealthMin; }
        public void setChairmanStartingWealthMin(long value) { this.chairmanStartingWealthMin = value; }
        public long getChairmanStartingWealthMax() { return chairmanStartingWealthMax; }
        public void setChairmanStartingWealthMax(long value) { this.chairmanStartingWealthMax = value; }
    }

    public static class Club {
        private String valuationVersion = "club-valuation-v1";
        private long minimumValuation = 1_000_000L;
        private long stadiumSeatValue = 250L;
        private long stadiumLevelValue = 2_000_000L;
        private long facilityLevelValue = 1_000_000L;
        private long reputationPointValue = 50_000L;
        private int performanceCapBps = 1_500;
        private int performanceLookbackSeasons = 3;
        private int controlThresholdBps = 5_001;
        private int takeoverPremiumBps = 2_000;
        private int takeoverQuoteExpiryDays = 7;
        private long minimumProtectedReserve = 1_000_000L;
        private int protectedWageMonths = 3;
        private boolean withdrawalAllowedWithDebt;

        public String getValuationVersion() { return valuationVersion; }
        public void setValuationVersion(String value) { this.valuationVersion = value; }
        public long getMinimumValuation() { return minimumValuation; }
        public void setMinimumValuation(long value) { this.minimumValuation = value; }
        public long getStadiumSeatValue() { return stadiumSeatValue; }
        public void setStadiumSeatValue(long value) { this.stadiumSeatValue = value; }
        public long getStadiumLevelValue() { return stadiumLevelValue; }
        public void setStadiumLevelValue(long value) { this.stadiumLevelValue = value; }
        public long getFacilityLevelValue() { return facilityLevelValue; }
        public void setFacilityLevelValue(long value) { this.facilityLevelValue = value; }
        public long getReputationPointValue() { return reputationPointValue; }
        public void setReputationPointValue(long value) { this.reputationPointValue = value; }
        public int getPerformanceCapBps() { return performanceCapBps; }
        public void setPerformanceCapBps(int value) { this.performanceCapBps = value; }
        public int getPerformanceLookbackSeasons() { return performanceLookbackSeasons; }
        public void setPerformanceLookbackSeasons(int value) { this.performanceLookbackSeasons = value; }
        public int getControlThresholdBps() { return controlThresholdBps; }
        public void setControlThresholdBps(int value) { this.controlThresholdBps = value; }
        public int getTakeoverPremiumBps() { return takeoverPremiumBps; }
        public void setTakeoverPremiumBps(int value) { this.takeoverPremiumBps = value; }
        public int getTakeoverQuoteExpiryDays() { return takeoverQuoteExpiryDays; }
        public void setTakeoverQuoteExpiryDays(int value) { this.takeoverQuoteExpiryDays = value; }
        public long getMinimumProtectedReserve() { return minimumProtectedReserve; }
        public void setMinimumProtectedReserve(long value) { this.minimumProtectedReserve = value; }
        public int getProtectedWageMonths() { return protectedWageMonths; }
        public void setProtectedWageMonths(int value) { this.protectedWageMonths = value; }
        public boolean isWithdrawalAllowedWithDebt() { return withdrawalAllowedWithDebt; }
        public void setWithdrawalAllowedWithDebt(boolean value) { this.withdrawalAllowedWithDebt = value; }
    }
}
