-- CONCORD Team Dynamics (feature flag dynamics.enabled, default OFF) — MySQL 8.
-- Monthly team meeting, contextual player conversations and their promises.
-- Indexes are declared inline because MySQL does not support CREATE INDEX IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS team_meeting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    season INTEGER NOT NULL,
    month_index INTEGER NOT NULL,
    held_day INTEGER NOT NULL DEFAULT 0,
    context VARCHAR(40) NOT NULL,
    tone VARCHAR(40) NOT NULL,
    manager_reputation DOUBLE NOT NULL DEFAULT 0,
    participant_count INTEGER NOT NULL DEFAULT 0,
    average_morale_delta DOUBLE NOT NULL DEFAULT 0,
    summary VARCHAR(1000),
    created_at_epoch_millis BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_team_meeting_team_season_month UNIQUE (team_id, season, month_index),
    CONSTRAINT ck_team_meeting_month CHECK (month_index BETWEEN 1 AND 12)
);

CREATE TABLE IF NOT EXISTS team_meeting_reaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    player_name VARCHAR(120),
    tier VARCHAR(30),
    reaction VARCHAR(20) NOT NULL,
    morale_before DOUBLE NOT NULL DEFAULT 0,
    morale_delta DOUBLE NOT NULL DEFAULT 0,
    reason VARCHAR(500),
    INDEX idx_tm_reaction_meeting (meeting_id)
);

CREATE TABLE IF NOT EXISTS player_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    player_name VARCHAR(120),
    season INTEGER NOT NULL,
    month_index INTEGER NOT NULL,
    held_day INTEGER NOT NULL DEFAULT 0,
    topic VARCHAR(40) NOT NULL,
    tone VARCHAR(40) NOT NULL,
    manager_reputation DOUBLE NOT NULL DEFAULT 0,
    reaction VARCHAR(20) NOT NULL,
    morale_before DOUBLE NOT NULL DEFAULT 0,
    morale_delta DOUBLE NOT NULL DEFAULT 0,
    player_response VARCHAR(1000),
    summary VARCHAR(1000),
    created_at_epoch_millis BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_player_conversation_player_season_month UNIQUE (player_id, season, month_index),
    CONSTRAINT ck_player_conversation_month CHECK (month_index BETWEEN 1 AND 12),
    INDEX idx_player_conversation_team (team_id)
);

CREATE TABLE IF NOT EXISTS dynamics_promise (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    player_name VARCHAR(120),
    season INTEGER NOT NULL,
    month_index INTEGER NOT NULL,
    created_day INTEGER NOT NULL DEFAULT 0,
    source VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL DEFAULT 0,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    due_season INTEGER NOT NULL DEFAULT 0,
    due_day INTEGER NOT NULL DEFAULT 0,
    created_at_epoch_millis BIGINT NOT NULL DEFAULT 0,
    resolved_at_epoch_millis BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_dyn_promise_team (team_id),
    INDEX idx_dyn_promise_player (player_id)
);
