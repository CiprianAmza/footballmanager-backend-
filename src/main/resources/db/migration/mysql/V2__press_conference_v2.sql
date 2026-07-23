-- Press Conference V2 — cross-database migration contract (MySQL).
-- Runtime datasource is H2; this migration exists so the MySQL schema contract
-- (FlywayPhase0CrossDatabaseIT / MySqlGameSaveIT) stays in parity. The tables
-- are additive and leave the legacy press_conference table untouched.
-- MySQL has no CREATE INDEX IF NOT EXISTS, so indexes are declared inline.

CREATE TABLE IF NOT EXISTS press_conference_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    fixture_key VARCHAR(128) NOT NULL,
    team_id BIGINT NOT NULL,
    opponent_id BIGINT,
    competition_id BIGINT,
    season_number INT NOT NULL,
    conference_day INT NOT NULL,
    seed BIGINT NOT NULL,
    generator_version VARCHAR(32) NOT NULL,
    current_question_index INT DEFAULT 0 NOT NULL,
    context_snapshot TEXT,
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    delegated_at BIGINT,
    delegated_by BIGINT,
    CONSTRAINT uk_pc_session_fixture UNIQUE (team_id, session_type, fixture_key, season_number),
    CONSTRAINT ck_pc_session_type CHECK (session_type IN ('PRE_MATCH', 'POST_MATCH')),
    CONSTRAINT ck_pc_session_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'DELEGATED')),
    KEY idx_pc_session_team_season (team_id, season_number),
    KEY idx_pc_session_status (status)
);

CREATE TABLE IF NOT EXISTS press_conference_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    order_index INT NOT NULL,
    catalog_question_id VARCHAR(96) NOT NULL,
    context_key VARCHAR(64),
    prompt_text TEXT NOT NULL,
    answer_options TEXT,
    answered_answer_id BIGINT,
    CONSTRAINT uk_pc_question_order UNIQUE (session_id, order_index),
    KEY idx_pc_question_session (session_id, order_index)
);

CREATE TABLE IF NOT EXISTS press_conference_answer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    catalog_answer_id VARCHAR(96) NOT NULL,
    answer_code VARCHAR(48),
    tone VARCHAR(32),
    stance VARCHAR(32),
    applied_effects TEXT,
    delegated BOOLEAN DEFAULT FALSE NOT NULL,
    applied_at BIGINT NOT NULL,
    CONSTRAINT uk_pc_answer_question UNIQUE (question_id),
    KEY idx_pc_answer_session (session_id)
);
