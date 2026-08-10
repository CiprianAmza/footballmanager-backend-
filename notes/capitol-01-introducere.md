# Capitolul 01 — Introduction

## Rezumat amplu

Capitolul deschide cartea cu ideea că nu poți aprecia noutățile din concurența Java modernă (virtual threads, structured concurrency) fără să înțelegi evoluția care a dus la ele. Autorul pornește de la citatul lui Rob Pike care separă concurența (a gestiona multe lucruri deodată) de paralelism (a executa multe lucruri deodată) și trasează istoria: Java 1.0 (1996) a venit cu suport nativ pentru thread-uri, Java 5 a adus pachetul `java.util.concurrent` (Executor, lock-uri, colecții concurente), Java 7 a introdus Fork/Join, iar Project Loom aduce thread-uri lightweight în user-mode. Prima teză centrală este că Java este „făcut din thread-uri": chiar și un `HelloWorld` rulează pe thread-ul `main`, iar garbage collector-ul, compilatorul JIT, debugger-ul și profiler-ele se bazează toate pe thread-uri; stack trace-urile excepțiilor sunt per-thread, ceea ce face din thread unitatea fundamentală de diagnoză. Urmează mecanica de bază: creezi thread-uri extinzând `Thread` sau implementând `Runnable` (azi cu lambda), pornești execuția cu `start()` (niciodată apelând `run()` direct), iar în practică delegi managementul unui `ExecutorService` cu thread pool. A doua teză centrală este costul ascuns al thread-urilor clasice: fiecare thread de platformă e un wrapper subțire peste un thread nativ al OS-ului, consumă circa 2 MiB de memorie în afara heap-ului, numărul lor e plafonat de OS (autorul atinge `OutOfMemoryError` la ~16.000 de thread-uri într-un experiment), iar context switching-ul costă cicluri de CPU. În modelul thread-per-request al serverelor web, aceste limite plafonează scalabilitatea, deși intuiția (și Little's Law) ar sugera că mai multe thread-uri înseamnă mai mult throughput. Problema reală nu e că thread-urile stau degeaba, ci că stau blocate pe I/O: într-un exemplu de calcul de credit scoring, cinci pași secvențiali a câte 200 ms blochează thread-ul aproape o secundă, timp în care nu poate servi alte cereri. Capitolul parcurge apoi, pe același exemplu, scara soluțiilor istorice: paralelizare manuală cu thread-uri ad-hoc și `AtomicReference` (mai rapid, dar fără control asupra ciclului de viață al thread-urilor), `ExecutorService` cu pool fix (management mai bun, dar `Future.get()` tot blochează, apare riscul de false sharing între core-uri și API-ul nu e compozabil), Fork/Join Pool (cache affinity și work-stealing pentru distribuție inteligentă a task-urilor), `CompletableFuture` (API fluent și compozabil peste Fork/Join, dar greu de învățat și de debugat) și, în final, programarea reactivă (WebFlux/Mono — non-blocking, dar cu learning curve abrupt, cognitive load mare, debugging dificil, risc de overengineering și vendor lock-in). Fiecare treaptă rezolvă ceva și lasă altceva nerezolvat, iar autorul subliniază că adoptarea asincroniei e o decizie arhitecturală care depinde de maturitatea echipei, nu doar una tehnică. Concluzia capitolului este promisiunea Project Loom: virtual threads pot fi create cu milioanele aproape fără overhead, se integrează perfect cu codul existent (e suficient `Executors.newVirtualThreadPerTaskExecutor()`), rulează deasupra thread-urilor de platformă (backed de Fork/Join) și cedează automat controlul carrier thread-ului când blochează pe I/O. Astfel poți scrie din nou cod imperativ, blocant, simplu — dar cu scalabilitatea modelelor asincrone. Restul cărții dezvoltă exact această tehnologie, începând cu mecanica virtual threads în Capitolul 2.

## Concepte explicate

### Thread (de platformă)

- **Definiție** — cea mai mică unitate de execuție în Java: o cale independentă de execuție în cadrul unui program, cu program counter, stack și variabile locale proprii, dar care împarte același address space (heap, variabile) cu celelalte thread-uri. Un thread Java clasic este un wrapper subțire peste un thread nativ al OS-ului, planificat de kernel.
- **De ce contează** — tot ecosistemul Java stă pe thread-uri (GC, JIT, debugging, profiling, stack trace-uri per-thread). Dar pentru că e mapat 1:1 pe un thread nativ, e o resursă scumpă: ~2 MiB memorie în afara heap-ului, limită de creare impusă de OS, cost de context switch. Ignorarea acestor costuri duce la `OutOfMemoryError: unable to create native thread` și la plafonarea scalabilității.
- **Exemplu de cod**

```java
public class Demo {
    public static void main(String[] args) {
        // chiar și "cod single-threaded" rulează pe un thread: main
        System.out.println(Thread.currentThread().getName()); // "main"

        Thread worker = new Thread(() -> System.out.println("lucrez"), "worker-1");
        worker.start(); // corect: pornește un thread NOU
        // worker.run(); // GREȘIT: ar executa run() în thread-ul curent, nu în unul nou
    }
}
```

- **Capcane frecvente** — apelul direct al lui `run()` în loc de `start()` (rulează sincron în thread-ul apelant); presupunerea că poți crea oricâte thread-uri; uitarea faptului că memoria de stack a thread-urilor e în afara heap-ului, deci `-Xmx` nu o limitează.

### Concurrency vs. Parallelism

- **Definiție** — paralelismul înseamnă execuție simultană efectivă (necesită mai multe core-uri: mai mulți muncitori construiesc o casă în același timp); concurența înseamnă structurarea programului astfel încât porțiuni din el să se poată suprapune în timp, chiar și pe un singur core (un bucătar care jonglează mai multe feluri de mâncare).
- **De ce contează** — confundarea lor duce la raționamente greșite de performanță: adăugarea de thread-uri (concurență) nu garantează paralelism și nici throughput mai mare; pe un CPU saturat, mai multe thread-uri înseamnă doar mai mult context switching.
- **Exemplu de cod**

```java
// Concurență fără paralelism: două task-uri se pot întrepătrunde chiar și pe 1 core
ExecutorService ex = Executors.newSingleThreadExecutor();
ex.submit(taskA); ex.submit(taskB); // se suprapun logic, nu fizic

// Paralelism: task-urile chiar rulează simultan pe core-uri diferite
ForkJoinPool.commonPool().invokeAll(List.of(taskA, taskB));
```

- **Capcane frecvente** — a folosi termenii interschimbabil; a măsura „speedup" pe o mașină cu un singur core și a te mira că nu apare; a crede că un program concurent e automat mai rapid.

### Modelul thread-per-request și throughput-ul

- **Definiție** — modelul clasic al containerelor servlet (Tomcat, Jetty): fiecare cerere HTTP primește un thread din pool care o „adoptă" pe tot ciclul request/response. Throughput = cereri procesate / timp (RPS/TPS).
- **De ce contează** — modelul e simplu și scalează până la un punct, dar numărul de cereri simultane e plafonat de numărul de thread-uri, care e plafonat de memorie și de OS. Deși Little's Law sugerează că mai multă concurență aduce mai mult throughput, costurile per-thread fac raționamentul „mai multe thread-uri = mai mult throughput" înșelător.
- **Exemplu de cod**

```java
// Conceptual: un pool de 200 de thread-uri => max 200 de cereri în zbor.
// Dacă fiecare cerere stă 900ms blocată pe I/O și 100ms pe CPU,
// thread-urile sunt "ocupate" dar 90% din timp nu fac nimic util.
ExecutorService requestPool = Executors.newFixedThreadPool(200);
serverSocketLoop(request -> requestPool.submit(() -> handle(request)));
```

- **Capcane frecvente** — a mări pool-ul la infinit ca răspuns la load (duce la OOM și context-switch thrashing); a uita că un thread blocat pe I/O e o cerere în minus pe care o poți servi.

### Costurile ascunse ale thread-urilor

- **Definiție** — trei costuri: (1) memorie: ~2 MiB per thread, în afara heap-ului; (2) limită de creare: OS-ul plafonează numărul de thread-uri native (experimentul autorului: ~16.363 înainte de `OutOfMemoryError`); (3) context switching: salvarea/restaurarea contextului la comutarea între thread-uri consumă CPU.
- **De ce contează** — aceste costuri sunt motivul întregii evoluții descrise în carte; dacă thread-urile ar fi gratuite, n-am avea nevoie nici de reactive, nici de virtual threads.
- **Exemplu de cod**

```java
// Test empiric al limitei de thread-uri pe mașina ta (idee, scrisă de mine):
var count = new AtomicInteger();
try {
    while (true) {
        new Thread(() -> { count.incrementAndGet(); LockSupport.park(); }).start();
    }
} catch (OutOfMemoryError e) {
    System.out.println("Limita: " + count.get()); // pe mașina autorului ~16k
}
```

- **Capcane frecvente** — a rula un asemenea test pe o mașină de producție (poate destabiliza sistemul); a presupune că limita e aceeași peste tot (depinde de OS, ulimits, stack size); a uita că paging-ul pe disc, când memoria fizică se termină, e de ~1.000 de ori mai lent decât RAM.

### Blocking I/O

- **Definiție** — situația în care un thread execută un apel (bază de date, HTTP, fișier) și rămâne suspendat până vine răspunsul; thread-ul e „ocupat" din perspectiva pool-ului, dar nu face muncă utilă.
- **De ce contează** — e ineficiența centrală atacată de întreaga carte: thread-uri scumpe stau legate așteptând sisteme externe, în loc să servească alte cereri. În aplicații high-throughput asta limitează sever scalabilitatea.
- **Exemplu de cod**

```java
// Varianta problematică: 4 operații a ~200ms, secvențial => ~1s per apel
Report buildReport(long id) {
    var user = db.findUser(id);        // blochează ~200ms
    var orders = api.fetchOrders(user); // blochează ~200ms
    var stats = db.stats(user);         // blochează ~200ms
    return merge(user, orders, stats);  // CPU ~200ms
}
// Varianta mai bună: fetchOrders și stats nu depind unul de altul => rulează-le în paralel
```

- **Capcane frecvente** — a confunda „thread busy" cu „thread productiv"; a paraleliza și operații care au dependențe între ele; a crede că ai eliminat blocarea când doar ai mutat-o (vezi `Future.get()`).

### AtomicReference și partajarea sigură a rezultatelor

- **Definiție** — container thread-safe pentru o referință la un obiect, folosit ca să publici în siguranță un rezultat calculat într-un thread către alt thread. Pentru primitive există `AtomicInteger`, `AtomicLong` etc.
- **De ce contează** — fără o publicare sigură (atomics, `volatile`, `join()`/happens-before), thread-ul cititor poate vedea o valoare veche sau parțial construită — data race-uri greu de reprodus.
- **Exemplu de cod**

```java
var resultRef = new AtomicReference<List<String>>();
var t = new Thread(() -> resultRef.set(fetchNames())); // scriere sigură
t.start();
t.join();                       // happens-before: după join, citirea e sigură
List<String> names = resultRef.get();
```

- **Capcane frecvente** — a folosi o variabilă locală „efectiv finală" printr-un array de un element (`Object[] holder`) în loc de atomic; a citi referința înainte de `join()`; a crede că `AtomicReference` face thread-safe și obiectul conținut (protejează doar referința).

### Gestionarea corectă a InterruptedException

- **Definiție** — `Thread.interrupt()` setează un flag intern; metodele blocante (`sleep`, `wait`, I/O blocant) aruncă `InterruptedException` și șterg flag-ul. Pattern-ul corect: în `catch`, apelezi `Thread.currentThread().interrupt()` ca să restaurezi flag-ul, apoi propagi (eventual împachetat în `RuntimeException`).
- **De ce contează** — dacă înghiți excepția și nu restaurezi flag-ul, codul de deasupra (pool-uri, framework-uri de shutdown) nu mai află că s-a cerut întreruperea, iar task-urile devin ne-anulabile — aplicația „nu se mai oprește".
- **Exemplu de cod**

```java
// GREȘIT: înghite întreruperea
try { Thread.sleep(200); } catch (InterruptedException e) { /* ignorat */ }

// CORECT: restaurează flag-ul și propagă
try {
    Thread.sleep(200);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException(e);
}
```

- **Capcane frecvente** — `catch (Exception e) {}` generic care ascunde întreruperea; logare fără restaurarea flag-ului; a nu ști că însuși catch-ul a șters deja flag-ul.

### Executor framework / thread pool

- **Definiție** — abstracție (`ExecutorService`, `Executors`) care separă trimiterea task-urilor de managementul thread-urilor: un pool de thread-uri pre-create ia task-uri dintr-o coadă și le execută, reutilizând thread-urile.
- **De ce contează** — elimină proliferarea de thread-uri ad-hoc (sursă de OOM și crash-uri), amortizează costul creării thread-urilor și dă control asupra gradului de concurență. E și puntea către virtual threads: schimbi doar factory-ul.
- **Exemplu de cod**

```java
// Rău: câte un thread nou pentru fiecare task, fără limită
for (Task t : tasks) new Thread(t::run).start();

// Bine: pool fix, închis automat (ExecutorService e AutoCloseable din Java 19+)
try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
    Future<List<Asset>> assets = pool.submit(() -> getAssets(p));
    Future<List<Liability>> liabs = pool.submit(() -> getLiabilities(p));
    return calculate(assets.get(), liabs.get()); // atenție: get() blochează
}
```

- **Capcane frecvente** — `Future.get()` tot blochează (ai mutat blocarea, nu ai eliminat-o); a uita `shutdown()` pe versiuni de Java unde `ExecutorService` nu e `AutoCloseable`; pool-uri dimensionate greșit pentru workload-uri I/O-bound.

### Cache coherence și false sharing

- **Definiție** — CPU-urile moderne au cache-uri pe niveluri (L1/L2/L3) organizate în cache lines; protocoale de coerență (MESI, MOESI) țin cache-urile core-urilor consistente, invalidând liniile modificate de alt core. False sharing = două thread-uri modifică variabile diferite care nimeresc în aceeași cache line, provocând invalidiări/reîncărcări continue deși logic nu partajează nimic.
- **De ce contează** — într-un thread pool, task-urile ajung pe core-uri diferite; false sharing degradează tăcut performanța fără nicio eroare vizibilă — codul e corect, doar lent.
- **Exemplu de cod**

```java
// Potențial false sharing: cele două countere pot împărți aceeași cache line
class Counters {
    volatile long hits;    // scris de thread A
    volatile long misses;  // scris de thread B — invalidări reciproce
}
// Atenuare: separă-le (padding sau @jdk.internal.vm.annotation.Contended),
// sau folosește LongAdder, care distribuie scrierile pe celule separate.
```

- **Capcane frecvente** — a diagnostica „contention" acolo unde nu există niciun lock; a micro-optimiza fără măsurători (folosește un profiler / JMH); a uita că problema depinde de layout-ul de memorie, deci poate apărea/dispărea între rulări sau versiuni de JVM.

### Fork/Join Pool: cache affinity și work-stealing

- **Definiție** — implementare specializată de `ExecutorService` (Java 7) în care fiecare worker thread are propria coadă de task-uri (deque). Cache affinity: task-urile înrudite tind să ruleze pe același core, profitând de datele deja în cache. Work-stealing: un thread rămas fără muncă „fură" task-uri de la coada altui thread, ca niciun core să nu stea degeaba.
- **De ce contează** — reduce cache miss-urile și echilibrează dinamic sarcina; e fundația pe care stau atât `CompletableFuture`, cât și scheduler-ul de virtual threads.
- **Exemplu de cod**

```java
ForkJoinPool pool = new ForkJoinPool(); // sau ForkJoinPool.commonPool()
pool.submit(() -> {
    // task paralelizabil; clasic se combină cu RecursiveTask/RecursiveAction
}).join();
```

- **Capcane frecvente** — a bloca (I/O) în task-uri de Fork/Join dimensionat pentru CPU (înfometezi pool-ul comun, folosit și de parallel streams); a-l trata ca pe un pool generic — e gândit pentru task-uri mici, divizibile, CPU-bound.

### CompletableFuture

- **Definiție** — API introdus în Java 8 pentru compunerea fluentă a operațiilor asincrone: lanțuri declarative de transformări (`thenApply`, `thenCompose`, `thenCombine`, `exceptionally`, `handle`), rulate implicit pe Fork/Join sau pe un executor custom.
- **De ce contează** — rezolvă lipsa de compozabilitate a `Future`: pipeline-ul rămâne non-blocking până la marginile lui. Dar cere schimbare de mindset, iar folosit greșit reintroduce blocarea sau produce lanțuri nedebugabile.
- **Exemplu de cod**

```java
CompletableFuture<Report> report =
    CompletableFuture.supplyAsync(() -> db.findUser(id))
        .thenCompose(u -> CompletableFuture.supplyAsync(() -> api.fetchOrders(u))
            .thenCombine(CompletableFuture.supplyAsync(() -> db.stats(u)),
                         (orders, stats) -> merge(u, orders, stats)));
// report.get() blochează — de folosit doar la graniță, nu în mijlocul lanțului
// report.join() în teste; în producție preferă thenAccept/callback-uri
```

- **Capcane frecvente** — `get()` presărat prin mijlocul pipeline-ului (anulează asincronia); propagarea erorilor în lanțuri cu mai multe dependențe devine greu de urmărit; debugging cu breakpoint-uri clasice e aproape imposibil (execuția nu mai e linie-cu-linie); a uita să dai un executor custom când task-urile sunt blocante.

### Programarea reactivă

- **Definiție** — paradigmă centrată pe fluxuri de date asincrone, evenimente și operații non-blocking, cu operatori funcționali și backpressure; în Java: RxJava, Akka, Vert.x, Spring WebFlux (Mono/Flux).
- **De ce contează** — atinge scalabilitate mare cu puține thread-uri, dar cu costuri: learning curve abrupt (Publisher/Subscriber, scheduler-e, backpressure), cognitive load mare, stack trace-uri inutile la erori (indică operatorul `zip`, nu sursa reală), risc de overengineering și vendor lock-in pe API-ul unui framework. Cartea o prezintă tocmai ca motivație pentru virtual threads: aceleași beneficii, fără schimbarea de paradigmă.
- **Exemplu de cod**

```java
Mono<Report> report =
    Mono.fromSupplier(() -> db.findUser(id))
        .flatMap(u -> Mono.zip(
                Mono.fromSupplier(() -> api.fetchOrders(u)),
                Mono.fromSupplier(() -> db.stats(u)))
            .map(t -> merge(u, t.getT1(), t.getT2())));
// nimic nu rulează până la subscribe(); erorile din zip ascund sursa reală
```

- **Capcane frecvente** — a adopta reactive pentru un sistem fără asincronie/streaming real (mismatch); a amesteca apeluri blocante în pipeline reactiv (blochezi event loop-ul); a subestima costul organizațional — echipa trebuie să poată întreține și debuga acest stil.

### Virtual threads (Project Loom)

- **Definiție** — thread-uri lightweight, gestionate de JVM în user-mode, nu de OS; pot fi create cu milioanele, rulează deasupra thread-urilor de platformă (carrier threads, backed de Fork/Join) și, la o operație blocantă (sleep, I/O de rețea), cedează automat carrier-ul, care trece la alt virtual thread; la finalul blocării, virtual thread-ul își reia execuția de unde a rămas.
- **De ce contează** — desface legătura „un request = un thread OS scump": poți scrie cod imperativ, blocant, simplu de citit și de debugat, dar cu scalabilitatea soluțiilor asincrone; elimină nevoia de a plăti taxa cognitivă a reactive doar pentru throughput.
- **Exemplu de cod**

```java
// Migrare aproape gratuită: schimbi doar factory-ul executorului
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var assets = executor.submit(() -> getAssets(person));       // cod blocant, OK
    var liabs  = executor.submit(() -> getLiabilities(person));  // scalează oricum
    return calculate(assets.get(), liabs.get());
}
```

- **Capcane frecvente** — a le pune într-un pool fix (sunt ieftine, se creează per task — pooling-ul le anulează rostul); a aștepta câștig pe task-uri pur CPU-bound (câștigul e la I/O-bound); a presupune că orice blocare cedează carrier-ul (detaliile — pinning etc. — vin în capitolul 2).

## Listing-uri cheie din carte

Textul capitolului nu numerotează exemplele („Example 1-N" nu apare); le identific după secțiunea în care apar. Figurile numerotate sunt: Figure 1-1 (execuția thread-urilor pe CPU-uri diferite), Figure 1-2 (thread pool care servește cereri servlet), Figure 1-3 (work-stealing în Fork/Join Pool).

1. **`HelloWorld` cu `Thread.currentThread().getName()`** (secțiunea „Java Is Made of Threads") — demonstrează că până și cel mai banal program rulează pe thread-ul `main`; instructiv pentru că spulberă iluzia codului „fără thread-uri".
2. **`CallStackDemo`** (secțiunea „Exceptions and threads") — un lanț de 4 metode care aruncă `SQLException` împachetat în excepție custom, pe un thread numit; arată că stack trace-ul e per-thread și de ce numele thread-ului contează la diagnoză.
3. **`ThreadCreationDemo`** (secțiunea „The Genesis of Java 1.0 Threads") — cele 4 moduri istorice de a crea un thread (extends Thread, implements Runnable, anonymous inner class, lambda); instructiv ca istorie API și pentru trade-off-ul „extends consumă slotul de moștenire".
4. **`ExecutorExample`** (secțiunea „Starting Threads") — pool fix de 5 thread-uri care execută 10 task-uri; primul contact cu ideea de reutilizare a thread-urilor.
5. **`ThreadLimitTest`** (secțiunea „How Many Threads Can You Create?") — buclă infinită de creare de thread-uri parcate până la `OutOfMemoryError` (~16.363 pe mașina autorului); dovada empirică a limitei native.
6. **`calculateCredit` secvențial** (secțiunea „Resource Efficiency in High-Scale Applications") — exemplul-fir-roșu al capitolului: 5 pași a 200 ms, ~1s total, thread blocat pe I/O; definește problema pe care toate soluțiile ulterioare o atacă.
7. **`calculateCreditWithUnboundedThreads` + modele record + `ExecutionTimer`** (secțiunea „The Parallel Execution Strategy") — paralelizare manuală cu 3 thread-uri și `AtomicReference`; 1026 ms → 616 ms (1,66x), dar ilustrează și pericolul thread-urilor ad-hoc.
8. **Pattern-ul de catch pentru `InterruptedException`** (caseta „Handling InterruptedException Correctly") — restaurarea flag-ului de întrerupere; micro-listing, dar unul dintre cele mai des greșite idiomuri din Java.
9. **`calculateCreditWithExecutor`** (secțiunea „Introducing the Executor Framework") — aceeași paralelizare, dar prin `ExecutorService` + `Future`; timp similar (~630 ms), câștigul e de management, nu de viteză — și pretextul pentru discuția despre limitele `Future.get()`.
10. **Snippet-ul minimal de Fork/Join** (secțiunea „Example of using a Fork/Join Pool") — `forkJoinPool.submit(...).join()`; arată cât de puțin management cere framework-ul.
11. **`calculateCreditWithCompletableFuture`** (secțiunea „Composable and fluent API") — lanț `runAsync/thenCompose/thenCombineAsync`; demonstrează compozabilitatea, dar și opacitatea fluxului de execuție.
12. **`calculateCreditReactive` cu `Mono`** (secțiunea „A Different Paradigm for Asynchronous Programming") — varianta Spring WebFlux cu `Mono.zip`; conceptual paralelă cu CompletableFuture, dar cu vocabular reactiv — folosită ca argument pentru costul cognitiv al paradigmei.
13. **`calculateCreditWithVirtualThread`** (secțiunea „Seamless Integration with Existing Codebases") — identic structural cu varianta Executor, doar cu `newVirtualThreadPerTaskExecutor()`; puntea către restul cărții: migrare cu o singură linie.

## Citate

- „Concurrency is about dealing with lots of things at once. Parallelism is about doing lots of things at once." — motto-ul capitolului (Rob Pike), secțiunea introductivă.
- „This isn't merely a technical decision. It's an architectural commitment" — despre adoptarea programării asincrone, nota din secțiunea „Disadvantages and limitations" (CompletableFuture).
- „There's no such thing as a free lunch" — despre costurile reactive programming, secțiunea „Drawbacks of Using Reactive Frameworks".

## Legături

- **Rolul în carte**: Capitolul 1 este rampa de lansare — construiește motivația istorică și economică pentru tot ce urmează. Fiecare limitare enumerată aici primește un capitol dedicat mai departe.
- **→ Cap. 2 (Understanding Virtual Threads)**: detaliază mecanica virtual threads schițată la final (carrier threads, mount/unmount la blocare) și reia formal Little's Law (anunțat aici în nota de subsol 1) și Fork/Join Pool (promis explicit: „I'll discuss the Fork/Join Pool in more detail in the following chapter").
- **→ Cap. 3 (The Mechanics of Modern Concurrency)**: aprofundează uneltele clasice atinse aici în treacăt — executori, Future, sincronizare.
- **→ Cap. 4 (Structured Concurrency)**: exemplul cu 3 thread-uri pornite și „join-uite" manual (și varianta cu `Future.get()`) este exact haosul nestructurat pe care structured concurrency îl disciplinează.
- **→ Cap. 5 (Scoped Values)**: continuă tema partajării sigure de date între thread-uri, începută aici cu `AtomicReference`.
- **→ Cap. 6 (Reactive Java în lumina virtual threads)**: secțiunea „Drawbacks of Using Reactive Frameworks" e teaser-ul direct al acelui capitol — merită reactive să mai existe când blocking-ul devine ieftin?
- **→ Cap. 7 (Frameworks)**: modelul thread-per-request din servlet containers, descris aici, e ceea ce framework-urile moderne (Spring Boot, Helidon etc.) portează pe virtual threads.
- **De stăpânit înainte de a merge mai departe**: diferența concurrency/paralelism; cele trei costuri ale thread-urilor de platformă (memorie, limită OS, context switch); de ce blocking I/O e problema reală (thread „busy" ≠ thread productiv); idiomul `InterruptedException`; limitele fiecărei soluții istorice (`Future.get()` blochează, CompletableFuture/reactive costă cognitiv) — altfel promisiunea virtual threads din Cap. 2 nu are contrast.
