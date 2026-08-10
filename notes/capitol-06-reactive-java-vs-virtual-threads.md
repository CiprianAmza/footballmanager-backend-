# Capitolul 06 — The Relevance of Reactive Java in Light of Virtual Threads

## Rezumat amplu

Capitolul pornește de la o afirmație provocatoare a lui Brian Goetz — că Loom va „ucide" reactive programming — și își propune să verifice cât adevăr e în ea, punând față în față paradigma reactivă și virtual threads. După ce capitolele anterioare au arătat cum virtual threads permit cod imperativ, blocant ca stil, dar scalabil, autorul recunoaște că, înainte de Loom, dezvoltatorii care aveau nevoie de scalabilitate pe I/O apelau la reactive programming (Project Reactor, RxJava, Vert.x, Akka Streams). Capitolul definește mai întâi reactive programming ca paradigmă declarativă construită pe stream-uri asincrone de date și pe propagarea automată a schimbării, apoi ancorează discuția în Reactive Manifesto (2013), cu cele patru principii: responsive, resilient, elastic, message-driven — care tehnic se reduc la non-blocking, event-driven și asynchronous. Pentru a face concrete aceste noțiuni, autorul construiește progresiv același server HTTP cu pipelining în patru variante: single-threaded blocant (un request lent de 30 s blochează tot serverul — demonstrat cu curl), multithreaded cu thread pool fix (10 conexiuni concurente, dar limitat de costul platform threads), varianta cu o singură linie schimbată pe `newVirtualThreadPerTaskExecutor()`, și în final varianta NIO non-blocantă cu `Selector`, `ServerSocketChannel` și un event loop pe un singur thread. Implementarea NIO evidențiază complexitatea reală a modelului: stare per-client (`ClientState`), parsare de request-uri parțiale, cozi thread-safe pentru răspunsuri și un pattern de `pendingUpdates` pentru a modifica `interestOps` doar din thread-ul selectorului — altfel apar race conditions și `CancelledKeyException`. Un load test cu 10 clienți pe virtual threads și 1.000 de request-uri pipelined arată că serverul NIO menține throughput ridicat cu un singur thread. Urmează saltul la framework-uri event-driven (Vert.x): event loop-ul rulează handler-ele aplicației, deci regula de aur este ca handler-ele să nu blocheze niciodată thread-ul de I/O (de aici `vertx.setTimer()` în loc de `Thread.sleep()`), iar operațiile blocante se mută pe worker pool — dar cu costul context switch-urilor. Ca să scrii cod non-blocant îți trebuie API-uri asincrone: capitolul parcurge evoluția de la callback-uri (care duc la callback hell) la `CompletableFuture` (compozabil prin `thenCompose`/`thenAccept`, dar limitat la o singură valoare) și apoi la reactive streams, care modelează fluxuri de 0..N elemente. Sunt explicate cele patru componente ale specificației Reactive Streams (Publisher, Subscriber, Subscription, Processor), cele trei tipuri de semnale (date, eroare, completare), tipurile din Reactor (`Flux`, `Mono`) și RxJava (`Observable`, `Single`, `Maybe`), plus caracterul lazy al stream-urilor reci. Un exemplu amplu — monitorizarea prețurilor crypto de la mai multe burse — demonstrează operatori reali: `merge`, `groupBy`, `window`, `buffer`, `Sinks` pentru hot streams, `doOnNext` pentru side effects. Backpressure primește o secțiune dedicată: mecanismul prin care consumatorul cere producătorului să încetinească, cu strategiile `buffer`, `buffer(maxSize)`, `drop`, `latest`, `error` și `sample`, demonstrate pe un feed simulat de 10.000 evenimente/secundă. Același sistem de monitorizare este apoi rescris cu virtual threads: cod imperativ, bucle explicite cu `sleep`, `BlockingQueue` pe post de backpressure manual, stare partajată sincronizată manual — mai familiar, dar cu mai multă responsabilitate pe umerii programatorului. Bilanțul final: reactive câștigă la backpressure nativ și transformări complexe de fluxuri, dar pierde la curba de învățare, debugging (stack traces criptice) și mentenanță; virtual threads câștigă la simplitate și compatibilitate cu codul sincron existent. Autorul înclină spre virtual threads, dar prezice coexistență și convergență — Vert.x 4.5 experimentează deja cu virtual threads.

## Concepte explicate

### Blocking vs. non-blocking I/O

- **Definiție** — la I/O blocant, thread-ul apelant stă blocat până se termină operația (read/accept/write); la I/O non-blocant, apelul se întoarce imediat (posibil cu 0 bytes transferați), iar OS-ul notifică aplicația când datele sunt gata, tipic printr-un callback sau printr-un mecanism de readiness.
- **De ce contează** — cu I/O blocant pe un singur thread, un request lent blochează întregul server: alte conexiuni sunt acceptate de OS la nivel TCP, dar aplicația nu le procesează (în carte, curl expiră după 5 s deși conexiunea TCP reușise). Soluția clasică — thread per conexiune — se lovește de costul platform threads.
- **Exemplu de cod** — varianta greșită (server single-threaded care se blochează):

  ```java
  // GREȘIT: accept() + procesare pe același thread => un client lent blochează tot
  try (ServerSocket ss = new ServerSocket(8080)) {
      while (true) {
          Socket client = ss.accept();      // blochează până vine o conexiune
          handle(client);                   // blochează până termină clientul
      }
  }

  // CORECT (pre-Loom): fiecare conexiune pe alt thread
  var pool = Executors.newFixedThreadPool(10);
  while (true) {
      Socket client = ss.accept();
      pool.submit(() -> handle(client));    // main-ul revine imediat la accept()
  }

  // CORECT (cu Loom): aceeași structură, scalare nelimitată practic
  var vexec = Executors.newVirtualThreadPerTaskExecutor();
  ```
- **Capcane frecvente** — a confunda „conexiunea TCP s-a stabilit" cu „serverul procesează request-ul" (OS-ul face handshake-ul chiar dacă aplicația e blocată); a crede că mărirea pool-ului de platform threads scalează liniar (nu — memorie + context switching); a uita `setSoTimeout` și a rămâne agățat pe clienți morți.

### Java NIO (channels, buffers, Selector)

- **Definiție** — API readiness-based, non-blocant, din JDK 1.4: înlocuiește stream-urile din `java.io` cu canale orientate pe buffere (`SocketChannel`, `ServerSocketChannel`, `FileChannel`) și un `Selector` care doarme în `select()` până când OS-ul raportează că unul sau mai multe canale sunt gata de I/O (evenimente `OP_ACCEPT`, `OP_READ`, `OP_WRITE`, `OP_CONNECT`). Un singur thread poate astfel servi mii de conexiuni. Bufferele permit batch processing (mai puține syscalls) și zero-copy prin direct/memory-mapped buffers. Implementarea selectorului depinde de OS (kqueue pe macOS, poll în varianta generică).
- **De ce contează** — a fost calea principală spre scalabilitate înainte de virtual threads; elimină costul thread-per-socket, dar mută complexitatea în aplicație: citiri/scrieri parțiale, stare per-client, mașinării de evenimente.
- **Exemplu de cod** — scheletul unui event loop:

  ```java
  Selector sel = Selector.open();
  ServerSocketChannel srv = ServerSocketChannel.open();
  srv.bind(new InetSocketAddress(8080));
  srv.configureBlocking(false);              // FĂRĂ asta, NIO devine tot blocant!
  srv.register(sel, SelectionKey.OP_ACCEPT);

  while (true) {
      sel.select(100);                       // timeout, nu blocare infinită
      var it = sel.selectedKeys().iterator();
      while (it.hasNext()) {
          SelectionKey key = it.next();
          it.remove();                       // altfel evenimentul se reprocesează
          if (key.isAcceptable()) accept(key, sel);
          else if (key.isReadable()) read(key);   // poate întoarce date PARȚIALE
          else if (key.isWritable()) write(key);  // poate scrie PARȚIAL
      }
  }
  ```
- **Capcane frecvente** — uitatul lui `configureBlocking(false)`; neeliminarea cheii din `selectedKeys` (evenimente fantomă); tratarea unui `read()` ca și cum ar livra mereu un mesaj complet (trebuie acumulat într-un buffer per-client până apare terminatorul, ex. `\r\n\r\n` la HTTP); lăsarea `OP_WRITE` înregistrat permanent (selectorul se trezește continuu degeaba — se scoate interesul când coada de răspunsuri e goală).

### Thread safety pe SelectionKey (pattern-ul pendingUpdates)

- **Definiție** — modificarea `interestOps()` pe un `SelectionKey` trebuie făcută exclusiv din thread-ul care rulează bucla selectorului; thread-urile async care termină procesarea pun o „cerere de update" într-o coadă thread-safe (`ConcurrentLinkedQueue`) și apelează `selector.wakeup()`, iar bucla principală aplică update-urile la începutul fiecărei iterații.
- **De ce contează** — documentația Java spune că selection keys sunt „safe for use by multiple concurrent threads", dar asta înseamnă doar că citirea nu corupe memoria, nu că `interestOps()` e atomic. Modificarea concurentă în timp ce `select()` rulează produce update-uri pierdute, evenimente I/O ratate, `CancelledKeyException` și bug-uri care apar doar sub load.
- **Exemplu de cod**:

  ```java
  // GREȘIT: din thread-ul async, direct pe cheie
  CompletableFuture.runAsync(() -> {
      String resp = process(req);
      state.responses.offer(resp);
      key.interestOps(key.interestOps() | SelectionKey.OP_WRITE); // race!
  });

  // CORECT: coadă + wakeup, aplicarea se face în thread-ul selectorului
  CompletableFuture.runAsync(() -> {
      state.responses.offer(process(req));
      pendingUpdates.offer(key);
      selector.wakeup();
  });
  // ...în bucla selectorului, înainte de select():
  SelectionKey k;
  while ((k = pendingUpdates.poll()) != null)
      if (k.isValid()) k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
  ```
- **Capcane frecvente** — încrederea oarbă în fraza din Javadoc; `select()` fără timeout (update-urile din async threads pot aștepta la nesfârșit dacă nu vine niciun eveniment I/O — de aceea cartea folosește `select(100)` plus `wakeup()`); uitatul verificării `key.isValid()` înainte de update.

### Event-driven architecture și event loop (Vert.x)

- **Definiție** — arhitectură în care un set mic de event loop threads ascultă evenimente I/O (peste NIO) și execută callback-uri scurte, non-blocante; framework-ul (Netty, Vert.x) oferă abstracții de nivel înalt (Router, HTTP request/response, event bus pentru comunicare asincronă loosely-coupled între componente), iar operațiile inerent blocante (DB, filesystem) se deleagă unui worker pool separat.
- **De ce contează** — handler-ele aplicației rulează PE event loop thread; dacă blochezi acest thread, întreaga aplicație îngheață — niciun alt eveniment nu mai e procesat. Abuzul de worker threads e și el o problemă: fiecare comutare I/O-thread ↔ worker-thread adaugă context switches care erodează exact câștigul de eficiență urmărit.
- **Exemplu de cod**:

  ```java
  // GREȘIT: blochează event loop-ul — tot serverul stă 2 secunde
  router.get("/slow").handler(ctx -> {
      Thread.sleep(2000);                     // catastrofal pe event loop
      ctx.response().end("done");
  });

  // CORECT: programezi un callback, thread-ul rămâne liber
  router.get("/slow").handler(ctx ->
      vertx.setTimer(2000, id -> ctx.response().end("done")));
  ```
- **Capcane frecvente** — apeluri blocante „mici" strecurate în handler (JDBC, `File.read`, chiar loguri sincrone lente); mutarea a tot ce mișcă pe worker pool „ca să fie sigur" (anulează avantajul reactiv); nefolosirea structurilor thread-safe (`AtomicLong` etc.) pentru stare partajată între event loop threads.

### API-uri asincrone: callbacks și callback hell

- **Definiție** — un API sincron blochează apelantul până întoarce rezultatul; unul asincron se întoarce imediat și livrează rezultatul mai târziu printr-un callback (`Consumer<T>`). Callback hell = imbricarea adâncă de callback-uri care apare când rezultatul unui apel async alimentează următorul.
- **De ce contează** — callback-urile nu compun: fluxurile secvențiale devin piramide de cod imbricat, greu de citit, de întreținut și de tratat la erori.
- **Exemplu de cod**:

  ```java
  // GREȘIT: piramida
  svc.fetch("a", r1 ->
      svc.fetch(r1, r2 ->
          svc.fetch(r2, r3 ->
              System.out.println(r3))));

  // CORECT: compoziție cu CompletableFuture (vezi conceptul următor)
  svc.fetch("a").thenCompose(svc::fetch)
                .thenCompose(svc::fetch)
                .thenAccept(System.out::println);
  ```
- **Capcane frecvente** — tratarea erorilor duplicată în fiecare nivel de callback; pierderea contextului (stack trace-ul nu mai arată lanțul logic); presupunerea că ordinea apelurilor async e ordinea execuției.

### CompletableFuture

- **Definiție** — abstracția standard din JDK pentru un rezultat asincron unic; se creează cu `supplyAsync`, se compune cu `thenCompose` (înlănțuire de operații async), `thenApply` (transformare), `thenAccept` (consum final) și are API bogat de error handling (`exceptionally`, `handle`).
- **De ce contează** — rezolvă callback hell pentru rezultate unice, dar nu modelează fluxuri: pentru 0..N valori emise în timp (ticks, mesaje, prețuri) e nevoie de reactive streams. Aceasta e exact granița unde capitolul justifică existența Reactor/RxJava.
- **Exemplu de cod**:

  ```java
  CompletableFuture<String> chat(String msg) {
      return CompletableFuture.supplyAsync(() -> callModel(msg));
  }
  chat("salut")
      .thenCompose(this::chat)               // async după async, fără nesting
      .thenAccept(System.out::println)
      .exceptionally(e -> { log(e); return null; });
  ```
- **Capcane frecvente** — `thenApply` în loc de `thenCompose` când funcția întoarce tot un future (rezultă `CompletableFuture<CompletableFuture<T>>`); apelarea lui `.join()`/`.get()` pe un event loop thread (reintroduce blocarea); ignorarea excepțiilor (un future eșuat tăcut, fără handler).

### Reactive streams (Publisher / Subscriber / Subscription / Processor)

- **Definiție** — standard (Reactive Streams Specification) pentru fluxuri asincrone de date cu backpressure. Patru componente: **Publisher** (sursa, emite 0..N elemente în timp), **Subscriber** (consumatorul), **Subscription** (legătura dintre ei, prin care se controlează cererea/backpressure), **Processor** (simultan Subscriber și Publisher — un operator de transformare). Un stream emite trei tipuri de semnale: elemente de date, un semnal de eroare (terminal) sau un semnal de completare (terminal). Biblioteci: Project Reactor (`Flux<T>` 0..N, `Mono<T>` 0..1), RxJava (`Observable`, `Single`, `Maybe`).
- **De ce contează** — codul se organizează în pipeline-uri declarative de transformări; stream-urile sunt lazy (nu emit nimic fără Subscriber — „cold stream"), iar erorile curg prin canal dedicat, nu prin try/catch clasic. Atenție: reactive streams ≠ `java.util.stream.Stream` (acela e sincron, in-memory, fără backpressure).
- **Exemplu de cod**:

  ```java
  Flux.just(1, 2, 3, 4, 5)
      .filter(n -> n % 2 == 0)
      .map(n -> "Valoare: " + n)
      .subscribe(
          System.out::println,                       // onNext
          err -> System.err.println("Eroare: " + err), // onError (terminal)
          () -> System.out.println("Gata"));           // onComplete
  // Fără subscribe(), pipeline-ul de mai sus NU rulează nimic.
  ```
- **Capcane frecvente** — construirea pipeline-ului fără `subscribe()` și mirarea că „nu face nimic"; confuzia cold vs. hot (un cold stream re-emite de la zero pentru fiecare Subscriber; un hot stream, ex. dintr-un `Sink`, difuzează aceleași evenimente tuturor); aruncarea de excepții în operatori fără să realizezi că termină definitiv stream-ul.

### Operatori de stream (merge, groupBy, window, buffer, Sinks)

- **Definiție** — funcții care primesc un stream și produc altul: `merge` combină mai multe surse interleaved; `groupBy` partiționează după cheie în substream-uri procesabile în paralel; `window(Duration)` taie fluxul în ferestre temporale (fiecare fereastră e ea însăși un `Flux`); `buffer(n, skip)` colectează elemente în liste cu fereastră glisantă; `Sinks` sunt puntea imperativ→reactiv (emiti manual cu `tryEmitNext`, creând hot streams multicast); `doOnNext` adaugă side effects (logging) fără să altereze fluxul.
- **De ce contează** — aceștia înlocuiesc gestiunea manuală de thread-uri, sincronizare și structuri partajate: în exemplul crypto, media mobilă pe 5 secunde e `window + flatMap + reduce`, fără niciun `synchronized`.
- **Exemplu de cod**:

  ```java
  Flux<Price> all = Flux.merge(feedBinance(), feedCoinbase(), feedKraken());
  all.groupBy(Price::symbol)
     .subscribe(perSymbol -> perSymbol
         .window(Duration.ofSeconds(5))
         .flatMap(w -> w.map(Price::value).collect(Collectors.averagingDouble(d -> d)))
         .subscribe(avg -> System.out.println(perSymbol.key() + " avg=" + avg)));

  Sinks.Many<Alert> alerts = Sinks.many().multicast().onBackpressureBuffer();
  alerts.tryEmitNext(new Alert(...));     // emitere imperativă în lumea reactivă
  alerts.asFlux().subscribe(this::notifyUser);
  ```
- **Capcane frecvente** — `buffer` vs. `window` (liste materializate vs. sub-fluxuri); subscribe imbricat scăpat de sub control (leak de subscriptions); uitarea că `groupBy` cu multe chei nedreanate poate bloca fluxul principal.

### Backpressure

- **Definiție** — mecanismul prin care Subscriber-ul semnalează Publisher-ului că nu poate procesa în ritmul de emisie; e parte din specificația Reactive Streams (prin Subscription) și are strategii explicite în Reactor: `onBackpressureBuffer()` (nimic pierdut, cost memorie), `onBackpressureBuffer(maxSize)` (limită + fail), `onBackpressureDrop()` (aruncă excesul), `onBackpressureLatest()` (păstrează doar ultimul), `onBackpressureError()` (fail fast), plus `sample(Duration)` pentru eșantionare periodică.
- **De ce contează** — fără backpressure, un producător rapid (10.000 evenimente/s în demo-ul din carte) îneacă un consumator lent: memorie umflată, latențe, OOM. E argumentul principal pe care autorul îl reține în favoarea reactive: virtual threads nu au un echivalent nativ — acolo backpressure-ul îl construiești manual (ex. `BlockingQueue` mărginit).
- **Exemplu de cod**:

  ```java
  hotFeed
      .onBackpressureLatest()                    // contează doar starea curentă
      .publishOn(Schedulers.boundedElastic())    // consum pe alt pool decât emisia
      .subscribe(p -> slowRender(p));            // consumator lent, dar sănătos

  // Alternative după cerință:
  // .sample(Duration.ofMillis(100))   -> snapshot periodic
  // .onBackpressureDrop(this::countDropped) -> real-time, pierderi acceptate
  // .onBackpressureBuffer(10_000)     -> nimic pierdut, dar cu plafon de memorie
  ```
- **Capcane frecvente** — `onBackpressureBuffer()` nelimitat pe fluxuri infinite (OOM cu întârziere); alegerea lui `drop` acolo unde completitudinea datelor contează (ex. tranzacții); uitarea lui `publishOn` — fără separarea producției de consum, strategia de backpressure nici nu apucă să se manifeste corect.

### Virtual threads ca alternativă la pipeline-ul reactiv

- **Definiție** — același sistem (monitorizare prețuri) scris imperativ: un virtual thread per feed cu buclă `while` + `Thread.sleep`, un virtual thread nou per eveniment de procesat, stare partajată în `ConcurrentHashMap` + `AtomicReference`, alerte printr-un `BlockingQueue`, medie mobilă cu `LinkedList` sub `synchronized`.
- **De ce contează** — arată trade-off-ul concret: codul reactiv „descrie transformări de date" declarativ (window, groupBy, onBackpressureDrop fac totul), pe când codul cu virtual threads „e o secvență de instrucțiuni" — familiar, ușor de citit linie cu linie și de debugat, dar programatorul preia manual sincronizarea, timing-ul și backpressure-ul.
- **Exemplu de cod** — echivalențe:

  ```java
  // Reactiv:                          // Virtual threads:
  Flux.interval(ofSeconds(1))          while (!interrupted()) {
      .map(i -> fetchPrice())              Thread.sleep(1000);
      .subscribe(this::process);           var p = fetchPrice();
                                           Thread.startVirtualThread(() -> process(p));
                                       }
  // Backpressure nativ:               // Backpressure manual:
  .onBackpressureBuffer(1000)          new LinkedBlockingQueue<Alert>(1000) // offer() refuză la plin
  ```
- **Capcane frecvente** — a uita că starea partajată între virtual threads cere aceeași sincronizare ca între platform threads (imutabilitatea din pipeline-urile reactive nu mai vine gratis); a crea virtual threads fără nicio limitare acolo unde downstream-ul e lent (echivalentul lipsei de backpressure); a copia mecanic patternuri reactive în cod imperativ în loc să folosești structuri clasice (cozi mărginite, semafoare).

### Debugging-ul și mentenanța codului reactiv

- **Definiție** — costurile ascunse ale paradigmei: execuția asincronă și event-driven face ca fluxul logic din cod să difere radical de fluxul real de execuție; stack trace-urile sunt dominate de frame-uri interne Reactor (`FluxMapFuseable$MapFuseableSubscriber.onNext`...), thread dump-urile devin greu de citit.
- **De ce contează** — o simplă `ArithmeticException` într-un `map` produce un stack trace în care doar prima linie indică codul tău; restul e plumbing de bibliotecă. Asta scumpește troubleshooting-ul în producție și ridică bariera pentru colegii noi pe proiect; combinat cu bogăția de operatori, face comportamentul greu de anticipat la modificări.
- **Exemplu de cod**:

  ```java
  Flux.just(1, 2, 3, 0, 5)
      .map(n -> 10 / n)          // la 0: ArithmeticException — stream-ul MOARE aici
      .subscribe(System.out::println,
                 Throwable::printStackTrace);  // 5 nu se mai emite niciodată
  // Mai robust: izolezi eroarea per element sau dai fallback
  Flux.just(1, 2, 3, 0, 5)
      .flatMap(n -> Mono.fromSupplier(() -> 10 / n)
                        .onErrorResume(e -> Mono.empty()))
      .subscribe(System.out::println);
  ```
- **Capcane frecvente** — a presupune că după `onError` stream-ul continuă (e semnal terminal); debugging cu breakpoint-uri clasice în lambda-uri care rulează pe alte thread-uri decât te aștepți; refactorizări „inofensive" de operatori care schimbă semantica (ex. ordinea `filter`/`map` cu side effects).

## Listing-uri cheie din carte

Textul capitolului NU numerotează exemplele cu captions „Example 6-N" (există doar Figura 6-1 — event loop-ul Vert.x — și Figura 6-2 — fluxul de date reactiv), așa că le identific după secțiune:

1. **`BlockingHttpServer`** (secțiunea „Blocking Versus Non-blocking I/O") — server HTTP single-threaded cu pipelining și keep-alive; instructiv pentru că demonstrează cu curl, măsurabil (47 s pentru fast+slow+fast), cum un singur request lent blochează tot serverul.
2. **Sesiunile curl de test** (aceeași secțiune) — nu e cod Java, dar e proba empirică: al doilea client stabilește conexiunea TCP (OS-ul o acceptă), însă aplicația n-o servește; distincția kernel vs. aplicație e lecția.
3. **`MultithreadedHttpServer`** (aceeași secțiune) — thread pool fix de 10 pentru conexiuni; arată soluția clasică și limita ei (costul platform threads), plus varianta one-liner cu `newVirtualThreadPerTaskExecutor()`.
4. **Comparația I/O vs. NIO pe citire de fișier** (caseta „Java I/O Versus NIO") — `BufferedReader` linie-cu-linie vs. `FileChannel` + `ByteBuffer` cu `flip()`/`clear()`; instructiv pentru contrastul stream-oriented vs. buffer-oriented în cea mai mică formă posibilă.
5. **`NonBlockingHttpServer`** (aceeași secțiune, partea NIO) — cel mai amplu listing din capitol: `Selector` + event loop + `handleAccept`/`handleRead`/`handleWrite` + `ClientState` + `processAllPendingRequests` cu `CompletableFuture.runAsync`; instructiv pentru pattern-ul complet de server NIO și mai ales pentru coada `pendingUpdates` + `selector.wakeup()` care rezolvă thread safety pe `SelectionKey` (nota din carte despre miconcepția „selection keys are thread-safe" e aur).
6. **`PipeliningLoadTest`** (aceeași secțiune) — client de load cu 10 virtual threads × 100 request-uri pipelined; instructiv pentru că folosește chiar virtual threads ca unealtă de test și pentru semantica pipelining-ului HTTP/1.1 (trimite tot, apoi citește răspunsurile în ordine).
7. **`VertxHttpServer`** (secțiunea „Event-Driven Architecture") — Router + handlers + `vertx.setTimer()` în loc de sleep; instructiv pentru regula de aur „nu bloca event loop-ul" și pentru endpoint-ul `/stats` care dovedește pe ce thread rulezi.
8. **Evoluția `AiService`** (secțiunea „Asynchronous APIs") — trei versiuni ale aceleiași metode `chat`: sincronă, cu callback `Consumer<String>` (culminând cu piramida callback hell pe 4 niveluri), apoi cu `CompletableFuture` + `thenCompose`; cea mai clară progresie didactică din capitol.
9. **`ReactiveExample`** (secțiunea „Understanding Reactive Streams") — primul `Flux`: `just → filter → map → subscribe(onNext, onError, onComplete)`; minimul necesar ca să vezi pipeline-ul și lazy-ness-ul.
10. **`SimplePriceMonitor` și `CryptoPriceMonitor`** (aceeași secțiune) — de la un `Flux.interval` cu filtru de prag, la sistemul complet multi-exchange cu `merge`, `groupBy`, `window`, `buffer(2,1)`, `Sinks` multicast; instructiv ca vitrină a operatorilor pe un caz realist.
11. **`BackpressureDemo`** (secțiunea „Backpressure") — feed de 10.000 evenimente/s cu trei strategii demonstrate live (`sample`, `onBackpressureDrop` + contorizare, `onBackpressureLatest`) și `publishOn(Schedulers.boundedElastic())`; instructiv pentru că fiecare strategie e legată de un caz de utilizare concret.
12. **`PriceMonitorWithVirtualThreads`** (finalul secțiunii de reactive) — rescrierea imperativă completă a monitorului de prețuri; instructiv exact prin comparația 1:1 cu varianta reactivă: ce era operator devine buclă+sleep, ce era backpressure devine `BlockingQueue`, ce era imutabil devine stare sincronizată manual.
13. **`ReactiveErrorExample`** (secțiunea „Benefits and Downsides", downsides) — `Flux.just(1,2,3,0,5).map(10/n)` + stack trace-ul real de ~15 frame-uri Reactor; instructiv ca dovadă concretă a costului de debugging.

## Citate

- „I think Loom is going to kill reactive programming... Reactive programming was a transitional technology." — Brian Goetz, epigraful capitolului.
- „Slow down! I can't process this fast enough." — definiția intuitivă a backpressure-ului, secțiunea „Backpressure".
- „It should never, under any circumstance, block the I/O threads." — regula de aur a event loop-ului, secțiunea „Event-Driven Architecture".

## Legături

- **Cap. 2 (Understanding Virtual Threads)** — fundamentul comparației: de acolo știm de ce platform threads sunt scumpe și de ce `newVirtualThreadPerTaskExecutor()` transformă serverul multithreaded într-unul masiv scalabil cu o singură linie. Capitolul 6 presupune stăpânit modelul mounting/unmounting și costul thread-per-request clasic.
- **Cap. 3 (Mechanics of Modern Concurrency)** — `ExecutorService`, `CompletableFuture` și primitivele de sincronizare (`AtomicReference`, `ConcurrentHashMap`, `BlockingQueue`) folosite intens aici, atât în serverul NIO cât și în varianta virtual-threads a monitorului de prețuri.
- **Cap. 4 (Structured Concurrency)** — autorul sugerează explicit în încheiere că principiile structured concurrency ar putea influența modelele reactive, făcându-le mai robuste; comparativ, pipeline-ul reactiv oferă deja un fel de „structură" a fluxului, dar fără scope-uri și anulare ierarhică.
- **Cap. 5 (Scoped Values)** — tangențial: propagarea contextului e o durere cunoscută în lumea reactivă (thread-ul se schimbă între operatori), pe care scoped values + virtual threads o rezolvă natural în stilul imperativ.
- **Cap. 7 (Modern Frameworks Utilizing Virtual Threads)** — continuarea directă: după verdictul „coexistență și convergență" (Vert.x 4.5 experimentează deja cu virtual threads), capitolul 7 arată cum framework-urile adoptă efectiv virtual threads.
- **De stăpânit înainte de a merge mai departe**: diferența blocking/non-blocking la nivel de OS și de aplicație; de ce nu ai voie să blochezi un event loop; cele 4 componente Reactive Streams + cele 3 semnale; strategiile de backpressure și cazurile lor de utilizare; trade-off-ul declarativ-cu-backpressure-nativ vs. imperativ-simplu-cu-sincronizare-manuală — acesta e criteriul de decizie pe care capitolele următoare îl presupun interiorizat.
