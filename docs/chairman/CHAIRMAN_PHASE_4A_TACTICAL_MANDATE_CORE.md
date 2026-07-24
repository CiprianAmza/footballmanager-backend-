# Chairman Phase 4A — Normalized Tactical Mandate Core

Phase 4A stores the chairman's canonical tactical mandate and exposes it through
`GET` and `PUT /api/clubs/{teamId}/tactical-mandate`. A mandate can require an
exact known formation and can lock zero to eleven real pitch slots to player IDs.
Slots are normalized as rows, ordered by `positionIndex`, and protected by a
team-unique mandate version and pessimistic team lock. Updates require the
expected version; stale writers receive `TACTICAL_MANDATE_STALE`.

Both endpoints authenticate through the current user, require a chairman career,
and reuse `ClubQueryService.dashboard(teamId, profile)` for canonical cap-table
control. Authorization is performed before formation or player validation, and
request bodies never carry actor identity. A missing mandate reads as an empty
state with version zero; an empty update persists the empty state.

`TacticService.isKnownFormation` and
`getFormationGridIndicesExact` are deliberately separate from the legacy fallback
lookup. Unknown formations therefore fail rather than silently becoming 4-4-2.
Players must belong to the target club, be `PLAYER_TYPE`, and not be retired.

`ChairmanTacticalMandateResolver` is a pure, immutable overlay. It applies the
required formation when present, removes manager XI entries conflicting with
mandated slots or players, adds all mandated slots, validates uniqueness and
formation membership, and returns deterministic position order. It does not
access persistence, fill missing players, alter other tactical instructions,
decide medical availability, or wire into match runtime in Phase 4A.

Phase 4A does not modify save/import contracts, legacy `CoachPermissions`, tactic
runtime, engine simulation, feature flags, frontend, or any Phase 4B–4D work.
