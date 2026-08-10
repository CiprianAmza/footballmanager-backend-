# Capitolul 06 — Task Execution

## Rezumat amplu
Deschide partea a doua a cărții (structurarea aplicațiilor concurente) cu întrebarea: care e unitatea de lucru și cine o execută? Majoritatea aplicațiilor server se descompun natural în task-uri — unități de lucru independente, cu granițe clare (request-ul e granița naturală la un server). Capitolul compară trei politici de execuție pe exemplul unui web server: secvențial (un singur thread servește request-urile pe rând — simplu, dar throughput inacceptabil, orice I/O blochează tot), thread-per-task (un thread nou per request — paralelism, dar costuri de creare/teardown, consum nelimitat de memorie pentru stive, și instabilitate: sub load aplicația moare cu `OutOfMemoryError` exact când e mai solicitată) și, soluția, thread pool-uri prin framework-ul Executor. Ideea centrală a `Executor`-ului e decuplarea: separă submisia task-ului (ce se execută) de mecanica execuției (cum: pe ce thread, în ce ordine, câte simultan) — politica de execuție devine configurație, nu cod împrăștiat prin `new Thread(...)`. Executor e construit pe producer-consumer: cine submite produce, thread-urile pool-ului consumă. Fabricile din `Executors` dau politicile standard: `newFixedThreadPool` (N thread-uri, coadă nemărginită), `newCachedThreadPool` (crește/scade elastic, pentru task-uri scurte), `newSingleThreadExecutor` (serializare cu garanții de ordine), `newScheduledThreadPool` (execuție întârziată/periodică — înlocuitorul corect al lui `Timer`, care are un singur thread, e deraiat de task-uri lente și moare definitiv la excepții nechecked — „thread leakage"). Pentru ciclul de viață apare `ExecutorService`: shutdown graceful (`shutdown` — termină ce e în coadă, nu mai primește), abrupt (`shutdownNow` — întrerupe ce rulează, întoarce ce nu a început), `awaitTermination`, stările running/shutting down/terminated. A doua jumătate a capitolului caută paralelism exploatabil în interiorul unui task, pe exemplul unui page renderer: varianta secvențială (text, apoi imagini pe rând) e lentă; prima paralelizare (tot textul pe un thread, toate imaginile pe un `Future`) câștigă puțin pentru că task-urile sunt eterogene — împărțirea muncii în „două felii inegale" nu scalează; lecția: paralelizarea plătește când descompui în multe task-uri omogene și independente. `Callable`/`Future` formalizează task-ul cu rezultat și ciclu de viață (creat, submis, început, terminat — normal, prin excepție sau anulat); `Future.get` blochează și împachetează rezultatul sau excepția. `CompletionService` combină Executor cu o coadă de rezultate: consumi rezultatele în ordinea terminării, nu a submisiei — page renderer-ul final descarcă imaginile în paralel și le desenează pe măsură ce sosesc. Finalul adaugă bugete de timp: `Future.get(timeout)` cu `cancel(true)` la expirare (reclama cu deadline) și `invokeAll` pentru un lot cu termen global (cotații de călătorie de la N furnizori în timp limitat).

## Concepte explicate

### Task-ul ca unitate de proiectare
- **Definiție** — o bucată de lucru independentă, cu graniță clară, care nu depinde de starea altor task-uri în zbor.
- **De ce contează** — independența e ce face execuția concurentă posibilă; granițele bune (request, mesaj, fișier) dau și izolare la erori, și puncte naturale de paralelism.
- **Exemplu de cod**
  ```java
  // task = un request; handler-ul nu partajează stare mutabilă cu alte requesturi
  Runnable task = () -> handleRequest(connection);
  ```
- **Capcane frecvente** — task-uri „independente" care partajează pe furiș stare mutabilă; granularitate greșită (task-uri uriașe → fără paralelism; minuscule → overhead domină).

### Politica de execuție
- **Definiție** — răspunsul la întrebările: în ce thread, în ce ordine, câte concurent, câte în coadă, ce sacrifici la suprasarcină, ce faci înainte/după execuție.
- **De ce contează** — separată de cod, poate fi schimbată la deployment; îngropată în `new Thread(...)`, e nemodificabilă. Oriunde vezi `new Thread(r).start()`, gândește-te la un Executor.
- **Exemplu de cod**
  ```java
  // politica e injectată, nu hardcodată
  class WebServer {
      private final Executor exec;
      WebServer(Executor exec) { this.exec = exec; }        // fix? cached? sincron? decide config-ul
      void serve(Socket s) { exec.execute(() -> handle(s)); }
  }
  ```
- **Capcane frecvente** — presupuneri ascunse despre politică (task-uri care depind de execuție concurentă pot face deadlock pe un single-thread executor — vezi cap. 8).

### Thread-per-task și de ce eșuează sub load
- **Definiție** — un thread nou pentru fiecare task, nelimitat.
- **De ce contează** — costul creării/teardown-ului e plătit per request; thread-urile idle consumă memorie (stive) și presează GC-ul; peste o limită, `OutOfMemoryError` — instabilitate exact la vârf de trafic.
- **Exemplu de cod**
  ```java
  // GREȘIT ca arhitectură de server (echivalentul Listing 6.2)
  while (true) { Socket s = server.accept(); new Thread(() -> handle(s)).start(); }
  // CORECT: pool mărginit
  ExecutorService pool = Executors.newFixedThreadPool(100);
  while (true) { Socket s = server.accept(); pool.execute(() -> handle(s)); }
  ```
- **Capcane frecvente** — testarea la trafic mic care „validează" modelul; limita reală apare doar sub load.

### Executor lifecycle (ExecutorService)
- **Definiție** — running → shutting down → terminated; `shutdown` = graceful (drenează coada), `shutdownNow` = abrupt (întrerupe, returnează ne-începutele), `awaitTermination` = așteptarea efectivă.
- **De ce contează** — JVM-ul nu iese cât timp există thread-uri non-daemon; fără shutdown, aplicația „atârnă"; cu shutdown greșit, pierzi task-uri fără să știi.
- **Exemplu de cod**
  ```java
  pool.shutdown();                                    // nu mai primim, terminăm ce avem
  if (!pool.awaitTermination(30, TimeUnit.SECONDS))   // așteptăm drenarea
      pool.shutdownNow();                             // apoi forțăm
  ```
- **Capcane frecvente** — `shutdownNow` fără să tratezi lista de task-uri returnate; a uita că `shutdown` nu blochează (fără `awaitTermination` nu ai așteptat nimic).

### Timer vs. ScheduledThreadPoolExecutor
- **Definiție** — `Timer`: un singur thread, timp absolut, moare la excepții nechecked; `ScheduledThreadPoolExecutor`: pool, timp relativ, izolarea erorilor per task.
- **De ce contează** — un task lent în Timer strică programarea celorlalte; o excepție nechecked omoară thread-ul Timer definitiv (thread leakage) — task-urile următoare nu mai rulează deloc.
- **Exemplu de cod**
  ```java
  ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
  sched.scheduleAtFixedRate(this::heartbeat, 0, 5, TimeUnit.SECONDS); // excepția unui run nu ucide executorul
  ```
- **Capcane frecvente** — `Timer` în cod nou; a uita că și în `ScheduledThreadPoolExecutor` o excepție aruncată suprimă rulările viitoare ale ACELUI task periodic.

### Callable, Future și ciclul de viață al task-ului
- **Definiție** — `Callable<V>` = task cu rezultat și excepții checked; `Future<V>` = mânerul ciclului de viață: `get` (blochează; întoarce rezultatul sau aruncă `ExecutionException` cu cauza împachetată), `cancel`, `isDone`.
- **De ce contează** — dă un protocol standard pentru „așteaptă rezultatul, tratează eșecul, renunță la nevoie" — fundamentul anulării din capitolul 7.
- **Exemplu de cod**
  ```java
  Future<Report> f = pool.submit(() -> buildReport(data));  // Callable<Report>
  try { Report r = f.get(10, TimeUnit.SECONDS); }
  catch (ExecutionException e) { handle(e.getCause()); }     // excepția reală e cauza
  catch (TimeoutException e) { f.cancel(true); }             // buget de timp depășit → anulăm
  ```
- **Capcane frecvente** — a loga `ExecutionException` în loc de `getCause()`; `get` fără timeout în cod cu SLA; a ignora rezultatul lui `submit` (excepțiile task-ului dispar tăcut în Future-ul nimănui).

### Heterogen vs. omogen; CompletionService
- **Definiție** — paralelizarea a două task-uri diferite (text vs. imagini) aduce puțin; multe task-uri similare și independente scalează. `CompletionService` = Executor + coadă blocantă de Future-uri terminate, consumate în ordinea completării.
- **De ce contează** — modelează corect „lansează N descărcări, procesează fiecare rezultat imediat ce sosește", fără polling pe lista de Future-uri.
- **Exemplu de cod**
  ```java
  CompletionService<Image> cs = new ExecutorCompletionService<>(pool);
  for (ImageInfo info : infos) cs.submit(info::download);   // fan-out
  for (int i = 0; i < infos.size(); i++)
      render(cs.take().get());                              // în ordinea TERMINĂRII
  ```
- **Capcane frecvente** — bucla de polling `isDone()` peste Future-uri (ori busy-wait, ori latență); împărțirea muncii în felii inegale și mirarea că speedup-ul e mic (tema reluată de legea Amdahl în cap. 11).

## Listing-uri cheie din carte
- **Listing 6.1/6.2 — server secvențial și thread-per-task**: cele două extreme greșite între care trăiește pool-ul.
- **Listing 6.3–6.6 — interfața Executor + variante**: cât de mică e interfața și cât de multe politici încap în ea (thread-per-task și execuție sincronă ca implementări de Executor).
- **Listing 6.7/6.8 — ExecutorService lifecycle + server cu shutdown**: tiparul complet de oprire curată.
- **Listing 6.9 — OutOfTime (Timer)**: demonstrația thread leakage-ului la `Timer`.
- **Listing 6.10–6.13 — page renderer secvențial → Future**: căutarea paralelismului exploatabil; și limita task-urilor eterogene.
- **Listing 6.14/6.15 — CompletionService + renderer final**: fan-out cu procesare la completare.
- **Listing 6.16 — reclamă cu buget de timp**: `Future.get(timeout)` + `cancel(true)` — idiomul deadline.
- **Listing 6.17 — invokeAll cu timeout**: lot de task-uri cu termen global; cele neterminate sunt anulate la expirare.

## Citate
Teza capitolului (6.2, parafrazată): decuplarea submisiei de execuție e valoarea centrală a framework-ului Executor — politica de execuție devine o decizie de configurare. Regula practică (6.1/6.2, parafrazată): construcția `new Thread(runnable).start()` presărată prin cod e un semnal că vrei, de fapt, un Executor.

## Legături
Se sprijină pe producer-consumer și `BlockingQueue` din capitolul 5; `FutureTask` de acolo devine aici `Future`-ul standard. Deschide direct capitolul 7 (cum anulezi task-uri și cum oprești serviciile de execuție — `shutdownNow`, întreruperea) și capitolul 8 (dimensionarea și configurarea fină a `ThreadPoolExecutor`, ce se întâmplă când politica și task-urile nu se potrivesc). Tema „felii omogene, nu eterogene" revine formalizată în capitolul 11 (Amdahl). De stăpânit: interfețele Executor/ExecutorService/Future și reflexul de a injecta politica de execuție.
