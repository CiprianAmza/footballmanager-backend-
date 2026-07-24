ALTER TABLE human ADD COLUMN IF NOT EXISTS stay_forward BOOLEAN DEFAULT FALSE;
UPDATE human SET stay_forward = FALSE WHERE stay_forward IS NULL;

EXECUTE IMMEDIATE (
    SELECT CASE WHEN COUNT(*) = 7 THEN
        'UPDATE human SET stay_forward = TRUE WHERE '
        || '(name = ''Kvekrpur'' AND team_id = 14 AND type_id = 1 AND position = ''ST'' AND age = 20 AND season_created = 1 AND rating = 300) OR '
        || '(name = ''Dostoievski'' AND team_id = 14 AND type_id = 1 AND position = ''ST'' AND age = 15 AND season_created = 1 AND rating = 300) OR '
        || '(name = ''Shakespeare'' AND team_id = 13 AND type_id = 1 AND position = ''ST'' AND age = 15 AND season_created = 1 AND rating = 300)'
    ELSE 'SELECT 1' END
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'HUMAN'
      AND COLUMN_NAME IN ('NAME', 'TEAM_ID', 'TYPE_ID', 'POSITION', 'AGE', 'SEASON_CREATED', 'RATING')
);

UPDATE human SET stay_forward = FALSE WHERE stay_forward IS NULL;
ALTER TABLE human ALTER COLUMN stay_forward SET DEFAULT FALSE;
ALTER TABLE human ALTER COLUMN stay_forward SET NOT NULL;
