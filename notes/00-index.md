# Index — „Modern Concurrency in Java" (A N M Bazlur Rahman, O'Reilly 2025)

Notițe de studiu, capitol cu capitol. Fiecare fișier are: rezumat amplu, concepte explicate (cu exemple de cod proprii), listing-uri cheie, citate scurte și legături între capitole.

## Capitole

| # | Fișier | Sinteză |
|---|--------|---------|
| 01 | [capitol-01-introducere.md](capitol-01-introducere.md) | Evoluția concurenței Java — de la thread-uri OS scumpe, prin Executor, Fork/Join, CompletableFuture și reactive — ca motivație pentru virtual threads (Project Loom): cod imperativ blocant simplu, scalabil la milioane de task-uri. |
| 02 | [capitol-02-virtual-threads.md](capitol-02-virtual-threads.md) | Virtual threads (JDK 21) mută gestiunea thread-urilor din OS în JVM — stack în heap, mount/unmount pe carrier threads — făcând blocking-ul ieftin și thread-per-request scalabil, cu prețul unor limitări noi (pinning, ThreadLocal, rate limiting explicit). |
| 03 | [capitol-03-mecanica-concurentei-moderne.md](capitol-03-mecanica-concurentei-moderne.md) | Mecanica internă: ForkJoinPool în async mode ca scheduler, continuation-uri (stack copying lazy) pentru pauză/reluare și polleri epoll/kqueue care trezesc thread-urile parcate la I/O. |
| 04 | [capitol-04-structured-concurrency.md](capitol-04-structured-concurrency.md) | StructuredTaskScope (preview, JDK 25) leagă lifecycle-ul subtask-urilor de părinte — fork/join în try-with-resources, politici de join prin Joiner — eliminând thread leaks și propagând erori și cancellation automat. |
| 05 | [capitol-05-scoped-values.md](capitol-05-scoped-values.md) | ScopedValue (stabil din JDK 25) înlocuiește ThreadLocal pentru propagarea contextului: valoare imutabilă legată de scope-ul dinamic (`where().run()/call()`), fără cleanup manual, moștenită doar prin `StructuredTaskScope.fork()`. |
| 06 | [capitol-06-reactive-java-vs-virtual-threads.md](capitol-06-reactive-java-vs-virtual-threads.md) | Reactive vs. virtual threads, pe aceleași exemple construite în ambele paradigme: virtual threads câștigă la simplitate și compatibilitate cu codul sincron; reactive rămâne relevant pentru backpressure nativ și transformări complexe de fluxuri. |
| 07 | [capitol-07-framework-uri-moderne.md](capitol-07-framework-uri-moderne.md) | Cum au integrat framework-urile virtual threads: Spring Boot 3.2 (flag global), Quarkus (`@RunOnVirtualThread` selectiv), Jakarta Concurrency 3.1 (`virtual = true`), Helidon Níma nativ, Micronaut automat — concurență masivă fără cod nou. |
| 08 | [capitol-08-concluzii.md](capitol-08-concluzii.md) | Recapitulare + ghid de adopție: migrare incrementală, atenție la pinning și ThreadLocal, alegerea modelului potrivit per workload, monitorizare cu JFR și învățare continuă. |

## Cele 5 idei centrale ale cărții

1. **Virtual threads schimbă economia concurenței, nu viteza ei.** Prin Little's Law, scalabilitatea vine din creșterea concurenței N acolo unde latența I/O e ireductibilă: milioane de thread-uri ieftine (stack în heap, mount/unmount pe carrier threads) fac blocking-ul aproape gratuit și readuc modelul simplu thread-per-request. Nu ajută workload-urile CPU-bound.

2. **Codul sincron, blocant, imperativ redevine stilul corect.** Toată infrastructura istorică (thread pools, callbacks, CompletableFuture, reactive) exista ca să ocolească costul blocării unui platform thread; când blocarea e ieftină, codul drept, lizibil și ușor de debugat câștigă — iar sub capotă JVM-ul (continuation-uri + polleri epoll/kqueue) scalează ca un sistem non-blocking.

3. **Concurența trebuie structurată.** `ExecutorService` + `Future` e „goto-ul" concurenței: subtask-uri orfane, erori nepropagate, thread leaks. StructuredTaskScope leagă lifecycle-ul subtask-urilor de blocul de cod al părintelui (fork/join în try-with-resources, fail-fast, cancellation automat), iar ScopedValue înlocuiește ThreadLocal cu context imutabil, cu lifetime mărginit, moștenit sigur doar în interiorul scope-urilor structurate.

4. **Vechile reflexe devin capcane.** Pooling-ul de thread-uri nu mai are sens pentru virtual threads (un thread nou per task); thread pool-ul nu mai protejează implicit resursele din aval — rate limiting explicit cu `Semaphore`; pinning-ul (`synchronized` blocant pe JDK ≤ 23, apeluri native) și ThreadLocal la milioane de thread-uri sunt principalele riscuri de migrare; diagnoza se face cu JFR, `jdk.tracePinnedThreads` și thread dumps JSON.

5. **Alege modelul după workload, nu după modă.** Virtual threads ca default pentru I/O-intensive; platform threads pentru CPU-bound și cod nativ/legacy; reactive rămâne justificat unde backpressure-ul nativ și fluxurile complexe de evenimente contează. Framework-urile (Spring, Quarkus, Jakarta, Helidon, Micronaut) fac adopția aproape gratuită — dar verifică empiric (`Thread.currentThread()`) că rulezi efectiv pe virtual threads.
