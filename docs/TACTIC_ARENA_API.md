# Tactic Arena API

All routes live under `/tactic-arena` and are **public**: no login, no session cookie and
no `X-XSRF-TOKEN` are needed (a session, when present, only sets the test's owner). Errors
are `{"error": "message"}` with a 4xx status. Dates are ISO-8601 strings
(`"2026-11-16T10:15:30Z"`).

Nothing here reads or writes the career being played, its user or its club. Clubs, squads
and simulations all come from the **base world**: a new world generated once at season 1,
day 1 (see `GET /tactic-arena/baseline`). Every run starts from a fresh copy of it and plays
the whole of season 1.

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
{ "pld": 114, "pts": 73, "ptsMax": 114, "ptsPct": 64.0, "w": 22, "d": 7, "l": 9,
  "gf": 68, "ga": 35, "gd": 33, "xgf": 61, "xga": 38, "xgd": 23 }
```

- `pld`: total league matches over the runs it is based on (sum, not average).
- `ptsPct`: total points as a share of the total maximum, in percent with one decimal: use it
  to sort across clubs whose leagues have a different number of matches (DIAMOND).
- `pts`: average league points per season, rounded. `ptsMax`: average league matches per
  season × 3 (fm-arena shows `pts / ptsMax`, e.g. "73 / 114").
- `w`, `d`, `l`, `gf`, `ga`, `gd`, `xgf`, `xga`, `xgd`: averages per season, rounded to
  integers (`gd` and `xgd` are signed; the UI prints `+`).
- `xgf`, `xga`, `xgd` are `null` when no match of the split has xG.

### `Results` (the fm-arena results block)

```json
{ "basedOn": 3, "overall": Split, "home": Split, "away": Split }
```

Only the club's **own domestic league** counts (`competitionId == leagueId`): the Diamond
League is reported apart, in `diamond` (below). Only league matches simulated in the run count (matches already played before the test
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

### `BaselineStatus`

```json
{
  "status": "READY",
  "progress": 100.0,
  "uid": "5d0c...",
  "createdAt": "2026-11-16T10:00:00Z",
  "appVersion": "classes 2026-11-16T09:40:12Z",
  "season": 1,
  "startDay": 1,
  "endDay": 330,
  "clubs": 182,
  "buildStartedAt": null,
  "builtByOtherInstance": false,
  "refreshing": false,
  "message": null
}
```

- `refreshing`: `clubs.json` is being recomputed (`POST /tactic-arena/baseline/refresh`); the
  world stays `READY` and usable meanwhile.

- `status`: `READY`, `BUILDING` (this or another backend sharing the folder generates it),
  `MISSING` (never generated, or deleted), `FAILED` (the last generation failed; `message`
  says why).
- `progress`: 0–100, approximate while `BUILDING`.
- `uid`, `createdAt`, `appVersion` (the packaged version, or the time of the compiled
  classes when run from a classes folder; may be `null`), `season`, `startDay`, `endDay`
  (last match day of season 1), `clubs`: `null` unless `READY`.

## Routes

### `GET /tactic-arena/baseline`

`BaselineStatus`.

### `POST /tactic-arena/baseline/refresh`

Recomputes only `clubs.json` of the existing base world (today: the press predictions,
`predictedPosition` / `leagueSize`), in a child process on an unpacked copy of
`world.zip`. The world, its uid and the tests bound to it do not change. About 20 seconds.
`409` while the base world is missing or being generated. Response `200`: `BaselineStatus`
(`refreshing: true`). With git-push on and the base world inside the results repository,
the new `clubs.json` is committed and pushed.

### `POST /tactic-arena/baseline/rebuild`

Deletes the base world and generates a new one (several minutes). `409` while a test is
`RUNNING`/`PREPARING` or a generation is under way. Response `200`: `BaselineStatus`.
Tests created on the old base world that have not started yet fail when they come up
(their players no longer exist).

### `GET /tactic-arena/clubs`

When the base world is not `READY`, this route and `.../setup` answer `409`:

```json
{ "error": "There is no base world yet; generate it with POST /tactic-arena/baseline/rebuild",
  "baseline": BaselineStatus }
```

Every club of the base world, grouped by league, best league first (tier, then reputation).

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
          "color1": "#c00000", "color2": "#ffffff",
          "predictedPosition": 6, "leagueSize": 12 }
      ]
    }
  ]
}
```

Teams are sorted by reputation, highest first. `predictedPosition` / `leagueSize`: the
press's predicted league finish (the game's media prediction, `LeaguePredictionService`),
shown as "6/12"; `null` when the base world has no predictions yet (see
`POST /tactic-arena/baseline/refresh`).

### `GET /tactic-arena/clubs/{teamId}/setup`

Everything the tactics page needs for any club of the base world (squad as at season 1,
day 1), in the shapes it already reads:

```json
{
  "team": { "teamId": 104, "name": "FC Example", "competitionId": 16,
            "competitionName": "Liga 1", "reputation": 7200,
            "color1": "#c00000", "color2": "#ffffff",
            "predictedPosition": 6, "leagueSize": 12 },
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

In a new world `savedTactic` often exists with an EMPTY `formationDataList` (the club's
seeded formation and instructions, no eleven): take the eleven from
`teamView.bestPossibleTactic` (or `teamView.managerTactic`) in that case.

These shapes come from the base world, not from the career. `team.color1` / `team.color2`
are the club's shirt colours. The `/tactic/...` routes need a login and read the **career**
database, so do not use them for Arena data (`allRoleSuitabilities`, `playerCard`,
`suggestSetPieceTakers`, `askAssistant`, `/teams/info/{teamId}` would describe the career,
not the base world). The static editor catalogs are public here instead, with exactly the
JSON of their `/tactic/...` originals:

| Public route | Same JSON as |
|--------------|--------------|
| `GET /tactic-arena/roles/{position}` | `GET /tactic/roles/{position}` |
| `GET /tactic-arena/instructions/{position}` | `GET /tactic/instructions/{position}` |
| `GET /tactic-arena/formationLayout/{tactic}` | `GET /tactic/formationLayout/{tactic}` |
| `GET /tactic-arena/ratingTiers` | `GET /tactic/ratingTiers` |

The formations available to a club are `setup.formationCatalog`. 404 if the club is not in
the base world, 409 as above.

### `POST /tactic-arena/tests`

Body:

```json
{ "name": "Gegenpress 4231", "teamId": 104, "tactic": PersonalizedTacticView, "owner": "Cip" }
```

**Without an eleven** (for scripts): add `"autoEleven": true`. Then `tactic` needs only the
formation key and, optionally, team instructions; the chosen club plays the game's automatic
best eleven for that formation with the assistant's default roles and duties, exactly like
the reference clubs. Missing instructions take the game's default (listed first below); a
value outside the list is a `400`. `formationDataList` and set-piece takers are ignored.

```json
{
  "name": "Base 442",
  "teamId": 2,
  "owner": "mass-script",
  "autoEleven": true,
  "tactic": {
    "tactic": "442",
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
    "recovery": "Standard"
  }
}
```

Formation keys (`tactic.tactic`): `442`, `433`, `343`, `451`, `352`, `4231`, `4141`, `4411`,
`4321`, `4222`, `3421`, `532`, `5212`, `541`, `3511`, `31411`.

| Field | Allowed values (default first) |
|-------|--------------------------------|
| `mentality` | `Balanced`, `Very Defensive`, `Defensive`, `Attacking`, `Very Attacking` |
| `inPossession` | `Standard`, `Keep Ball`, `Free Ball Early` |
| `passingType` | `Normal`, `Short`, `Long` |
| `tempo` | `Standard`, `Much Lower`, `Lower`, `Higher`, `Much Higher` |
| `timeWasting` | `Sometimes`, `Never`, `Frequently`, `Always` |
| `defensiveLine` | `Standard`, `Deep`, `High` |
| `pressing` | `Normal`, `Very Easy`, `Easy`, `Aggressive`, `Very Aggressive` |
| `width` | `Balanced`, `Narrow`, `Wide` |
| `dribbling` | `Standard`, `Less`, `More` |
| `foulFrequency` | `Normal`, `Rarely`, `Often` |
| `foulHardness` | `Medium`, `Soft`, `Hard` |
| `tempoFragmentation` | `Normal`, `Flowing`, `Fragment` |
| `widePlay` | `Shoot`, `Cut Inside`, `Cross` |
| `transition` | `Balanced`, `Win Fouls`, `Fast Counter` |
| `recovery` | `Standard`, `Slow`, `Very Fast`, `Instantly` |

**Two parts of a test.** The *general* part plays the tactic on the three fixed reference
clubs (`tactic-arena.reference-teams`, default `Inazuma Japan, Diamond Dust, Desert Lion`, in that
order, whatever the chosen club's league): the averages and the leaderboard come from it,
so all tests compare on the same clubs. The *custom* part is the run with the club the user
chose (`CHOSEN`), shown separately. A reference name that is not in the base world is a
`400` at launch. With the setting empty the reference clubs are, as before, the highest /
median / lowest reputation of the chosen club's league.

**Categories** (`"category"`, optional, default `"GENERAL"`):

- `GENERAL`: the three fixed reference clubs above, with `tier` `TOP` / `MID` / `BOTTOM`
  (1st, 2nd, 3rd club of `tactic-arena.reference-teams`: top, mid-table and bottom club).
- `DIAMOND`: one reference run for each club of `tactic-arena.diamond-teams` (default, in this
  order: `Inazuma Japan, Shadows, FC San Marino, Tik Tok, Xenon, Ligthnings, No Merci,
  Athletic Sohatu`), each a full season with the same tactic and the automatic eleven;
  `tier: null`, `label: "Diamond League club"`. The averages use all 8 runs
  (`basedOn: 8`); `ptsMax` is the mean of the clubs' maxima and `ptsPct` makes the clubs of
  different leagues comparable. A chosen club, if given, is still the separate `CHOSEN` run.
  A name not in the base world is a `400`.

```json
{ "name": "Diamond 433", "category": "DIAMOND", "autoEleven": true, "owner": "mass-script",
  "tactic": { "tactic": "433" } }
```

**General-only test**: with `autoEleven: true`, `teamId` may be left out; the test then has
only the three reference runs:

```json
{ "name": "Base 433", "autoEleven": true, "owner": "mass-script",
  "tactic": { "tactic": "433", "pressing": "Aggressive" } }
```

Without `autoEleven`, `teamId` is required (the eleven belongs to a club).

`tactic.teamId` is ignored (the body's `teamId` wins). `owner` is optional: the owner is the
signed-in user when the request has a session, else `owner` (cut to 40 characters), else
`"guest"`. Validated: name 1–80 characters, known formation, 11 starters that belong to the
club **in the base world**, no duplicate players or slots. If the base world is not ready
yet, the squad check happens once it is (the test then fails with the reason).

Response `200`:

```json
{ "id": 7 }
```

The test is queued at once. Tests are served in launch order and their runs share the
backend's simulation slots (`tactic-arena.parallel-runs`, default 4), so several tests can
run at the same time.

### `GET /tactic-arena/tests`

The leaderboard (all tests, newest first; the UI sorts).

```json
{
  "testsCount": 12,
  "matchesPlayed": 1368,
  "gitPush": GitPushStatus,
  "baseline": BaselineStatus,
  "referenceTeams": [
    { "tier": "TOP", "name": "Inazuma Japan", "teamId": 87, "reputation": 10000, "predictedPosition": 1, "leagueSize": 20 },
    { "tier": "MID", "name": "Diamond Dust", "teamId": 96, "reputation": 6400, "predictedPosition": 10, "leagueSize": 20 },
    { "tier": "BOTTOM", "name": "Desert Lion", "teamId": 104, "reputation": 4800, "predictedPosition": 18, "leagueSize": 20 }
  ],
  "diamondTeams": [ { "tier": null, "name": "Inazuma Japan", "teamId": 87, ... } ],
  "tests": [ TestSummary ]
}
```

`diamondTeams`: the clubs of the DIAMOND category, same shape (`tier: null`).
`referenceTeams`: the fixed reference clubs in force (each with `tier`: `TOP`, `MID`, `BOTTOM`) (`null` when the setting is empty;
`teamId`/`reputation`/prediction `null` for a name not found or while there is no base
world).

`testsCount` = tests with status `DONE`; `matchesPlayed` = sum of `results.overall.pld`
over those tests. The list also holds the tests other backends exported into the shared
`tactic-arena.export-dir` (read at startup and, at most every 30 s, when this list is
requested).

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
  "predictedPosition": 6,
  "leagueSize": 12,
  "season": 1,
  "startDay": 1,
  "endDay": 330,
  "engine": "classic",
  "category": "GENERAL",
  "autoEleven": false,
  "createdAt": "2026-11-16T10:00:00Z",
  "finishedAt": "2026-11-16T10:40:00Z",
  "status": "RUNNING",
  "progress": 42.5,
  "results": Results,
  "chosen": Results,
  "byTier": {
    "TOP":    { "teamId": 87,  "teamName": "Inazuma Japan", "results": { "overall": Split, "home": Split, "away": Split },
                "diamond": RunDiamond },
    "MID":    { "teamId": 96,  "teamName": "Diamond Dust",  "results": { ... } },
    "BOTTOM": null
  },
  "byClub": [
    { "teamId": 87, "teamName": "Inazuma Japan", "predictedPosition": 1, "leagueSize": 20,
      "leagueName": "Eleven League", "results": { "overall": Split, "home": Split, "away": Split },
      "diamond": RunDiamond }
  ]
}
```

- `category`: `GENERAL` or `DIAMOND` (older tests: `GENERAL`).
- `diamond`: the **Diamond League** (competition type 7: the 8 elite clubs, each meets each
  4 times, 28 matches, max 84 points), apart from the league: `{basedOn, overall, home, away}`
  (`Results` shape) = mean of the reference runs whose club plays in it (GENERAL: usually only
  the top club; DIAMOND: all 8); `null` when none does. A base world generated before the
  Diamond League existed has none: rebuild it.
- `byTier` / `byClub` entries carry `finalPosition`, `finalLeagueSize` (league place) and
  `diamond` (`RunDiamond` with `position` / `of` = Diamond League place, or `null`) too.
- `byTier` (GENERAL only): each reference run on its own; a tier is `null` until its run is
  `DONE`. Sort the leaderboard by the result at the top / mid-table / bottom club with it.
- `byClub` (both categories): every reference run on its own, in the order of the setting;
  `results` is `null` until the run is `DONE`.

- `status`: `QUEUED` (waiting for a free simulation slot, behind earlier tests),
  `PREPARING` (the base world is generated first; `message` says so), `RUNNING` (at least
  one run started), `DONE`, `FAILED`, `CANCELLED`.
- `progress`: 0–100, the mean of the four runs' progress (a finished run counts 100); while
  `PREPARING`, the approximate progress of the base world generation.
- `teamName`, `leagueId`, `leagueName`, `startDay`, `endDay` are `null`/`0` until the test is
  bound to the base world (at creation when it exists, otherwise after `PREPARING`).
- `engine`: `"classic"` (game runs with `match2d.mode=off`) or `"2d-human"` (only the
  tested club's matches go through the 2D engine, the rest classic).
- `teamId`, `teamName`, `leagueId`, `leagueName`, `predictedPosition`, `leagueSize`: the
  CHOSEN club; all `null` for a general-only test.
- `results`: all reference runs (3, or 8 for DIAMOND; `null` until all are `DONE`). `ptsMax` comes from
  the reference clubs' own league matches.
- `chosen`: the run with the chosen club, with the maximum of its own league (`null` until
  it is `DONE`, and always `null` for a general-only test).

### `GET /tactic-arena/tests/{id}`

`TestSummary` plus:

```json
{
  "message": null,
  "tactic": PersonalizedTacticView,
  "leagueMatchesTotal": 44,
  "leagueMatchesRemaining": 44,
  "baselineUid": "5d0c...",
  "gitPush": GitPushStatus,
  "capture": { "status": "DONE", "message": "Captured in 41 s", "at": "2026-11-16T10:41:00Z",
               "images": 12, "expected": 12 },
  "runs": [ Run ]
}
```

- `capture`: the headless screenshots taken by the backend after the test is DONE
  (`tactic-arena.capture`). `status`: `null` (never asked since this backend started),
  `OFF` (`message` says why: off, no browser, page address unknown), `QUEUED`, `RUNNING`,
  `DONE`, `FAILED` (`message`: how many images arrived before the time limit).
  `images` / `expected`: images stored now / 3 × the test's runs (12 for a GENERAL test with a
  chosen club, 9 without; 27 or 24 for DIAMOND).
- `message`: why the test failed / was cancelled, else `null`.
- `leagueMatchesTotal` / `leagueMatchesRemaining`: the chosen club's league matches in
  season 1; always equal now (every run plays the whole season from day 1), kept so the
  page does not change.
- `baselineUid`: the base world the test runs on.
- `runs`: in this order `CHOSEN`, then the reference runs: `REFERENCE_TOP`, `REFERENCE_MEDIAN`,
  `REFERENCE_BOTTOM` (GENERAL) or 8 × `DIAMOND` (indexes 1–8, order of the setting);
  a general-only test has only the three reference runs (indexes 1–3). With fixed
  reference clubs the three kinds are just three positions: show `label` ("Reference") and
  the club's name, not "highest / median / lowest reputation".

`Run`:

```json
{
  "id": 21,
  "index": 0,
  "kind": "CHOSEN",
  "label": "Chosen club",
  "tier": null,
  "diamond": null,
  "teamId": 104,
  "teamName": "FC Example",
  "teamReputation": 7200,
  "predictedPosition": 6,
  "leagueSize": 12,
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

Until the test is bound, the three reference runs have `teamId: 0` and `teamName: null`.
`finalPosition` / `finalLeagueSize`: the club's final place in its own league and the
league's size (`null` until the run is `DONE`); the same two fields are on every `byTier`
and `byClub` entry.

`diamond` (`RunDiamond`): the run's Diamond League, `{overall, home, away, position, of}` —
`Split`s like the league's plus the final place and the number of clubs — or `null` when the
club does not play in it.

`label`: the text to show for the run's role: `"Chosen club"`, `"Reference · top club"`,
`"Reference · mid-table club"`, `"Reference · bottom club"` or `"Diamond League club"`.
`tier`: `TOP` / `MID` / `BOTTOM` for the GENERAL reference runs (from their position; also for
tests stored before the field existed), `null` for the chosen club and DIAMOND runs. With
`tactic-arena.reference-teams` empty the tiers are the highest / median / lowest reputation
of the chosen club's league. `predictedPosition` / `leagueSize`: the club's press prediction, read
from the base world by `teamId` (so finished tests have it too; `null` without predictions).
`progress` = `simulatedDays / totalDays × 100`; `stats` (`basedOn: 1`) and `result` are
`null` until the run is `DONE`. `images` lists the kinds already uploaded.

`RunResult` (what was collected from the simulated season):

```json
{
  "teamId": 104,
  "teamName": "FC Example",
  "color1": "#c00000",
  "color2": "#ffffff",
  "leagueId": 16,
  "leagueName": "Liga 1",
  "season": 1,
  "startDay": 1,
  "endDay": 330,
  "engine": "classic",
  "tactic": PersonalizedTacticView,
  "squad": [ PlayerView ],
  "matches": [ Match ],
  "competitions": [ { ... } ],
  "leagueTable": [ TeamCompetitionView ]
}
```

- `color1`, `color2`: the club's shirt colours (as in `clubs`).
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
  - `inRun`: always `true` now (no match is played before a run starts);
  - `competitionKind`: `LEAGUE` (the club's own league), `DIAMOND`, `CUP`, `CONTINENTAL` or
    `OTHER` (the schedule's `competitionType` says `"League"` for the Diamond League too).
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
- `players`: every player of the club at the END of the season, most appearances first,
  then best average rating; from the game's own per-match rows (`Scorer`, as the squad
  pages), cards from the match events, minutes from the season stats:

  ```json
  { "playerId": 2677, "name": "Ireanius", "position": "GK", "age": 27, "rating": 78.4,
    "apps": 40, "starts": 39, "minutes": null, "goals": 0, "assists": 1, "avgRating": 6.92,
    "yellow": 2, "red": 0, "cleanSheets": 17,
    "byKind": { "LEAGUE": { "apps": 38, "goals": 0, "assists": 1, "avgRating": 6.95 },
                "DIAMOND": { ... }, "CUP": { ... } } }
  ```

  `rating` = the player's value (as in the squad); `apps` = matches he played (starts +
  substitute appearances), `starts`, `goals`, `assists`, `avgRating` (match ratings 0–10,
  two decimals; `null` without a rated match), `yellow`, `red`; `cleanSheets` for
  goalkeepers only (started, no goal conceded), else `null`; `minutes`: `null` when the game
  keeps no minutes for those matches; `byKind`: the same per kind of competition (keys as
  `competitionKind`). There is no man-of-the-match figure in the game.
- `playerSummary`:

  ```json
  { "topScorer": { "playerId": 2698, "name": "Axenus", "goals": 21 },
    "topAssists": { "playerId": 5470, "name": "Itexoa", "assists": 12 },
    "bestRated": { "playerId": 5468, "name": "Saviola", "avgRating": 7.61, "apps": 35 },
    "bestRatedMinApps": 12,
    "teamAvgRating": 6.88 }
  ```

  `bestRated` counts only players with at least `bestRatedMinApps` matches
  (max(5, 30% of the most-used player's matches)); `teamAvgRating` = mean of all the club's
  match ratings. `null` entries when nobody qualifies.
- `squad` stays the squad at the START of the run (its season figures are 0).
- `diamondLeagueId`, `diamondTable`: the Diamond League's id and final table (same items), or
  `null` when the club does not play in it. In `competitions` its entry has `kind: "DIAMOND"`,
  `diamond: true`, `position`, `points`, `of` and `games`, `wins`, `draws`, `loses`,
  `goalsFor`, `goalsAgainst`; every other entry has `kind` `LEAGUE` / `CUP` and `diamond: false`.

### `POST /tactic-arena/tests/{id}/recapture`

Takes the test's images again (e.g. after the page's panels changed): queues a headless
capture whose first step deletes the stored images (database and export folder; the
deletion goes into the next commit), then the page draws and uploads them as after a test
ends. Response `200`: the test's `capture` object (`status: "QUEUED"`). `409` when the test
is not `DONE`, or when captures are not possible on this backend (`error` says why: off, no
browser, page address unknown).

### `POST /tactic-arena/recapture`

Queues the recapture of every `DONE` test, or of one category with `?category=GENERAL` /
`?category=DIAMOND`. Returns at once; the captures run one after the other (one browser at a
time). Response `200`: `{"queued": 54, "category": "ALL"}`. `409` when captures are not
possible on this backend.

At startup the backend also queues the capture of every `DONE` test that has fewer images
than expected (3 per run), so a restart never leaves finished tests without images.

### `POST /tactic-arena/tests/{id}/cancel`

Stops a `QUEUED` or `RUNNING` test (running simulations are killed). Response `200`:
the `TestSummary`. Finished tests are returned unchanged.

### `DELETE /tactic-arena/tests/{id}`

Cancels if needed (a base world being generated keeps going), then deletes the test, its runs and images from the database. The
exported folder on disk is left alone. Response `204`.

### `PUT /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`

`kind` = `tactic` | `schedule` | `competitions`. Body = the image bytes, with header
`Content-Type: image/webp` or `image/png` (max 8 MB). Stored as sent. Replaces an
existing image of the same kind. Response `204`. 404 if the test/run does not exist,
400 for another kind or content type.

### `GET /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`

Returns the bytes with their `Content-Type`; 404 when not uploaded yet.
