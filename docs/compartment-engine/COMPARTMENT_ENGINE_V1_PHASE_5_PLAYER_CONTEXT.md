# Compartment Engine V1 Phase 5 Player Context

Phase 5 adds the canonical player context data foundation for future compartment scoring and individual training. It does not connect this data to match scoring runtime.

## Canonical Positions

`PlayerPosition` defines the initial stable position codes:

`GK`, `DC`, `DL`, `DR`, `WBL`, `WBR`, `DM`, `MC`, `ML`, `MR`, `AMC`, `AML`, `AMR`, `ST`.

Parsing is explicit and exact after trim/case normalization. Unknown, blank and null values resolve to no position unless a caller uses the strict `require` helper.

## Persisted Context

`PLAYER_POSITION_FAMILIARITY` stores one 1-20 familiarity per player and canonical position code. At most one row may be marked `PRIMARY_POSITION` for a player.

`PLAYER_ROLE_FAMILIARITY` stores one 1-20 familiarity per player, position code and stable `PlayerRole` enum code. This is role familiarity, not role suitability. Suitability remains an attribute-derived concept owned by the existing role service.

`PLAYER_FOOT_PROFILE` stores independent 1-20 left and right foot ratings. `Human.preferredFoot` remains for legacy compatibility and frontend display.

## Legacy Migration

H2 migration `V7__titan_player_context_model.sql` backfills only players (`Human.typeId = 1`).

Valid legacy `Human.position` values create a primary position familiarity row with `familiarity = 20`. Unknown, blank or null positions create no invented position row.

Foot ratings are deterministic:

`Right` or unknown: left 8, right 20.
`Left`: left 20, right 8.
`Both`: left 16, right 16.

No role familiarity rows are invented.

## Read Fallbacks

`PlayerCapabilityService` reads player context in batch and returns immutable `PlayerCapabilitySnapshot` values. A simple read never writes fallback rows.

Position fallback:

Persistent rows win. If no position rows exist and the legacy natural position is valid, that position is returned as primary with familiarity 20. Secondary fallback converts the legacy `MatchEngineConfig.PlayerValue.familiarity(natural, used)` factor to 1-20 with:

`round(factor * 20)`, then clamp to `[1,20]`.

Role fallback:

Persistent rows win. Valid missing position-role combinations fall back to neutral familiarity 10. Invalid position-role combinations are rejected.

Foot fallback:

Persistent foot profiles win. Missing profiles derive from `Human.preferredFoot` using the deterministic migration mapping above.

## Save Version

H2 save version is now 12. The v12 manifest includes:

`playerPositionFamiliarities`, `playerRoleFamiliarities`, `playerFootProfiles`.

Legacy v5-v11 saves are migrated inside the import preflight plan by `LegacyPlayerContextMigrator`, using the already parsed `HUMAN` rows so the rollback sandbox and live apply see the same world.
