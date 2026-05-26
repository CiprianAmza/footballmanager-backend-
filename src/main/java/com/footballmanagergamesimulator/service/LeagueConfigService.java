package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Service
public class LeagueConfigService {

    private int defaultEncounters18Plus = 2;
    private int defaultEncountersUnder18 = 4;
    private final Map<String, Integer> leagueOverrides = new HashMap<>();

    @PostConstruct
    public void loadConfig() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream is = new ClassPathResource("league-config.json").getInputStream();
            JsonNode root = mapper.readTree(is);

            if (root.has("defaultEncounters18Plus")) {
                defaultEncounters18Plus = root.get("defaultEncounters18Plus").asInt(2);
            }
            if (root.has("defaultEncountersUnder18")) {
                defaultEncountersUnder18 = root.get("defaultEncountersUnder18").asInt(4);
            }
            if (root.has("leagueOverrides")) {
                JsonNode overrides = root.get("leagueOverrides");
                overrides.fieldNames().forEachRemaining(name ->
                        leagueOverrides.put(name, overrides.get(name).asInt())
                );
            }

            System.out.println("=== League config loaded: default18+=" + defaultEncounters18Plus
                    + ", defaultUnder18=" + defaultEncountersUnder18
                    + ", overrides=" + leagueOverrides);
        } catch (Exception e) {
            System.err.println("Failed to load league-config.json, using defaults: " + e.getMessage());
        }
    }

    /**
     * Returns the number of encounters (times each team plays every other team)
     * for a league with the given name and team count.
     */
    public int getEncounters(String leagueName, int teamCount) {
        int encounters;
        if (leagueOverrides.containsKey(leagueName)) {
            encounters = leagueOverrides.get(leagueName);
        } else {
            encounters = teamCount >= 18 ? defaultEncounters18Plus : defaultEncountersUnder18;
        }
        // 0 is allowed (skip league entirely for faster simulation)
        if (encounters == 0) return 0;
        // Otherwise must be even (home+away pairs) and at least 2
        if (encounters < 2) encounters = 2;
        if (encounters % 2 != 0) encounters++;
        return encounters;
    }

    /**
     * Returns the number of encounters for calendar/scheduling purposes
     * when we only know the team count (fallback).
     */
    public int getDefaultEncounters(int teamCount) {
        return teamCount >= 18 ? defaultEncounters18Plus : defaultEncountersUnder18;
    }
}
