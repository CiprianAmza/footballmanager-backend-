# In-memory simulation checkpoint prototype

This branch adds an opt-in `fast-sim` profile. Gameplay repositories use an H2 database in memory. At a configured number of matchdays, the application captures an H2 SQL snapshot while holding the game lock, then compresses and writes that immutable snapshot to a separate PostgreSQL database on a background worker. A manual checkpoint uses the same path. On restart, the latest complete checkpoint is restored before game initialization.

This is an experimental test mode, **not a migration of the existing PostgreSQL save**. Use a separate PostgreSQL database for checkpoints. The current implementation snapshots the whole H2 world, including its history; it does not yet keep historical seasons separately in PostgreSQL or run against an existing multi-season save. A process crash can lose advances after the latest completed checkpoint. Snapshot capture itself briefly blocks an advance, while compression and PostgreSQL upload run in the background.

Example local run (choose an isolated, empty PostgreSQL database):

```sh
CHECKPOINT_DB_URL=jdbc:postgresql://localhost:5432/fm_checkpoint_test \
CHECKPOINT_DB_USERNAME=footballmanager \
CHECKPOINT_DB_PASSWORD=footballmanager \
CHECKPOINT_INTERVAL_MATCHDAYS=5 \
mvn spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=fast-sim --server.port=8087'
```

The separate `application-fast-sim.properties` in the repository root is needed when `spring-boot:run` finds the existing external root configuration. The classpath YAML supplies the same settings for packaged application runs. `CHECKPOINT_DB_URL` is required, so this mode cannot silently write snapshots into the normal PostgreSQL save.

After admin login, `GET /game/checkpoints` reports interval, matchdays since the last completed save, save state, captured game date, capture timestamp, and persistence timestamp. `PUT /game/checkpoints/interval` with `{"matchdays": 5}` changes and persists the interval. `POST /game/checkpoints` starts a manual save and returns `202`; poll the status until it is `SAVED` or `FAILED`. These mutating routes require the admin role and the normal CSRF header.

The integration test needs an isolated PostgreSQL test database:

```sh
CHECKPOINT_TEST_DB_URL=jdbc:postgresql://localhost:5432/fm_checkpoint_it \
CHECKPOINT_TEST_DB_USERNAME=footballmanager \
CHECKPOINT_TEST_DB_PASSWORD=footballmanager \
mvn -Dtest=MemoryCheckpointIT test
```

Without `CHECKPOINT_TEST_DB_URL`, this test is skipped. It exercises the two-matchday interval, background upload, and restore of the captured H2 state.

Before using this mode with a real long-running save, the next steps are a migration/import path for the current PostgreSQL world, moving historical reads to PostgreSQL, a checkpoint retention policy, and end-to-end performance and recovery tests on a representative multi-season dataset.
