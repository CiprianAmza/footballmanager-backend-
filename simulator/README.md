# Tactic Arena simulator

A self-contained Tactic Arena: one Java process serves the pages and the API, simulates
the tests, takes the screenshots in a headless Edge/Chrome and commits + pushes every
result to the results repository. Any computer with Java 17+ and git can run it.

## Needs

- Java 17 or newer (`java -version`);
- git, with push rights on the results repository (credentials already configured:
  `git push` must work there without a prompt);
- optional: Microsoft Edge or Google Chrome/Chromium, for the automatic screenshots.

## Start

```bash
./run.sh                 # Linux, macOS, Git Bash
run.bat                  # Windows
./run.sh https://github.com/<owner>/<results-repo>.git   # another results repository
```

It clones (or updates) the results repository into `./results`, keeps its own database in
`./data` and its work files in `./work`, and starts on port 8390 (`PORT=...` /
`-Port ...` to change). Open `http://localhost:8390/tactic-arena`.

- If the repository already has a base world (`baseline/`), the simulator starts from a
  copy of it (about a minute) and uses it for every test.
- If not, the first start generates a game world (about 8 minutes), then the base world is
  generated once (about 5 minutes) and pushed, so later computers skip both.
- Every finished test goes to `results/tests/<id>-<name>-<uid>/` (result + 12 screenshots)
  and is committed and pushed. Tests pushed by other computers appear in the leaderboard.

Stop with Ctrl+C. Start again in the same folder to continue with the same database.
