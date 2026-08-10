# Capitolul 05 — Building Blocks

## Rezumat amplu
Capitolul închide prima parte a cărții trecând în revistă „piesele de Lego" pe care platforma le oferă ca să nu-ți construiești singur sincronizarea: colecții sincronizate și concurente, cozi blocante, sincronizatoare. Începe cu colecțiile sincronizate (`Vector`, `Hashtable`, wrapper-ele `Collections.synchronizedXxx`): thread-safe metodă-cu-metodă, dar operațiile compuse ale clientului (getLast, iterate, navigare) rămân neatomice — exemplul pe `Vector` arată cum `size()` urmat de `get()` poate arunca `ArrayIndexOutOfBoundsException`. Fix-ul e client-side locking pe lock-ul colecției, inclusiv pe durata iterării — dar asta serializează accesul și ține lock-ul mult. Iteratorii sunt fail-fast: `ConcurrentModificationException` la modificare concurentă, iar capcana majoră sunt iteratorii ascunși — `toString`, `hashCode`, `equals`, `containsAll`, constructori de copiere — care iterează fără să se vadă în cod. Urmează colecțiile concurente, upgrade-ul recomandat: `ConcurrentHashMap` folosește lock striping ca cititorii și un număr de scriitori să lucreze simultan, oferă iteratori weakly consistent (nu aruncă CME, tolerează modificări) și acceptă că `size()`/`isEmpty()` sunt aproximative; în schimb nu mai poți face client-side locking — de aceea operațiile compuse frecvente (put-if-absent, remove-if-equal, replace) sunt urcate în interfața `ConcurrentMap` ca operații atomice native. `CopyOnWriteArrayList` copiază tabloul la fiecare mutare: iterare fără lock pe un snapshot imuabil — ideal când citirile/iterările domină covârșitor scrierile (liste de listeners). A treia piesă: `BlockingQueue` și tiparul producer-consumer — decuplezi producătorii de consumatori, cozile mărginite oferă backpressure (producătorii blochează când coada e plină), iar exemplul desktop search (un thread scanează fișiere, altele indexează) arată și serial thread confinement: obiectul mutabil e „predat" prin coadă, cu un singur thread proprietar la orice moment. `Deque` + work stealing extind modelul pentru producători-consumatori care își sunt și una și alta. Secțiunea despre blocare tratează `InterruptedException`: ori o propagi, ori restaurezi statusul cu `Thread.currentThread().interrupt()` — niciodată n-o înghiți. Urmează sincronizatoarele: `CountDownLatch` (așteaptă evenimente o singură dată — start gate/end gate în teste), `FutureTask` (rezultat calculat o dată, așteptat de mulți), `Semaphore` (permise — bounding pentru colecții și pool-uri), `CyclicBarrier` (toate thread-urile se așteaptă reciproc la un punct, reutilizabil — simulări pe pași). Finalul construiește piesa de rezistență: `Memoizer`, un cache de rezultate scalabil, dezvoltat în patru iterații — `HashMap` + synchronized (corect, dar serial), `ConcurrentHashMap` (scalabil, dar cu fereastră de calcul duplicat), `FutureTask` în map (fereastra se îngustează la un check-then-act), și final `putIfAbsent` pe `Future` (atomic: un singur calcul, ceilalți așteaptă rezultatul). Ultimele pagini rezumă „regulile de buzunar" ale întregii părți întâi.

## Concepte explicate

### Colecții sincronizate vs. operații compuse ale clientului
- **Definiție** — wrapper-ele sincronizate fac fiecare metodă atomică, dar secvențele de metode ale clientului rămân race-uri (check-then-act pe `size`/`get`, iterare).
- **De ce contează** — e cea mai frecventă iluzie de siguranță: „folosesc o colecție thread-safe, deci codul meu e thread-safe".
- **Exemplu de cod**
  ```java
  List<String> list = Collections.synchronizedList(new ArrayList<>());
  // GREȘIT — compus, neatomic
  if (!list.isEmpty()) { String last = list.get(list.size() - 1); } // poate exploda între apeluri
  // CORECT — client-side locking pe lock-ul colecției (documentat: obiectul însuși)
  synchronized (list) {
      if (!list.isEmpty()) { String last = list.get(list.size() - 1); }
  }
  ```
- **Capcane frecvente** — iterarea fără lock-ul colecției; uitarea faptului că și for-each folosește un `Iterator` fail-fast.

### Iteratori ascunși
- **Definiție** — metode care iterează colecția fără ca în codul tău să apară vreo buclă: `toString`, `equals`, `hashCode`, `containsAll`, `removeAll`, constructori de copiere.
- **De ce contează** — sursa clasică de `ConcurrentModificationException` „imposibil de explicat", adesea dintr-un banal string de log.
- **Exemplu de cod**
  ```java
  Set<Integer> set = Collections.synchronizedSet(new HashSet<>());
  // GREȘIT — concatenarea apelează set.toString(), care iterează fără lock
  log.debug("Current set: " + set);
  ```
- **Capcane frecvente** — logging și debugging care „nu pot strica nimic"; punerea colecției drept cheie în altă colecție (`hashCode` iterează).

### ConcurrentHashMap + ConcurrentMap (operații atomice native)
- **Definiție** — hash map cu lock striping și iteratori weakly consistent; interfața `ConcurrentMap` adaugă put-if-absent, remove(key, value), replace ca primitive atomice.
- **De ce contează** — înlocuiește aproape întotdeauna `Hashtable`/`synchronizedMap` cu mult mai multă scalabilitate; dar renunți la posibilitatea de a bloca întreaga hartă (nu există lock unic de client-side).
- **Exemplu de cod**
  ```java
  ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
  // CORECT — operație atomică nativă în loc de check-then-act manual
  counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
  ```
- **Capcane frecvente** — check-then-act manual (`if (!map.containsKey(k)) map.put(k, v)`) pe un `ConcurrentHashMap` — race-ul rămâne; a te baza pe `size()` exact.

### CopyOnWriteArrayList
- **Definiție** — listă care re-copiază tabloul intern la fiecare modificare; iteratorii văd snapshot-ul de la creare, fără lock și fără CME.
- **De ce contează** — perfect pentru read-mostly (listeners, configurări); dezastruos pentru scrieri frecvente sau liste mari (copie completă per mutare).
- **Exemplu de cod**
  ```java
  private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
  void fire(Event e) { for (EventListener l : listeners) l.on(e); } // iterare fără lock, fără CME
  ```
- **Capcane frecvente** — folosirea ca listă generală de lucru cu multe scrieri; a te aștepta ca iteratorul să vadă elementele adăugate după crearea lui.

### BlockingQueue, producer-consumer, backpressure, serial thread confinement
- **Definiție** — coadă cu `put`/`take` blocante; producer-consumer decuplează producerea de consum; coada mărginită împinge presiunea înapoi în producători; obiectele predate prin coadă au mereu un singur proprietar (serial confinement).
- **De ce contează** — cel mai robust tipar de arhitectură concurentă: fiecare actor rămâne cod secvențial simplu, iar coada absoarbe vârfurile și limitează memoria.
- **Exemplu de cod**
  ```java
  BlockingQueue<Path> toIndex = new LinkedBlockingQueue<>(1000); // MĂRGINITĂ = backpressure
  Runnable crawler = () -> { for (Path f : scan()) toIndex.put(f); };       // blochează când e plin
  Runnable indexer = () -> { while (true) index(toIndex.take()); };          // blochează când e gol
  ```
- **Capcane frecvente** — cozi nemărginite („n-o să se umple niciodată") → OOM sub load; a atinge obiectul după ce l-ai pus în coadă (rupe serial confinement).

### InterruptedException — propagă sau restaurează
- **Definiție** — semnalul cooperativ că altcineva cere oprirea/abandonul blocării; ai două răspunsuri legitime: rethrow sau `Thread.currentThread().interrupt()`.
- **De ce contează** — înghițirea întreruperii (catch gol) fură informația de la codul de mai sus și face task-urile neanulabile.
- **Exemplu de cod**
  ```java
  // GREȘIT: catch (InterruptedException e) {}   — întreruperea dispare
  // CORECT când nu poți arunca (ex. în Runnable):
  try { queue.take(); }
  catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
  ```
- **Capcane frecvente** — catch-all care prinde și `InterruptedException`; „păstrez excepția pentru mai târziu" fără să restaurezi flag-ul.

### Sincronizatoare: latch, FutureTask, semaphore, barrier
- **Definiție** — obiecte care coordonează fluxul thread-urilor pe baza stării proprii: `CountDownLatch` (poartă unică — așteaptă N evenimente), `FutureTask` (rezultatul unui calcul, o singură execuție), `Semaphore` (N permise pentru acces/resurse), `CyclicBarrier` (punct de întâlnire reutilizabil pentru N thread-uri).
- **De ce contează** — acoperă coordonările uzuale fără wait/notify manual; alegerea corectă: latch pentru evenimente one-shot, barrier pentru pași repetați ai acelorași thread-uri, semaphore pentru bounding.
- **Exemplu de cod**
  ```java
  // Start-gate / end-gate pentru un test de timing (tiparul din Listing 5.11)
  CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(N);
  for (int i = 0; i < N; i++) new Thread(() -> {
      try { start.await(); doWork(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      finally { done.countDown(); }
  }).start();
  long t0 = System.nanoTime(); start.countDown(); done.await(); // toate pornesc „deodată"
  ```
- **Capcane frecvente** — refolosirea unui latch (nu se resetează — pentru asta e barrier-ul); uitarea `countDown` în `finally` (latch-ul rămâne blocat la excepție).

### Memoizer — cache-ul scalabil de rezultate
- **Definiție** — map de la input la `Future<rezultat>`: cine vine primul instalează atomic un `FutureTask` și îl rulează; ceilalți găsesc Future-ul și așteaptă rezultatul.
- **De ce contează** — rezolvă simultan două probleme: contenția (nu blochezi toată harta pe durata calculului) și calculul duplicat (fereastra check-then-act e închisă de `putIfAbsent`).
- **Exemplu de cod**
  ```java
  class Memoizer<A, V> {
      private final ConcurrentMap<A, Future<V>> cache = new ConcurrentHashMap<>();
      private final Function<A, V> compute;
      Memoizer(Function<A, V> f) { this.compute = f; }
      V get(A arg) throws InterruptedException, ExecutionException {
          while (true) {
              Future<V> f = cache.get(arg);
              if (f == null) {
                  FutureTask<V> ft = new FutureTask<>(() -> compute.apply(arg));
                  f = cache.putIfAbsent(arg, ft);      // atomic: un singur câștigător
                  if (f == null) { f = ft; ft.run(); } // doar câștigătorul calculează
              }
              try { return f.get(); }                   // ceilalți așteaptă rezultatul
              catch (CancellationException e) { cache.remove(arg, f); } // retry curat
          }
      }
  }
  ```
- **Capcane frecvente** — cache-uirea directă a valorii (fereastră lungă de calcul dublu); a uita cache pollution — un `Future` eșuat/anulat trebuie scos din map; lipsa politicii de expirare.

## Listing-uri cheie din carte
- **Listing 5.1/5.2 — getLast/deleteLast pe Vector**: operații compuse pe colecție sincronizată; problema și fix-ul cu client-side locking.
- **Listing 5.3–5.5 — iterare**: CME, iterare cu lock, for-each care ascunde Iterator.
- **Listing 5.6 — iterator ascuns în concatenare (Don't)**: `toString` care iterează; cea mai instructivă capcană din capitol.
- **Listing 5.7 — interfața ConcurrentMap**: operațiile compuse devin primitive atomice.
- **Listing 5.8/5.9 — desktop search**: producer-consumer real cu `BlockingQueue`.
- **Listing 5.10 — restaurarea întreruperii**: idiomul standard pentru cod care nu poate arunca.
- **Listing 5.11 — TestHarness cu CountDownLatch**: start/end gates — reapare în capitolul 12.
- **Listing 5.12/5.13 — Preloader cu FutureTask**: preîncărcare + idiomul `launderThrowable` pentru despachetarea `ExecutionException`.
- **Listing 5.14 — BoundedHashSet cu Semaphore**: bounding adăugat prin compunere.
- **Listing 5.15 — CellularAutomata cu CyclicBarrier**: simulare pe pași, un thread per core.
- **Listing 5.16–5.19 — evoluția Memoizer**: cea mai bună demonstrație din carte a raționamentului iterativ concurență/scalabilitate.
- **Listing 5.20 — Factorizer cu Memoizer**: rezolvarea finală a servlet-ului din capitolele 2–3.

## Citate
Morala colecțiilor sincronizate (5.1, parafrazată): thread-safe per metodă nu înseamnă thread-safe per utilizare — operațiile compuse rămân responsabilitatea ta. Din rezumatul părții întâi (5.6, parafrazată): starea mutabilă e rădăcina tuturor problemelor de concurență — cu cât mai puțină, cu atât mai ușor de garantat corectitudinea.

## Legături
Încheie „fundamentele" (cap. 1–5) și dă vocabularul de componente pentru tot restul cărții: Executor (cap. 6) e construit pe producer-consumer; anularea (cap. 7) folosește `Future` și `BlockingQueue`; testarea (cap. 12) refolosește latch-uri și bariere; capitolul 11 explică de ce `ConcurrentHashMap` scalează (lock striping), iar 14–15 arată cum sunt construite intern sincronizatoarele (AQS, CAS). De stăpânit: alegerea colecției potrivite, idiomul `InterruptedException` și construcția Memoizer pas cu pas.
