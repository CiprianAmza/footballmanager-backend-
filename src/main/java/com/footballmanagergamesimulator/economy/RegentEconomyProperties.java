package com.footballmanagergamesimulator.economy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "regent")
public class RegentEconomyProperties {

    private boolean enabled;
    private final Economy economy = new Economy();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Economy getEconomy() {
        return economy;
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
}
