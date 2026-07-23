CREATE TABLE IF NOT EXISTS users (
    id INTEGER AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64),
    password VARCHAR(100) DEFAULT '!legacy-login-disabled!' NOT NULL,
    email VARCHAR(254),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    to_be_upgraded VARCHAR(255) DEFAULT '',
    active BOOLEAN DEFAULT TRUE NOT NULL,
    roles VARCHAR(255) DEFAULT 'USER' NOT NULL,
    career_role VARCHAR(20) DEFAULT 'MANAGER' NOT NULL,
    team_id BIGINT,
    last_team_id BIGINT,
    manager_id BIGINT,
    fired BOOLEAN DEFAULT FALSE NOT NULL,
    ever_managed BOOLEAN DEFAULT FALSE NOT NULL,
    initial_offers_generated BOOLEAN DEFAULT FALSE NOT NULL
) ENGINE=InnoDB;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'password');
SET @column_sql = IF(@column_exists = 0,
    'ALTER TABLE users ADD COLUMN password VARCHAR(100) DEFAULT ''!legacy-login-disabled!'' NOT NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'email');
SET @column_sql = IF(@column_exists = 0,
    'ALTER TABLE users ADD COLUMN email VARCHAR(254)', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'roles');
SET @column_sql = IF(@column_exists = 0,
    'ALTER TABLE users ADD COLUMN roles VARCHAR(255) DEFAULT ''USER'' NOT NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'career_role');
SET @column_sql = IF(@column_exists = 0,
    'ALTER TABLE users ADD COLUMN career_role VARCHAR(20) DEFAULT ''MANAGER'' NOT NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;
UPDATE users
SET password = '$2y$10$sh5eeMsF.BOSaj0/SY0TZeluYJqfKs8zitWKx4Y1kTa49fEX7q1Ua'
WHERE password IS NULL
   OR (password NOT LIKE '$2a$%' AND password NOT LIKE '$2b$%' AND password NOT LIKE '$2y$%');
UPDATE users SET email = CONCAT('legacy-', id, '@invalid.local') WHERE email IS NULL OR TRIM(email) = '';
UPDATE users SET roles = 'USER' WHERE roles IS NULL OR TRIM(roles) = '';
UPDATE users SET career_role = 'MANAGER' WHERE career_role IS NULL OR TRIM(career_role) = '';

SET @username_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'uk_users_username');
SET @username_sql = IF(@username_index = 0,
    'CREATE UNIQUE INDEX uk_users_username ON users(username)', 'SELECT 1');
PREPARE username_statement FROM @username_sql;
EXECUTE username_statement;
DEALLOCATE PREPARE username_statement;

SET @email_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'uk_users_email');
SET @email_sql = IF(@email_index = 0,
    'CREATE UNIQUE INDEX uk_users_email ON users(email)', 'SELECT 1');
PREPARE email_statement FROM @email_sql;
EXECUTE email_statement;
DEALLOCATE PREPARE email_statement;

CREATE TABLE IF NOT EXISTS human (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255),
    type_id BIGINT DEFAULT 1 NOT NULL,
    retired BOOLEAN DEFAULT FALSE NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS person_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INTEGER,
    human_id BIGINT,
    career_type VARCHAR(20) NOT NULL,
    control_type VARCHAR(20) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_season INTEGER DEFAULT 0 NOT NULL,
    created_day INTEGER DEFAULT 0 NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    retired BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT uk_person_profile_user UNIQUE (user_id),
    CONSTRAINT uk_person_profile_human UNIQUE (human_id)
) ENGINE=InnoDB;

INSERT INTO person_profile (user_id, human_id, career_type, control_type, display_name, active, retired)
SELECT u.id, u.manager_id, 'MANAGER', 'USER', COALESCE(NULLIF(TRIM(u.username), ''), CONCAT('user-', u.id)), TRUE, FALSE
FROM users u
WHERE u.manager_id IS NOT NULL
  AND u.id = (SELECT MIN(u2.id) FROM users u2 WHERE u2.manager_id = u.manager_id)
  AND NOT EXISTS (SELECT 1 FROM person_profile p WHERE p.user_id = u.id OR p.human_id = u.manager_id);

INSERT INTO person_profile (user_id, career_type, control_type, display_name, active, retired)
SELECT u.id, COALESCE(NULLIF(TRIM(u.career_role), ''), 'MANAGER'), 'USER',
       COALESCE(NULLIF(TRIM(u.username), ''), CONCAT('user-', u.id)), TRUE, FALSE
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM person_profile p WHERE p.user_id = u.id);

INSERT INTO person_profile (human_id, career_type, control_type, display_name, active, retired)
SELECT h.id, CASE WHEN h.type_id = 4 THEN 'MANAGER' ELSE 'PLAYER' END, 'AI',
       COALESCE(NULLIF(TRIM(h.name), ''), CONCAT('human-', h.id)), NOT h.retired, h.retired
FROM human h
WHERE NOT EXISTS (SELECT 1 FROM person_profile p WHERE p.human_id = h.id);
