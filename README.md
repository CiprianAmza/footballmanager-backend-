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
computer and on a world of its own: the career you are playing (its database, its user, its
club) is never read or changed. The HTTP contract is
[`docs/TACTIC_ARENA_API.md`](docs/TACTIC_ARENA_API.md); the code is in the `tacticarena`
package.

### 1. Start a test from the interface

The Tactic Arena pages and routes are public: they open without an account (a signed-in
user is only recorded as the test's owner; a guest may type a name, else "guest").

1. Open **Tactic Arena** in the menu, then **New test**.
2. If there is no base world yet (see 3), generate it once and wait for it.
3. Pick any club of the base world (clubs are grouped by league).
4. Build the tactic on the normal tactics page: formation, roles, duties, player and team
   instructions, set-piece takers and the starting eleven from that club's squad **as it is
   at the start of season 1 of the base world**. Saving there only keeps the tactic for the
   test.
5. Give the test a name and press **Start**. The page shows the progress of every run and
   of the whole test, survives a page reload and has a **Cancel** button.
6. When it finishes, the leaderboard (`/tactic-arena`) lists it and its page shows the
   results block, then for each run the tactic, the full schedule and the competition
   overview as images.

### 2. What is simulated

Every run is the whole of **season 1** of the real game, from its first day to the last
match day (every competition the club plays: league, cups, continental), with the tested
tactic fixed all season. A test has two parts:

- **General** (always): the tactic on the **same three reference clubs for every test**, so
  that all tests are compared on the same clubs: `tactic-arena.reference-teams`, by default
  `Inazuma Japan, Diamond Dust, Desert Lion` (all three in the Eleven League), whatever the chosen
  club's league. The averages and the leaderboard come from these three runs.
- **Diamond League** (a separate category, `"category": "DIAMOND"`): instead of the three
  reference clubs, one full season on each of the 8 clubs of `tactic-arena.diamond-teams`
  (Inazuma Japan, Shadows, FC San Marino, Tik Tok, Xenon, Ligthnings, No Merci, Athletic
  Sohatu), averaged over the 8 runs; `ptsPct` (points as a share of the maximum) compares
  clubs of leagues of different sizes. Example body:
  `{"name": "Diamond 433", "category": "DIAMOND", "autoEleven": true, "tactic": {"tactic": "433"}}`.
- **Custom** (optional): the club the user picked, with the eleven they picked, shown
  separately. A test launched with `autoEleven: true` and no club is general only (three
  runs); a script can launch many of these.

| Run | Club | Starting eleven |
|-----|------|-----------------|
| 0 `CHOSEN` | the club you picked (custom part) | the eleven you picked, or the automatic one with `autoEleven` |
| 1 `REFERENCE_TOP` (tier `TOP`) | 1st reference club, the top club (Inazuma Japan) | the game's automatic best XI for the formation |
| 2 `REFERENCE_MEDIAN` (tier `MID`) | 2nd reference club, the mid-table club (Diamond Dust) | same |
| 3 `REFERENCE_BOTTOM` (tier `BOTTOM`) | 3rd reference club, the bottom club (Desert Lion) | same |

Reference clubs get the same formation and instructions; the roles, duties and player
instructions are copied slot by slot onto their automatic eleven. With
`tactic-arena.reference-teams` empty, the reference clubs are instead the highest, median
and lowest reputation of the chosen club's league. Next to each club's reputation the pages
show the press's predicted league finish ("6/12", the game's media prediction), stored in
the base world's `clubs.json`.

How a run works:

1. Every run unpacks its own fresh copy of the **base world** (3) and starts its own Java
   process on it: the same application and classpath, with
   `--tactic-arena.child.run-dir=<dir>` and a random port. So every club plays on a new,
   untouched season 1, and all runs of all tests start from the same world.
2. In that copy the tested club becomes the only human-managed club, with "always
   continue" on, and the test tactic is saved as its tactic. The game then plays its own
   calendar day by day, exactly as when you press Continue: injuries, suspensions,
   fatigue and the other clubs behave normally. If the game changes the club's tactic,
   it is put back before the next day; when a selected player is unavailable the game
   replaces the eleven itself, as it does for your own club.
3. Match engine: the one the backend runs with (`match2d.mode`). With `off` every match is
   classic; otherwise only the tested club's matches go through the 2D engine (`human`)
   and every other match stays classic, so a season does not take tens of minutes. The
   test records the engine it used (`classic` or `2d-human`).
4. At the end the run writes what it collected (every match of the club with day,
   competition, stage, home/away, opponent, score with extra time and shoot-out, xG; the
   club's competitions with league place, points, W/D/L, goals, stage reached and
   trophies; the final league table; the tactic, eleven and squad used) and exits. The
   backend stores it and deletes the run's database copy at once.

Runs of all tests share `tactic-arena.parallel-runs` simulation processes (default 4).
Tests are served in launch order: a test stays `QUEUED` until its first run gets a slot,
and the runs of two or more tests can run at the same time. A crashed run is marked
`FAILED` with its message and does not block the queue. If the backend restarts,
unfinished tests and runs become `FAILED`.

### 3. The base world

The base world is a world newly generated by the game, at season 1, day 1, with its
calendar created and no match played. It is generated **once**, in a child process on an
empty database (the game's own world generation, as on a first start), then:

1. a test user starts a career the way a player does (`CareerOnboardingService.setupManager`
   for the lowest-reputation club that takes a manager without a licence), and the season 1
   calendar is created;
2. the database is backed up (`world.zip`) before any match;
3. the club list and every club's tactics-page data (squad, formations, the club's own
   tactic) are written next to it (`clubs.json`, `setup/<teamId>.json.gz`, `meta.json`).
   The launch page reads these files, so you choose the eleven from the squad that will
   really play.

It lives in `tactic-arena.baseline-dir` (default `<work-dir>/baseline`). Several backends
can share the folder: generating takes a lock file (`<baseline-dir>.lock`; a lock left by a
dead process, or older than 2 hours, is taken over), while reading and copying need no
lock. If it is missing, the first test generates it (the test shows `PREPARING` with an
approximate progress, then its runs start), or generate it from the launch page
(`POST /tactic-arena/baseline/rebuild`). `GET /tactic-arena/baseline` shows whether it
exists, when it was made and with which build.

`POST /tactic-arena/baseline/refresh` recomputes only `clubs.json` (for example the press
predictions) from a copy of the existing `world.zip`, in about 20 seconds, without changing
the world, its uid or the tests bound to it.

Generate it again (**Rebuild**) when the game's world changes: new or changed nations,
leagues or clubs, or a change in how players or squads are generated. A new world has
different players, so tests that were created on the old one and have not started yet fail
when their turn comes; finished tests keep their results.

### 4. How the averages are computed

Like fm-arena, from the **3 reference runs** ("Based on 3 test result(s)"), league matches
only, separately for Overall / Home / Away:

- `PLD` = total league matches of the three runs;
- `PTS` = average points per season, shown against the maximum (`73 / 114` = average
  matches per season × 3, from the reference clubs' own league);
- `GF`, `GA`, `GD`, `xGF`, `xGA`, `xGD` = averages per season, rounded to whole numbers.

The run with the chosen club gets the same figures, shown separately. The leaderboard also
carries each reference run on its own (`byTier`: top / mid-table / bottom club; `byClub`:
every club, also for DIAMOND), so the best tactic overall, for a top club, a mid-table club
or a bottom club can be told apart by sorting.

### 5. How long it takes

Measured on this computer (182 clubs, 12-club league of 44 rounds, every match classic):

- **First time only**, generating the base world: about **4.5 minutes** (new world, career
  step, backup, the 182 club pages). It is about 7 MB on disk.
- Then every test: each run starts its process on a fresh copy (about 40 seconds) and plays
  the 330 days of season 1. With 4 runs at once, one test took about **6 minutes**; two
  tests launched together took about 10 minutes, the second one's runs starting as soon as
  the first one's finished.

A bigger league, more parallel runs than processor cores, or the 2D engine for the club's
matches make it longer.

### 6. Where results are kept

- Database tables (created by Hibernate): `tactic_arena_test`, `tactic_arena_run`,
  `tactic_arena_run_result` (the collected season as gzip-compressed JSON) and
  `tactic_arena_image` (the WebP/PNG captures as uploaded; they are already compressed, so
  they are not gzipped again).
- Images are captured by the test page from the real panels and uploaded once
  (`PUT /tactic-arena/tests/{id}/runs/{runId}/images/{kind}`).
- Disk while a run works: its database copy and log in `<work-dir>/test-<id>/run-<n>`
  (the copy starts at about 30 MB and grows to about 300 MB by the end of the season). The folder is deleted as soon as the result is stored; after a failed run only
  its log stays.

### 7. Export to a folder and GitHub

The database is wiped often in development, so results can also live on disk:

```properties
tactic-arena.export-dir=C:/Users/me/IdeaProjects/footballmanager-tactic-arena-results/tests
```

When a test finishes the backend writes `<dir>/<id>-<short-name>-<uid>/result.json.gz`
(the whole test as `GET /tactic-arena/tests/{id}` returns it, gzip) and the images as
`<run>-<kind>.webp|png` as they arrive. Tests found in the folder that are missing from the
database are imported at startup and, at most every 30 seconds, when the leaderboard is
opened (so tests finished by other backends appear too).

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
and `git push`; when the push is refused because another backend pushed first, it runs
`git pull --rebase --autostash` and pushes again (up to 3 times; every test has its own
folder, so there is nothing to conflict). It needs an export folder inside a working copy
of a GitHub repository whose remote and git authentication are already configured on this
computer (the backend creates no repository and stores no credentials). It never prompts
(`GIT_TERMINAL_PROMPT=0`, no input), has a time limit and runs one git command at a time.
A failure (no network, no authentication) does not affect the test: the backend logs a
warning with git's output, `GET /tactic-arena/tests/{id}` shows it in `gitPush`, and the
next test tries again, pushing any commit that stayed local.

### 8. Running several instances

To simulate more at once, start more backends (each runs its own child processes) and,
if you want to watch them, one interface per backend.

Shared between instances: the base world (`tactic-arena.baseline-dir`, same folder), the
results folder (`tactic-arena.export-dir`) and its GitHub repository. Not shared: each
backend's own game database, its port, its child processes and its `tactic-arena.work-dir`
(the default already depends on the port).

A second backend, e.g. on port 8293 (H2 file database; the folder can be empty, the
backend then generates a new career world on its first start, about 8 minutes):

```bash
export DB_URL="jdbc:h2:file:C:/fm/instance2/fm;AUTO_SERVER=TRUE"
export DB_USERNAME=sa DB_PASSWORD= DB_DRIVER=org.h2.Driver DB_DIALECT=org.hibernate.dialect.H2Dialect
java -cp "target/classes;<dependency classpath>" com.footballmanagergamesimulator.Main \
  --server.port=8293 --spring.flyway.locations=classpath:db/migration/h2 \
  --cors.allowed-origins=http://localhost:4216 \
  --tactic-arena.baseline-dir=C:/fm/tactic-arena-baseline \
  --tactic-arena.export-dir=C:/Users/me/IdeaProjects/footballmanager-tactic-arena-results/tests \
  --tactic-arena.git-push=true
```

Give every backend the same `baseline-dir` and `export-dir`.

The interface picks its backend from the port of the page: `urlApp` in
`src/app/app.component.ts` maps 4215 → 8291, 4201 → 8087 and every other port → 8086.
For a new pair today you have to add the mapping there (for example
`window.location.port === '4216' ? 'http://localhost:8293' : ...`), start
`ng serve --port 4216` and start the backend with
`--cors.allowed-origins=http://localhost:4216`. Then open `http://localhost:4216/tactic-arena`.

### 9. Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `tactic-arena.parallel-runs` | `4` | simulation processes of this backend, shared by all tests |
| `tactic-arena.diamond-teams` | `Inazuma Japan, Shadows, FC San Marino, Tik Tok, Xenon, Ligthnings, No Merci, Athletic Sohatu` | the clubs of the DIAMOND category, one run each |
| `tactic-arena.reference-teams` | `Inazuma Japan, Diamond Dust, Desert Lion` | the three fixed reference clubs (names in the base world); empty = highest/median/lowest reputation of the chosen club's league |
| `tactic-arena.baseline-dir` | `<work-dir>/baseline` | the base world (can be shared by several backends) |
| `tactic-arena.export-dir` | empty (no export) | folder for exported tests |
| `tactic-arena.git-push` | `false` | commit and push the export folder after each test |
| `tactic-arena.work-dir` | `<temp>/fm-tactic-arena/<port>` | database copies and logs of running runs |
| `tactic-arena.child-jvm-args` | `-Xmx3g` | JVM options of each simulation process |
| `tactic-arena.ui-dir` | empty | folder of the simulator build of the interface; when set, the backend serves it (`/`, `/tactic-arena`, `/tactic-arena/new`, `/tactic-arena/{id}`) |
| `tactic-arena.capture` | `auto` | `auto`: after a test, open its page in a headless Edge/Chrome so the page uploads its 12 images; `off` |
| `tactic-arena.capture.browser` | found automatically | path of Edge/Chrome (Windows install folders, then `google-chrome`, `chromium`, `microsoft-edge` on the PATH, then the macOS apps) |
| `tactic-arena.capture.page-url` | own address when `ui-dir` is set | base address of the pages, e.g. `http://localhost:4215` |
| `tactic-arena.capture.timeout-seconds` | `300` | the browser is stopped after this, images or not |

### 10. Limits

- Child processes always use an H2 file database (their own copy). The backend itself can
  run on any database; on PostgreSQL the result and image columns are large objects.
- Each running run needs disk space for one database copy and memory for one more JVM
  (`child-jvm-args`): four parallel runs are roughly four extra game backends.
- A run starts the application again on its copy before it simulates.
- With the 2D mode on, the tested club's matches use the 2D service; if it is down the game
  falls back to the classic engine as usual (the test still says `2d-human`).
- The base world folder defaults to the temp folder; set `tactic-arena.baseline-dir` to a
  durable place to keep it across temp clean-ups.

### 11. Simulator: run it on any computer

The simulator is one Java process that serves the Tactic Arena pages and API itself, runs
the tests, takes the screenshots without a person and commits + pushes everything to the
results repository. The public site that reads that repository is
<https://ciprianamza.github.io/footballmanager-tactic-arena-results/>.

**What you need**: Java 17+; git with push rights on the results repository (credentials
already configured, `git push` must work there without a prompt); optionally Microsoft Edge
or Google Chrome/Chromium for the screenshots (without one, tests finish without images).

**Build the package** (once, on a computer with Maven, Node and the frontend checkout):

```bash
simulator/build.sh <frontend-dir>          # or: powershell -File simulator\build.ps1 -Frontend <frontend-dir>
```

It runs `ng build --configuration simulator` in the frontend (the pages call the API on
their own origin), `mvn package -DskipTests` (an executable Spring Boot jar) and puts
`simulator.jar`, `ui/`, `run.sh`, `run.ps1`, `run.bat` and a short README into
`simulator/dist/` (about 95 MB). Copy that folder to any computer.

**Start it** in an empty folder:

```bash
<dist>/run.sh [results-repository-url]     # Linux, macOS, Git Bash
<dist>\run.bat [results-repository-url]    # Windows
```

The default repository is `https://github.com/CiprianAmza/footballmanager-tactic-arena-results.git`.
The script clones (or updates) it into `./results`, keeps the simulator's database in
`./data` and its work files in `./work`, and starts on port 8390 (`PORT=` / `-Port`) with
`ui-dir`, `export-dir=results/tests`, `baseline-dir=results/baseline`, `git-push=true`,
`match2d.mode=off` and an H2 file database (no Docker, no PostgreSQL). Open
`http://localhost:8390/tactic-arena`.

**What it does by itself**:

1. If the repository has a base world (`results/baseline/`), the simulator's own database
   starts as a copy of it (about a minute) and every test uses it.
2. If not, the first start generates a game world (about 8 minutes); the base world is then
   generated once (from the page or by the first test), committed and pushed. Before it
   generates one it runs `git pull`, so a base world pushed meanwhile by another computer
   is used instead. Its lock file and build folders stay outside git
   (`.git/info/exclude`).
3. Every test runs as described above; when it is DONE its result is exported to
   `results/tests/<id>-<name>-<uid>/`, the page is opened in a headless browser, which
   draws and uploads the 12 images (tactic, schedule, competitions × 4 runs), and the
   folder is committed and pushed (`git pull --rebase` and retry when another computer
   pushed first). Tests pushed by other computers show up in the leaderboard.

Measured on this computer: building the package about 2.5 minutes (95 MB: an 86 MB jar and
a 9 MB `ui/`). First start against an empty repository: about 7 minutes until the page
answers, then about 7.5 minutes for the base world (committed and pushed). A test with 4
runs: about 6.5 minutes, then its 12 screenshots in about 16 seconds (headless Edge), all
pushed. A second start in another empty folder with the same repository: about 45
seconds, no generation, and the existing test is in the leaderboard.

The results repository then holds `baseline/` (the shared base world: `world.zip`,
`clubs.json`, `setup/`, `meta.json`), `tests/` (one folder per test) and the site.



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


