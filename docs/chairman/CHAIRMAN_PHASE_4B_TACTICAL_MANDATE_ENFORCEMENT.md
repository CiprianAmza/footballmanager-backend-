# Chairman Phase 4B tactical mandate enforcement

Chairman tactical locks are resolved through one policy shared by the tactic
controller, automatic lineup selection and match simulation. Precedence is:
Chairman locks, non-conflicting legacy `CoachPermission` locks, then manager
entries. Conflicts are checked by both grid slot and player identity. Lock
position indexes are exact: Chairman locks are never relocated, and legacy
locks outside the effective grid are omitted rather than moved. The result is
defensive-copy immutable, sorted, and bounded to at most 11 starters (slots
0..29) and 7 substitutes (slots 30..36).

Human-team ratings and `MatchPlayerRating` snapshots consume the same canonical
runtime formation. A saved `first11` is parsed defensively, overlaid with the
current Chairman and legacy locks, and filtered for match availability. If the
effective saved XI is incomplete, the Chairman-aware assistant selection is
used for the complete XI; no tactic re-save is required after a mandate change.

When no mandate exists, the historical legacy rating path remains authoritative:
saved XI role/instruction weighting is unchanged, and missing or invalid saved
data falls back to `getBestElevenWithSlots`. Chairman canonical completion is
not invoked for that case.

The canonical runtime formation domain is exactly
`TacticService.getAllExistingTactics()`. Player-only mandates retain a proposed
formation only when every locked slot is present; otherwise the first canonical
formation containing all locks is selected deterministically. No incompatible
proposed formation is used as a fallback.

Completion is centralized in the enforcement service: valid manager entries are
kept with their role, duty and instructions, and only missing starter/bench
slots are filled from the assistant selection. All consumers use the same
bounded, duplicate-free deterministic result.

The effective formation and XI shown by `getFormation` and `teamView` are
copies. A saved tactic is never mutated merely because a Chairman mandate is
active; reads expose the enforced formation and lineup instead.

`getFormation` is an edit view: it uses no runtime-unavailable set, so a locked
injured or suspended player remains visible and editable. `teamView` and match
selection remain runtime views and exclude unavailable players.

Automatic selection produces the starting XI and bench together, so a locked
player cannot be selected independently into both collections. Saved user
lineups are overlaid with current Chairman and legacy locks before runtime
fallback. Availability (injury or suspension) is applied only at runtime;
an unavailable player is omitted from both XI completion and bench completion,
while edit-time enforcement still validates the mandate.

Mandate changes publish a domain event after persistence. Cache invalidation is
bound to `AFTER_COMMIT` and is per-team, including ratings, best XI, starters,
substitutions, profile/formation caches, manager tactic/vector state and wide
share. Rollbacks do not invalidate these caches.
