# Capitolul 03 — The Mechanics of Modern Concurrency in Java

## Rezumat amplu

Capitolul coboară sub API-ul de virtual threads prezentat în Capitolul 2 și explică mecanica internă prin care acestea funcționează. Autorul pornește de la premisa (citându-l pe Ibn Sina) că nu cunoști cu adevărat un lucru până nu îi cunoști cauzele, deci un developer solid trebuie să înțeleagă implementarea, nu doar utilizarea. Două concepte sunt anunțate ca fundament: ForkJoinPool (scheduler-ul care rulează virtual threads) și continuation (mecanismul prin care un virtual thread se poate opri și relua exact de unde a rămas). Drumul începe însă mai de jos, cu noțiunea de thread pool: de ce nu putem crea threads ad-hoc la nesfârșit (limitele OS-ului, risc de crash, lipsa de rate limiting) și ce câștigăm dintr-un pool — control asupra numărului de threads, latență mică (threads gata create), lifecycle gestionat automat și un model mental de „task ca unitate de lucru". Pentru a fixa ideea, autorul construiește de la zero un SimpleThreadPool cu workers, BlockingQueue, flag volatile de shutdown și închidere grațioasă prin AutoCloseable. Urmează Executor framework-ul real din JDK: ThreadPoolExecutor cu parametrii săi (corePoolSize, maximumPoolSize, keepAliveTime, workQueue, threadFactory, rejection handler) și tipurile de pool-uri oferite de Executors — FixedThreadPool, CachedThreadPool, SingleThreadExecutor, ScheduledThreadPoolExecutor, WorkStealingPool — fiecare cu scenariul lui de utilizare. Regula clasică de dimensionare este reamintită: workload CPU-bound → threads cât core-urile; workload I/O-bound → mai multe threads, dar exact acolo virtual threads elimină nevoia de tuning (cu mențiunea onestă că pool-urile clasice nu dispar — studiul OpenLiberty arată că un pool tradițional bine acordat poate încă bate virtual threads). Capitolul introduce apoi Callable și Future pentru task-uri care întorc rezultate, subliniind că get() este blocant. Punctul de cotitură este demonstrația cu Fibonacci: același algoritm recursiv, rulat pe un FixedThreadPool cu 100 de threads, intră în deadlock — fiecare task părinte ține un thread ostatic așteptându-și copiii care nu mai apucă să ruleze — pe când ForkJoinPool rezolvă instant, pentru că un worker care așteaptă un join poate executa alte task-uri în loc să blocheze. Sunt explicate apoi mecanica work-stealing (fiecare worker are propriul deque; owner-ul ia LIFO din capăt pentru cache locality, hoții iau FIFO din coadă pentru a minimiza contention), operațiile CAS care înlocuiesc lock-urile, modul async (FIFO) pentru task-uri de tip eveniment și motivul pentru care exact acest pool, în async mode, a fost ales ca scheduler pentru virtual threads. A doua jumătate a capitolului tratează continuation-urile: capacitatea unui bloc de cod de a face yield și de a fi reluat mai târziu, demonstrată cu API-ul intern jdk.internal.vm.Continuation (cu avertisment explicit să nu fie folosit în producție). La nivel de implementare, la yield JVM-ul copiază stack frame-urile thread-ului în obiectul Continuation, iar la resume le copiază înapoi — optimizat prin lazy copying și return barriers, ca să nu se plătească costul întregii stive la fiecare suspendare. Capitolul culminează cu construirea unui „virtual thread" artizanal, NanoThread: un scheduler pe work-stealing pool cu 2 workers, un ThreadLocal pentru thread-ul curent și o simulare de transfer de fișiere în care task-ul face yield la „I/O" și este reprogramat după un delay — output-ul arată negru pe alb că același NanoThread pornește pe un worker și se termină pe altul, exact ca virtual threads reale. Ultima secțiune leagă totul de realitate: la I/O blocant, un virtual thread apelează LockSupport.park(), care duce la yield-ul continuation-ului, iar un poller JVM-wide (kqueue pe macOS, epoll pe Linux, wepoll pe Windows) ține o hartă file descriptor → virtual thread și face unpark când datele sosesc pe socket. Concluzia: aproape toate punctele blocante din JDK au fost rescrise ca să demonteze virtual thread-ul de pe carrier în loc să blocheze OS thread-ul.

## Concepte explicate

### Thread pool
- **Definiție** — un grup de threads create la pornirea aplicației și ținute în viață, care consumă task-uri dintr-o coadă partajată; când toate sunt ocupate, task-urile noi așteaptă în coadă.
- **De ce contează** — previne epuizarea resurselor (limita de threads a OS-ului, crash la burst de request-uri), oferă rate limiting natural și elimină costul creării unui thread per task; fără pool, un server web care creează un thread per request poate cădea la trafic brusc.
- **Exemplu de cod**
  ```java
  // Greșit: thread nou per cerere — la 50k cereri simultane, OOM / limită OS
  for (Request r : requests) {
      new Thread(() -> handle(r)).start();
  }

  // Corect: număr fix de workers, cererile în plus așteaptă în coadă
  try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
      for (Request r : requests) {
          pool.submit(() -> handle(r));
      }
  }
  ```
- **Capcane frecvente** — coadă nemărginită care maschează suprasarcina până la OutOfMemoryError; uitarea shutdown-ului (aplicația nu se mai termină); flag de oprire fără `volatile` (workers nu văd semnalul); dimensionare „la ochi" fără măsurători.

### ThreadPoolExecutor și dimensionarea pool-ului
- **Definiție** — implementarea centrală din Executor framework, configurabilă prin corePoolSize (threads ținute mereu în viață), maximumPoolSize (plafonul), keepAliveTime (cât trăiesc threads-urile în exces), workQueue, threadFactory și RejectedExecutionHandler; factory-urile din `Executors` sunt doar configurări diferite ale acestui constructor.
- **De ce contează** — dimensionarea greșită e o sursă clasică de probleme de performanță: la CPU-bound, mai multe threads decât core-uri înseamnă doar context switching în plus; la I/O-bound, prea puține threads înseamnă throughput irosit. Virtual threads elimină acest tuning pentru I/O-bound, dar pool-urile clasice rămân relevante (legacy, compatibilitate, cazuri unde un pool acordat bate virtual threads — ex. studiul OpenLiberty).
- **Exemplu de cod**
  ```java
  // CPU-bound: threads = core-uri, coadă mărginită, respingere explicită
  int cores = Runtime.getRuntime().availableProcessors();
  var pool = new ThreadPoolExecutor(
      cores, cores,
      0L, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1_000),
      Executors.defaultThreadFactory(),
      new ThreadPoolExecutor.CallerRunsPolicy());
  ```
- **Capcane frecvente** — presupunerea că `newFixedThreadPool` are coadă mărginită (nu are — e nelimitată); alegerea CachedThreadPool pentru task-uri lungi (explozie de threads); folosirea SingleThreadExecutor unde e nevoie de paralelism; ignorarea handler-ului de rejection până când task-urile încep să dispară silențios.

### Callable și Future
- **Definiție** — `Callable<V>` este echivalentul lui Runnable care întoarce o valoare și poate arunca excepții; `Future<V>` este „bonul de ordine" returnat imediat la submit, din care rezultatul se extrage cu `get()` (blocant) sau se verifică cu `isDone()`.
- **De ce contează** — este puntea dintre lumea sincronă și cea asincronă; dar `get()` blochează thread-ul apelant, iar apelat greșit transformă paralelismul în execuție secvențială sau, mai rău, în deadlock (vezi ForkJoinPool mai jos).
- **Exemplu de cod**
  ```java
  // Greșit: get() imediat după fiecare submit — task-urile rulează pe rând
  for (int n : inputs) {
      long v = pool.submit(() -> compute(n)).get(); // serializare mascată
  }

  // Corect: întâi trimiți tot, apoi colectezi — task-urile rulează în paralel
  List<Future<Long>> futures = new ArrayList<>();
  for (int n : inputs) futures.add(pool.submit(() -> compute(n)));
  for (Future<Long> f : futures) results.add(f.get());
  ```
- **Capcane frecvente** — pattern-ul submit+get în aceeași iterație (paralelism zero); `get()` fără timeout pe task-uri care pot atârna; înghițirea `ExecutionException` fără a inspecta cauza reală; a uita că excepția din task apare abia la `get()`, nu la submit.

### ForkJoinPool și divide-and-conquer
- **Definiție** — pool special (din Java 7) pentru task-uri care se descompun recursiv în subtask-uri (`RecursiveTask`/`RecursiveAction` cu fork/join); diferența crucială față de ThreadPoolExecutor: un worker care așteaptă un `join()` nu blochează, ci execută alte task-uri în așteptare.
- **De ce contează** — previne deadlock-ul prin epuizarea pool-ului: în demonstrația din carte, Fibonacci recursiv pe un FixedThreadPool cu 100 threads îngheață complet (fiecare părinte ține un thread ostatic așteptându-și copiii aflați încă în coadă), pe când ForkJoinPool termină instant. E și motivul alegerii lui ca scheduler pentru virtual threads.
- **Exemplu de cod**
  ```java
  // Greșit (pe pool clasic): părintele blochează un thread pe fiecare nivel
  long fib(int n, ExecutorService pool) throws Exception {
      if (n <= 1) return n;
      Future<Long> a = pool.submit(() -> fib(n - 1, pool));
      Future<Long> b = pool.submit(() -> fib(n - 2, pool));
      return a.get() + b.get(); // pool-ul se epuizează -> deadlock
  }

  // Corect: RecursiveTask — fork pe o ramură, compute direct pe cealaltă
  class Fib extends RecursiveTask<Long> {
      final int n;
      Fib(int n) { this.n = n; }
      protected Long compute() {
          if (n <= 1) return (long) n;
          Fib left = new Fib(n - 1);
          left.fork();                          // în deque-ul worker-ului
          return new Fib(n - 2).compute() + left.join();
      }
  }
  long r = new ForkJoinPool().invoke(new Fib(20));
  ```
- **Capcane frecvente** — fork pe ambele ramuri în loc de fork+compute (irosește un slot de paralelism); task-uri blocking (I/O, lock-uri) trimise într-un ForkJoinPool dimensionat pentru CPU; a uita că pool-ul NU descompune singur problema — granularitatea e responsabilitatea developer-ului; descompunere prea fină (overhead-ul de fork depășește munca utilă).

### Work-stealing
- **Definiție** — fiecare worker are propriul deque de task-uri: owner-ul face push/pop la un capăt (LIFO), iar un worker rămas fără treabă „fură" de la celălalt capăt (FIFO) al deque-ului altuia.
- **De ce contează** — LIFO pentru owner exploatează cache-ul CPU (task-urile cele mai recente au datele calde), FIFO pentru hoți ia task-urile vechi (adesea cele mai mari) și, pentru că cei doi operează la capete opuse, contention-ul e minim; rezultatul e echilibrarea automată a încărcării fără o coadă centrală disputată.
- **Exemplu de cod**
  ```java
  // Beneficiezi de work-stealing fără cod special:
  try (ExecutorService pool = Executors.newWorkStealingPool()) {
      for (int i = 0; i < 1_000; i++) {
          int id = i;
          pool.submit(() -> process(id)); // distribuit + furat între workers
      }
  }
  ```
- **Capcane frecvente** — a te aștepta la ordine de execuție FIFO (modul implicit e LIFO per worker); task-uri lungi și inegale care lasă un singur worker cu un monolit ce nu mai poate fi „furat" (furtul mută task-uri întregi, nu bucăți); a confunda `newWorkStealingPool()` cu un pool clasic la debugging (numărul de threads e daemon și legat de core-uri).

### CAS (compare-and-swap) și algoritmi lock-free
- **Definiție** — operație atomică hardware: „schimbă valoarea doar dacă e încă cea pe care o aștept"; pe eșec, thread-ul reîncearcă în buclă în loc să blocheze pe un lock. În Java se accesează prin clase Atomic* sau, la nivel jos, prin VarHandle.compareAndSet.
- **De ce contează** — elimină race condition-ul pe update-uri de tip read-modify-write fără costul lock-urilor; sub contention redus e mai rapid decât synchronized. ForkJoinPool își administrează deque-urile exact cu CAS ca să țină workers-ii productivi în loc de blocați.
- **Exemplu de cod**
  ```java
  // Greșit: volatile nu face ++ atomic — se pierd incremente
  volatile int counter = 0;
  void increment() { counter++; } // read+add+write, neatomic

  // Corect: buclă CAS — nimeni nu blochează, pierzătorul reîncearcă
  final AtomicInteger counter = new AtomicInteger();
  void increment() {
      int cur;
      do {
          cur = counter.get();
      } while (!counter.compareAndSet(cur, cur + 1));
      // echivalent: counter.incrementAndGet();
  }
  ```
- **Capcane frecvente** — credința că `volatile` singur rezolvă incrementarea concurentă (garantează doar vizibilitate, nu atomicitate); CAS pe operații compuse din mai multe variabile (CAS acoperă una singură); retry-loop sub contention masiv care arde CPU (acolo lock-ul poate fi mai bun); problema ABA la structuri de date mai complexe.

### Continuation
- **Definiție** — o bucată de cod care își poate salva starea de execuție (stack frames incluse) la un punct de `yield` și poate fi reluată mai târziu exact de acolo, posibil pe alt thread; în Java 21 există în pachetul intern `jdk.internal.vm` (Continuation + ContinuationScope), NU în API-ul public.
- **De ce contează** — este chiar mecanismul din spatele virtual threads: la un apel blocant, continuation-ul face yield, virtual thread-ul e demontat de pe carrier, iar carrier-ul rămâne liber pentru alt lucru. Fără acest concept, comportamentul „blochez fără să blochez OS thread-ul" pare magie.
- **Exemplu de cod**
  ```java
  // Doar ilustrativ — API intern, necesită:
  // --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
  var scope = new ContinuationScope("demo");
  var cont = new Continuation(scope, () -> {
      System.out.println("pasul 1");
      Continuation.yield(scope);      // pauză — controlul revine apelantului
      System.out.println("pasul 2");  // la următorul run() continuă de AICI
  });
  cont.run(); // tipărește "pasul 1", apoi se oprește la yield
  cont.run(); // tipărește "pasul 2"
  ```
- **Capcane frecvente** — folosirea API-ului intern în producție (se poate schimba/dispărea fără preaviz); confuzia continuation ≠ thread (continuation-ul e doar starea reluabilă; scheduler-ul e o piesă separată); presupunerea că reluarea are loc pe același OS thread (nu e garantat deloc).

### Stack copying, lazy copy și return barriers
- **Definiție** — mecanismul fizic al yield-ului: la suspendare, JVM copiază frame-urile continuation-ului din stiva thread-ului în obiectul Continuation (heap); la reluare nu copiază totul înapoi, ci lazy — doar 1-2 frame-uri — iar „return barriers" (mici bucăți de cod injectate la punctele de return) aduc frame-urile următoare la cerere și detectează ce trebuie salvat la un yield ulterior.
- **De ce contează** — copierea întregii stive la fiecare suspendare ar fi prohibitivă pentru call stack-uri adânci; lazy copy face ca yield/resume să fie suficient de ieftin încât milioane de virtual threads cu I/O frecvent să fie fezabile. Explică și de ce stack-urile virtual threads trăiesc în heap și cresc/scad dinamic.
- **Exemplu de cod**
  ```java
  // Nu există API — e mecanism intern JVM. Consecință practică observabilă:
  void adanc(int nivel) throws Exception {
      if (nivel == 0) {
          // yield aici (ex. I/O blocant pe virtual thread) suspendă
          // ~1000 de frame-uri; lazy copy face resume-ul ieftin
          httpClient.send(request, BodyHandlers.ofString());
          return;
      }
      adanc(nivel - 1);
  }
  Thread.ofVirtual().start(() -> { /* adanc(1000) */ });
  ```
- **Capcane frecvente** — a crede că suspendarea virtual threads e complet gratuită (stive foarte adânci tot costă la prima copiere); a trage concluzii de performanță din stack traces (frame-urile pot fi parțial pe heap); confuzia între context switch OS (registre, mod kernel) și yield de continuation (copiere de frame-uri în user space, mult mai ieftin).

### Carrier threads și scheduler-ul virtual threads (ForkJoinPool în async mode)
- **Definiție** — virtual threads sunt executate („montate") pe un mic set de platform threads numite carrier threads, administrate de un ForkJoinPool dedicat rulând în async mode (FIFO); la yield, virtual thread-ul e demontat, iar la reprogramare poate ateriza pe alt carrier.
- **De ce contează** — explică de ce codul nu trebuie să depindă de identitatea OS thread-ului: ThreadLocal legat implicit de carrier, cod nativ sau lock-uri care „pin-uiesc" carrier-ul strică modelul. Async mode (FIFO) e ales pentru fairness între task-uri independente de tip eveniment, nu pentru divide-and-conquer.
- **Exemplu de cod**
  ```java
  // Demonstrație: același virtual thread, potențial alt carrier după I/O
  Thread.ofVirtual().start(() -> {
      System.out.println(Thread.currentThread()); // VirtualThread[#21]/runnable@ForkJoinPool-1-worker-1
      try { Thread.sleep(100); } catch (InterruptedException e) { }
      System.out.println(Thread.currentThread()); // ...poate worker-3 acum
  });
  ```
- **Capcane frecvente** — cache-uri sau stări per-thread care presupun un carrier stabil; sincronizare care pin-uiește carrier-ul în timpul I/O (reduce paralelismul real); a crea manual pool-uri de virtual threads (nu au sens — virtual threads sunt ieftine, nu se refolosesc).

### Parking și I/O pollers (epoll/kqueue/wepoll)
- **Definiție** — când un virtual thread face un apel blocant de rețea care nu e gata imediat, `LockSupport.park()` detectează că thread-ul e virtual și face yield pe continuation; un poller JVM-wide (event loop pe epoll în Linux, kqueue pe macOS, wepoll pe Windows) ține o hartă file descriptor → virtual thread și face unpark când FD-ul devine ready. Există poller separat pentru citire și pentru scriere.
- **De ce contează** — este veriga finală care face ca stilul de cod sincron-blocant să scaleze ca un sistem non-blocking: developer-ul scrie `read()` obișnuit, iar JVM-ul face sub capotă exact ce ar face un framework reactiv cu event loop, dar fără a-i schimba modelul de programare. Aproape toate punctele blocante din JDK au fost rescrise pe acest tipar.
- **Exemplu de cod**
  ```java
  // Cod sincron banal — dar pe virtual thread NU ține ocupat un OS thread:
  Thread.ofVirtual().start(() -> {
      try (var socket = new Socket("example.com", 80)) {
          var in = socket.getInputStream();
          int b = in.read(); // nu-i gata? park -> yield -> FD înregistrat
                             // la poller; carrier-ul rulează alt VT;
                             // date sosite -> poller unpark -> resume
      } catch (IOException e) { /* ... */ }
  });
  ```
- **Capcane frecvente** — presupunerea că ORICE blocare demontează virtual thread-ul (operații pe fișiere sau cod nativ pot să nu treacă prin poller); a reimplementa event loop-uri manuale peste virtual threads (dublezi ce face deja JVM-ul); a interpreta „blocking is cheap" ca „blocking is free" — înregistrarea la poller și replanificarea au totuși un cost.

## Listing-uri cheie din carte

Textul capitolului nu numerotează exemplele de cod („Example 3-N" nu apare); doar figurile sunt numerotate (3-1 … 3-7). Identific listing-urile după secțiunea în care apar:

1. **SimpleThreadPool + Worker (secțiunea „Building a Simple Thread Pool in Java")** — pool de la zero: workers care consumă dintr-un LinkedBlockingDeque, flag `volatile running`, ThreadGroup pentru interrupt colectiv, AutoCloseable pentru shutdown grațios. Instructiv pentru că expune toate deciziile de design (backpressure prin `put()` blocant, coadă mărginită contra OOM) pe care ExecutorService le ascunde.
2. **SimpleThreadPoolDemo (aceeași secțiune)** — 100 de task-uri pe 4 workers cu try-with-resources; arată vizual reutilizarea threads-urilor.
3. **Constructorul ThreadPoolExecutor (secțiunea „The Executor Framework")** — semnătura cu cei 7 parametri; cheia pentru a înțelege că toate factory-urile din `Executors` sunt configurări ale aceleiași clase.
4. **Mini-exemplele celor 5 pool-uri (subsecțiunile FixedThreadPool, CachedThreadPool, SingleThreadExecutor, ScheduledThreadPoolExecutor, WorkStealingPool)** — câte un snippet per tip, cu „when to use"; utile ca fișă de decizie.
5. **CallableExample — Fibonacci cu Future (secțiunea „Callable")** — submit de Callable, rezultat prin Future, cache ConcurrentHashMap; introduce ideea că `get()` e blocant.
6. **Lista de Futures pentru mai mulți indici Fibonacci (secțiunea „Future")** — pattern-ul corect „întâi toate submit-urile, apoi toate get-urile".
7. **FibonacciNumberWithTraditionalThreadPool (secțiunea „The ForkJoinPool", cu Figura 3-1)** — exemplul-pivot al capitolului: 100 de threads, deadlock garantat, pentru că părinții blochează threads așteptând copii care rămân în coadă. Cel mai instructiv listing — demonstrează o limită structurală, nu un bug de implementare.
8. **FibonacciNumberWithForkJoinPool (aceeași secțiune)** — soluția cu RecursiveTask și idioma fork() pe o ramură + compute() direct pe cealaltă + join(); arată de ce ForkJoinPool nu se autoblochează.
9. **AtomicCounter cu VarHandle (caseta „Implementing a Lock-Free Counter with CAS")** — bucla clasică read → compute → compareAndSet-cu-retry, verificată de două threads care incrementează concurent până la exact 200; ilustrează sincronizarea fără lock-uri, exact tehnica din deque-urile ForkJoinPool.
10. **AsyncModeExample (secțiunea despre async mode)** — ForkJoinPool construit cu flag-ul async=true și task-uri „eveniment" independente; important pentru că exact acest mod e folosit de scheduler-ul virtual threads.
11. **ContinuationExample (secțiunea „Continuation")** — trei run() intercalate cu două yield(); output-ul alternat main/continuation face vizibil conceptul de pauză/reluare la nivel de metodă (cu avertismentul: API intern, nu în producție).
12. **NanoThread / NanoThreadScheduler / FileOperation / NanoThreadDemo (secțiunea „Building Our Own Virtual Threads from Scratch", Figura 3-7)** — un virtual thread „de casă": Continuation + work-stealing pool cu 2 workers + ScheduledExecutorService pe post de „I/O poller" simulat. Cel mai valoros ansamblu didactic: output-ul arată același NanoThread pornind pe worker-2 și terminând pe worker-1 — exact comportamentul carrier-switch al virtual threads reale.
13. **LockSupport.park() (secțiunea „Virtual Threads and I/O Polling")** — fragmentul din JDK cu branch-ul `isVirtual()`: locul concret unde un apel blocant se transformă în yield de continuation.

## Citate

- „Knowing how to use something is important, but understanding how it works is essential." — introducerea capitolului.
- „They keep threads productive rather than waiting." — despre operațiile CAS din work-stealing queues (caseta CAS).
- „The threads in the ForkJoinPool execute all the virtual threads and act as a scheduler." — finalul secțiunii despre async mode.

## Legături

- **Înapoi la Cap. 1 (Introduction)**: capitolul reia Executor framework-ul introdus acolo și îl duce în profunzime (ThreadPoolExecutor, tipuri de pool-uri); presupune că știi deja să creezi threads și ce e un Runnable.
- **Înapoi la Cap. 2 (Understanding Virtual Threads)**: Cap. 2 a arătat CE sunt virtual threads și de ce ajută la I/O-bound; Cap. 3 arată CUM funcționează — scheduler-ul (ForkJoinPool în async mode), mecanismul de suspendare (continuation + stack copying) și trezirea la I/O (pollers). Afirmația din Cap. 2 că „blocarea e ieftină" primește aici explicația cauzală.
- **Înainte spre Cap. 4 (Structured Concurrency)**: modelul părinte-copii cu fork/join din ForkJoinPool este precursorul conceptual al structured concurrency; deadlock-ul din exemplul Fibonacci motivează nevoia unui model în care ciclul de viață al subtask-urilor e legat structurat de părinte.
- **Înainte spre Cap. 5 (Scoped Values)**: capcana ThreadLocal + carrier threads văzută în NanoThreadScheduler (CURRENT_NANO_THREAD pe ThreadLocal, cu remove manual) prefigurează de ce virtual threads au nevoie de o alternativă — scoped values.
- **Înainte spre Cap. 6 (Reactive Java)**: poller-ul epoll/kqueue arată că JVM-ul face intern ceea ce framework-urile reactive fac în user code; comparația onestă virtual threads vs. reactive din Cap. 6 se sprijină pe această mecanică.
- **De stăpânit înainte de a merge mai departe**: diferența structurală ThreadPoolExecutor vs. ForkJoinPool (și de ce primul poate intra în deadlock pe task-uri dependente), idioma fork/compute/join, ce garantează și ce NU garantează volatile vs. CAS, ciclul mount → park/yield → unmount → poller unpark → remount al unui virtual thread.
