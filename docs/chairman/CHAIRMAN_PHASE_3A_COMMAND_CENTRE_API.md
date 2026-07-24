# Chairman Phase 3A — Command Centre API

## Endpoint

`GET /api/clubs/{teamId}/chairman-command-centre`

The endpoint is read-only. The authenticated principal is resolved by the
controller through `CurrentUserService` and `PersonProfileService`; caller
supplied actor or account identifiers are not accepted.

## Authorization and source of truth

The command-centre service first calls the canonical Chairman dashboard query.
That call remains the gate for club existence, Chairman identity, control,
valuation and treasury data. Its errors are propagated unchanged. No further
sport or finance reads are performed when that gate fails.

The response composes existing domain services and repositories: team and
competition metadata, stadium data, manager/staff data, competition standings,
DataHub form, calendar fixtures, squad availability, dashboard finance and
ownership, and the current-season financial-record window. It does not
recompute standings, fixtures, valuations, treasury, or ownership formulas.

The response contains the current season/day/phase from the global game
calendar. If the calendar is unavailable, the service returns the typed
`GAME_STATE_UNAVAILABLE` economy conflict.

Collections in the response are immutable defensive copies. Missing optional
manager, competition, standing, or stadium data is represented without
inventing domain values.

## Phase boundary

This contract is backend-only and read-only. No schema, save format, feature
flag, Phase 2A/2B code, Phase 3B work, or Phase 4 work is part of this change.
