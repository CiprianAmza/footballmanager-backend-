# Capitolul 12 — Testing Concurrent Programs

## Rezumat amplu
Capitolul răspunde la întrebarea incomodă: cum testezi cod ale cărui bug-uri sunt probabilistice, rare și sensibile la timing? Punctul de plecare: testarea concurenței caută să lărgească, nu să elimine, incertitudinea — majoritatea defectelor sunt evenimente rare, deci testele trebuie să crească șansa de apariție și apoi să ruleze destul cât raritatea să devină vizibilă. Obiectul de studiu al capitolului e un `BoundedBuffer` construit pe `Semaphore` (availableItems/availableSpaces), testat progresiv. Nivelul 1 — teste de corectitudine secvențială: orice test concurent trebuie să înceapă ca test secvențial (postcondiții, invarianți — buffer proaspăt e gol/negol), altfel vânezi race-uri în cod care nu merge nici pe un thread. Nivelul 2 — testarea blocării: verifici că `take` pe buffer gol chiar blochează — pornești un thread „taker", aștepți, îl întrerupi și ceri să se fi terminat prin `InterruptedException`; `join` cu timeout + `isAlive` dau verdictul; simetric pentru `put` pe buffer plin. Nivelul 3 — testarea safety-ului sub concurență adevărată: N producători și N consumatori, iar corectitudinea se verifică prin checksums insensibile la ordine — fiecare element produs intră într-o sumă a producătorului, fiecare element consumat într-a consumatorului; la final sumele trebuie să coincidă; datele trebuie să fie „aleatorii dar reproductibile" (un RNG ieftin, xorShift, sămânțat divers), nu secvențiale (ar masca bug-urile prin predictibilitate) și nu `Random` partajat (ar introduce sincronizare străină care serializează exact ce testezi). Orchestrarea folosește `CyclicBarrier` la start și la final ca toate thread-urile să lovească simultan; regulile de mediu: mai multe thread-uri active decât core-uri, rulări pe mai multe platforme, teste mai lungi decât „o secundă de noroc". Nivelul 4 — resource management: testezi și că NU se rețin referințe (heap inspection înainte/după), că pool-urile cresc/scad conform config-ului (ThreadFactory instrumentat care numără thread-urile create), și folosești callback-uri (hook-urile pool-ului) ca puncte de observație. Truc util: `Thread.yield()`/sleep scurt presărat în secțiuni critice generează mai multe intercalări (unelte gen aspect-uri le pot injecta fără să murdărești codul). Partea a doua — teste de performanță: extinzi testele de safety cu măsurare (barrier action care pornește/oprește cronometrul), măsori throughput pe perechi put/take la diverse mărimi de buffer și numere de thread-uri, compari implementări (BoundedBuffer vs ArrayBlockingQueue vs LinkedBlockingQueue — linked câștigă la concurență, contrar intuiției, pentru că permite put și take simultan), și măsori variance-ul răspunsurilor, nu doar media (histogramele arată fairness vs. throughput la semafoare fair/nonfair — fairness costă enorm). Partea a treia — capcanele benchmarking-ului: garbage collection (rulează destul cât GC-ul să fie inclus, sau deloc), compilarea dinamică (warm-up, altfel măsori interpretorul + compilarea însăși), sampling nerealist de code path-uri (JIT-ul optimizează pentru ce vede — testează mix-ul real), grade nerealiste de contenție (benchmark-ul fără „muncă locală" între accese e alt program decât producția), dead code elimination (JIT-ul șterge calculul nefolosit — consumă rezultatele într-un mod imprevizibil, hashCode + print condiționat). Partea a patra — complementele testării: code review de experți, analiză statică (FindBugs cu bug patterns: notify fără condiție, wait în afara buclei, lock neeliberat pe toate căile, double-checked locking, spin loop pe câmp ne-volatile), model checking, instrumentare. Concluzia: nicio unealtă singură nu ajunge — corectitudinea concurenței se obține prin straturi: design curat, review, static analysis, teste țintite.

## Concepte explicate

### Teste de blocare (blocking behavior)
- **Definiție** — verifici că o metodă chiar blochează în condițiile specificate: thread dedicat care încearcă operația, timp de grație, `interrupt`, apoi asserturi pe modul de terminare.
- **De ce contează** — blocarea e parte din contract (take pe gol TREBUIE să aștepte); succesul nedorit („n-a blocat") e un bug la fel de grav ca deadlock-ul.
- **Exemplu de cod**
  ```java
  void testTakeBlocksWhenEmpty() throws InterruptedException {
      BoundedBuffer<Integer> buf = new BoundedBuffer<>(10);
      Thread taker = new Thread(() -> {
          try {
              buf.take();
              fail("take-ul pe buffer gol NU trebuia să reușească");   // dacă ajunge aici, bug
          } catch (InterruptedException success) { /* exact ce așteptam */ }
      });
      taker.start();
      Thread.sleep(LOCKUP_DETECT_TIMEOUT);   // îi dăm timp să ajungă în blocare
      taker.interrupt();                      // îl scoatem din blocare
      taker.join(LOCKUP_DETECT_TIMEOUT);
      assertFalse(taker.isAlive());           // dacă e încă viu: blocat unde nu trebuie
  }
  ```
- **Capcane frecvente** — sleep-ul „destul de lung" e euristic, nu garanție; a uita join+isAlive (testul trece deși thread-ul a rămas agățat); asserturi JUnit în thread-uri secundare care nu se propagă la runner (colectează eșecurile explicit).

### Checksums insensibile la ordine + date reproductibile
- **Definiție** — proprietatea testată global: tot ce intră iese exact o dată — sume/checksum-uri per producător și consumator, comparate la final; datele vin dintr-un RNG ieftin, deterministic per seed.
- **De ce contează** — nu poți verifica ordinea exactă la N producători, dar poți verifica conservarea; datele predictibile sau RNG-ul partajat distrug sensul testului (mascare, respectiv serializare parazită).
- **Exemplu de cod**
  ```java
  static int xorShift(int y) { y ^= y << 6; y ^= y >>> 21; y ^= y << 7; return y; } // ieftin, „destul de aleator"
  // producător: for (...) { sum += item; buf.put(item); item = xorShift(item); }
  // consumator: for (...) { sum += buf.take(); }
  // final:      assertEquals(putSum.get(), takeSum.get());
  ```
- **Capcane frecvente** — `Random` partajat între thread-uri (sincronizarea lui devine punct de coordonare care ascunde race-urile); numere consecutive drept date de test; a agrega sumele tot printr-un punct de contenție fierbinte.

### Orchestrare cu CyclicBarrier (start/stop simultan)
- **Definiție** — o barieră de N+1 (thread-urile + main) la începutul și sfârșitul testului: nimeni nu pornește până nu sunt toți gata, main-ul nu măsoară/verifică până nu au terminat toți.
- **De ce contează** — fără barieră, primele thread-uri termină înainte ca ultimele să pornească — „concurența" testată e secvențialitate deghizată.
- **Exemplu de cod**
  ```java
  CyclicBarrier barrier = new CyclicBarrier(nPairs * 2 + 1);
  // fiecare worker: barrier.await(); muncă; barrier.await();
  // main: barrier.await();  /* toți pornesc */  barrier.await();  /* toți au terminat */  verifică();
  ```
- **Capcane frecvente** — a număra greșit părțile barierei (testul atârnă); a pune verificările înainte de a doua barieră.

### Testarea managementului de resurse
- **Definiție** — verifici dimensiunea pool-urilor (ThreadFactory care numără), eliberarea referințelor (heap size înainte/după drenare) și folosești hook-urile framework-ului ca senzori.
- **De ce contează** — scurgerile de memorie și pool-urile care nu respectă limitele sunt bug-uri de concurență la fel de reale ca race-urile — doar că ucid mai lent.
- **Exemplu de cod**
  ```java
  class CountingFactory implements ThreadFactory {
      final AtomicInteger created = new AtomicInteger();
      public Thread newThread(Runnable r) { created.incrementAndGet(); return new Thread(r); }
  }
  // umpli pool-ul cu task-uri blocate, apoi: assertEquals(MAX_SIZE, factory.created.get());
  ```
- **Capcane frecvente** — heap assertions fără `System.gc()` explicit și fără toleranțe; a testa mărimea pool-ului fără să saturezi cererea (pool-ul leneș nu crește degeaba).

### Capcanele benchmark-urilor pe JVM
- **Definiție** — cele cinci distorsiuni: GC inclus/exclus nedeliberat, compilare JIT în timpul măsurării (fără warm-up), mix nerealist de code paths, contenție nerealistă (fără muncă locală între accese), dead code elimination.
- **De ce contează** — fiecare poate inversa concluzia unui benchmark; cifrele „măsurate" pe un microbenchmark naiv descriu alt program decât al tău.
- **Exemplu de cod**
  ```java
  // anti-dead-code: consumă rezultatul imprevizibil, aproape gratuit
  if (result.hashCode() == System.nanoTime()) System.out.print(" ");
  // anti-JIT-în-măsurătoare: rulează câteva mii de iterații de warm-up înainte de cronometru
  ```
- **Capcane frecvente** — media fără varianță (fairness/latency ascunse); benchmark single-run; a compara implementări la UN singur nivel de contenție și a generaliza.

### Complementele testării: review, analiză statică, profiling
- **Definiție** — code review de oameni care știu concurență; analizatoare de bug patterns (notify în loc de notifyAll, wait fără buclă, câmpuri ne-volatile în spin loops, lock-uri neeliberate); model checking pentru protocoale mici; instrumentarea/profilarea în producție.
- **De ce contează** — testele demonstrează prezența bug-urilor, nu absența; pattern-urile statice prind exact clasele de erori pe care testele le ratează sistematic (fereastra prea îngustă ca s-o lovești cu noroc).
- **Capcane frecvente** — încrederea exclusivă într-o singură unealtă; tratarea warning-urilor statice ca zgomot fără triaj.

## Listing-uri cheie din carte
- **Listing 12.1 — BoundedBuffer pe Semaphore**: obiectul de test al capitolului; el însuși un exemplu bun de design cu semafoare.
- **Listing 12.2 — teste secvențiale de bază**: pasul zero obligatoriu.
- **Listing 12.3 — testul de blocare cu interrupt+join**: idiomul complet pentru „chiar blochează?".
- **Listing 12.4 — xorShift RNG**: aleator destul, ieftin destul, fără sincronizare parazită.
- **Listing 12.5/12.6 — PutTakeTest**: scheletul canonic producători/consumatori + checksums + bariere; șablonul de refolosit în orice test de colecție concurentă.
- **Listing 12.7 — testul de resource leak**: heap inspection în jurul drenării bufferului.
- **Listing 12.8/12.9 — ThreadFactory de test + verificarea expansiunii pool-ului**: senzori în punctele de creație.
- **Listing 12.10 — Thread.yield pentru intercalări**: mărirea artificială a spațiului de interleaving.
- **Listing 12.11–12.13 — timerul pe barieră și driverul de timing**: transformarea testului de safety în benchmark disciplinat.

## Citate
Teza capitolului (introducere, parafrazată): testele pot demonstra că un program concurent e greșit, nu că e corect — de aceea scopul testării e să maximizeze șansa de a expune defectul, iar plasa de siguranță e stratificată. Despre benchmark-uri (12.3, parafrazată): pe o platformă cu compilare dinamică și GC, un benchmark naiv măsoară aproape întotdeauna altceva decât crede autorul lui.

## Legături
Închide partea a treia folosind tot arsenalul anterior: semafoare și bariere din capitolul 5, pool-uri și ThreadFactory din capitolul 8, noțiunile de contenție din capitolul 11 (testele de performanță sunt operaționalizarea acelui capitol). Bug pattern-urile enumerate anticipează capitolul 14 (wait/notify corect) și 16 (double-checked locking). De stăpânit: idiomul testului de blocare, șablonul PutTakeTest cu checksums + bariere și lista celor cinci capcane de benchmark — fiecare dintre ele apare în practică la orice măsurătoare JVM.
