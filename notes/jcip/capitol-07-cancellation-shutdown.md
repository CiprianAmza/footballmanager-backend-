# Capitolul 07 — Cancellation and Shutdown

## Rezumat amplu
Capitolul tratează partea cea mai delicată a ciclului de viață: cum oprești task-uri și servicii curat, fără să lași lucrul la jumătate și fără să pierzi date. Premisa: Java nu are un mecanism sigur de oprire forțată a unui thread (`Thread.stop`/`suspend` sunt deprecated pe bună dreptate); tot ce există e cooperativ — task-ul trebuie să fie scris să răspundă la cererea de oprire. Prima variantă: un flag `volatile cancelled`, verificat în bucla de lucru (`PrimeGenerator`); merge doar dacă task-ul trece regulat prin punctul de verificare — dacă blochează într-un `BlockingQueue.put`, nu mai citește flag-ul niciodată (`BrokenPrimeProducer` — producătorul rămâne înțepenit deși consumatorul a plecat). De aici, mecanismul potrivit: întreruperea. Fiecare thread are un status de întrerupere; `interrupt()` îl setează, metodele blocante (`sleep`, `wait`, `take`) îl monitorizează și ies devreme cu `InterruptedException` (ștergând statusul); `isInterrupted()` îl citește, `Thread.interrupted()` îl citește și îl șterge (periculos dacă ignori rezultatul). Nuanța esențială: întreruperea nu oprește nimic — e o cerere politicoasă, iar sensul ei e definit de politica de întrerupere a thread-ului, nu de dorința apelantului. Regula de aur: nu întrerupe un thread decât dacă îi cunoști și îi controlezi politica de întrerupere; task-urile rulează în thread-uri împrumutate (pool), deci un task nu are voie să înghită întreruperea — ori aruncă `InterruptedException` mai departe, ori restaurează statusul. Studiul de caz `timedRun` arată de ce „împrumutul" e periculos: programarea unui interrupt pe thread-ul apelantului (Listing 7.8) poate lovi codul care rulează după expirarea task-ului; variantele corecte: thread dedicat cu `join` pe timeout (7.9) sau, cel mai curat, `Future.get(timeout)` + `cancel(true)` (7.10). Pentru blocări care nu răspund la interrupt (socket I/O, lock-uri intrinseci, NIO), anularea se „încapsulează": suprascrii `interrupt()` ca să închidă și socketul (`ReaderThread`), sau `newTaskFor` ca task-ul să-și expună propriul `cancel` (`SocketUsingTask`). A doua jumătate: oprirea serviciilor bazate pe thread-uri. Principiu: cine deține thread-urile le oprește — aplicația nu manipulează thread-urile pool-ului direct, ci cere serviciului să se oprească; serviciul are nevoie de metode de lifecycle (exemplul `LogWriter`: oprirea naivă pierde mesaje sau blochează producătorii; varianta corectă ține un contor de „rezervări" sub lock ca să drenaze coada exact; varianta pragmatică — delegi totul unui `ExecutorService` cu `shutdown`+`awaitTermination`). Alternativă elegantă pentru producer-consumer: poison pill — un obiect santinelă pus în coadă care spune consumatorului „după mine nu mai e nimic" (merge doar cu producători/consumatori cunoscuți la număr și coadă nemărginită). `shutdownNow` are o limitare: îți dă task-urile ne-începute, dar nu pe cele în curs — `TrackingExecutor` le înregistrează pe cele întrerupte ca să le poți relua (cu riscul de rulări duble — task-urile trebuie să fie idempotente). Finalul: fire-and-forget nu există pentru erori — un task care aruncă necontrolat își omoară thread-ul; worker-ii de pool se protejează cu try-catch/finally și notifică pool-ul; plasa de siguranță e `UncaughtExceptionHandler` (logare, alertare); iar la nivel de JVM, shutdown hooks (curățenie la oprire ordonată — dar toate rulează concurent, trebuie să fie thread-safe și rapide), daemon threads (nu țin JVM-ul în viață, dar sunt abandonate brutal — nefolosibile pentru orice implică I/O) și finalizers (de evitat complet).

## Concepte explicate

### Anulare cooperativă cu flag volatile
- **Definiție** — task-ul verifică periodic un flag setat de altcineva; oprirea are loc la următorul punct de verificare.
- **De ce contează** — cel mai simplu protocol de anulare; dar garantează doar oprirea „eventually" și doar dacă task-ul nu blochează între verificări.
- **Exemplu de cod**
  ```java
  class PrimeGen implements Runnable {
      private volatile boolean cancelled;                  // volatile: vizibilitate garantată
      private final List<BigInteger> primes = new ArrayList<>();
      public void run() {
          BigInteger p = BigInteger.ONE;
          while (!cancelled) { p = p.nextProbablePrime(); synchronized (this) { primes.add(p); } }
      }
      public void cancel() { cancelled = true; }
  }
  ```
- **Capcane frecvente** — flag ne-volatile (anularea poate să nu fie văzută niciodată); combinarea cu operații blocante — flag-ul nu e citit cât timp thread-ul doarme în `put`/`take`.

### Întreruperea și politica de întrerupere
- **Definiție** — mecanism cooperativ per-thread: `interrupt()` setează statusul; metodele blocante ies cu `InterruptedException`; politica de întrerupere = ce înseamnă întreruperea pentru ACEL thread (de obicei: anulare + curățenie + terminare).
- **De ce contează** — e singurul mod rezonabil de a anula task-uri blocante; dar semantica aparține thread-ului: task-urile (cod oaspete pe thread-ul pool-ului) nu au voie să tragă concluzii sau să ascundă întreruperea.
- **Exemplu de cod**
  ```java
  // Producător anulabil corect — întreruperea sparge și bucla, și blocarea în put
  public void run() {
      try {
          BigInteger p = BigInteger.ONE;
          while (!Thread.currentThread().isInterrupted())
              queue.put(p = p.nextProbablePrime());        // aruncă InterruptedException dacă e întrerupt
      } catch (InterruptedException consumed) {
          /* thread-ul MEU, politica MEA: ies curat */
      }
  }
  ```
- **Capcane frecvente** — `Thread.interrupted()` (citește ȘI șterge) folosit ca simplu getter; a întrerupe thread-uri străine (worker de pool) direct; a trata `InterruptedException` ca pe o eroare de logat și de mers mai departe.

### Răspunsul la InterruptedException: propagă sau restaurează
- **Definiție** — cod de bibliotecă/task: ori declară și aruncă mai departe, ori face `Thread.currentThread().interrupt()` înainte să iasă; doar codul care DEȚINE thread-ul are voie să o consume definitiv.
- **De ce contează** — statusul de întrerupere e informație pentru straturile de deasupra (bucla worker-ului din pool o folosește ca să decidă oprirea); pierderea lui face sistemul neanulabil.
- **Exemplu de cod**
  ```java
  // Task neanulabil care totuși nu fură întreruperea (idiomul Listing 7.7)
  Task getNextTask(BlockingQueue<Task> q) {
      boolean interrupted = false;
      try {
          while (true) {
              try { return q.take(); }
              catch (InterruptedException e) { interrupted = true; } // reține și reia
          }
      } finally {
          if (interrupted) Thread.currentThread().interrupt();       // restaurează la ieșire
      }
  }
  ```
- **Capcane frecvente** — catch gol; restaurarea uitată pe căile de excepție (pune-o în `finally`); a arunca `RuntimeException(e)` peste `InterruptedException` (pierzi semantica).

### Anularea prin Future
- **Definiție** — `Future.cancel(mayInterruptIfRunning)`: anulează task-ul; `true` întrerupe thread-ul care îl rulează (sigur în pool-uri standard, unde politica e cunoscută).
- **De ce contează** — e abstracția corectă: nu tu întrerupi thread-uri, ci ceri framework-ului să anuleze task-ul; compune natural cu bugete de timp.
- **Exemplu de cod**
  ```java
  Future<?> f = pool.submit(task);
  try { f.get(timeout, unit); }
  catch (TimeoutException e) { /* expirat */ }
  catch (ExecutionException e) { throw launder(e.getCause()); }
  finally { f.cancel(true); }   // inofensiv dacă task-ul e deja gata
  ```
- **Capcane frecvente** — `cancel(true)` pe task-uri care nu verifică întreruperea (nu se opresc); a presupune că `cancel` oprește instant ceva.

### Blocări ne-întreruptibile și încapsularea anulării
- **Definiție** — socket I/O clasic, lock-urile intrinseci, unele NIO nu răspund la `interrupt()`; anularea se obține prin altă pârghie (închiderea socketului) împachetată în `interrupt()` suprascris sau în `newTaskFor`/`CancellableTask`.
- **De ce contează** — altfel `shutdownNow`/`cancel` devin no-op-uri pe exact task-urile care blochează cel mai mult.
- **Exemplu de cod**
  ```java
  class ReaderThread extends Thread {
      private final Socket socket;
      ReaderThread(Socket s) { this.socket = s; }
      @Override public void interrupt() {
          try { socket.close(); } catch (IOException ignored) {}
          finally { super.interrupt(); }                    // închide ȘI setează statusul
      }
      public void run() { /* read() va arunca acum SocketException la interrupt */ }
  }
  ```
- **Capcane frecvente** — a uita `super.interrupt()`; a lăsa resursa (socket, canal) nedeclarată/inaccesibilă pentru anulator.

### Oprirea serviciilor: ownership, LogWriter, poison pill
- **Definiție** — serviciul care a creat thread-urile expune lifecycle (`stop`); oprirea trebuie să drenaze coada fără să piardă mesaje și fără să blocheze producătorii pe termen nelimitat; poison pill = santinelă în coadă care marchează sfârșitul fluxului.
- **De ce contează** — oprirea naivă a unui producer-consumer ori pierde date (race între check și put), ori face deadlock (coadă plină + consumator oprit).
- **Exemplu de cod**
  ```java
  // Poison pill pentru un producător și un consumator
  static final Item POISON = new Item();
  // producer, la final:  queue.put(POISON);
  // consumer:
  while (true) {
      Item it = queue.take();
      if (it == POISON) break;    // nimic după pastilă; ieșire curată
      process(it);
  }
  ```
- **Capcane frecvente** — pastile cu N producători/M consumatori fără numărătoare (fiecare consumator are nevoie de pastila lui, fiecare producător trebuie să pună una); poison pill pe coadă mărginită plină (put-ul pastilei blochează); flag de shutdown verificat fără lock, cu fereastră de pierdere de mesaje (Listing 7.14).

### Erori nechecked în worker-i: UncaughtExceptionHandler
- **Definiție** — hook per-thread (sau default) chemat când un thread moare cu o excepție nepropagată; worker-ii de pool se împachetează în try-finally ca să anunțe pool-ul înainte să moară.
- **De ce contează** — fără el, thread-urile mor tăcut și pool-ul „se subțiază" invizibil; minimul civilizat e logarea.
- **Exemplu de cod**
  ```java
  ThreadFactory tf = r -> {
      Thread t = new Thread(r);
      t.setUncaughtExceptionHandler((thr, ex) -> log.error("Worker {} died", thr.getName(), ex));
      return t;
  };
  ExecutorService pool = Executors.newFixedThreadPool(8, tf);
  ```
- **Capcane frecvente** — handler-ul NU e chemat pentru task-uri trimise cu `submit` (excepția intră în `Future` — dacă nu chemi `get`, dispare); a confunda `execute` (handler) cu `submit` (Future).

### Shutdown hooks, daemon threads, finalizers
- **Definiție** — hooks: thread-uri pornite de JVM la oprirea ordonată, concurent și în ordine negarantată; daemon: thread-uri care nu împiedică ieșirea JVM-ului, abandonate fără finally; finalizers: de evitat.
- **De ce contează** — curățenia reală (flush de log, închidere de fișiere) trebuie în shutdown hooks idempotente și rapide; serviciile nu se opresc din hook-uri diferite cu dependențe între ele (race) — un singur hook care oprește serviciile în ordine.
- **Exemplu de cod**
  ```java
  Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { logService.stop(); } catch (InterruptedException ignored) {}
  }));
  ```
- **Capcane frecvente** — daemon threads care fac I/O (mor cu buffer-ele nescrise); hooks lente care blochează oprirea; dependențe implicite între hooks.

## Listing-uri cheie din carte
- **Listing 7.1/7.2 — PrimeGenerator + utilizare**: anulare cu flag volatile; și de ce `cancel` merge în `finally`.
- **Listing 7.3 — BrokenPrimeProducer (Don't)**: flag-ul nu ajută când ești blocat în `put`; motivația întregului mecanism de interrupt.
- **Listing 7.5 — PrimeProducer**: aceeași problemă rezolvată corect cu întrerupere.
- **Listing 7.7 — task neanulabil care restaurează întreruperea**: idiomul reține-și-restaurează.
- **Listing 7.8/7.9/7.10 — cele trei timedRun-uri**: greșit (interrupt pe thread împrumutat), acceptabil (thread dedicat + join), corect (Future + cancel).
- **Listing 7.11/7.12 — ReaderThread și SocketUsingTask**: încapsularea anulării nestandard, la nivel de thread și la nivel de task (`newTaskFor`).
- **Listing 7.13–7.16 — evoluția LogWriter**: de la fără-oprire, la oprire cu race, la drenaj corect cu rezervări, la delegarea către ExecutorService.
- **Listing 7.17–7.19 — poison pill (IndexingService)**: oprirea fluxului prin santinelă.
- **Listing 7.21/7.22 — TrackingExecutor**: recuperarea task-urilor întrerupte la `shutdownNow` pentru reluare.
- **Listing 7.23–7.25 — worker structure + UncaughtExceptionHandler**: cum mor thread-urile civilizat.
- **Listing 7.26 — shutdown hook**: curățenia la oprirea JVM-ului.

## Citate
Regula centrală (7.1.2, parafrazată): pentru că fiecare thread are propria politică de întrerupere, nu întrerupe un thread decât dacă știi ce înseamnă întreruperea pentru el. Despre servicii (7.2, parafrazată): furnizorul de thread-uri e cel care are dreptul să le oprească — aplicația oprește serviciul, serviciul își oprește thread-urile.

## Legături
Construiește pe `Future`/`ExecutorService` din capitolul 6 și pe idiomul `InterruptedException` din capitolul 5. E fundalul obligatoriu pentru capitolul 8 (pool-urile configurate corect presupun task-uri care răspund la întrerupere) și pentru capitolul 9 (anularea task-urilor lungi din GUI). Fără acest capitol, `shutdownNow` și `cancel` sunt butoane care nu fac nimic. De stăpânit: diferența flag vs. interrupt, cine are voie să consume întreruperea și tiparul poison pill.
