-- Expected points (xPts) next to xG. Nullable columns, so databases and Tactic Arena base world
-- backups created before xPts keep working: their existing rows simply have no value. Guarded with
-- IF EXISTS / IF NOT EXISTS so the script is a no-op on an empty database (Hibernate creates the
-- tables there) and when ddl-auto=update has already added the columns.
ALTER TABLE IF EXISTS match_stats ADD COLUMN IF NOT EXISTS home_xpts INTEGER;
ALTER TABLE IF EXISTS match_stats ADD COLUMN IF NOT EXISTS away_xpts INTEGER;
ALTER TABLE IF EXISTS match_plan ADD COLUMN IF NOT EXISTS home_xpts DOUBLE PRECISION;
ALTER TABLE IF EXISTS match_plan ADD COLUMN IF NOT EXISTS away_xpts DOUBLE PRECISION;
