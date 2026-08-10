# Java Concurrency in Practice (Brian Goetz) — Index de studiu

Notițe capitol cu capitol, în română (termeni tehnici în engleză). Cartea are 16 capitole, grupate în patru părți.

## Partea I — Fundamente

| # | Capitol | Fișier | Sinteză într-un rând |
|---|---------|--------|----------------------|
| 01 | Introduction | [capitol-01-introducere.md](capitol-01-introducere.md) | De ce există thread-urile, ce câștigi (CPU, modelare, responsivitate) și cele trei categorii de risc — safety, liveness, performance — care structurează toată cartea. |
| 02 | Thread Safety | [capitol-02-thread-safety.md](capitol-02-thread-safety.md) | Thread safety = gestionarea stării mutabile partajate: race conditions (check-then-act, read-modify-write), atomicitate, lock-uri intrinseci reentrante, regula „un invariant = un lock". |
| 03 | Sharing Objects | [capitol-03-sharing-objects.md](capitol-03-sharing-objects.md) | A doua jumătate a sincronizării — vizibilitatea: stale data, `volatile`, publicare și evadarea lui `this`, thread confinement, imutabilitate, cele patru mecanisme de safe publication. |
| 04 | Composing Objects | [capitol-04-composing-objects.md](capitol-04-composing-objects.md) | Proiectarea claselor thread-safe: inventarul stării + invarianți + politică de sincronizare; instance confinement, monitor pattern, delegare (și când eșuează), compoziție vs. client-side locking. |
| 05 | Building Blocks | [capitol-05-building-blocks.md](capitol-05-building-blocks.md) | Componentele platformei: colecții sincronizate vs. concurente, iteratori ascunși, `BlockingQueue` + producer-consumer, sincronizatoare (latch, semaphore, barrier) și construcția Memoizer. |

## Partea II — Structurarea aplicațiilor concurente

| # | Capitol | Fișier | Sinteză într-un rând |
|---|---------|--------|----------------------|
| 06 | Task Execution | [capitol-06-task-execution.md](capitol-06-task-execution.md) | Task-ul ca unitate de proiectare și framework-ul Executor: decuplarea submisiei de politica de execuție, `Callable`/`Future`, `CompletionService`, bugete de timp. |
| 07 | Cancellation and Shutdown | [capitol-07-cancellation-shutdown.md](capitol-07-cancellation-shutdown.md) | Oprirea e cooperativă: flag-uri vs. întrerupere, politica de întrerupere, anulare prin `Future`, oprirea serviciilor (poison pill, drenaj), `UncaughtExceptionHandler`, shutdown hooks. |
| 08 | Applying Thread Pools | [capitol-08-applying-thread-pools.md](capitol-08-applying-thread-pools.md) | Configurarea reală a `ThreadPoolExecutor`: thread starvation deadlock, formula de dimensionare, cozi și politici de saturație, thread factories, hook-uri, paralelizarea recursivității. |
| 09 | GUI Applications | [capitol-09-gui-applications.md](capitol-09-gui-applications.md) | Thread confinement ca arhitectură: event dispatch thread, `invokeLater`, task-uri lungi cu feedback și anulare, `SwingWorker`, split data models. |

## Partea III — Liveness, performanță și testare

| # | Capitol | Fișier | Sinteză într-un rând |
|---|---------|--------|----------------------|
| 10 | Avoiding Liveness Hazards | [capitol-10-liveness-hazards.md](capitol-10-liveness-hazards.md) | Deadlock prin ordine de lock-uri (statică și dinamică), deadlock între obiecte cooperante, open calls, diagnoză cu thread dumps; starvation și livelock. |
| 11 | Performance and Scalability | [capitol-11-performance-scalability.md](capitol-11-performance-scalability.md) | Throughput vs. latency, legea lui Amdahl și serializarea ascunsă, costurile contenției, scara de detensionare: îngustare → lock splitting → striping → eliminarea hot fields. |
| 12 | Testing Concurrent Programs | [capitol-12-testing-concurrent-programs.md](capitol-12-testing-concurrent-programs.md) | Teste de blocare cu interrupt+join, checksums insensibile la ordine, orchestrare cu bariere, testarea resurselor, cele cinci capcane de benchmark pe JVM, analiză statică. |

## Partea IV — Subiecte avansate

| # | Capitol | Fișier | Sinteză într-un rând |
|---|---------|--------|----------------------|
| 13 | Explicit Locks | [capitol-13-explicit-locks.md](capitol-13-explicit-locks.md) | `ReentrantLock` pentru ce `synchronized` nu poate: `tryLock` polled/cu timeout, achiziție întreruptibilă, fairness; plus `ReadWriteLock` și interdicția upgrade-ului. |
| 14 | Building Custom Synchronizers | [capitol-14-custom-synchronizers.md](capitol-14-custom-synchronizers.md) | Operații state-dependent: condition predicate, protocolul wait/notify (buclă obligatorie), `Condition` multiple per lock, anatomia AQS. |
| 15 | Atomic Variables and Nonblocking Sync | [capitol-15-atomic-variables-nonblocking.md](capitol-15-atomic-variables-nonblocking.md) | Sub lock-uri: CAS, bucla optimistă, clasele atomice, problema ABA, algoritmi lock-free (stiva Treiber, coada Michael-Scott cu helping). |
| 16 | The Java Memory Model | [capitol-16-java-memory-model.md](capitol-16-java-memory-model.md) | Fundamentul formal: happens-before, data race, piggybacking, lazy initialization corectă (holder idiom) vs. double-checked locking, initialization safety. |

## Cele 5 idei centrale ale cărții

1. **Problema nu sunt thread-urile, ci starea mutabilă partajată.** Fiecare variabilă mutabilă atinsă de mai multe thread-uri are nevoie de o decizie explicită: nu o partaja (confinement), fă-o imutabilă, sau păzește-o cu un lock. Cu cât mai puțină stare mutabilă partajată, cu atât mai puțin de demonstrat.

2. **Sincronizarea înseamnă două lucruri, nu unul: atomicitate ȘI vizibilitate.** Un lock nu doar serializează accesul; face vizibile toate scrierile anterioare. De aceea și citirile trebuie sincronizate, `volatile` rezolvă doar jumătate din problemă, iar publicarea unui obiect are nevoie de un mecanism (safe publication), nu doar de o asignare.

3. **Invariantele decid granularitatea locking-ului.** Toate variabilele legate de același invariant trebuie păzite de același lock; atomicitatea per-variabilă (mai multe `Atomic*`) nu compune. Corolarul de design: identifică starea și invarianții înainte de a alege mecanismul.

4. **Gândește în task-uri și politici de execuție, nu în thread-uri.** Decuplarea „ce se execută" de „cum se execută" (Executor, Future, pool-uri configurate, anulare cooperativă) e ce face aplicațiile concurente scalabile, oprite curat și reconfigurabile — spre deosebire de `new Thread(...)` presărat prin cod.

5. **Corect întâi, rapid după — și doar pe bază de măsurători.** Scalabilitatea se câștigă reducând serializarea (Amdahl), nu adăugând thread-uri; contenția e produsul frecvență × durată, iar tehnicile (secțiuni critice înguste, lock splitting/striping, imutabilitate, CAS) se aplică după ce profiling-ul arată unde e problema — nu înainte.

## Ordinea recomandată de studiu

- **Minim vital pentru cod de zi cu zi**: 1 → 2 → 3 → 4 → 5. Fără capitolele 2–3 nimic altceva nu are sens.
- **Pentru aplicații server**: adaugă 6 → 7 → 8 (execuție, anulare, dimensionare) și 10 → 11 (deadlock, scalabilitate).
- **Pentru diagnoză și tuning**: 10, 11, 12 împreună.
- **Aprofundare / interviuri / cod de bibliotecă**: 13 → 14 → 15 → 16.
- Capitolul 9 (GUI) merită citit chiar dacă nu scrii Swing: e cel mai clar studiu de caz de thread confinement dintr-un framework single-threaded.
