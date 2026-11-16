[Personal project of a football manager game] Argus Football World ===

<img width="1512" height="904" alt="image" src="https://github.com/user-attachments/assets/a7a620fe-d326-44d9-a4bc-e4374df964db" />


 
Project Overview

A web-based football management game set in its own fictional world. Built with a Java Spring Boot backend and an Angular frontend, it lets players shape a club through tactics, squad planning, finances, scouting, and matchdays. Its aim is to make choices and their consequences feel connected across seasons, rather than reproduce another game's interface or feature list.

The interface offers multiple themes, interactive data visualizations, tactical boards, and analytical dashboards.

Key Features

⚽ Tactics & Match Engine

Interactive Tactics Board: Full drag-and-drop functionality to set formations and player roles on a visual pitch.

Advanced Instructions: Granular control over team mentality, possession style, passing directness, and tempo.

Squad Planner: Visual depth chart to plan the current and future squad structure.

📊 Data Hub & Analytics

Performance Analysis: SVG-based Radar Charts comparing team performance against league averages.

Key Findings: Automated insights into attacking and defensive efficiency (xG, pass completion, etc.).

Match Reports: Detailed post-match breakdowns and opponent analysis.

🌍 Club & Competition Management

Dynamic Competitions: specialized views for League Tables (standings, form guides) and Cup Tournaments (knockout brackets).

Club Overview: Detailed profiles including stadium info, rivalries, kits, and club history/honours.

Finances: Interactive charts tracking income, expenditure, and wage budgets over time.

🔎 Scouting & Player Development

Advanced Scouting Network: Searchable database with complex filtering (age, salary, attributes, potential).

Player Profiles: Comprehensive views for attributes, contract details, career history, and trophy cabinets.

Development Center: Track loan progress with visual form guides and manage youth prospects.

Medical Centre: Risk assessment dashboard monitoring match load, fatigue, and injury susceptibility.

Tech Stack

Backend

Java Spring Boot: RESTful API architecture.

Hibernate / JPA: Data persistence and object-relational mapping.

Jackson: Advanced JSON processing for complex tactical data storage.

H2 Database: Lightweight, in-memory database for development and testing.

Frontend

Angular: Component-based SPA architecture.

TypeScript: Strongly typed logic for complex game mechanics.

CSS Grid & Flexbox: Responsive layouts for the game's management screens.

SVG & Canvas: Custom-built charts and tactical visualizations without heavy external libraries.

This is an original, fictional football management world. Football Manager is one reference point for studying the genre, not a design specification for this game.

<img width="1512" height="857" alt="image" src="https://github.com/user-attachments/assets/5b737987-ad1c-4699-973c-d37c3b81ac5c" />

## Development setup

The application is split across two GitHub repositories:

- Backend: `https://github.com/CiprianAmza/footballmanager-backend-.git`
- Frontend: `https://github.com/CiprianAmza/footballmanager-frontend-test.git`

### Requirements

- Java 17
- Maven 3.9+
- Docker with Docker Compose
- Node.js 20 and npm 10
- Git LFS (required for the bundled save game)

On macOS, install and initialise Git LFS before cloning:

```bash
brew install git-lfs
git lfs install
```

Clone both projects:

```bash
mkdir -p ~/IdeaProjects
cd ~/IdeaProjects
git clone https://github.com/CiprianAmza/footballmanager-backend-.git
git clone https://github.com/CiprianAmza/footballmanager-frontend-test.git
```

If the backend was cloned before Git LFS was installed, download the save with:

```bash
cd ~/IdeaProjects/footballmanager-backend-
git lfs pull
```

Start the backend:

```bash
cd ~/IdeaProjects/footballmanager-backend-
docker compose up -d postgres
mvn clean install
mvn spring-boot:run
```

Start the frontend in a second terminal:

```bash
cd ~/IdeaProjects/footballmanager-frontend-test
npm ci
npm start
```

- Frontend: `http://localhost:4200`
- Backend: `http://localhost:8086`

The backend uses PostgreSQL by default. Local Docker credentials are:

```text
Database: footballmanager
Username: footballmanager
Password: footballmanager
JDBC URL: jdbc:postgresql://localhost:5432/footballmanager
```

Override `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DRIVER`, `DB_DIALECT`, and
`DDL_AUTO` with deployment secrets outside local development. PostgreSQL data is
retained in the Docker volume `postgres-data` when the container is stopped.

The backend owns this schema and currently completes it with Hibernate
`ddl-auto=update`; Flyway has only the PostgreSQL identity/security migration so
far. This is suitable for the local transition and lets read-only services share
the database. Before production, convert the remaining historical H2 migrations
to PostgreSQL and switch `DDL_AUTO` to `validate`. A separate service should use
its own database user with `SELECT` permission instead of the owner credentials.

## Bundled save game

The repository contains the current career snapshot at:

```text
saves/ciprian-sherlock-season-15-day-341.json
```

Snapshot state:

- Season 15
- 7 July (day 341, morning)
- Ciprian managing Sherlock FC
- Save format version 5

The save is stored through Git LFS because the complete match and career history
is approximately 411 MB. After starting the backend, restore it with:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data-binary @saves/ciprian-sherlock-season-15-day-341.json \
  http://localhost:8086/game/import
```

A successful import returns `"success": true` together with the restored season,
date, team and manager. Refresh the frontend after the import.

The development database is persisted by PostgreSQL, so restarting the backend
does not erase the running career. Export snapshots before destructive schema
changes or whenever a portable save is needed:

```bash
curl http://localhost:8086/game/export -o football-manager-save.json
```

## 2D match engine (optional microservice)

The game can play its matches with a separate service, the 2D match engine: it
simulates the whole match on a pitch from the players' ratings and the clubs'
tactics, and the game stores what it produced. It is **off by default**; with
the flag off nothing changes and the service is never called.

### 1. Start the service

The engine is its own small project in this repository, in the folder
`match2d-service` (Java 17 or newer, no dependencies, not part of the Maven
build). Its scripts use `JAVA_HOME` when set, otherwise `java`, `javac` and
`jar` from the PATH; on Windows run them from Git Bash.

```bash
cd ~/IdeaProjects/footballmanager-backend-/match2d-service
bash build.sh serve                                  # builds out/serve/match2d.jar (the service must be stopped)
java -jar out/serve/match2d.jar serve --port 8791
curl http://localhost:8791/health                    # {"status":"ok","engine":"java",...}
```

`match2d-service/README.md` (in Romanian) describes the engine itself: its
rules, weights, events, the knockout request and its own tests
(`bash test.sh KnockoutTest`).

### 2. Choose the engine

In `src/main/resources/application.yml`, or as command-line arguments
(`--match2d.mode=all --match2d.service.url=http://localhost:8791`):

```yaml
match2d:
  mode: "off"          # "off" = classic engine | "human" = 2D only in matches of a human-managed club | "all" = every match
  fallback: classic    # classic = play the round with the classic engine when the service fails | fail = error
  service:
    url: http://localhost:8791
    timeout-seconds: 20
```

Keep the quotes around `"off"`: YAML reads a bare `off` as a boolean. The flag
needs `match.engine.match-plan.enabled: true` (the default).

### 3. What the game takes from the 2D engine

| | With `match2d.mode` on |
|---|---|
| Score at 90 minutes, scorers, assists, goal minutes | 2D engine |
| Player ratings, man of the match, match stats | 2D engine |
| Knockout ties level after 90 minutes | 2D engine: extra time (2 x 15 minutes), then a penalty shootout |
| Injuries | classic rules |
| Substitutions, red cards | none: the starting eleven plays the whole match |
| Admin-forced score | still wins |
| Service down, or a fixture it cannot play | the round is played by the classic engine, with a warning in the log |

Each competition round is sent in one request. The engine plays the starting
eleven the game itself picks for the fixture. A match played by the 2D engine
does not open the interactive live match.

Knockout ties: a single-leg tie goes to extra time when it is level after 90
minutes; in a two-leg tie the second leg goes to extra time when the aggregate
is level. Extra-time goals count in the score and for the scorers; shootout
kicks do not. There is no away-goals rule.

### 4. Watch a match again

Every fixture played by the 2D engine is stored (table `match2d_replay`) with
the exact request that produced it, so the same match can be shown later:

```bash
curl http://localhost:8086/match/2d/replay/{competitionId}/{season}/{round}/{teamId1}/{teamId2}
# {"viewUrl":"http://localhost:8791/view/...","score":[2,1],"engineVersion":"1","versionMismatch":false}
```

`viewUrl` is a page served by the engine (pitch, highlights mode, goal
replays); the frontend shows it in the **Watch 2D** window, from the result
window after Continue and from the match report. The human match result and
`/match/report/...` carry `"match2d": true` for such a fixture. The route
answers 404 for a match that was not played by the 2D engine and 502/503 when
the service does not answer. The engine keeps only the last matches in memory,
so the backend sends the stored request again each time; a replay matches the
stored result only while the engine version is the same (`versionMismatch`).

### 5. Ask for a result on demand

These routes only return what the engine played; they write nothing to the
database and do not need `match2d.mode`:

```bash
# one match (seed, minutes and ref are optional)
curl -X POST -H "Content-Type: application/json" \
  -d '{"homeTeamId": 3, "awayTeamId": 8, "seed": 7, "minutes": 90, "ref": "round-5"}' \
  http://localhost:8086/match/2d/result

# a group of matches, answered in the same order
curl -X POST -H "Content-Type: application/json" \
  -d '{"matches": [{"homeTeamId": 3, "awayTeamId": 8}, {"homeTeamId": 5, "awayTeamId": 9, "ref": "b"}]}' \
  http://localhost:8086/match/2d/results
```

A result has `score`, `goals` (minute, team, `playerId`, `assistId`, own goal,
how), `summary` (team stats), `players` (rating, man of the match, counters per
player), `highlights` and `engineVersion`; a fixture that failed comes back as
`{"ref", "error"}` without failing the group. Without a seed, the same pair of
teams always gives the same match. Two more routes help while developing:
`GET /match/2d/preview?teamId1=..&teamId2=..&seed=..&minutes=..` (plays a match
and returns its `viewUrl`) and `GET /match/2d/lineup/{teamId}` (the team exactly
as the engine receives it).

The service's own routes, used by the backend: `POST /results` (one fixture or
`{"matches": [...]}`, up to 512, played in parallel), `POST /matches` (plays one
fixture and keeps it for `GET /view/{id}`), `GET /health`.

### 6. Tests

```bash
mvn test -Dtest='Match2d*Test,MatchPlanningServicePlayed*Test'
```

## Tactic testing (Tactic Arena)

Tactic Arena tests a tactic the way fm-arena.com does: the same tactic plays whole seasons
of the real game, and the results are averaged into a leaderboard. Everything runs on this
computer; the career you are playing is never changed. The HTTP contract is
[`docs/TACTIC_ARENA_API.md`](docs/TACTIC_ARENA_API.md); the code is in the
`tacticarena` package.

### 1. Start a test from the interface

1. Open **Tactic Arena** in the menu, then **New test**.
2. Pick any club in the world (clubs are grouped by league).
3. Build the tactic on the normal tactics page: formation, roles, duties, player and team
   instructions, set-piece takers and the starting eleven from that club's squad. Saving
   there does not touch the career; it only keeps the tactic for the test.
4. Give the test a name and press **Start**. The page shows the progress of every run and
   of the whole test, survives a page reload and has a **Cancel** button.
5. When it finishes, the leaderboard (`/tactic-arena`) lists it and its page shows the
   results block, then for each run the tactic, the full schedule and the competition
   overview as images.

### 2. What is simulated

Each test makes **4 runs**, each one a full season of the real game (every competition the
club plays: league, cups, continental), with the tested tactic fixed all season:

| Run | Club | Starting eleven |
|-----|------|-----------------|
| 0 `CHOSEN` | the club you picked | the eleven you picked |
| 1 `REFERENCE_TOP` | highest reputation in that club's league | the game's automatic best XI for the formation |
| 2 `REFERENCE_MEDIAN` | median reputation in the league | same |
| 3 `REFERENCE_BOTTOM` | lowest reputation in the league | same |

Reference clubs get the same formation and instructions; the roles, duties and player
instructions are copied slot by slot onto their automatic eleven.

How a run works:

1. When the test starts, the backend copies the current game database once
   (`BACKUP TO`, a consistent hot copy of the H2 file; the career keeps running).
2. Every run gets its own unpacked copy and its own Java process: the same application and
   classpath, started with `--tactic-arena.child.run-dir=<dir>` and a random port.
3. In that copy the tested club becomes the only human-managed club, with "always
   continue" on, and the test tactic is saved as its tactic. The game then plays its own
   calendar day by day, exactly as when you press Continue: injuries, suspensions,
   fatigue and the other clubs behave normally. If the game changes the club's tactic,
   it is put back before the next day; when a selected player is unavailable the game
   replaces the eleven itself, as it does for your own club.
4. The season runs from the career's **current day** to the **last match day of the
   current season** (no season transition). A test started mid-season says how many
   league matches were left (`leagueMatchesRemaining` of `leagueMatchesTotal`); only
   matches simulated by the run count.
5. Match engine: the one the game runs with (`match2d.mode`). With `off` every match is
   classic; otherwise only the tested club's matches go through the 2D engine (`human`)
   and every other match stays classic, so a season does not take tens of minutes. The
   test records the engine it used (`classic` or `2d-human`).
6. At the end the run writes what it collected (every match of the club with day,
   competition, stage, home/away, opponent, score with extra time and shoot-out, xG; the
   club's competitions with league place, points, W/D/L, goals, stage reached and
   trophies; the final league table; the tactic, eleven and squad used) and exits.

Runs execute in parallel (`tactic-arena.parallel-runs`, default 2). Only one test runs at
a time; the next ones wait in the queue. A crashed run is marked `FAILED` with its
message and does not block the queue. If the backend restarts, unfinished tests and runs
become `FAILED`.

### 3. How the averages are computed

Like fm-arena, from the **3 reference runs** ("Based on 3 test result(s)"), league matches
only, separately for Overall / Home / Away:

- `PLD` = total league matches of the three runs;
- `PTS` = average points per season, shown against the maximum (`73 / 114` = average
  matches per season × 3);
- `GF`, `GA`, `GD`, `xGF`, `xGA`, `xGD` = averages per season, rounded to whole numbers.

The run with the chosen club gets the same figures, shown separately.

### 4. How long it takes

Measured on this computer (H2 file database of about 40 MB, 12-club league, test started
on day 61 of 330, `parallel-runs=2`, 2D service not running so every match was classic):
the whole test took **about 6 minutes**, about 3 minutes per run, of which about 30
seconds is the start of the simulation process. A test started on day 1 simulates about a
fifth more days; a bigger world, a bigger league or the 2D engine for the club's matches
make it longer.

### 5. Where results are kept

- Database tables (created by Hibernate): `tactic_arena_test`, `tactic_arena_run`,
  `tactic_arena_run_result` (the collected season as gzip-compressed JSON) and
  `tactic_arena_image` (the WebP/PNG captures as uploaded; they are already compressed, so
  they are not gzipped again).
- Images are captured by the test page from the real panels and uploaded once
  (`PUT /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`).

### 6. Export to a folder and GitHub

The database is wiped often in development, so results can also live on disk:

```properties
tactic-arena.export-dir=C:/Users/me/IdeaProjects/footballmanager-tactic-arena-results/tests
```

When a test finishes the backend writes `<dir>/<id>-<short-name>/result.json.gz` (the
whole test as `GET /tactic-arena/tests/{id}` returns it, gzip) and the images as
`<run>-<kind>.webp|png` as they arrive. At startup, tests found in the folder that are
missing from the database are imported back.

To keep them on GitHub by hand, make the folder part of a separate repository and commit it
yourself:

```bash
cd C:/Users/me/IdeaProjects/footballmanager-tactic-arena-results
git add -A tests
git commit -m "Tactic tests"
git push
```

**Automatic push** (`tactic-arena.git-push=true`, default `false`): after a test is
exported, and after images of an exported test arrive (grouped: at most one push every few
seconds), the backend runs in the export folder, with the `git` on the PATH:
`git add -A .`, `git commit -m "Tactic test <id>: <name>"` (only when something changed)
and `git push`. It needs an export folder inside a working copy of a GitHub repository
whose remote and git authentication are already configured on this computer (the backend
creates no repository and stores no credentials). It never prompts (`GIT_TERMINAL_PROMPT=0`,
no input), has a time limit and runs one git command at a time. A failure (no network, no
authentication, a conflict) does not affect the test: the backend logs a warning with git's
output, `GET /tactic-arena/tests/{id}` shows it in `gitPush`, and the next test tries
again, pushing any commit that stayed local.

### 7. Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `tactic-arena.parallel-runs` | `2` | runs simulated at the same time |
| `tactic-arena.export-dir` | empty (no export) | folder for exported tests |
| `tactic-arena.git-push` | `false` | commit and push the export folder after each test |
| `tactic-arena.work-dir` | `<temp>/fm-tactic-arena/<port>` | database copies and logs of running tests |
| `tactic-arena.child-jvm-args` | `-Xmx3g` | JVM options of each simulation process |

### 8. Limits

- Only the H2 file database is supported. With PostgreSQL the test fails at start: the
  copy would need `pg_dump`/`createdb` (or a template database) per run and a child
  started with that database's URL; this is not implemented.
- Each run needs disk space for one database copy and memory for one more JVM
  (`child-jvm-args`); two parallel runs need roughly two extra game backends.
- A run starts the application again on its copy (about a minute) before it simulates.
- With the 2D mode on, the tested club's matches use the 2D service; if it is down the game
  falls back to the classic engine as usual (the test still says `2d-human`).
- A test can only start while the season still has matches to play.

## Automated testing

Fast unit tests:

```bash
mvn test
```

Standard integration suite:

```bash
mvn verify
```

Full career simulation through the real calendar, every competition and three
season transitions:

```bash
mvn verify -Pcareer
```

Use `-Dcareer.seasons=2` for the shorter two-season variant. The accepted range
is deliberately limited to 2–3 seasons. A run writes its audit report to
`target/full-career-simulation.md`.


