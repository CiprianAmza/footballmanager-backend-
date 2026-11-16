# Tactic Arena API

All routes live under `/tactic-arena`, need the same authenticated session as the rest of
the game, and the mutating ones (`POST`, `PUT`, `DELETE`) need the usual `X-XSRF-TOKEN`
header, exactly like `/tactic/saveFormation`. Errors are `{"error": "message"}` with a 4xx
status. Dates are ISO-8601 strings (`"2026-11-16T10:15:30Z"`).

Nothing here writes to the career: the tactic sent with a test is kept only by the test.

## Shared shapes

### `PersonalizedTacticView` (the tactic, unchanged from the tactics page)

Exactly the payload the tactics page (`tactics4`) posts to `/tactic/saveFormation`, and the
shape `/tactic/getFormation/{teamId}` returns:

```json
{
  "teamId": 104,
  "tactic": "4231",
  "mentality": "Balanced",
  "inPossession": "Standard",
  "passingType": "Normal",
  "tempo": "Standard",
  "timeWasting": "Sometimes",
  "defensiveLine": "Standard",
  "pressing": "Normal",
  "width": "Balanced",
  "dribbling": "Standard",
  "foulFrequency": "Normal",
  "foulHardness": "Medium",
  "tempoFragmentation": "Normal",
  "widePlay": "Shoot",
  "transition": "Balanced",
  "recovery": "Standard",
  "penaltyTakerId": 5512,
  "freeKickTakerId": 5512,
  "cornerTakerLeftId": 5520,
  "cornerTakerRightId": 5520,
  "formationDataList": [
    { "positionIndex": 2, "playerId": 5501, "role": "Goalkeeper", "duty": "Defend",
      "instructions": null, "specialRole": null, "shadow": false },
    { "positionIndex": 30, "playerId": 5530, "role": null, "duty": null,
      "instructions": null, "specialRole": null, "shadow": false }
  ]
}
```

`positionIndex < 30` = starter slot on the pitch grid, `30..36` = bench. Exactly 11 starters.
Set-piece taker ids may be `null`.

### `Split` (one row of the results block)

```json
{ "pld": 114, "pts": 73, "ptsMax": 114, "w": 22, "d": 7, "l": 9,
  "gf": 68, "ga": 35, "gd": 33, "xgf": 61, "xga": 38, "xgd": 23 }
```

- `pld`: total league matches over the runs it is based on (sum, not average).
- `pts`: average league points per season, rounded. `ptsMax`: average league matches per
  season × 3 (fm-arena shows `pts / ptsMax`, e.g. "73 / 114").
- `w`, `d`, `l`, `gf`, `ga`, `gd`, `xgf`, `xga`, `xgd`: averages per season, rounded to
  integers (`gd` and `xgd` are signed; the UI prints `+`).
- `xgf`, `xga`, `xgd` are `null` when no match of the split has xG.

### `Results` (the fm-arena results block)

```json
{ "basedOn": 3, "overall": Split, "home": Split, "away": Split }
```

Only league matches simulated in the run count (matches already played before the test
started are excluded). `basedOn` is the number of finished runs it is computed from: 3 for
a finished test's reference block, 1 for the chosen-club block or a single run.

### `GitPushStatus`

```json
{ "enabled": true, "lastAttemptAt": "2026-11-16T10:15:30Z", "ok": false,
  "message": "git push: fatal: could not read Username ..." }
```

`enabled` is `tactic-arena.git-push`; `lastAttemptAt`, `ok`, `message` are `null` while
nothing has been tried since the backend started. `ok: true` → `message` is a short
summary (e.g. `"Pushed: Tactic test 7: Gegenpress"` or `"Nothing to commit"`).

## Routes

### `GET /tactic-arena/clubs`

Every club in the world, grouped by league, best league first (tier, then reputation).

```json
{
  "leagues": [
    {
      "competitionId": 16,
      "name": "Liga 1",
      "nationId": 3,
      "nationName": "Romania",
      "tier": 1,
      "teams": [
        { "teamId": 104, "name": "FC Example", "reputation": 7200,
          "color1": "#c00000", "color2": "#ffffff" }
      ]
    }
  ]
}
```

Teams are sorted by reputation, highest first.

### `GET /tactic-arena/clubs/{teamId}/setup`

Everything the tactics page needs for any club, in the shapes it already reads:

```json
{
  "team": { "teamId": 104, "name": "FC Example", "competitionId": 16,
            "competitionName": "Liga 1", "reputation": 7200,
            "color1": "#c00000", "color2": "#ffffff" },
  "players": [ PlayerView ],
  "formationCatalog": [ { "tacticName": "442", "totalRating": 0 } ],
  "savedTactic": PersonalizedTacticView,
  "teamView": { ... }
}
```

- `players`: same list as `GET /tactic/getPlayers/{teamId}`.
- `formationCatalog`: same as `GET /tactic/formationCatalog/{teamId}`.
- `savedTactic`: same as `GET /tactic/getFormation/{teamId}`; `null` when the club has none
  (then use `teamView.managerTactic` or `teamView.bestPossibleTactic`).
- `teamView`: same body as `GET /tactic/teamView/{teamId}` (`teamId`, `teamName`,
  `managerId`, `managerName`, `managerTacticSource`, `managerTactic`,
  `bestPossibleTactic`, `chairmanRequiredFormation`, `chairmanLockedSlots`).

The other read-only `/tactic/...` routes the page uses (`formationLayout`, `instructions`,
`roles`, `allRoleSuitabilities`, `playerCard`, `suggestSetPieceTakers`, `askAssistant`,
`/teams/info/{teamId}`) work for any club and can be called as today. 404 if the club
does not exist.

### `POST /tactic-arena/tests`

Body:

```json
{ "name": "Gegenpress 4231", "teamId": 104, "tactic": PersonalizedTacticView }
```

`tactic.teamId` is ignored (the body's `teamId` wins). Validated: name 1–80 characters,
known formation, 11 starters that belong to the club, no duplicate players or slots.

Response `200`:

```json
{ "id": 7 }
```

The test is queued at once; only one test simulates at a time, the rest wait (`QUEUED`).

### `GET /tactic-arena/tests`

The leaderboard (all tests, newest first; the UI sorts).

```json
{
  "testsCount": 12,
  "matchesPlayed": 1368,
  "gitPush": GitPushStatus,
  "tests": [ TestSummary ]
}
```

`testsCount` = tests with status `DONE`; `matchesPlayed` = sum of `results.overall.pld`
over those tests.

`TestSummary`:

```json
{
  "id": 7,
  "uid": "1b0e8a8e-4f5e-4a52-9a43-7d1c8b0f5c2e",
  "name": "Gegenpress 4231",
  "formation": "4231",
  "owner": "ciprian",
  "teamId": 104,
  "teamName": "FC Example",
  "leagueId": 16,
  "leagueName": "Liga 1",
  "season": 3,
  "startDay": 120,
  "endDay": 352,
  "engine": "classic",
  "createdAt": "2026-11-16T10:00:00Z",
  "finishedAt": "2026-11-16T10:40:00Z",
  "status": "RUNNING",
  "progress": 42.5,
  "results": Results,
  "chosen": Results
}
```

- `status`: `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`.
- `progress`: 0–100, the mean of the four runs' progress (a finished run counts 100).
- `engine`: `"classic"` (game runs with `match2d.mode=off`) or `"2d-human"` (only the
  tested club's matches go through the 2D engine, the rest classic).
- `results`: the 3 reference runs (`null` until all three are `DONE`).
- `chosen`: the run with the chosen club (`null` until it is `DONE`).

### `GET /tactic-arena/tests/{id}`

`TestSummary` plus:

```json
{
  "message": null,
  "tactic": PersonalizedTacticView,
  "leagueMatchesTotal": 38,
  "leagueMatchesRemaining": 26,
  "gitPush": GitPushStatus,
  "runs": [ Run ]
}
```

- `message`: why the test failed / was cancelled, else `null`.
- `leagueMatchesTotal` / `leagueMatchesRemaining`: the chosen club's league matches in the
  season and how many were still to play when the test started (season already under way
  ⇔ remaining < total).
- `runs`: always 4, in this order: `CHOSEN`, `REFERENCE_TOP`, `REFERENCE_MEDIAN`,
  `REFERENCE_BOTTOM`.

`Run`:

```json
{
  "id": 21,
  "index": 0,
  "kind": "CHOSEN",
  "teamId": 104,
  "teamName": "FC Example",
  "teamReputation": 7200,
  "status": "DONE",
  "progress": 100.0,
  "currentDay": 352,
  "simulatedDays": 232,
  "totalDays": 232,
  "message": null,
  "startedAt": "2026-11-16T10:01:00Z",
  "finishedAt": "2026-11-16T10:20:00Z",
  "images": ["tactic", "schedule"],
  "stats": Results,
  "result": RunResult
}
```

`progress` = `simulatedDays / totalDays × 100`; `stats` (`basedOn: 1`) and `result` are
`null` until the run is `DONE`. `images` lists the kinds already uploaded.

`RunResult` (what was collected from the simulated season):

```json
{
  "teamId": 104,
  "teamName": "FC Example",
  "leagueId": 16,
  "leagueName": "Liga 1",
  "season": 3,
  "startDay": 120,
  "endDay": 352,
  "engine": "classic",
  "tactic": PersonalizedTacticView,
  "squad": [ PlayerView ],
  "matches": [ Match ],
  "competitions": [ { ... } ],
  "leagueTable": [ TeamCompetitionView ]
}
```

- `tactic`: the tactic the club used all season, `formationDataList` = the XI and bench
  actually set (for reference clubs: the game's automatic best XI for the formation, with
  the test's roles/duties/instructions on the same slots).
- `squad`: the club's players at the start of the run, same shape as
  `GET /tactic/getPlayers/{teamId}` (to draw the pitch).
- `matches`: every official match of the club in the season, in calendar order, each one a
  `ScheduleView` exactly as `GET /match/getScheduleForSeasonNumber/{season}/{teamId}`
  returns it (`day`, `date`, `competitionName`, `competitionType`, `roundLabel`,
  `homeOrAway`, `opponentTeam`, `opponentTeamId`, `score`, `teamGoals`,
  `opponentGoals`, `resultOutcome`, `decidedBy`, `winnerTeamId`,
  `penaltyTeam1Score`, `penaltyTeam2Score`, `aggregateTeam1Score`, ... ) plus:
  - `xgFor`, `xgAgainst`: numbers with 2 decimals, `null` when the game has no stats;
  - `inRun`: `false` for matches already played before the test started.
- `competitions`: the club's competitions at season end, each item exactly as
  `GET /competition/getTeamCompetitions/{teamId}` returns it (name, typeId, tier,
  `stageReached`, `status` such as `WINNER` / `RUNNER_UP` / `ELIMINATED`, `statusLabel`,
  and for leagues `games`, `wins`, `draws`, `loses`, `goalsFor`, `goalsAgainst`, ...),
  plus `position` (final league place, leagues only, else `null`), `points` (leagues only)
  and `trophy` (`true` when the club won it: for a league, `position == 1`). For leagues,
  ignore `status` / `stageReached` (the game's cup-progress model reports any finished
  league as `WINNER` / `Final`); show `position` and `points` instead.
- `leagueTable`: the final table of the club's league, same items as
  `GET /competition/getTeams/{competitionId}`.

### `POST /tactic-arena/tests/{id}/cancel`

Stops a `QUEUED` or `RUNNING` test (running simulations are killed). Response `200`:
the `TestSummary`. Finished tests are returned unchanged.

### `DELETE /tactic-arena/tests/{id}`

Cancels if needed, then deletes the test, its runs and images from the database. The
exported folder on disk is left alone. Response `204`.

### `PUT /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`

`kind` = `tactic` | `schedule` | `competitions`. Body = the image bytes, with header
`Content-Type: image/webp` or `image/png` (max 8 MB). Stored as sent. Replaces an
existing image of the same kind. Response `204`. 404 if the test/run does not exist,
400 for another kind or content type.

### `GET /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`

Returns the bytes with their `Content-Type`; 404 when not uploaded yet.
