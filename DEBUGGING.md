# Debugging toolkit

Four things are wired up: a database console, SQL logging, a set of runnable
HTTP requests, and CPU/memory profiling. None of them is on by default.

---

## 1. H2 console — the database in a browser

The app runs on in-memory H2, so until now there was no way to look at the data
while a season was being simulated. The console is now reachable at
**http://localhost:8086/h2-console**.

It is gated on `spring.h2.console.enabled`, which lives in the local
`application.properties` and is deliberately absent from the packaged
`src/main/resources/application.yml` — the same pattern as `facelab.enabled`.
When the flag is off, `WebSecurityConfig` keeps its `denyAll` rule and Spring
never registers the console servlet.

Three things had to be relaxed for it, all inside the same flag:

- the `/h2-console/**` deny rule becomes `permitAll`
- CSRF is ignored for that path (the console posts plain forms, it has no token)
- `X-Frame-Options` drops from `DENY` to `SAMEORIGIN` (the console uses frames)

### The matcher trap — worth reading before touching this

The two console rules use `PathRequest.toH2Console()`, **not** the usual
`requestMatchers("/h2-console/**")` string. This is not a style choice.

With Spring MVC on the classpath, a string matcher becomes an
`MvcRequestMatcher`, which resolves the pattern against the DispatcherServlet.
The H2 console is a separate servlet, so the pattern misses — and the failure is
confusing rather than obvious:

| Request | With a string matcher | With `PathRequest.toH2Console()` |
|---|---|---|
| `GET /h2-console/` | 200 | 200 |
| `GET /h2-console` | **401** | 302 → `/h2-console/` |
| `POST /h2-console/login.do` | **403** | 200, logs in |

The trailing-slash form working is what makes this hard to spot: the console
appears half-alive, and the actual login is what fails with a Whitelabel 403.

**The JDBC URL changes on every boot** — the datasource is
`jdbc:h2:mem:${random.uuid}`. So `H2ConsoleBanner` reads the URL back off a live
connection and logs it at startup, ready to copy:

```
┌───────────────────────────────────────────────────────────────
│ H2 console  http://localhost:8086/h2-console
│ JDBC URL    jdbc:h2:mem:3f2a...;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
│ User        sa          Password  (empty)
└───────────────────────────────────────────────────────────────
```

Paste that URL into the console's form, leave the password empty, connect.

> Spring Boot's own `H2ConsoleAutoConfiguration` already logs the same URL a few
> lines earlier (`H2 console available at '/h2-console'. Database available at
> 'jdbc:h2:mem:...'`). `H2ConsoleBanner` exists only because that line is easy to
> lose in the boot log; it adds the port and username and prints last, after the
> app is ready. Delete it if the duplication annoys more than it helps.

> A standalone client such as DBeaver can attach to the same database, but only
> over TCP — an in-memory H2 inside the app's JVM is not reachable from another
> process. The web console is the practical option here.

---

## 2. SQL logging

Off by default, because one matchday writes thousands of rows and full
statement logging turns a season simulation into millions of log lines.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=sqldebug
# or
./scripts/profile-app.sh --sqldebug
```

`application-sqldebug.properties` turns on:

| What | Why it matters here |
|---|---|
| `org.hibernate.SQL` + `orm.jdbc.bind` | the exact statement *and* its parameters |
| `hibernate.generate_statistics` | per-session summary — this is how you spot an N+1 |
| `LOG_QUERIES_SLOWER_THAN_MS=25` | just the slow ones, when the full log is too noisy |
| transaction loggers | confirms each `@Transactional` really got its own short session |
| Hikari config | pool exhaustion during parallel matchday simulation shows up here first |

Everything is mirrored to `target/sql-debug.log`, so statements can be counted
afterwards:

```bash
grep -c 'select' target/sql-debug.log
```

---

## 3. Runnable HTTP requests — `http/`

Instead of rebuilding requests by hand each time, the API is checked in as
`.http` files. IntelliJ runs them with a gutter click; VS Code needs the REST
Client extension.

```
http/
  http-client.env.json   ports, credentials, default ids  ← pick "dev" in the env dropdown
  00-auth.http           CSRF, register, login, team pick, admin login
  01-game.http           /game/advance, fast-forward, save export, squad, finances
  02-competition.http    standings, fixtures, simulateRound, European groups, coefficients
  03-match-live.http     match events, stats, and the interactive live session
  04-admin.http          setScore, injectMoney, generatePlayer, draws, repairs
```

**Run `00-auth.http` top to bottom first.** It stores two things every other
file depends on: the session cookie, and a `{{csrfToken}}` global that gets sent
as `X-XSRF-TOKEN` on every mutating request.

Two entry points worth knowing:

- `04-admin.http` → `setScore` forces an exact scoreline, so a qualification or
  tie-break bug can be reproduced without fighting the RNG.
- `02-competition.http` → `simulateRound` re-simulates one round without
  advancing the calendar — the narrowest reproduction available.

Admin requests need both a `ROLE_ADMIN` session and the `X-Admin-Token` header;
`adminLogin` in `00-auth.http` sets up both. Note that it invalidates the
manager session, so re-run `login` afterwards to go back.

---

## 4. Profiling — VisualVM and JFR

### Setup already done

VisualVM 2.2.1 is installed at `/Applications/VisualVM.app`.

No JDK on this machine is registered with `/usr/libexec/java_home` (they all
come from Homebrew), so VisualVM could not find one to start on. It is pointed
at JDK 17 through a **user-level** config file:

```
~/Library/Application Support/VisualVM/2.2.1/etc/visualvm.conf
```

That location matters. Editing `visualvm.conf` inside `/Applications/VisualVM.app`
breaks the bundle's code signature and macOS then refuses to launch the app,
silently and with no error message. The user-level file is read afterwards and
overrides the bundled one.

### The JDK 26 trap

`mvn -v` reports **JDK 26**, and the IntelliJ run configuration uses it too, but
the project targets 17. VisualVM 2.2.1 cannot model a JDK 26 JVM — it logs
`Unrecognized java.vm.version 26.0.1` and the process shows up crippled.

So for profiling, start the app on 17:

```bash
./scripts/profile-app.sh
```

It pins `JAVA_HOME` to `openjdk@17` and adds `-XX:+DebugNonSafepoints`, which is
what makes JFR's method sampling trustworthy — without it the JVM only samples
at safepoints and systematically blames the wrong lines inside hot loops, which
is exactly the kind of code a season simulation is made of.

`jcmd` still attaches fine to a JDK 26 process, so `scripts/jfr.sh` works against
an IDE-launched app as well; only VisualVM's live view suffers.

### VisualVM

Open it from the Dock or Spotlight — the process appears under **Local**. Useful
tabs: **Monitor** (heap, GC, threads), **Sampler** (CPU and memory sampling, no
restart needed), **Threads** (deadlocks, thread pool saturation during parallel
matchday simulation).

### JFR — `scripts/jfr.sh`

JFR costs 1–2% and is built into the JVM, so it can be switched on against a
process that is already misbehaving. No restart, no agent.

```bash
./scripts/jfr.sh start          # then trigger the slow work
./scripts/jfr.sh dump           # writes target/jfr/<timestamp>.jfr
./scripts/jfr.sh stop

./scripts/jfr.sh summary <file> # event counts — what kind of problem is this
./scripts/jfr.sh hot <file>     # hottest project methods
./scripts/jfr.sh hotall <file>  # hottest methods overall (JDK/Hibernate too)
./scripts/jfr.sh open <file>    # open in VisualVM
```

Or record from the first millisecond:

```bash
./scripts/profile-app.sh --record
./scripts/profile-app.sh --record --duration 120s
```

Two truncation traps the script already handles, both of which silently produce
an empty `hot` list:

- the **recording** defaults to 64 stack frames, and a request passes through
  dozens of Tomcat/Spring/Hibernate frames before reaching our code — `start`
  raises this to 256
- `jfr print` **displays** only 5 frames unless told otherwise — the script
  passes `--stack-depth 2048`

Sample output from a 10-second recording:

```
 489 com.footballmanagergamesimulator.service.CoachPermissionService.getOrDefault(long)
 238 com.footballmanagergamesimulator.service.TransferOfferLifecycleService.removeActiveOffersForPlayers(Collection)
```

---

## Not set up

**Spring Boot Actuator** was left out deliberately. It would add
`/actuator/mappings` (worth having against a 6700-line controller),
`/actuator/loggers` (change log levels at runtime without a restart),
health, metrics and heap dumps — one dependency plus a few lines of security
config. Worth adding if the above turns out not to be enough.

`springfox` and `springdoc` are both on the classpath. springfox 3 is
unmaintained and does not work on Boot 3; only springdoc is actually serving
`/swagger-ui/index.html`. Removing the two springfox dependencies from `pom.xml`
would be a safe cleanup.
