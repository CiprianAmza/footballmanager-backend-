# Chairman Phase 2A — Club Catalog, Holdings and Control

Implemented backend contract:

- `GET /api/clubs?scope=ALL|HELD|CONTROLLED`, with `ALL` as the default.
- Catalog summaries are ordered by `teamId` and use the canonical team competition,
  cap-table and valuation services.
- Principal shares, stake basis points, equity value, held status and control status
  are derived server-side from the authenticated profile's personal account.
- `GET /api/clubs/{teamId}/chairman-dashboard` requires a Chairman profile and
  canonical control of the requested club before valuation, treasury or other
  private data is calculated.

Public catalog responses contain no treasury, salary, reserve, obligations or
administrative data. No client-supplied identity is accepted.
