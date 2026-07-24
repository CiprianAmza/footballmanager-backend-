# Chairman Phase 4B tactical mandate enforcement

Chairman tactical locks are resolved through one policy shared by the tactic
controller, automatic lineup selection and match simulation. Precedence is:
Chairman locks, non-conflicting legacy `CoachPermission` locks, then manager
entries. Conflicts are checked by both grid slot and player identity, and all
returned formation data is defensive-copy immutable.

The effective formation and XI shown by `getFormation` and `teamView` are
copies. A saved tactic is never mutated merely because a Chairman mandate is
active; reads expose the enforced formation and lineup instead.

Automatic selection produces the starting XI and bench together, so a locked
player cannot be selected independently into both collections. Availability
(injury or suspension) is applied only at runtime; an unavailable mandated
player is omitted from the runtime lineup, while edit-time enforcement still
validates the mandate.

Mandate changes publish a domain event after persistence. Cache invalidation is
bound to `AFTER_COMMIT` and is per-team, including ratings, best XI, starters,
substitutions, profile/formation caches, manager tactic/vector state and wide
share. Rollbacks do not invalidate these caches.
