ALTER TABLE human ADD COLUMN IF NOT EXISTS stay_forward BOOLEAN;

EXECUTE IMMEDIATE (
    SELECT CASE WHEN COUNT(*) = 5 THEN
        'UPDATE human SET stay_forward = TRUE WHERE stay_forward IS NULL AND ('
        || '(id = 107 AND name = ''Kvekrpur'' AND team_id = 14 AND type_id = 1 AND position = ''ST'') OR '
        || '(id = 108 AND name = ''Dostoievski'' AND team_id = 14 AND type_id = 1 AND position = ''ST'') OR '
        || '(id = 4060 AND name = ''Shakespeare'' AND team_id = 13 AND type_id = 1 AND position = ''ST''))'
    ELSE 'SELECT 1' END
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'HUMAN'
      AND COLUMN_NAME IN ('ID', 'NAME', 'TEAM_ID', 'TYPE_ID', 'POSITION')
);

UPDATE human SET stay_forward = FALSE WHERE stay_forward IS NULL;
ALTER TABLE human ALTER COLUMN stay_forward SET DEFAULT FALSE;
ALTER TABLE human ALTER COLUMN stay_forward SET NOT NULL;
