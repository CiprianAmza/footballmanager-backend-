package com.footballmanagergamesimulator.match2d;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A real fixture played by the 2D match engine, kept so it can be watched again: the engine
 * keeps only its last matches, but it is deterministic, so the same request played again gives
 * the same match and the same page.
 */
@Entity
@Table(name = "match2d_replay", uniqueConstraints = @UniqueConstraint(columnNames = "fixture_key"))
public class Match2dReplay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "fixture_key", nullable = false, unique = true)
    private String fixtureKey;

    private long competitionId;

    @Column(name = "season_number")
    private int season;

    @Column(name = "round_number")
    private int round;

    private long team1Id;
    private long team2Id;
    private long seed;

    @Column(name = "match_minutes")
    private int minutes;

    private String engineVersion;

    /** The exact {@code POST /matches} body that plays this match. */
    @Lob
    @Column(name = "request_json", nullable = false)
    private String requestJson;

    protected Match2dReplay() {
    }

    public Match2dReplay(String fixtureKey, long competitionId, int season, int round,
                         long team1Id, long team2Id, long seed, int minutes,
                         String engineVersion, String requestJson) {
        this.fixtureKey = fixtureKey;
        update(competitionId, season, round, team1Id, team2Id, seed, minutes, engineVersion, requestJson);
    }

    public void update(long competitionId, int season, int round, long team1Id, long team2Id,
                       long seed, int minutes, String engineVersion, String requestJson) {
        this.competitionId = competitionId;
        this.season = season;
        this.round = round;
        this.team1Id = team1Id;
        this.team2Id = team2Id;
        this.seed = seed;
        this.minutes = minutes;
        this.engineVersion = engineVersion;
        this.requestJson = requestJson;
    }

    public long getId() { return id; }
    public String getFixtureKey() { return fixtureKey; }
    public long getCompetitionId() { return competitionId; }
    public int getSeason() { return season; }
    public int getRound() { return round; }
    public long getTeam1Id() { return team1Id; }
    public long getTeam2Id() { return team2Id; }
    public long getSeed() { return seed; }
    public int getMinutes() { return minutes; }
    public String getEngineVersion() { return engineVersion; }
    public String getRequestJson() { return requestJson; }
}
