# Capitolul 13 — Explicit Locks

## Rezumat amplu
Capitolul deschide partea a patra (subiecte avansate) cu `ReentrantLock` și rudele lui: nu un înlocuitor al lui `synchronized`, ci o unealtă cu capabilități în plus pentru situațiile în care locking-ul intrinsec e prea rigid. `Lock` definește contractul: `lock`/`unlock` explicite, `tryLock` (negativ imediat sau cu timeout), `lockInterruptibly` (achiziție care răspunde la întrerupere), plus `newCondition` (capitolul 14). `ReentrantLock` implementează aceleași semantici de excludere mutuală, reentranță și vizibilitate ca `synchronized` — dar cu formă liberă: lock-ul nu mai e legat de structura de bloc, poate fi luat într-o metodă și eliberat în alta (hand-over-hand locking pe liste înlănțuite). Prețul libertății: eliberarea nu mai e automată — idiomul obligatoriu e `lock(); try { ... } finally { unlock(); }`, iar uitarea `finally`-ului lasă „bombe cu ceas". Capabilitățile care justifică folosirea: (1) `tryLock` polled — încerci ambele lock-uri, dacă nu le prinzi pe amândouă le eliberezi pe toate și reîncerci (cu backoff) — deadlock-ul dinamic de la `transferMoney` (capitolul 10) devine imposibil, doar improbabil de întârziat prin livelock, atenuat cu delay aleator; (2) `tryLock` cu timeout — bugete de timp pe activități care au nevoie de lock; cu lock-uri intrinseci, o achiziție blocată e definitivă; (3) `lockInterruptibly` — achiziția însăși devine anulabilă, necesară în task-urile anulabile din capitolul 7. Performanță: la contenție mare pe Java 5, ReentrantLock zdrobea intrinsecul; din Java 6, ambele folosesc mecanisme similare și diferența e mică — performanța nu mai e motiv de alegere (și „performanța e o țintă mișcătoare": cifrele de ieri nu justifică complexitatea de azi). Fairness: `ReentrantLock` poate fi fair (FIFO strict pe coada de așteptare) sau nonfair (default: barging permis — cine sosește când lock-ul tocmai s-a eliberat îl poate lua înaintea celor din coadă); fairness-ul costă enorm la throughput (ordini de mărime pe contenție intensă) pentru că forțează suspendare/trezire pe fiecare predare; nonfair câștigă exact pentru că cel care „taie coada" e deja pe CPU în timp ce candidatul din coadă abia se trezește; fair are sens doar când lock-ul e ținut mult și cererile vin rar. Verdictul capitolului: `synchronized` rămâne default-ul — mai concis, familiar, mai sigur (eliberare garantată), mai bine integrat cu thread dump-urile (viitoarele optimizări JVM merg tot spre el); `ReentrantLock` doar când ai nevoie efectiv de tryLock/timeout/întreruptibilitate/fairness/formă liberă. Finalul introduce `ReadWriteLock`: perechea read lock (partajat între cititori) / write lock (exclusiv), profitabilă când citirile domină și sunt destul de lungi; implementările variază pe release preference, reader barging, reentranță, downgrade (writer → reader, permis) vs. upgrade (reader → writer, interzis — deadlock dacă doi cititori îl vor simultan); `ReentrantReadWriteLock` + un `Map` obișnuit dau un container citit-concurent, iar la citiri dominate throughput-ul crește vizibil față de lock-ul exclusiv (deși `ConcurrentHashMap` rămâne superior când e aplicabil).

## Concepte explicate

### Lock / ReentrantLock și idiomul try-finally
- **Definiție** — lock explicit cu aceleași garanții ca `synchronized` (excludere, reentranță, vizibilitate), dar cu achiziție/eliberare programabile.
- **De ce contează** — deblochează scenariile imposibile cu intrinsecul; dar transferă asupra ta responsabilitatea eliberării pe toate căile de ieșire.
- **Exemplu de cod**
  ```java
  private final ReentrantLock lock = new ReentrantLock();
  void update() {
      lock.lock();                 // niciodată în interiorul try-ului!
      try { state.mutate(); }
      finally { lock.unlock(); }   // eliberare garantată, inclusiv la excepție
  }
  ```
- **Capcane frecvente** — `lock()` în interiorul `try` (dacă lock() aruncă, unlock-ul din finally eliberează un lock nedeținut → IllegalMonitorStateException care maschează problema); return timpuriu fără finally; a folosi ReentrantLock „din principiu" unde synchronized ajungea.

### tryLock polled — evitarea deadlock-ului dinamic
- **Definiție** — încerci toate lock-urile necesare fără să blochezi; dacă nu le obții pe toate, eliberezi ce ai prins și reîncerci mai târziu.
- **De ce contează** — elimină structural deadlock-ul de ordine (nimeni nu așteaptă ținând ceva); înlocuiește deadlock-ul cu un risc de livelock, tratat cu backoff aleator.
- **Exemplu de cod**
  ```java
  boolean transfer(Account from, Account to, long amount, long timeoutMs) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
      while (System.nanoTime() < deadline) {
          if (from.lock.tryLock()) {
              try {
                  if (to.lock.tryLock()) {
                      try { move(from, to, amount); return true; }
                      finally { to.lock.unlock(); }
                  }
              } finally { from.lock.unlock(); }   // nu ținem nimic cât așteptăm
          }
          Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5)); // backoff aleator anti-livelock
      }
      return false;   // buget epuizat — eșec raportabil, nu blocare eternă
  }
  ```
- **Capcane frecvente** — retry imediat fără randomizare (livelock sincronizat); a uita să eliberezi primul lock când al doilea eșuează; a ignora valoarea de retur a lui `tryLock`.

### Achiziție cu timeout și întreruptibilă
- **Definiție** — `tryLock(timeout, unit)` respectă un buget de timp și întreruperea; `lockInterruptibly()` blochează dar iese la interrupt.
- **De ce contează** — face „obținerea lock-ului" o activitate anulabilă, integrabilă în task-urile cu deadline și în protocolul de anulare din capitolul 7; cu `synchronized`, un thread înțepenit pe lock e nerecuperabil.
- **Exemplu de cod**
  ```java
  boolean trySendOnSharedLine(Message m, long timeout, TimeUnit unit) throws InterruptedException {
      if (!lineLock.tryLock(timeout, unit)) return false;  // buget de timp pe achiziție
      try { send(m); return true; }
      finally { lineLock.unlock(); }
  }
  ```
- **Capcane frecvente** — bugetul măsurat doar pe lucrul util, uitând că și achiziția consumă din el; `lockInterruptibly` fără tratarea corectă a `InterruptedException` (propagă sau restaurează).

### Fairness: fair vs. nonfair (barging)
- **Definiție** — fair: lock-ul se acordă strict în ordinea cozii; nonfair (default): un thread care cere lock-ul exact când e liber îl poate lua înaintea celor suspendați în coadă.
- **De ce contează** — nonfair exploatează fereastra „candidatul din coadă încă se trezește" — throughput dramatic mai bun; fairness-ul statistic e oricum rezonabil fără garanții FIFO; alege fair doar pentru lock-uri rare și lungi unde barging-ul ar înfometa sistematic.
- **Exemplu de cod**
  ```java
  Lock throughputFriendly = new ReentrantLock();      // nonfair, default
  Lock strictOrder      = new ReentrantLock(true);    // fair: plătești ordinea cu viteza
  ```
- **Capcane frecvente** — „fair sună corect" ales din reflex (și throughput-ul se prăbușește); a aștepta de la lock-urile intrinseci vreo garanție de fairness (nu oferă niciuna).

### synchronized rămâne default-ul
- **Definiție** — politica de alegere: intrinsec pentru cazul comun; ReentrantLock numai pentru capabilitățile lui distinctive.
- **De ce contează** — codul cu synchronized e mai scurt, imposibil de „uitat deschis", vizibil în thread dump-uri; riscul de leak la lock-urile explicite e real și scump.
- **Capcane frecvente** — migrarea în masă la ReentrantLock „pentru performanță" pe JVM-uri moderne (nejustificată); amestecul ambelor mecanisme pe aceeași stare (două lock-uri diferite = nicio protecție).

### ReadWriteLock, downgrade/upgrade
- **Definiție** — un lock logic cu două fețe: read (partajat, N cititori simultan) și write (exclusiv); downgrade = ținând write, iei read, eliberezi write (permis); upgrade = invers (interzis: doi cititori care vor simultan upgrade se blochează reciproc).
- **De ce contează** — la read-mostly cu citiri nescurte, paralelizarea cititorilor recuperează throughput pierdut pe lock exclusiv; dar complexitatea suplimentară (două cozi, politici) îl face mai lent decât un lock simplu la contenție mixtă — măsoară.
- **Exemplu de cod**
  ```java
  class ReadMostlyMap<K, V> {
      private final Map<K, V> map = new HashMap<>();
      private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
      V get(K k)        { rw.readLock().lock();  try { return map.get(k); }    finally { rw.readLock().unlock(); } }
      V put(K k, V v)   { rw.writeLock().lock(); try { return map.put(k, v); } finally { rw.writeLock().unlock(); } }
  }
  ```
- **Capcane frecvente** — tentativa de upgrade read→write (deadlock prin construcție — eliberează read-ul întâi și re-verifică starea); RWLock pe citiri scurte și dese (overhead-ul mănâncă câștigul; ConcurrentHashMap sau chiar synchronized bat); scrieri frecvente (writer starvation sau serializare oricum).

## Listing-uri cheie din carte
- **Listing 13.1 — interfața Lock**: contractul complet — lock, tryLock (ambele forme), lockInterruptibly, newCondition.
- **Listing 13.2 — idiomul canonic try-finally**: forma pe care N-AI voie s-o abreviezi; motivul pentru care synchronized rămâne mai sigur.
- **Listing 13.3 — transferMoney cu tryLock polled**: rezolvarea alternativă a deadlock-ului dinamic din capitolul 10.
- **Listing 13.4 — tryLock cu buget de timp**: achiziția ca activitate cu deadline.
- **Listing 13.5 — lockInterruptibly**: achiziția ca activitate anulabilă.
- **Listing 13.6/13.7 — ReadWriteLock + Map împachetat**: containerul read-mostly; șablonul minimal de folosire corectă.

## Citate
Verdictul capitolului (13.4, parafrazată): ReentrantLock nu e succesorul lui synchronized, ci unealta pentru cazurile în care synchronized nu poate — achiziții cu timeout, întreruptibile, polled, fair sau ne-bloc-structurate. Despre fairness (13.3, parafrazată): cozile FIFO stricte plătesc cu throughput-ul exact fereastra în care candidatul planificat încă se trezește.

## Legături
Rezolvă cu alte mijloace problema centrală a capitolului 10 (deadlock — tryLock ca alternativă la ordinea globală) și completează protocolul de anulare din capitolul 7 (achiziție întreruptibilă). `newCondition` e podul direct către capitolul 14 (condition queues explicite), iar mecanica internă (cozi de așteptare, achiziție) se dezvăluie în capitolul 14 la AQS — ReentrantLock e construit exact pe el. Comparațiile de scalabilitate folosesc vocabularul capitolului 11. De stăpânit: idiomul try-finally, criteriile reale de alegere ReentrantLock vs synchronized și interdicția upgrade-ului la RWLock.
