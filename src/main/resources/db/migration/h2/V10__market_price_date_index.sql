CREATE INDEX IF NOT EXISTS idx_market_price_date
    ON market_price_snapshot(season_number, game_day, instrument_id);
