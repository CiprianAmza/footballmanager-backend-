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

The game can have its matches drawn by a separate service, the 2D match engine:
it simulates the whole match on a pitch from the players' ratings and the clubs'
tactics. The **score is always decided by the classic engine** (Compartment V1):
the 2D engine receives that score as a target and plays a match that ends
exactly with it, and the game keeps the details of that match. It is **off by
default**; with the flag off nothing changes and the service is never called.

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

### 2. Choose which matches are drawn

In `src/main/resources/application.yml`, or as command-line arguments
(`--match2d.mode=all --match2d.service.url=http://localhost:8791`):

```yaml
match2d:
  mode: "off"          # "off" = no match is drawn | "human" = matches of a human-managed club | "all" = every match
  fallback: classic    # classic = a match the 2D engine cannot draw stays classic | fail = error
  service:
    url: http://localhost:8791
    timeout-seconds: 60
```

Keep the quotes around `"off"`: YAML reads a bare `off` as a boolean. The flag
needs `match.engine.match-plan.enabled: true` (the default).

The mode never changes a result: scores, extra time and shootouts are exactly
those of `mode: "off"`. It only says which matches also get a drawn 2D match.
`human` draws the one or two matches of the human clubs in a round; `all` draws
every match of every competition, and each drawn match costs one or more 2D
simulations (the engine plays until a match ends with the target score). As
measured: about 1.3 s per drawn match, so a round of ~20 matches takes 10-36 s
with `all`, against ~0.3 s per matchday with `off`; with `human`, the match of
the human-managed club takes about 2-3 s more. `timeout-seconds` (default 60)
applies to each drawn match; a match that takes longer stays classic on its
own, and the next match is asked for as usual.

### 3. What the game takes from the drawn match

For a match the mode selects, the classic engine first decides it completely:
the 90-minute score and, in a knockout tie, extra time and the shootout. Then
the game sends the fixture to the engine (`POST /results`, one match) with that
result as `target` (`score`, plus `extraTime` and `penalties` only when they
were played), and the engine plays a match that ends exactly with it.

| | Drawn match (`match2d.mode` on) |
|---|---|
| Score at 90 minutes, extra time, shootout | classic engine (the drawn match reproduces them) |
| Scorers, assists, goal minutes (90 minutes and extra time) | drawn match |
| Player ratings, man of the match, match stats | drawn match |
| Injuries | classic rules |
| Substitutions, red cards | none in a drawn match: the starting eleven plays the whole match |
| Admin-forced score | not drawn: the match stays classic |
| A timeout, an error, or a match that misses the target | that match stays wholly classic (classic scorers, no replay), with a warning in the log |
| Service down (cannot be reached) | the match stays classic, and so does every match of the next ~30 s, without asking the service; one warning when the pause starts, one with the number of skipped matches when it ends |

The engine plays the starting eleven the game itself picks for the fixture. A
match of a human club chosen for drawing does not open the interactive live
match, even when the service then fails (it is then resolved instantly by the
classic engine). The game accepts a drawn match only when its goals add up and
it matches the target exactly: the 90-minute goals of each side, the extra-time
goals of each side and the shootout score. Only a service that cannot be
reached at all (connection refused, or no connection within 3 s) pauses the 2D
engine for 30 seconds.

Knockout ties are decided by the classic rule: a single-leg tie goes to extra
time when it is level after 90 minutes; in a two-leg tie the second leg goes to
extra time when the aggregate is level. Extra-time goals count in the score and
for the scorers; shootout kicks do not. There is no away-goals rule.

### 4. Watch a match again

Every fixture drawn by the 2D engine is stored (table `match2d_replay`) with
the exact request that produced it (target included), so the same match can be
shown later:

```bash
curl http://localhost:8086/match/2d/replay/{competitionId}/{season}/{round}/{teamId1}/{teamId2}
# {"viewUrl":"http://localhost:8791/view/...","score":[2,1],"engineVersion":"1","versionMismatch":false}
```

`viewUrl` is a page served by the engine (pitch, highlights mode, goal
replays); the frontend shows it in the **Watch 2D** window, from the result
window after Continue and from the match report. The human match result and
`/match/report/...` carry `"match2d": true` for such a fixture. The route
answers 404 for a match that was not drawn by the 2D engine and 502/503 when
the service does not answer. The engine keeps only the last matches in memory,
so the backend sends the stored request again each time; a replay matches the
stored result only while the engine version is the same (`versionMismatch`).

### 5. Ask for a result on demand

These routes only return what the engine played, without a target; they write
nothing to the database and do not need `match2d.mode`:

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

The service's own routes, used by the backend: `POST /results` (one fixture,
optionally with `target`, or `{"matches": [...]}`, up to 512, played in
parallel), `POST /matches` (plays one fixture and keeps it for `GET /view/{id}`),
`GET /health`.

### 6. Tests

```bash
mvn test -Dtest='Match2d*Test,MatchPlanningServicePlayed*Test'
```

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


