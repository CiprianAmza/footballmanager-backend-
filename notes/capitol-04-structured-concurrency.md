# Capitolul 04 — Structured Concurrency

## Rezumat amplu

Capitolul pornește de la problema rămasă deschisă după introducerea virtual threads în capitolele anterioare: chiar dacă putem crea mii de threaduri ieftine, modelele clasice (`ExecutorService` + `Future`) nu cunosc relația dintre un task părinte și subtask-urile lui. Autorul demonstrează asta cu un serviciu de produse care aduce în paralel detaliile produsului și review-urile: dacă un subtask eșuează, celălalt continuă să ruleze inutil, iar dacă părintele este întrerupt, subtask-urile devin threaduri orfane care consumă resurse. Acest „unstructured concurrency" este comparat explicit cu `goto` din programarea veche: execuția sare arbitrar între threaduri și starea programului devine greu de urmărit. Structured concurrency propune un principiu simplu: toate subtask-urile pornite de un task trebuie să se întoarcă în același punct din codul părintelui, iar durata lor de viață este strict inclusă în cea a părintelui. Beneficiile enumerate sunt: propagarea unitară a erorilor și a cancellation-ului între task-uri „frați", eliminarea thread leak-urilor, observabilitate mai bună (thread dumps ierarhice) și un stil declarativ, concentrat pe logica de business. API-ul central este `StructuredTaskScope` (preview în JDK 25, necesită `--enable-preview`), un interfață sealed folosită în try-with-resources, cu metodele `fork()`, `join()` și `close()`; închiderea în ordine greșită a scope-urilor imbricate aruncă `StructureViolationException`. Rescrierea exemplului cu produse arată câștigul: la eșecul unui subtask, celălalt este anulat automat, iar `join()` aruncă `FailedException` cu excepția originală drept cauză — versiunea eșuată se termină în ~7ms în loc de ~2 secunde. Comportamentul lui `join()` este definit de un obiect `Joiner`, care are metode de outcome (`result()`, `exception()`) și hook-uri de lifecycle (`onFork()`, `onComplete()`). Cartea parcurge politicile standard: `awaitAllSuccessfulOrThrow()` (default, fail-fast, tipuri de rezultat diferite), `anySuccessfulResultOrThrow()` (cursă — primul succes câștigă și anulează restul), `allSuccessfulOrThrow()` (fail-fast, dar întoarce `Stream<Subtask>` când toate task-urile au același tip), `awaitAll()` (așteaptă tot, indiferent de eșecuri — ideal pentru side effects, notificări multi-canal și servere rezistente) și `allUntil(Predicate)` (condiție de oprire custom, ex. „oprește-te la primul backup reușit"). Urmează un tur al strategiilor de exception handling: catch pe `FailedException` cu `getCause()`, pattern matching cu `switch` pe cauză pentru mesaje de business specifice (exemplu de order processing e-commerce), propagarea deliberată către niveluri superioare pentru handling centralizat și tratarea excepțiilor în interiorul subtask-ului pentru graceful degradation cu valori default. Secțiunea de configurare arată overload-ul `open(joiner, configFunction)` cu `withThreadFactory()` (threaduri numite pentru debugging), `withTimeout()` (cronometrul pornește la deschiderea scope-ului, nu la `join()`) și `withName()` (numele scope-ului în monitoring). Partea cea mai avansată sunt joiner-ele custom: implementând `onFork`/`onComplete`/`result` obții coordonări arbitrare — colectarea tuturor rezultatelor și excepțiilor, quorum (3 din 5 noduri), circuit breaker adaptiv pe rata de eșec, rate limiting cu `Semaphore` și execuție condiționată de un health check. Capitolul explică apoi garanțiile de memory consistency: `fork()` și `join()` creează relații happens-before în ambele direcții, deci starea pregătită de owner e vizibilă subtask-urilor și invers, fără sincronizare suplimentară — dar colecțiile concurente rămân necesare când subtask-urile scriu în stare partajată. Scope-urile pot fi imbricate în ierarhii pe niveluri (exemplu: document processor cu fază de gathering și fază de analiză), erorile propagându-se natural în sus prin arbore. Observabilitatea este concretizată prin thread dumps JSON generate cu `jcmd` sau programatic cu `HotSpotDiagnosticMXBean`, în care scope-urile numite apar ca thread containers cu owner și copii clar identificați. În încheiere, autorul precizează că structured concurrency funcționează și cu platform threads (printr-un `ThreadFactory` custom), dar virtual threads rămân alegerea firească datorită scalabilității.

## Concepte explicate

### Unstructured concurrency (problema de bază)

- **Definiție** — stilul clasic de concurență în care task-urile lansate prin `ExecutorService`/`Future` sunt entități independente, fără nicio legătură formală de lifecycle între task-ul care le-a creat și ele.
- **De ce contează** — produce thread leaks (subtask-uri orfane care rulează după ce părintele a eșuat/a fost anulat), muncă irosită (aștepți un rezultat care nu mai poate fi folosit), erori care nu se propagă între frați și thread dumps ilizibile, fără relații task–subtask. Este analogia modernă a lui `goto`.
- **Exemplu de cod** — varianta problematică:

```java
// GREȘIT: dacă fetchA() aruncă, fetchB() continuă să ruleze degeaba
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<A> fa = exec.submit(this::fetchA);
    Future<B> fb = exec.submit(this::fetchB);
    return combine(fa.get(), fb.get()); // fa.get() poate arunca; fb rulează orfan
}
```

- **Capcane frecvente** — presupunerea că închiderea executor-ului anulează task-urile eșuate „la timp" (doar așteaptă terminarea lor); ordinea apelurilor `get()` decide ce eșec vezi primul; interruption pe părinte nu se propagă la subtask-uri.

### Structured concurrency (principiul)

- **Definiție** — paradigmă în care un grup de task-uri înrudite este tratat ca o singură unitate de lucru: subtask-urile trăiesc strict în interiorul lifecycle-ului părintelui și toate „raportează înapoi" în același punct din cod înainte ca operația să se încheie.
- **De ce contează** — garantează cleanup automat (fără orfani), propagare naturală a erorilor și cancellation-ului în ambele sensuri, și face structura runtime a concurenței să oglindească structura lexicală a codului.
- **Exemplu de cod** — vezi `StructuredTaskScope` mai jos; principiul e vizibil în faptul că `fork`-urile și `join`-ul stau în același bloc try.
- **Capcane frecvente** — a scoate un `Subtask` în afara scope-ului și a-l folosi după `close()`; a crede că structured concurrency elimină nevoia de thread safety pe starea partajată (nu o elimină).

### StructuredTaskScope

- **Definiție** — interfața sealed din `java.util.concurrent` (preview în JDK 25) care materializează scope-ul părinte: se creează cu factory-ul static `open()` / `open(Joiner)` / `open(Joiner, configFn)`, se folosește în try-with-resources și oferă `fork()`, `join()`, `close()`.
- **De ce contează** — este containerul care leagă lifecycle-urile: la ieșirea din scope, toate threadurile subtask-urilor sunt garantat terminate. Închiderea incorectă a scope-urilor imbricate (copil rămas deschis) duce la `StructureViolationException`.
- **Exemplu de cod**:

```java
ProductInfo fetch(long id) throws InterruptedException {
    try (var scope = StructuredTaskScope.<Object>open()) {   // politica default
        Subtask<Product> p = scope.fork(() -> fetchProduct(id));
        Subtask<List<Review>> r = scope.fork(() -> fetchReviews(id));
        scope.join();                        // toate reușesc sau FailedException
        return new ProductInfo(p.get(), r.get());
    }
}
```

- **Capcane frecvente** — apelarea manuală a `close()` în interiorul try-with-resources; apelarea `join()` de mai multe ori (permis o singură dată, doar de ownerul scope-ului); `fork()` după `close()` aruncă `IllegalStateException`; argumente `null` (joiner, callable) aruncă `NullPointerException`; a uita `--enable-preview` la compilare/rulare.

### fork() și Subtask

- **Definiție** — `fork(Callable)` / `fork(Runnable)` creează și pornește un subtask pe un thread nou (tipic virtual) și întoarce imediat un handle `Subtask<U>`. La finalizare, subtask-ul are o stare: `SUCCESS`, `FAILED` sau `UNAVAILABLE` (anulat înainte de a se termina). Intern, `onFork()` al joiner-ului e consultat — dacă scope-ul e deja anulat, threadul nici nu mai pornește.
- **De ce contează** — `Subtask` nu este un `Future`: rezultatul (`get()`) sau excepția (`exception()`) pot fi citite **doar după** `join()`. Asta impune disciplina „întâi aștepți tot grupul, apoi citești".
- **Exemplu de cod**:

```java
// GREȘIT: get() înainte de join()
var t = scope.fork(this::work);
var v = t.get();          // IllegalStateException

// CORECT
var t2 = scope.fork(this::work);
scope.join();
var v2 = t2.get();
```

- **Capcane frecvente** — tratarea `Subtask` ca pe un `Future` cu blocare proprie; ignorarea stării `UNAVAILABLE` când procesezi manual subtask-urile (ex. cu `awaitAll()`); varianta `Runnable` întoarce `null` din `get()`.

### join() și FailedException

- **Definiție** — `join()` blochează ownerul până când politica joiner-ului e satisfăcută (toate au reușit, primul succes, timeout etc.). Dacă outcome-ul e o excepție, `join()` aruncă `StructuredTaskScope.FailedException` având drept cauză excepția originală a subtask-ului; la timeout aruncă `TimeoutException`.
- **De ce contează** — centralizează error handling-ul: un singur punct de eșec pentru tot grupul, cu excepția reală accesibilă prin `getCause()`. Cu politica default, primul eșec anulează automat frații (fail-fast).
- **Exemplu de cod**:

```java
try (var scope = StructuredTaskScope.<String>open()) {
    scope.fork(this::taskA);
    scope.fork(this::taskB);
    scope.join();
} catch (StructuredTaskScope.FailedException e) {
    log.warn("un subtask a eșuat: " + e.getCause().getMessage());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // restaurează flagul!
    throw new IllegalStateException(e);
}
```

- **Capcane frecvente** — a loga `e.getMessage()` al wrapper-ului în loc de `getCause()`; a înghiți `InterruptedException` fără `Thread.currentThread().interrupt()`; a presupune că `awaitAll()` aruncă `FailedException` (nu o aruncă niciodată).

### Joiner (politicile de join)

- **Definiție** — obiectul care dictează când se termină `join()` și ce produce. Interfața are metode de outcome (`result()`, `exception()`) și hook-uri (`onFork()`, `onComplete()`). Politici standard: `awaitAllSuccessfulOrThrow()` (default — toate reușesc sau fail-fast), `anySuccessfulResultOrThrow()` (cursă, primul succes anulează restul), `allSuccessfulOrThrow()` (fail-fast + `Stream<Subtask<T>>` la succes, pentru task-uri de același tip), `awaitAll()` (așteaptă tot, nu anulează nimic, `join()` întoarce null), `allUntil(Predicate<Subtask>)` (anulare custom pe predicat).
- **De ce contează** — alegerea joiner-ului este alegerea semanticii de business: „all-or-nothing", „primul câștigă", „cât mai multe canale", „quorum". Cu joiner nepotrivit obții fie muncă irosită, fie rezultate parțiale nedorite.
- **Exemplu de cod** — cursă între surse:

```java
Product fastest(long id) throws InterruptedException {
    try (var scope = StructuredTaskScope.open(
            StructuredTaskScope.Joiner.<Product>anySuccessfulResultOrThrow())) {
        scope.fork(() -> fromCache(id));
        scope.fork(() -> fromDb(id));
        scope.fork(() -> fromApi(id));
        return scope.join();   // rezultatul primului subtask reușit
    }
}
```

- **Capcane frecvente** — folosirea `allSuccessfulOrThrow()` când task-urile au tipuri diferite (mai natural default-ul + `get()` individual); așteptarea unui rezultat din `join()` la `awaitAll()` (întoarce null — rezultatele se strâng prin side effects thread-safe); la `anySuccessfulResultOrThrow()`, `FailedException` apare doar dacă **toate** eșuează.

### Exception handling: patru strategii

- **Definiție** — capitolul sistematizează patru pattern-uri: (1) catch local pe `FailedException` + mesaj prietenos; (2) pattern matching cu `switch` pe `getCause()` pentru mapare pe excepții de business; (3) propagare deliberată (fără catch, semnătura declară `FailedException`) către un nivel cu mai mult context; (4) handling în interiorul subtask-ului, care convertește eșecul în rezultat default (graceful degradation).
- **De ce contează** — decide unde trăiește logica de recovery. Handling-ul în subtask previne ca eșecul unei surse opționale să anuleze tot dashboard-ul; pattern matching-ul transformă un eșec tehnic în feedback acționabil pentru utilizator.
- **Exemplu de cod** — degradare grațioasă în subtask:

```java
private ServiceResponse fetchSafe(String svc) {
    try {
        return new ServiceResponse(svc, callService(svc), true);
    } catch (Exception e) {          // eșecul NU mai ajunge la joiner
        return new ServiceResponse(svc, "cached-default", false);
    }
}
// ...în scope: scope.fork(() -> fetchSafe("recommendations"));
```

- **Capcane frecvente** — catch generic care ascunde tipul real al eșecului; nedeclararea excepțiilor checked la propagare prin straturi intermediare; a uita că, dacă subtask-urile își tratează singure excepțiile, blocul `catch (FailedException)` exterior devine cod „mort" care merită logat ca anomalie.

### Configuration (ThreadFactory, timeout, nume)

- **Definiție** — overload-ul `open(joiner, cf -> ...)` primește o funcție care transformă configurația default (builder imutabil): `withThreadFactory()` pentru threaduri numite (sau platform threads), `withTimeout(Duration)` pentru limită pe întreaga operație, `withName(String)` pentru identificarea scope-ului în tooling.
- **De ce contează** — timeout-ul previne blocarea nelimitată pe servicii lente (aruncă `TimeoutException` din `join()`), iar numele de threaduri/scope fac thread dumps și profiling-ul lizibile în producție.
- **Exemplu de cod**:

```java
ThreadFactory tf = Thread.ofVirtual().name("api-", 0).factory();
try (var scope = StructuredTaskScope.open(
        Joiner.<String>allSuccessfulOrThrow(),
        cf -> cf.withThreadFactory(tf)
                .withTimeout(Duration.ofSeconds(10))
                .withName("api-aggregation"))) {
    ...
}
```

- **Capcane frecvente** — timeout-ul curge **de la deschiderea scope-ului**, nu de la `join()` — muncă făcută între `open()` și `join()` consumă din buget; configurarea e imutabilă (fiecare `withX` întoarce alt obiect — trebuie chain-uite); platform threads prin factory funcționează, dar reintroduc problema rarității lor.

### Custom Joiner (onFork / onComplete / result)

- **Definiție** — implementarea directă a `StructuredTaskScope.Joiner<T,R>`: `onFork()` e apelat la fiecare fork (returnând `true` anulezi scope-ul înainte de pornirea threadului), `onComplete()` la fiecare finalizare (returnând `true` anulezi restul și închei), `result()` produce outcome-ul final al `join()`.
- **De ce contează** — transformă scope-ul într-o platformă de coordonare: quorum (N din M succes), circuit breaker pe rata de eșec, rate limiting cu `Semaphore` în `onFork`/`onComplete`, colectare simultană de succese și eșecuri, condiții externe (health check) care opresc lansarea de task-uri noi.
- **Exemplu de cod** — quorum minimal:

```java
class QuorumJoiner<T> implements StructuredTaskScope.Joiner<T, Boolean> {
    private final int needed;
    private final AtomicInteger ok = new AtomicInteger();
    QuorumJoiner(int needed) { this.needed = needed; }

    @Override public boolean onComplete(Subtask<? extends T> st) {
        return st.state() == Subtask.State.SUCCESS
            && ok.incrementAndGet() >= needed;   // true => anulează restul
    }
    @Override public Boolean result() { return ok.get() >= needed; }
}
```

- **Capcane frecvente** — hook-urile rulează pe threadurile subtask-urilor, deci starea internă trebuie să fie thread-safe (`AtomicInteger`, `ConcurrentLinkedQueue`, `volatile`); uitarea cazului `UNAVAILABLE` în `switch` pe stare; blocarea în `onFork` (ca la rate limiting) ține pe loc threadul care face fork — e o alegere deliberată, nu un accident.

### Memory consistency (happens-before)

- **Definiție** — `fork()` stabilește happens-before de la acțiunile ownerului dinainte de fork către subtask; finalizarea subtask-ului + `join()` stabilesc happens-before de la subtask înapoi la owner, la citirea rezultatului.
- **De ce contează** — starea pregătită înainte de fork (config, cache) e garantat vizibilă în subtask-uri, iar modificările subtask-urilor sunt vizibile ownerului după `join()`, fără `synchronized`/`volatile` suplimentar pe acel flux.
- **Exemplu de cod**:

```java
config = "prod";                        // scris de owner ÎNAINTE de fork
try (var scope = StructuredTaskScope.<String>open()) {
    var t = scope.fork(() -> "văd: " + config);  // garantat "prod"
    scope.join();
    log(t.get());                       // vizibil garantat după join
}
```

- **Capcane frecvente** — garanția e doar pe axa owner↔subtask prin fork/join; **între** subtask-uri care scriu concurent în aceeași structură ai nevoie tot de colecții concurente / atomice (altfel, clasicul „lost update").

### Nested scopes (ierarhii de scope-uri)

- **Definiție** — un subtask (sau o fază ulterioară a metodei) poate deschide propriul `StructuredTaskScope`, formând un arbore de scope-uri; părintele răspunde de lifecycle-ul copiilor, iar erorile/cancellation-ul se propagă în sus, nivel cu nivel.
- **De ce contează** — modelează natural workflow-uri multi-fază (ex.: fază de gathering paralel, apoi fază de analiză paralelă), cu cleanup și cancelare în cascadă corecte la fiecare nivel.
- **Exemplu de cod**:

```java
Report process(String id) throws InterruptedException {
    String header, body;
    try (var gather = StructuredTaskScope.<String>open()) {
        var h = gather.fork(() -> fetchHeader(id));
        var b = gather.fork(() -> fetchBody(id));
        gather.join();
        header = h.get(); body = b.get();
    }
    try (var analyze = StructuredTaskScope.<Object>open()) {  // scope de nivel 2
        var words = analyze.fork(() -> countWords(body));
        var mood  = analyze.fork(() -> sentiment(body));
        analyze.join();
        return new Report((Integer) words.get(), (String) mood.get());
    }
}
```

- **Capcane frecvente** — închiderea scope-urilor în ordine greșită (copilul trebuie închis înaintea părintelui, altfel `StructureViolationException`); ierarhii prea adânci care ascund logica — arborele trebuie să oglindească structura de business, nu invers.

### Observability (structured thread dumps)

- **Definiție** — pentru că scope-urile formează containere explicite de threaduri, `jcmd <pid> Thread.dump_to_file -format=json <file>` produce un dump ierarhic: fiecare scope numit apare ca `threadContainer` cu `parent`, `owner` (threadul care l-a deschis) și lista subtask-urilor cu stack-urile lor. Programatic, `HotSpotDiagnosticMXBean.dumpThreads(..., JSON)` poate captura dump-ul la eroare.
- **De ce contează** — înlocuiește ghicitul din thread dumps plate cu o vedere pe arbore: vezi imediat ce operație logică deține fiecare thread și ce face el acum — debugging sistematic în loc de arheologie.
- **Exemplu de cod** — captură automată la eșec:

```java
} catch (StructuredTaskScope.FailedException e) {
    var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    bean.dumpThreads("sc-error.json",
        HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
    throw e;
}
```

- **Capcane frecvente** — fără `withName()` pe scope și fără `ThreadFactory` cu nume, dump-ul rămâne greu de citit (scope anonim, threaduri nenumite) — investiția minimă în naming se plătește exact când ai un incident.

## Listing-uri cheie din carte

Textul capitolului **nu numerotează** exemplele de cod (nu există captions „Example 4-N"; numerotate sunt doar figurile 4-1, 4-2, 4-3), așa că le identific după secțiune:

- **„The Challenge of Unstructured Concurrency" — ProductService cu ExecutorService/Future**: implementarea „rezonabilă la prima vedere" a fetch-ului paralel de produs + review-uri. Instructiv pentru că e cod idiomatic pre-structured-concurrency care pare corect, dar conține toate defectele (leak, lipsă de cancelare).
- **Aceeași secțiune — scenariul de eșec 5s/1s**: produs lent (5s) + review-uri care pică rapid (1s); logul arată cum părintele mai așteaptă degeaba 5 secunde. Demonstrația empirică a thread leak-ului și a muncii irosite.
- **„Understanding the API" — fetchProductInfo cu StructuredTaskScope.open()**: rescrierea aceluiași caz în 10 linii; la eșec, fratele e anulat automat și `join()` aruncă `FailedException` cu cauza originală. Arată câștigul net de concizie și corectitudine.
- **„Wait for all to succeed or first to fail" — DefaultPolicyDemo (+ comparația cu executor)**: succes în ~2s (timpul celui mai lent task), eșec în ~7ms (fail-fast); aceeași situație cu `ExecutorService` durează tot ~2s. Cea mai clară măsurătoare „before/after" din capitol.
- **„Race for the first successful result" — RacePolicyDemo**: trei surse (cache 500ms, DB 2s, API 3s) cu `anySuccessfulResultOrThrow()`; logurile „was canceled" dovedesc anularea live a perdanților. Instructiv pentru pattern-ul hedging/failover.
- **„Gather all results or fail fast" — BatchValidationDemo**: `allSuccessfulOrThrow()` + fork per user ID + `Stream<Subtask>` la succes; arată și când e preferabil acest joiner (rezultate omogene) față de default.
- **„Wait for all" — AwaitAllDemo (notificări multi-canal)**: email/SMS/push cu rate de succes diferite; `awaitAll()` nu anulează nimic, rezultatele se adună prin side effects thread-safe (`CopyOnWriteArrayList`, `AtomicInteger`). Modelul „partial success e valoros".
- **„Resilient concurrent server" — ResilientServer**: echo server pe socket-uri, un fork per conexiune sub `awaitAll()`; izolarea defectelor — o conexiune căzută nu afectează restul, iar `join()` la shutdown așteaptă conexiunile active.
- **„After first success" — BackupDemo**: `allUntil(predicate)` cu `AtomicBoolean` — oprește backup-urile rămase la primul succes. Ilustrează politica personalizată fără joiner custom complet.
- **„Exception Handling" — BasicExceptionHandling / OrderProcessingService / ExceptionPropagationExample / SubtaskExceptionHandling**: cele patru strategii; OrderProcessingService e vedeta — `switch` cu pattern matching pe `getCause()` mapează excepții de business (payment declined, out of stock, shipping) pe răspunsuri specifice pentru client.
- **„General exceptions" — ExceptionBehaviorDemo**: comportamentul API-ului la utilizare greșită — `NullPointerException` pentru joiner/callable null, `IllegalStateException` la fork pe scope închis.
- **„Configuration" — NamedThreadExample / TimeoutExample / ComprehensiveConfigurationExample**: threaduri numite `user-processor-N`, timeout de 5s care taie sursa de 8s, respectiv combinația factory + timeout + nume de scope.
- **„Custom Joiners" — CollectingJoiner + NewsAggregator**: joiner care strânge și succese și excepții într-un `Result(successes, failures)`; agregatorul de știri afișează tot ce s-a putut aduce plus raportul eșecurilor.
- **„Custom Joiners" — QuorumJoiner + DistributedDatabase**: scriere pe 5 noduri, succes la 3 confirmări, apoi anularea restului — pattern clasic din sisteme distribuite exprimat în ~30 de linii.
- **„Custom Joiners" — AdaptiveJoiner + WebCrawlerWithCircuitBreaker**: circuit breaker — oprire anticipată când rata de eșec depășește 30% după un eșantion minim; trei scenarii (normal, stres, cascadă).
- **„Custom Joiners" — RateLimitedJoiner + RateLimitedAPIService**: `Semaphore` în `onFork`/`onComplete` limitează la 3 task-uri concurente din 6; logul arată clar valurile de execuție.
- **„Custom Joiners" — ConditionalJoiner + SystemHealthCheckDemo**: `onFork` consultă un `Supplier<Boolean>` (health check) și refuză task-uri noi când sistemul devine nesănătos.
- **„Memory Consistency Effects" — MemoryConsistencyDemo**: demonstrația happens-before în ambele direcții (config vizibil în subtask-uri; contoare/mape actualizate de subtask-uri vizibile după join).
- **„Nested Scopes" — DocumentProcessor**: două niveluri de scope (gathering, apoi analiză) cu diagrama ierarhiei; în varianta din „Observability", același processor cu scope numit produce thread dump-ul JSON ierarhic comentat în detaliu.
- **„In Closing" — demonstratePlatformThreads**: structured concurrency cu platform threads prin `Thread.ofPlatform().factory()` — posibil, dar cu avertismentul scalabilității.

## Citate

- „Simplicity is prerequisite for reliability." — motto-ul capitolului (Edsger W. Dijkstra), deschiderea capitolului.
- „It's like the goto statement back in the old days." — secțiunea „The Challenge of Unstructured Concurrency", despre concurența nestructurată.
- „the nested structure mirrors business logic, making concurrent code as readable and maintainable as sequential code" — secțiunea „In Closing".

## Legături

- **Cap. 2 (Understanding Virtual Threads) și Cap. 3 (The Mechanics of Modern Concurrency)** sunt fundația directă: structured concurrency presupune că fork-ul unui thread e ieftin — de aceea fiecare `fork()` își permite un virtual thread dedicat. Fără înțelegerea virtual threads (mounting/unmounting, blocking ieftin) și a mecanicii `ExecutorService`/`Future`/interruption din Cap. 3, nici problema (thread leaks, lipsă de cancelare) și nici soluția nu au sens. Exemplul negativ al capitolului folosește chiar `newVirtualThreadPerTaskExecutor()` din capitolele anterioare.
- **Cap. 5 (Scoped Values)** este perechea naturală: StructuredTaskScope rezolvă *lifecycle-ul* task-urilor, iar scoped values rezolvă *partajarea imutabilă de context* de-a lungul aceleiași ierarhii părinte–copil; cele două API-uri sunt proiectate să lucreze împreună.
- **Cap. 6 (Reactive Java)** revine la întrebarea „mai avem nevoie de reactive?": multe dintre motivele istorice ale programării reactive (compoziție de operații async, cancellation, fan-out/fan-in) sunt acoperite acum de scope-uri + joiner-e, cu cod secvențial la citire.
- **Cap. 7 (Modern Frameworks)** arată cum framework-urile adoptă aceste primitive; pattern-urile de aici (fail-fast, race, quorum, rate limiting) sunt exact ceea ce framework-urile ambalează.
- **De stăpânit înainte de a merge mai departe**: relația scope–subtask și regula „get() doar după join()"; semantica fiecărui joiner standard (mai ales diferența `allSuccessfulOrThrow()` vs `awaitAll()`); cele patru strategii de exception handling și `FailedException.getCause()`; faptul că timeout-ul curge de la `open()`; garanțiile happens-before ale fork/join și limitele lor (starea partajată între subtask-uri rămâne responsabilitatea ta).
