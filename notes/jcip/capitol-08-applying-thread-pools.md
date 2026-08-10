# Capitolul 08 — Applying Thread Pools

## Rezumat amplu
Capitolul coboară în măruntaiele `ThreadPoolExecutor`: cum dimensionezi, configurezi și extinzi pool-urile, și ce se întâmplă când task-urile și politica de execuție nu se potrivesc. Începe cu cuplajele implicite: deși Executor promite decuplarea, unele task-uri cer politici specifice — task-urile dependente între ele riscă thread starvation deadlock (un task într-un single-thread executor submite alt task în același executor și îi așteaptă rezultatul: primul ocupă singurul thread, al doilea nu rulează niciodată; același fenomen apare la orice pool suficient de mic sau suficient de plin), task-urile care exploatează thread confinement cer executor serial, cele sensibile la timp de răspuns cer pool-uri adecvate, iar cele care folosesc `ThreadLocal` nu au voie să conteze pe reutilizarea thread-ului. Regula: dacă task-urile depind de alte task-uri, documentează cerința de pool nemărginit — altfel „merge până nu merge". Task-urile lungi îmbâcsesc pool-ul (responsivitatea moare chiar fără deadlock); paliativ: versiuni cu timeout ale metodelor blocante. Dimensionarea: mărimile de pool nu se hardcodează, se derivă din `Runtime.availableProcessors()` și natura task-urilor — pentru task-uri compute-intensive, N_cpu + 1; pentru task-uri cu așteptare, formula N_threads = N_cpu × U_cpu × (1 + W/C) (utilizare țintă × raportul așteptare/calcul); resursele altele decât CPU (memorie, conexiuni, file handles) impun propriile plafoane — iar pool-urile care concurează pe aceleași resurse se dimensionează împreună. Configurarea `ThreadPoolExecutor` prin constructorul general: core size, maximum size, keep-alive (cum cresc/scad thread-urile — fixed = core=max; cached = core 0, max nelimitat, SynchronousQueue), tipul cozii (nemărginită `LinkedBlockingQueue` la fixed — riscul mutat din thread-uri în coadă; mărginită — cere politică de saturație; `SynchronousQueue` — handoff direct, fără coadă, pentru pool-uri nemărginite sau cu respingere) și politica de saturație: `AbortPolicy` (aruncă `RejectedExecutionException`), `DiscardPolicy`/`DiscardOldestPolicy` (aruncă tăcut task-ul nou/cel mai vechi — atenție la combinația cu priorități), `CallerRunsPolicy` (execută task-ul în thread-ul apelantului — throttling elegant: serverul încetinește acceptarea în loc să moară, presiunea se propagă spre exterior până la TCP). Alternativa manuală: un `Semaphore` care mărginește submisiile. Thread factories personalizate dau nume, UncaughtExceptionHandler, daemon flag sau instrumentare thread-urilor; `unconfigurableExecutorService` previne reconfigurarea ostilă a unui executor partajat. Extinderea: hook-urile `beforeExecute`/`afterExecute`/`terminated` permit logging, timing și statistici per-task. Finalul aplică totul pe paralelizarea algoritmilor recursivi: buclele cu iterații independente se paralelizează direct (submit per iterație); recursivitatea în care rezultatele pașilor nu se consumă în timpul parcurgerii se transformă în fan-out de task-uri + `shutdown`/`awaitTermination`; studiul de caz — puzzle solver (sliding blocks): versiunea secvențială DFS devine căutare concurentă BFS-ish în care fiecare poziție e un task, `ConcurrentHashMap.putIfAbsent` deduplică stările vizitate, iar rezultatul e livrat printr-un `ValueLatch` (latch purtător de valoare, construit pe `CountDownLatch`) — primul care găsește soluția o setează, main-ul așteaptă pe `getValue()`; versiunea finală numără task-urile active ca să detecteze „nu există soluție" (când contorul ajunge la zero fără rezultat, închide latch-ul cu null).

## Concepte explicate

### Thread starvation deadlock
- **Definiție** — deadlock în care toate thread-urile pool-ului așteaptă rezultate ale unor task-uri care stau în coadă și nu vor rula niciodată pentru că nu mai există thread-uri libere.
- **De ce contează** — nu cere lock-uri: e un deadlock de resurse (thread-urile sunt resursa); apare la orice pool mărginit în care task-urile submit și așteaptă alte task-uri din același pool.
- **Exemplu de cod**
  ```java
  // GREȘIT — echivalentul ThreadDeadlock (Listing 8.1)
  ExecutorService single = Executors.newSingleThreadExecutor();
  Future<String> outer = single.submit(() -> {
      Future<String> header = single.submit(() -> "H");  // intră în coadă...
      return header.get() + "body";                       // ...și așteptăm după el: DEADLOCK
  });
  // CORECT: pool separat pentru subtask-uri, sau nu aștepta sincron în task
  ```
- **Capcane frecvente** — merge în teste (pool relaxat), moare în producție (pool plin); dependența de mărimea pool-ului nedocumentată.

### Dimensionarea pool-urilor
- **Definiție** — pentru CPU-bound: N_cpu + 1; pentru task-uri cu așteptare: N_cpu × U_cpu × (1 + W/C), unde W/C e raportul timp-de-așteptare / timp-de-calcul; alte resurse (conexiuni DB) își impun propriile plafoane.
- **De ce contează** — pool prea mic = throughput irosit; prea mare = contenție pe CPU/memorie; formula transformă intuiția în calcul măsurabil (W și C se estimează prin profiling).
- **Exemplu de cod**
  ```java
  int nCpu = Runtime.getRuntime().availableProcessors();
  // task-uri care stau ~90% din timp pe I/O (W/C = 9), țintă 100% CPU:
  int poolSize = (int) (nCpu * 1.0 * (1 + 9.0));   // ex: 8 core-uri -> 80 de thread-uri
  ```
- **Capcane frecvente** — hardcodarea („16 merge la noi"); un singur pool pentru task-uri CPU-bound și I/O-bound amestecate; ignorarea plafonului dat de pool-ul de conexiuni (thread-uri care doar stau la coadă la DB).

### Cozi de task-uri și politici de saturație
- **Definiție** — coada absoarbe vârfurile: nemărginită (risc de OOM mutat din thread-uri în coadă), mărginită (cere să decizi ce faci când e plină), `SynchronousQueue` (handoff fără stocare). Saturația: Abort / Discard / DiscardOldest / CallerRuns.
- **De ce contează** — la suprasarcină CEVA trebuie să cedeze; alegerea explicită (respinge, aruncă, încetinește sursa) e diferența dintre degradare controlată și crash.
- **Exemplu de cod**
  ```java
  ThreadPoolExecutor pool = new ThreadPoolExecutor(
      8, 8, 0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(1_000),                    // mărginită: backpressure
      new ThreadPoolExecutor.CallerRunsPolicy());          // la saturație: rulează în thread-ul apelant
  // efect: thread-ul care submită (ex. acceptorul de conexiuni) devine ocupat,
  // nu mai acceptă o vreme, presiunea se propagă spre client — throttling gradual
  ```
- **Capcane frecvente** — `newFixedThreadPool` din fabrică are coadă NEmărginită — saturația nu se declanșează niciodată, doar memoria crește; `DiscardOldest` + `PriorityBlockingQueue` = arunci exact task-ul cel mai prioritar.

### Bounding cu Semaphore la submisie
- **Definiție** — un `Semaphore` cu N permise dobândit înainte de `execute` și eliberat la finalul task-ului limitează task-urile „în zbor" (rulând + în coadă).
- **De ce contează** — alternativă la coada mărginită când vrei să blochezi submitter-ul în loc să respingi (Executor nu are „put blocant" nativ).
- **Exemplu de cod**
  ```java
  class BoundedExecutor {
      private final Executor exec; private final Semaphore sem;
      BoundedExecutor(Executor e, int bound) { exec = e; sem = new Semaphore(bound); }
      void submit(Runnable task) throws InterruptedException {
          sem.acquire();
          try {
              exec.execute(() -> { try { task.run(); } finally { sem.release(); } });
          } catch (RejectedExecutionException e) { sem.release(); throw e; }
      }
  }
  ```
- **Capcane frecvente** — release uitat pe calea de rejectare (scurgere de permise); bound mai mic decât mărimea pool-ului (subutilizare).

### ThreadFactory și extinderea ThreadPoolExecutor
- **Definiție** — `ThreadFactory` controlează crearea thread-urilor (nume, daemon, prioritate, handler); hook-urile `beforeExecute`/`afterExecute`/`terminated` inserează logică în jurul fiecărui task.
- **De ce contează** — nume de thread-uri = thread dump-uri care se pot citi; hook-urile dau timing/logging fără să atingi task-urile; `afterExecute` vede și excepțiile aruncate.
- **Exemplu de cod**
  ```java
  class TimingPool extends ThreadPoolExecutor {
      private final ThreadLocal<Long> start = new ThreadLocal<>();
      TimingPool(int n) { super(n, n, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
      @Override protected void beforeExecute(Thread t, Runnable r) { start.set(System.nanoTime()); }
      @Override protected void afterExecute(Runnable r, Throwable t) {
          log.info("task took {}ns{}", System.nanoTime() - start.get(), t != null ? " FAILED" : "");
      }
  }
  ```
- **Capcane frecvente** — a uita că `afterExecute` nu vede excepțiile task-urilor `submit`-uite (sunt în Future); `beforeExecute` care aruncă → task-ul nu mai rulează deloc.

### Paralelizarea algoritmilor recursivi + ValueLatch
- **Definiție** — iterațiile independente ale buclelor devin task-uri; parcurgerile recursive devin fan-out de task-uri care acumulează rezultate într-o colecție concurentă; `ValueLatch` = latch care poartă primul rezultat setat (result-bearing latch).
- **De ce contează** — șablonul general pentru „explorare paralelă cu primul rezultat câștigă" (căutări, soluri de puzzle, probe de rezolvare); deduplicarea prin `putIfAbsent` previne explozia de lucru repetat.
- **Exemplu de cod**
  ```java
  class ValueLatch<T> {
      private T value;                                    // @GuardedBy("this")
      private final CountDownLatch done = new CountDownLatch(1);
      boolean isSet() { return done.getCount() == 0; }
      synchronized void setValue(T v) { if (!isSet()) { value = v; done.countDown(); } } // primul câștigă
      T getValue() throws InterruptedException { done.await(); synchronized (this) { return value; } }
  }
  ```
- **Capcane frecvente** — a uita detecția „fără soluție" (fără contor de task-uri active, `getValue()` așteaptă la infinit); a paraleliza recursii în care pașii CONSUMĂ rezultatele pașilor anteriori (nu sunt independente — nu se pretează).

## Listing-uri cheie din carte
- **Listing 8.1 — ThreadDeadlock (Don't)**: task care așteaptă alt task în același single-thread executor; imaginea canonică a thread starvation deadlock-ului.
- **Listing 8.2 — constructorul general ThreadPoolExecutor**: cele șapte butoane ale politicii de execuție.
- **Listing 8.3 — fixed pool + coadă mărginită + CallerRuns**: configurația recomandată pentru servere care vor degradare grațioasă.
- **Listing 8.4 — BoundedExecutor cu Semaphore**: bounding blocant al submisiilor.
- **Listing 8.6/8.7 — MyThreadFactory/MyAppThread**: thread-uri cu nume, logging și handler de excepții.
- **Listing 8.9 — TimingThreadPool**: hook-urile before/after/terminated pentru statistici.
- **Listing 8.11/8.12 — transformarea recursivității în paralelism**: șablonul fan-out + await.
- **Listing 8.13–8.18 — puzzle solver**: de la DFS secvențial la căutare concurentă cu deduplicare, `ValueLatch` și detecția lipsei de soluție — sinteza întregii părți a doua.

## Citate
Avertismentul central (8.1, parafrazată): în orice pool mărginit, task-urile care depind de alte task-uri din același pool pot muri de foame — documentează cerința sau elimin-o. Despre saturație (8.3.3, parafrazată): CallerRuns transformă suprasarcina în încetinire graduală care se propagă spre client, în loc de eșec brusc.

## Legături
Continuarea directă a capitolului 6 (aici afli cum se configurează efectiv ce acolo era abstract) și beneficiarul capitolului 7 (saturația și shutdown-ul presupun task-uri anulabile). Formula de dimensionare și discuția despre contenție anticipează capitolul 11; `ValueLatch` arată cum compui sincronizatoare din cele existente — tema capitolului 14. Puzzle solver-ul e șablonul de reținut pentru orice căutare paralelă. De stăpânit: starvation deadlock, cele patru politici de saturație și formula N_cpu × U × (1 + W/C).
