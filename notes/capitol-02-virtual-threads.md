# Capitolul 02 — Understanding Virtual Threads

## Rezumat amplu

Capitolul pornește de la limitarea fundamentală moștenită din modelul clasic Java: platform threads sunt mapate 1:1 pe kernel threads, deci sunt scumpe (memorie, context switch, limite OS), iar asta plafonează scalabilitatea aplicațiilor server. Soluția propusă sunt virtual threads (stabile din JDK 21, Project Loom): threads gestionate integral de JVM, nu de OS, cu stack-ul stocat în heap-ul garbage-collected, ceea ce le face să pornească de la câteva sute de bytes și să crească dinamic. JVM-ul le rulează prin multiplexare pe un pool mic de carrier threads (un ForkJoinPool dedicat, FIFO — distinct de common pool-ul LIFO folosit de parallel streams), cu paralelism implicit egal cu numărul de procesoare, configurabil prin `jdk.virtualThreadScheduler.parallelism`. Ideea centrală este că blocking-ul devine ieftin: când un virtual thread întâlnește o operație blocantă (I/O, sleep), este demontat (unmounted) de pe carrier thread, stack-ul îi este copiat înapoi în heap, iar carrier-ul rămâne liber pentru alte virtual threads — analogia autorului fiind memoria virtuală, unde paginile inactive sunt „paged out" pe disc. API-ul rămâne intenționat familiar: virtual threads sunt instanțe de `Thread`, se creează cu `Thread.startVirtualThread()`, `Thread.ofVirtual()` sau `Executors.newVirtualThreadPerTaskExecutor()`, iar interruption funcționează identic ca la platform threads. Există totuși particularități: toate virtual threads sunt daemon, au prioritate fixă `NORM_PRIORITY` (setDaemon/setPriority nu au efect) și aparțin unui singur ThreadGroup. Justificarea teoretică a scalabilității vine din Little's Law (λ = N/d): dacă latența d nu poate fi redusă (I/O de rețea, disc), singura pârghie pentru throughput e creșterea concurenței N — exact ce permit virtual threads, demonstrat printr-un benchmark unde virtual threads ating ~18.000 tasks/s față de ~2.000 tasks/s pentru un fixed pool de 1.000 platform threads. Autorul subliniază apăsat că virtual threads nu sunt mai rapide, ci mai scalabile, și ajută doar în workload-uri I/O-bound cu multe task-uri concurente, nu în cele CPU-bound. Concurența „nelimitată" ridică însă o problemă nouă: resursele din aval (baze de date, API-uri externe) nu suportă milioane de cereri simultane, iar thread pool-ul, care înainte juca implicit rolul de limitator, dispare — soluția fiind rate limiting explicit cu `Semaphore` (inclusiv variante cu fairness, `tryAcquire` cu timeout și monitoring al peak-ului de conexiuni). Urmează limitările: pinning (virtual thread-ul rămâne blocat pe carrier în blocuri `synchronized` — rezolvat abia în JDK 24 prin JEP 491 — și la apeluri native/foreign functions, unde persistă), cu remedii precum `ReentrantLock` (bazat pe park/unpark, prietenos cu unmounting-ul); și `ThreadLocal`, care la milioane de threads devine o bombă de memorie (demonstrat cu obiecte de 500 KB × 1.000 threads în JConsole), alternativa modernă fiind scoped values (Capitolul 5). Capitolul acoperă apoi tooling-ul de diagnoză: flag-urile `-Djdk.tracePinnedThreads` și `-Djdk.traceVirtualThreadLocals`, evenimentele JFR (`jdk.VirtualThreadPinned` etc.), thread dumps cu `jcmd` în format JSON și `HotSpotDiagnosticMXBean.dumpThreads()`. Se face și o deschidere spre structured concurrency (JEP 505, JDK 25 preview) ca răspuns la fragilitatea gestionării manuale a excepțiilor și timeout-urilor pe `Future`-uri. Închide cu sfaturi de migrare (librării actualizate, izolarea codului blocant legacy în thread pools clasice, monitorizarea CPU/memorie/latență) și reafirmă beneficiile: request-per-thread redevine viabil la scară mare, cu cod simplu, sincron, blocant.

## Concepte explicate

### Virtual thread

- **Definiție** — Un thread lightweight gestionat de JVM (nu de OS), instanță a clasei `Thread`, al cărui stack trăiește în heap și crește/scade dinamic. Nu e mapat 1:1 pe un kernel thread; multe virtual threads se multiplexează pe puține carrier threads.
- **De ce contează** — Elimină plafonul de scalabilitate impus de costul platform threads: poți avea milioane de task-uri concurente blocante fără să epuizezi memoria sau OS-ul. Reînvie modelul simplu „un request = un thread".
- **Exemplu de cod**
  ```java
  // Greșit: pare că merge, dar nu afișează nimic — virtual threads sunt daemon,
  // iar main-ul se termină înainte ca task-ul să ruleze.
  Thread.startVirtualThread(() -> System.out.println("salut"));

  // Corect: aștepți terminarea.
  Thread vt = Thread.ofVirtual().start(() -> System.out.println("salut"));
  vt.join();
  ```
- **Capcane frecvente** — Uiți `join()`/shutdown și task-urile mor odată cu main-ul; te aștepți ca task-urile CPU-bound să meargă mai repede (nu merg — beneficiul e doar la I/O-bound); încerci `setPriority`/`setDaemon(false)` (nu au efect); pool-uiești virtual threads (antipattern — sunt de unică folosință, unul per task).

### Platform thread (carrier thread)

- **Definiție** — Thread-ul clasic Java, mapat 1:1 pe un kernel thread al OS-ului, cu stack alocat monolitic. Un **carrier thread** e un platform thread dintr-un ForkJoinPool dedicat (FIFO), pe care JVM-ul „montează" temporar virtual threads pentru a le executa.
- **De ce contează** — Carrier threads sunt resursa rară (implicit = numărul de core-uri). Tot ce le blochează inutil (pinning) sabotează întregul model. OS-ul nu vede decât carrier threads; virtual threads îi sunt invizibile.
- **Exemplu de cod**
  ```java
  // Identitatea carrier-ului apare în toString-ul virtual thread-ului:
  Thread.ofVirtual().start(() ->
      System.out.println(Thread.currentThread()))
      // ex: VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1
      .join();
  // Paralelismul carrier-ilor e configurabil (setat înainte de prima folosire):
  // java -Djdk.virtualThreadScheduler.parallelism=4 -jar app.jar
  ```
- **Capcane frecvente** — Presupui că poți afla programatic din cod pe ce carrier rulezi (nu există API; `Thread::getAllStackTraces` nici măcar nu include virtual threads); confuzi ForkJoinPool-ul scheduler-ului de virtual threads cu common pool-ul parallel streams (sunt separate, moduri FIFO vs. LIFO); setezi `parallelism` după ce scheduler-ul a fost deja folosit (prea târziu).

### Mounting / unmounting (blocking ieftin)

- **Definiție** — Mecanismul prin care JVM-ul copiază stack frames-urile unui virtual thread din heap pe stack-ul carrier-ului la execuție (mount) și înapoi în heap la o operație blocantă (unmount), eliberând carrier-ul. Aproape toate punctele blocante din JDK au fost adaptate să declanșeze unmount.
- **De ce contează** — E exact ce face blocking-ul „gratuit": poți scrie cod sincron, blocant (`Future.get()`, `Thread.sleep()`, I/O) fără penalizarea de performanță care a împins ecosistemul spre callback-uri și reactive programming.
- **Exemplu de cod**
  ```java
  // Stil sincron, dar scalabil: 10.000 de task-uri care „dorm" 1s
  // rulează concurent pe o mână de carrier threads.
  try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      IntStream.range(0, 10_000).forEach(i ->
          executor.submit(() -> {
              Thread.sleep(Duration.ofSeconds(1)); // unmount, carrier liber
              return i;
          }));
  } // try-with-resources așteaptă terminarea tuturor task-urilor
  ```
- **Capcane frecvente** — Crezi că unmount-ul funcționează oriunde: în blocuri `synchronized` (pre-JDK 24) și în cod nativ NU funcționează (vezi pinning); presupui că ThreadLocal-urile carrier-ului sunt vizibile din virtual thread (nu sunt — izolarea e completă).

### Little's Law (λ = N/d)

- **Definiție** — Lege a sistemelor cu cozi: throughput-ul λ = concurența medie N împărțită la latența medie d. E agnostică față de natura unității de concurență (thread, casier de bancă, ATM).
- **De ce contează** — Explică matematic de ce virtual threads scalează: când d e ireductibil (I/O de rețea/disc), singura cale spre throughput mai mare e creșterea lui N — imposibilă cu platform threads limitate de OS, trivială cu virtual threads.
- **Exemplu de cod**
  ```java
  // 10.000 task-uri × 500ms latență fiecare:
  // N nelimitat  -> toate rulează simultan -> total ~0,5s -> ~18k tasks/s
  // N = 100      -> 100 de „valuri" -> total ~50s -> ~200 tasks/s
  benchmark(Executors.newVirtualThreadPerTaskExecutor()); // N ~ nelimitat
  benchmark(Executors.newFixedThreadPool(100));           // N = 100
  ```
- **Capcane frecvente** — Concluzia greșită că virtual threads reduc latența unui task individual (nu — d rămâne același, crește doar N); benchmark-uri naive fără warm-up JVM/iterații multiple/JMH, care măsoară artefacte, nu diferențe reale.

### Rate limiting cu Semaphore

- **Definiție** — `Semaphore` (java.util.concurrent) ține evidența unui număr fix de permits: `acquire()` blochează dacă nu mai sunt, `release()` returnează unul. Cu virtual threads devine mecanismul explicit de limitare a accesului la resurse finite (DB, API-uri), rol jucat înainte implicit de mărimea thread pool-ului.
- **De ce contează** — Virtual threads elimină limitatorul natural: aplicația poate accepta un milion de requesturi, dar baza de date nu le duce. Fără rate limiting explicit, sufoci resursele din aval. Bonus: blocarea pe `acquire()` e ieftină, fiind un punct de unmount.
- **Exemplu de cod**
  ```java
  // Greșit: semaforul nu leagă permit-ul de thread — release fără acquire
  // sau acquire fără release corup numărul efectiv de permits.
  sem.acquire();
  doWork();          // dacă aruncă, permit-ul se pierde definitiv
  sem.release();

  // Corect: acquire ÎNAINTE de try, release în finally.
  sem.acquire();
  try {
      return doWork();
  } finally {
      sem.release();
  }
  ```
- **Capcane frecvente** — `release()` în afara `finally` (leak de permits la excepții); `release()` dintr-un thread care n-a făcut `acquire()` (semaforul nu verifică — crește tăcut limita efectivă); `acquire()` fără timeout (blocare nedefinită — preferă `tryAcquire(t, unit)` cu degradare grațioasă); limită prea mică, care sugrumă concurența și anulează beneficiul virtual threads; semafor fair vs. nonfair ales fără gândire (fair previne starvation, dar costă performanță).

### Pinning

- **Definiție** — Situația în care un virtual thread NU se poate demonta de pe carrier deși e blocat, monopolizând carrier-ul. Cauze: blocuri/metode `synchronized` (pe JDK ≤ 23) și apeluri native/foreign functions. Diagnostic vizibil: același `ForkJoinPool-1-worker-N` înainte și după blocare.
- **De ce contează** — Cu doar câteva carrier threads disponibile, câteva virtual threads pinned cu operații blocante lungi înfundă tot sistemul: throughput redus, coadă de virtual threads care așteaptă carrier liber — exact anti-teza modelului.
- **Exemplu de cod**
  ```java
  // Risc de pinning (JDK <= 23): apel blocant în synchronized
  synchronized (lock) {
      var resp = httpClient.send(req, bodyHandler); // carrier blocat tot timpul
  }

  // Corect: ReentrantLock permite unmount în timpul blocării
  lock.lock();
  try {
      var resp = httpClient.send(req, bodyHandler); // carrier eliberat
  } finally {
      lock.unlock();
  }
  ```
- **Capcane frecvente** — Panică la orice `synchronized`: cele scurte, ne-blocante (ex. `return a + b;`) sunt practic inofensive — problema e blocarea ÎN interiorul blocului (`wait()`, I/O, sleep); ignori pinning-ul din librăriile third-party neactualizate; uiți că JEP 491 (JDK 24) rezolvă doar `synchronized`, nu și pinning-ul din cod nativ, class initializers sau class loading; pe LTS (JDK 21) problema e în continuare reală.

### ReentrantLock și park/unpark

- **Definiție** — `ReentrantLock` oferă aceleași garanții de excludere mutuală ca `synchronized`, dar e implementat peste primitiva `LockSupport.park()/unpark()`, pe care JVM-ul o detectează și o tratează ca punct de unmount pentru virtual threads. Oferă în plus fairness, `tryLock`, interruptibility.
- **De ce contează** — Este remediul standard pentru pinning pe JDK ≤ 23: un virtual thread blocat pe `ReentrantLock` eliberează carrier-ul și poate fi remontat ulterior pe ORICE carrier disponibil (dovada: worker-1 înainte, worker-3 după).
- **Exemplu de cod**
  ```java
  private static final ReentrantLock lock = new ReentrantLock();

  void criticalSection() throws InterruptedException {
      lock.lock();
      try {
          Thread.sleep(25); // virtual thread-ul se demontează; carrier liber
      } finally {
          lock.unlock();    // OBLIGATORIU în finally, altfel deadlock
      }
  }
  ```
- **Capcane frecvente** — `unlock()` uitat sau în afara `finally` (deadlock permanent, mai grav decât la synchronized, care se eliberează singur la ieșirea din bloc); migrare mecanică synchronized→ReentrantLock peste tot, inclusiv unde nu era nicio problemă (blocuri scurte ne-blocante).

### ThreadLocal la scară de virtual threads

- **Definiție** — `ThreadLocal` dă fiecărui thread propria copie a unei variabile. Pattern-ul rămâne funcțional pe virtual threads (inclusiv moștenirea de la părinte), dar costul per-thread se înmulțește cu milioane.
- **De ce contează** — 1.000.000 de virtual threads × un obiect ThreadLocal de 500 KB = dezastru de memorie; plus overhead de inițializare/curățare și bug-uri subtile de inheritance. Alternativa proiectată pentru virtual threads: scoped values (imutabile, cu lifetime delimitat — Capitolul 5).
- **Exemplu de cod**
  ```java
  // Problematic la scară: fiecare din milioanele de virtual threads
  // își materializează propriul buffer mare.
  static final ThreadLocal<byte[]> BUF =
      ThreadLocal.withInitial(() -> new byte[512 * 1024]);

  // Mai bine: pasează contextul explicit ca parametru,
  // sau folosește ScopedValue (JDK nou):
  static final ScopedValue<RequestContext> CTX = ScopedValue.newInstance();
  ScopedValue.where(CTX, ctx).run(() -> handleRequest());
  ```
- **Capcane frecvente** — Migrezi la virtual threads păstrând ThreadLocal-uri grele din era pool-urilor (unde erau reutilizate de N threads, acum sunt N milioane de copii); uiți `remove()` (deși la threads de unică folosință problema devine volumul, nu leak-ul clasic); folosești ThreadLocal pentru `SimpleDateFormat` când `DateTimeFormatter` e deja thread-safe.

### Monitoring și diagnoză (flags, JFR, jcmd)

- **Definiție** — Setul de unelte pentru depistarea problemelor specifice virtual threads: `-Djdk.tracePinnedThreads=full|short` (stack trace la fiecare pinning, cu `reason:MONITOR` și marcajul `<== monitors:1`), `-Djdk.traceVirtualThreadLocals` (stack trace la accesul ThreadLocal din virtual threads), evenimentele JFR `jdk.VirtualThreadStart/End/Pinned/SubmitFailed` (Pinned e activ implicit, prag 20ms), thread dumps JSON via `jcmd <PID> Thread.dump_to_file -format=json` sau `HotSpotDiagnosticMXBean.dumpThreads()`.
- **De ce contează** — Migrarea aplicațiilor legacy (milioane de linii) nu se poate face „citind codul": ai nevoie de semnale runtime care să arate exact UNDE apar pinning-ul și abuzul de ThreadLocal, ca să știi ce refactorizezi.
- **Exemplu de cod**
  ```bash
  # Depistare pinning:
  java -Djdk.tracePinnedThreads=full MyApp.java
  # Înregistrare JFR țintită pe evenimente de virtual threads:
  java -XX:StartFlightRecording=filename=rec.jfr,settings=VThreadEvents.jfc MyApp
  jfr print --events jdk.VirtualThreadPinned rec.jfr
  # Thread dump JSON (include virtual threads, spre deosebire de dump-ul clasic):
  jcmd $(jps -q) Thread.dump_to_file -format=json dump.json
  ```
- **Capcane frecvente** — Te bazezi pe thread dump-ul tradițional (nu vede virtual threads); ignori că dump-ul `jcmd` omite locks, adrese de obiecte, statistici heap; nu observi că evenimentele `VirtualThreadStart/End` sunt dezactivate implicit (doar `Pinned` și `SubmitFailed` sunt active); pentru `HotSpotDiagnosticMXBean.dumpThreads()` uiți că path-ul trebuie să fie absolut.

### Structured concurrency (avanpremieră)

- **Definiție** — API (JEP 505, preview în JDK 25) care rulează subtask-uri concurente într-un scope cu lifecycle comun: `StructuredTaskScope.open()`, `scope.fork(...)`, `scope.join()`; dacă un subtask eșuează sau expiră timeout-ul, toate celelalte din scope sunt anulate automat.
- **De ce contează** — Exemplele „naive" cu `Future.get()` nu tratează bine excepțiile și pot bloca nedefinit fără timeout. Scope-ul fail-fast încapsulează anularea, propagarea excepțiilor și timeout-urile într-un construct built-in, declarativ.
- **Exemplu de cod**
  ```java
  try (var scope = StructuredTaskScope.open()) {
      var user  = scope.fork(() -> fetchUser(id));
      var stats = scope.fork(() -> fetchStats(id));
      scope.join();                       // dacă unul pică, celălalt e anulat
      return new Profile(user.get(), stats.get());
  } // necesită JDK 25 cu --enable-preview
  ```
- **Capcane frecvente** — Agregi Future-uri manual fără timeout/anulare a fraților la eșec (leak de muncă orfană); folosești API-ul pe JDK 21 fără preview (nu compilează). Detaliile complete vin în Capitolul 4.

## Listing-uri cheie din carte

Textul capitolului NU numerotează exemplele cu captions „Example 2-N" (singura numerotare „Example 1/2/3" apare la mini-exemplele de risc de pinning din secțiunea despre synchronized). Le identific după secțiune:

1. **Creating Virtual Threads in Java** — cele patru căi de creare: `Thread.startVirtualThread()`, `Thread.ofVirtual().start()/.unstarted()`, `Executors.newVirtualThreadPerTaskExecutor()`. Instructiv pentru capcana daemon-by-default (primul exemplu nu afișează nimic fără `join()`).
2. **Adapting to Virtual Threads** — perechea `PlatformThreadInterruption` / `VirtualThreadInterruption`: output identic, demonstrând că semantica `interrupt()` e neschimbată; plus `VirtualThreadGroupExample` (100 de threads, un singur ThreadGroup „VirtualThreads").
3. **Demonstrating Virtual Thread Creation** — 10.000 de task-uri cu sleep de 1s pe `newVirtualThreadPerTaskExecutor`; contrastul cu `newCachedThreadPool` (crash potențial) și `newFixedThreadPool(200)` (serializare).
4. **The Fundamental Principle Behind Virtual Threads' Scalability** — `LittleLawExample`: benchmark comparativ virtual threads vs. fixed pools (100/500/1000); cifrele (18.115 vs. 198/989/1968 tasks/s) fac legea palpabilă.
5. **Simplifying Asynchronous Operations** — `generatePhrase()` (două Future-uri, `get()` blocant fără remușcări) și `ImageProcessingExample` (`invokeAll` pentru task-uri omogene cu agregare în listă).
6. **The Promise of Structured Concurrency** — schița `StructuredTaskScope` cu două `fork`-uri; teaser pentru Capitolul 4.
7. **Managing Resource Constraints with Rate Limiting** — `ResourceAwareRateLimitExample` (50 de cereri HTTP, semafor de 10): pattern-ul complet acquire/try/finally/release peste virtual threads.
8. **Understanding Semaphores in Java** — `ResourcePool`, apoi `MonitoredResourcePool` (fairness, `tryAcquire` cu timeout de 5s, contoare atomice active/peak) și `ResourcePoolTest` (50 de cereri, peak niciodată > 5, unele timeout-uri — dovada că limitarea funcționează). Sidebar-ul „Limitations of Semaphores" arată bug-ul release-fără-acquire.
9. **Pinning** — `ThreadPinnedExample`: sleep în `synchronized`, același carrier înainte/după = pinning dovedit empiric prin toString.
10. **Addressing the Pinning Problem with ReentrantLock** — `PreventPinningExample`: același scenariu cu `ReentrantLock`; carrier diferit după blocare (worker-1 → worker-3) = unmount reușit. Cel mai instructiv contrast al capitolului.
11. **Synchronized Blocks and Virtual Thread Pinning** — cele trei mini-exemple numerotate 1-3: bloc scurt inofensiv, apel HTTP blocant riscant, `wait()` la fel de riscant; nuanțează „nu tot synchronized-ul e rău".
12. **Native Method Invocation and Pinning** — funcția C `addNumbers` cu `usleep(200000)` invocată prin FFM API (`ThreadPinnedNativeMethodExample`): pinning la cod nativ, plus nota că pe JDK 25 outputul arată carriers diferiți (JEP 491).
13. **The Conundrum of ThreadLocal Variables** — `ThreadLocalExample`: 1.000 threads × obiect de 500 KB în ThreadLocal, cu comparația de heap în JConsole (cu/fără ThreadLocal).
14. **Monitoring** — `JFRVirtualThreadDemo` (trei threads: lifecycle simplu, pinned pe synchronized, ne-pinned pe ReentrantLock — JFR le distinge), fișierul `.jfc` custom, `ThreadDumpDemo` (jcmd programatic din ProcessBuilder) și `takeThreadDump()` cu `HotSpotDiagnosticMXBean`.

## Citate

- „Virtual threads are not designed to be faster but to offer greater scalability." — secțiunea *Throughput and Scalability*
- „The operating system is unaware of virtual threads." — secțiunea *Carrier Threads and OS Involvement*
- „But remember, with virtual threads, blocking is cheap." — secțiunea *Managing Resource Constraints with Rate Limiting*

## Legături

- **Din Capitolul 1 (Introduction)**: capitolul 2 concretizează promisiunea schițată acolo — de ce modelul thread-per-request a devenit neviabil cu platform threads și cum Loom îl reabilitează. Aici primești mecanica: scheduler, mount/unmount, heap stacks.
- **Spre Capitolul 3 (The Mechanics of Modern Concurrency)**: autorul promite explicit („more about how virtual threads work and their internals will be discussed in later chapters") aprofundarea internals-urilor — continuations, detaliile scheduler-ului.
- **Spre Capitolul 4 (Structured Concurrency)**: secțiunea „The Promise of Structured Concurrency" e un teaser direct; problemele lăsate deschise aici (excepții pe `Future.get()`, lipsa timeout-urilor, anularea fraților) sunt exact ce rezolvă `StructuredTaskScope`.
- **Spre Capitolul 5 (Scoped Values)**: problema ThreadLocal (memorie, inheritance) e motivația explicită a scoped values — capitolul 2 o pune pe masă, capitolul 5 o rezolvă.
- **Spre Capitolul 6 (Reactive Java)**: dacă blocking-ul e ieftin, rațiunea de a exista a codului reactiv (evitarea blocării) se subțiază — capitolul 2 furnizează argumentul, capitolul 6 trage concluziile.
- **Spre Capitolul 7 (Modern Frameworks)**: sfaturile de migrare (librării actualizate, „know your framework") anticipează discuția despre suportul Spring/Quarkus etc.
- **De stăpânit înainte de a merge mai departe**: diferența platform vs. virtual vs. carrier thread; mecanismul mount/unmount și de ce face blocking-ul ieftin; Little's Law și limitarea la workload-uri I/O-bound; pinning-ul (cauze, diagnoza cu `jdk.tracePinnedThreads`/JFR, remedierea cu ReentrantLock, statutul JEP 491 pe JDK 24+ vs. LTS 21); pattern-ul semafor pentru rate limiting; de ce ThreadLocal nu scalează. Fără acestea, structured concurrency și scoped values din capitolele 4-5 rămân soluții fără problemă.
