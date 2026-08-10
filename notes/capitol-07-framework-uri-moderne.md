# Capitolul 07 — Modern Frameworks Utilizing Virtual Threads

## Rezumat amplu

Capitolul 7 mută discuția din planul limbajului și al JDK-ului (capitolele 2–5) în planul ecosistemului: cum au adoptat framework-urile enterprise virtual threads după finalizarea lor în JDK 21. Autorul anunță din start că nu intră în mecanica internă a fiecărui framework, ci arată modelul de adopție și configurarea practică pentru fiecare. Problema de fond este aceeași ca în restul cărții: modelul clasic thread-per-request pe platform threads se scalează prost la volume mari de request-uri I/O-bound, iar soluțiile istorice (pool-uri de thread-uri, `@Async` pe `ThreadPoolTaskExecutor` cu core/max pool size) doar mută limita, nu o elimină. Spring Boot este tratat primul: de la versiunea 3.2 (Spring Framework 6.1, noiembrie 2023), un singur property — `spring.threads.virtual.enabled=true` — comută întreaga aplicație pe virtual threads; Spring auto-configurează un `AsyncTaskExecutor` bazat pe `SimpleAsyncTaskExecutor` și un `SimpleAsyncTaskScheduler`, iar efectul se propagă peste `@EnableAsync`, return-urile `Callable` din MVC, procesarea asincronă a request-urilor și chiar execuția blocantă ocazională din WebFlux. Autorul demonstrează cu log-uri că handler-ele Tomcat, task-urile `@Async` și task-urile `@Scheduled` rulează efectiv pe `VirtualThread[...]`. Pentru versiuni pre-3.2 sau pentru control fin, se arată configurarea manuală: un bean `applicationTaskExecutor` construit cu `TaskExecutorAdapter` peste `Executors.newVirtualThreadPerTaskExecutor()`, plus un `TomcatProtocolHandlerCustomizer` care setează executorul serverului embedded pe virtual threads. Quarkus urmează un model opus, țintit, nu global: fiind construit pe Vert.x și pe un event loop reactiv, adoptă virtual threads selectiv, per metodă, prin adnotarea `@RunOnVirtualThread`, care scoate operațiile blocante de pe event loop fără a-i afecta responsivitatea. Autorul construiește un exemplu complet: un endpoint REST Quarkus care apelează un serviciu extern printr-un REST client MicroProfile, plus serverul extern scris doar cu `com.sun.net.httpserver.HttpServer` din JDK, el însuși rulat pe `newVirtualThreadPerTaskExecutor` și lansat direct cu `java SimpleHttpServer.java` (source-file launching din Java 11). Tot la Quarkus se arată puntea reactiv–imperativ: un `Uni<String>` (Mutiny) consumat blocant cu `await().atMost(...)` din interiorul unei metode `@RunOnVirtualThread`, ceea ce e sigur pentru că blocarea are loc pe un virtual thread, nu pe event loop. A treia stație este Jakarta EE: specificația Jakarta Concurrency 3.1 adaugă atributul `virtual = true` pe `@ManagedExecutorDefinition`, `@ManagedScheduledExecutorDefinition` și `@ManagedThreadFactory`, cu fallback automat pe platform threads dacă runtime-ul rulează pe Java 17. Exemplele arată un `ManagedExecutorService` cu qualifier custom injectat într-o resursă JAX-RS și un `ManagedScheduledExecutorService` virtual pentru task-uri întârziate, rulate pe Open Liberty — primul runtime care suportă complet spec-ul, la momentul scrierii, restul (Payara, WildFly, TomEE, GlassFish) fiind așteptate odată cu Jakarta EE 11. Autorul avertizează că spec-ul definește contractul, dar comportamentul concret variază între runtime-uri. Închiderea lărgește cadrul: Helidon Níma e construit nativ, de la zero, în jurul virtual threads (nu retrofit), iar Micronaut le folosește automat pe JDK 21+ fără nicio configurare. Concluzia capitolului: indiferent de framework, dezvoltatorii pot scrie acum cod puternic concurent păstrând stilul simplu, imperativ — exact promisiunea centrală a virtual threads din capitolele anterioare, de data aceasta livrată „la cheie" de ecosistem.

## Concepte explicate

### `spring.threads.virtual.enabled` (Spring Boot 3.2+)

- **Definiție** — Property de configurare (properties/YAML) care comută global concurența Spring Boot pe virtual threads: request handling în Tomcat, `@Async`, `Callable` returns, `@Scheduled`, execuția blocantă din WebFlux. Necesită Spring Boot 3.2+ și rulare pe Java 21 (baseline-ul de compilare rămâne Java 17).
- **De ce contează** — Elimină bottleneck-ul pool-ului finit de platform threads la sarcini I/O-bound, fără nicio modificare de cod. Previne situația în care request-urile stau la coadă pentru un thread liber deși CPU-ul e idle.
- **Exemplu de cod** —
  ```yaml
  # Greșit (istoric): limitezi manual concurența
  # @Bean ThreadPoolTaskExecutor cu corePoolSize=10, maxPoolSize=100
  # → la 101 request-uri lente, al 101-lea așteaptă.

  # Corect (Spring Boot 3.2+, Java 21):
  spring:
    threads:
      virtual:
        enabled: true
  ```
- **Capcane frecvente** — (1) Property-ul e ignorat pe Spring Boot < 3.2 sau pe JDK < 21 — nu primești eroare, doar rămâi pe platform threads; verifică în log `Thread.currentThread()`. (2) Dacă definești propriul bean de executor, auto-configurarea nu se mai aplică — bean-ul tău câștigă și poate fi un pool clasic. (3) Virtual threads nu accelerează sarcinile CPU-bound; câștigul e doar la I/O.

### `SimpleAsyncTaskExecutor` / `SimpleAsyncTaskScheduler` (auto-configurare)

- **Definiție** — Executor/scheduler pe care Spring Boot le auto-configurează (sub numele `applicationTaskExecutor`, respectiv `taskScheduler`) când virtual threads sunt activate; creează câte un thread nou (virtual) per task, în loc de pooling. Fără flag, default-urile sunt `ThreadPoolTaskExecutor`/`ThreadPoolTaskScheduler`.
- **De ce contează** — Pooling-ul are sens când thread-urile sunt scumpe; virtual threads sunt ieftine, deci „un thread nou per task" devine strategia corectă. Înțelegerea acestei inversări previne reflexul de a re-introduce pool-uri limitate peste virtual threads.
- **Exemplu de cod** —
  ```java
  // Anti-pattern: pooling de virtual threads printr-un pool clasic
  ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor(); // platform threads, limită artificială

  // Corect: lași Spring să configureze SimpleAsyncTaskExecutor pe virtual threads,
  // sau explicit:
  @Bean
  AsyncTaskExecutor applicationTaskExecutor() {
      return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
  }
  ```
- **Capcane frecvente** — A presupune că `@Scheduled` rămâne pe platform threads; cu flag-ul activ și scheduler-ul migrează. A confunda numele bean-urilor și a suprascrie din greșeală `applicationTaskExecutor` cu un pool clasic.

### `TaskExecutorAdapter` vs. `Executors.newVirtualThreadPerTaskExecutor()` direct

- **Definiție** — `TaskExecutorAdapter` e adaptorul Spring care împachetează un `ExecutorService` JDK (aici, executorul virtual-thread-per-task) în contractul `AsyncTaskExecutor`, integrându-l în lifecycle-ul Spring (inițializare, shutdown).
- **De ce contează** — Folosind executorul JDK „gol" ca bean, pierzi managementul de lifecycle al Spring; adaptorul previne, de exemplu, shutdown dezordonat la oprirea contextului.
- **Exemplu de cod** —
  ```java
  @Configuration
  public class VtConfig {
      @Bean
      public AsyncTaskExecutor applicationTaskExecutor() {
          // corect: adaptor Spring peste executorul JDK
          return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
      }
  }
  ```
- **Capcane frecvente** — Expunerea directă a `ExecutorService`-ului JDK ca bean fără să te gândești la cine îl închide; alegerea adaptorului potrivit trebuie făcută conștient, în funcție de cât management Spring vrei.

### `TomcatProtocolHandlerCustomizer` (server embedded pe virtual threads)

- **Definiție** — Bean prin care setezi manual executorul protocol handler-ului Tomcat embedded la `newVirtualThreadPerTaskExecutor()`, astfel încât fiecare request HTTP să fie servit de un virtual thread — echivalentul manual (pre-3.2) al flag-ului global.
- **De ce contează** — Degeaba `@Async` rulează pe virtual threads dacă handler-ele de request rămân în pool-ul limitat Tomcat; acesta e stratul unde se simte scalabilitatea la mii de conexiuni simultane.
- **Exemplu de cod** —
  ```java
  @Bean
  TomcatProtocolHandlerCustomizer<?> vtProtocol() {
      return handler -> handler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }
  ```
- **Capcane frecvente** — A configura doar executorul de `@Async` și a crede că request-urile HTTP beneficiază și ele; sunt două executoare diferite. Pe Spring Boot 3.2+ customizer-ul e redundant — flag-ul face deja asta.

### `@RunOnVirtualThread` (Quarkus)

- **Definiție** — Adnotare SmallRye (`io.smallrye.common.annotation`) prin care Quarkus execută o metodă (tipic un endpoint JAX-RS) pe un virtual thread în loc de event loop-ul Vert.x sau un worker thread; framework-ul gestionează complet ciclul de viață al thread-ului. Model de adopție selectiv, per metodă — opus flag-ului global din Spring.
- **De ce contează** — Pe un event loop, o operație blocantă (apel REST sincron, JDBC) blochează toate conexiunile servite de acel loop. Adnotarea previne exact acest bug clasic reactiv, permițând cod imperativ blocant fără a sufoca event loop-ul.
- **Exemplu de cod** —
  ```java
  // Greșit: apel blocant direct pe event loop-ul Vert.x
  @GET
  public String slow() { return blockingRestCall(); } // îngheață event loop-ul

  // Corect: mutat pe virtual thread
  @GET
  @RunOnVirtualThread
  public String slow() { return blockingRestCall(); } // event loop rămâne liber
  ```
- **Capcane frecvente** — A pune adnotarea peste tot din reflex (pe metode pur CPU-bound sau non-blocante nu ajută); a uita că restul aplicației rămâne reactivă și că modelul e hibrid, nu o migrare globală.

### Puntea reactiv–imperativ: `Uni.await().atMost(...)` pe virtual thread

- **Definiție** — Pattern Quarkus în care un tip reactiv Mutiny (`Uni<T>`, o computație amânată) este consumat blocant, cu timeout, din interiorul unei metode `@RunOnVirtualThread`, transformând un API reactiv într-un apel sincron sigur.
- **De ce contează** — Permite reutilizarea clienților reactivi existenți (REST clients care întorc `Uni`) în cod imperativ simplu; blocarea e inofensivă pentru că suspendă doar un virtual thread ieftin, nu event loop-ul. E ilustrarea practică a tezei din capitolul 6 (reactivul devine opțional, nu obligatoriu).
- **Exemplu de cod** —
  ```java
  @GET
  @RunOnVirtualThread
  public String hello() {
      Uni<String> deferred = externalClient.hello();
      return deferred.await().atMost(Duration.ofSeconds(5)); // blocant, dar pe VT
  }
  ```
- **Capcane frecvente** — Același `await()` apelat pe event loop (fără `@RunOnVirtualThread`) e o eroare gravă; lipsa timeout-ului (`atMost`) transformă un serviciu extern căzut într-un thread suspendat pe termen nelimitat.

### Jakarta Concurrency 3.1 — `virtual = true`

- **Definiție** — Atribut standardizat pe adnotările `@ManagedExecutorDefinition`, `@ManagedScheduledExecutorDefinition` și `@ManagedThreadFactory` prin care o aplicație Jakarta EE cere ca executorul/factory-ul managed definit să folosească virtual threads; pe Java 17 runtime-ul face fallback silențios la platform threads.
- **De ce contează** — Aduce virtual threads în lumea executorilor *managed* (cu context propagation și lifecycle gestionate de container), într-un mod portabil între runtime-uri, în loc de hack-uri specifice fiecărui server. Fallback-ul previne crash-uri la deploy pe JDK-uri vechi, dar poate ascunde faptul că nu rulezi de fapt pe virtual threads.
- **Exemplu de cod** —
  ```java
  @ManagedExecutorDefinition(
      name = "java:module/concurrent/vt-exec",
      qualifiers = WithVirtualThreads.class,
      virtual = true)
  public class MyResource {
      @Inject @WithVirtualThreads
      ManagedExecutorService exec;

      String work() throws Exception {
          return exec.submit(() -> doBlockingIo()).get();
      }
  }
  ```
- **Capcane frecvente** — (1) `virtual = true` e o *cerere*, nu o garanție — pe Java 17 primești platform threads fără avertisment. (2) Comportamentul diferă între runtime-uri (Open Liberty era singurul complet la momentul scrierii); citește docs-ul serverului tău. (3) A folosi executori JDK „unmanaged" în container și a pierde propagarea contextului Jakarta.

### Adopție nativă vs. retrofit (Helidon Níma, Micronaut)

- **Definiție** — Două strategii de ecosistem: Helidon Níma e proiectat de la zero în jurul virtual threads (arhitectură nativă), în timp ce Spring/Quarkus/Jakarta le-au adăugat peste modele existente (retrofit); Micronaut le activează automat, transparent, pe JDK 21+.
- **De ce contează** — Alegerea framework-ului determină cât de mult „glue code" și configurare îți trebuie și câte straturi istorice (pool-uri, event loops) rămân dedesubt; util la decizii de arhitectură pentru microservicii noi.
- **Capcane frecvente** — A presupune că orice framework pe JDK 21 folosește automat virtual threads (Spring nu, fără flag; Micronaut da); a compara benchmark-uri între framework-uri fără a ști pe ce model de thread rulează fiecare.

## Listing-uri cheie din carte

Textul capitolului nu numerotează exemplele (nu există captions „Example 7-N"), așa că le identific după secțiune, în ordinea apariției:

1. **Spring Boot — `ThreadPoolConfig` cu `ThreadPoolTaskExecutor` (core 10 / max 100)** — exemplul „istoric", varianta-problemă: arată cum `@Async` clasic consumă dintr-un pool finit de platform threads; e reperul față de care se măsoară tot restul capitolului.
2. **Spring Boot — property `spring.threads.virtual.enabled=true` (properties + YAML)** — demonstrează că activarea globală e o singură linie de configurare; instructiv prin contrastul brutal cu complexitatea istorică.
3. **Spring Boot — `GreetingsController` + log-ul `VirtualThread[#63,tomcat-handler-0]`** — dovada empirică: handler-ul Tomcat rulează pe virtual thread montat pe un worker ForkJoinPool; învață cititorul să verifice cu `Thread.currentThread()` în loc să creadă pe cuvânt.
4. **Spring Boot — `AsyncController` care returnează `Callable<String>`** — arată că procesarea asincronă a request-urilor MVC (return `Callable`) migrează și ea pe virtual threads (`task-1`).
5. **Spring Boot — `ScheduledTasks` cu `@Scheduled(fixedRate = 1000)`** — demonstrează că și scheduler-ul (`scheduling-36`) trece pe virtual threads; important pentru că scheduling-ul e adesea uitat în discuțiile despre request handling.
6. **Spring Boot — `VirtualThreadConfig` cu `TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())`** — configurarea manuală pentru pre-3.2/control fin; instructiv pentru distincția adaptor-Spring vs. executor JDK brut.
7. **Spring Boot — `RemoteApiService` cu `@Async` + `CompletableFuture`** — arată efectul configurării manuale asupra metodelor `@Async`; include și tratarea corectă a `InterruptedException` (re-interrupt).
8. **Spring Boot — `TomcatConfig` cu `TomcatProtocolHandlerCustomizer`** — mută serverul embedded pe virtual threads; evidențiază că executorul serverului e distinct de cel de `@Async`.
9. **Quarkus — `VirtualThreadApp` cu `@RunOnVirtualThread` + REST client injectat** — exemplul central Quarkus: endpoint blocant (apel remote sincron) servit sigur de pe un virtual thread (`quarkus-virtual-thread-1` în log).
10. **Quarkus — interfața `RemoteService` cu `@RegisterRestClient` + property-ul de URL** — arată wiring-ul MicroProfile REST Client necesar exemplului 9.
11. **`SimpleHttpServer` cu `com.sun.net.httpserver` + `setExecutor(newVirtualThreadPerTaskExecutor())`** — serviciul extern construit doar cu JDK-ul; instructiv dublu: HTTP server fără framework și virtual threads la nivel de JDK pur, plus tip-ul cu `java SimpleHttpServer.java` (source-file launching).
12. **Quarkus — `ReactiveResource`/`HelloService`/`ExternalService` cu `Uni` + `await().atMost(5s)`** — puntea reactiv–imperativ: consum blocant al unui tip Mutiny din interiorul unui virtual thread, cu timeout.
13. **Jakarta EE — `VirtualThreadExampleService` cu `@ManagedExecutorDefinition(virtual = true)` + qualifier** — configurarea standard a unui `ManagedExecutorService` virtual, injectat prin qualifier și verificat prin log pe Open Liberty.
14. **Jakarta EE — `VirtualThreadSchedulerExample` cu `@ManagedScheduledExecutorDefinition(virtual = true)`** — varianta pentru task-uri programate/întârziate (`schedule(..., 5, SECONDS)`) pe virtual threads.

## Citate

- „The best way to predict the future is to invent it." — motto-ul capitolului (Alan Kay), secțiunea de deschidere.
- „Helidon Níma is built around virtual threads" — secțiunea *In Closing*, despre adopția nativă vs. retrofit.
- „Java 21 is now treated as a first-class runtime environment" — secțiunea *Spring Boot*, despre suportul din 3.2.

## Legături

- **Cap. 2 (Understanding Virtual Threads)** — fundamentul obligatoriu: tot capitolul 7 presupune că știi ce e un virtual thread, de ce e ieftin și de ce blocarea lui e inofensivă. Log-urile `VirtualThread[...]/runnable@ForkJoinPool-1-worker-N` din capitol se citesc doar dacă înțelegi montarea pe carrier threads din capitolul 2.
- **Cap. 3 (Mechanics of Modern Concurrency)** — `Executors.newVirtualThreadPerTaskExecutor()`, folosit în aproape fiecare exemplu de aici, e introdus acolo; la fel raționamentul „thread-per-task în loc de pooling".
- **Cap. 6 (Relevance of Reactive Java)** — capitolul 7 e continuarea practică directă: exemplul Quarkus cu `Uni.await().atMost(...)` arată în cod concluzia teoretică din 6 — reactivul nu dispare, dar devine opțional, iar codul imperativ blocant pe virtual threads îl poate consuma sigur.
- **Cap. 4–5 (Structured Concurrency, Scoped Values)** — nu apar explicit aici, dar executorii managed din Jakarta (context propagation) și task-urile fan-out din servicii sunt terenul natural unde acele concepte se combină cu framework-urile.
- **Cap. 8 (Conclusion and Takeaways)** — capitolul 7 pregătește concluzia cărții: virtual threads nu mai sunt o curiozitate de JDK, ci infrastructură standard a ecosistemului (Spring, Quarkus, Jakarta EE 11, Helidon Níma, Micronaut).
- **De stăpânit înainte de a merge mai departe**: cum verifici pe ce thread rulezi (`Thread.currentThread()` în log), diferența dintre adopția globală (Spring), selectivă (Quarkus) și declarativă/managed (Jakarta), și de ce „nou thread per task" înlocuiește pooling-ul.
