# Capitolul 14 — Building Custom Synchronizers

## Rezumat amplu
Capitolul explică cum se construiesc clase cu operații dependente de stare — cele care trebuie să aștepte până când o precondiție devine adevărată (take pe buffer gol, acquire fără permise) — întâi cu wait/notify, apoi cu `Condition`, apoi cu AQS, scheletul pe care stau toate sincronizatoarele platformei. Firul conducător: aceeași clasă `BoundedBuffer`, în versiuni succesive. Versiunea 1 — balking: precondiția neîndeplinită aruncă excepție (`GrumpyBoundedBuffer`); corectă, dar mută așteptarea la client, care ajunge să facă spin-and-sleep — excepțiile pentru „buffer plin" confundă o stare normală cu o eroare. Versiunea 2 — polling crud (`SleepyBoundedBuffer`): bucla client testează-și-doarme; alegerea intervalului de sleep e un compromis imposibil între CPU irosit și latență. Versiunea 3 — condition queues intrinseci: `wait()` suspendă thread-ul ȘI eliberează lock-ul atomic, `notify/notifyAll` trezesc; buffer-ul devine simplu și eficient, dar corectitudinea cere un protocol strict. Regulile canonice: (1) există un condition predicate — expresia de stare așteptată (notEmpty: count > 0) — pe care TREBUIE să-l identifici și documentezi; (2) testarea predicatului și `wait()` se fac ținând lock-ul care păzește starea predicatului (altfel missed signal: verifici, între timp altcineva schimbă starea și notifică, apoi tu aștepți o notificare deja consumată — aștepți pentru totdeauna); (3) `wait()` se cheamă ÎNTOTDEAUNA într-o buclă care re-testează predicatul după trezire — pentru că wait se poate întoarce spuriu, pentru că notificarea poate viza alt predicat pe aceeași coadă, și pentru că între trezire și re-achiziția lock-ului alt thread poate consuma condiția; (4) `notifyAll` e default-ul sigur — `notify` (un singur trezit) e corect doar când o singură condiție e pe coadă și fiecare notificare activează cel mult un thread (uniform waiters + one-in-one-out); altfel semnalele se pierd pe thread-uri care așteptau altceva. Optimizarea conditional notification (notifică doar la tranziția gol→negol sau plin→neplin) reduce trezirile inutile, cu prețul fragilității la subclasare. Exemplul `ThreadGate` (poartă redeschidibilă) arată subtilitatea generației: un simplu boolean nu ajunge — deschide-închide rapid trebuie să lase să treacă toți cei care așteptau la deschidere (contor de generații în predicat). Reguli conexe: documentează protocolul de sincronizare, nu expune coada de condiție dacă nu-i controlezi utilizarea, atenție la subclasare. Versiunea 4 — `Condition` explicite (de la `Lock.newCondition`): mai multe cozi de condiție pe același lock — `notFull` și `notEmpty` separate fac `signal` (nu signalAll) corect și eficient, pentru că fiecare coadă are un singur predicat; echivalentele: await/signal/signalAll, cu aceleași reguli (buclă, lock ținut). Urmează anatomia: `AbstractQueuedSynchronizer` — clasa pe care sunt construite ReentrantLock, Semaphore, CountDownLatch, FutureTask, ReentrantReadWriteLock; ideea: starea sincronizatorului e un `int` (`getState/setState/compareAndSetState`), iar subclasele definesc doar `tryAcquire`/`tryRelease` (exclusiv) sau `tryAcquireShared`/`tryReleaseShared` (partajat) — AQS gestionează cozile de așteptare, blocarea/trezirea, fairness-ul. Exemple: `OneShotLatch` în ~20 de linii (state 0/1, acquireShared trece doar când state==1), cum ReentrantLock folosește state drept contor de reentranță și cum Semaphore îl folosește drept permise (CAS-loop în tryAcquireShared). Morala: nu-ți construi sincronizatoare pe wait/notify de la zero dacă le poți asambla din cele existente sau extinde AQS — și înțelege-le anatomia ca să le folosești corect.

## Concepte explicate

### Condition predicate + protocolul wait/notify
- **Definiție** — predicatul de condiție e expresia de stare care trebuie să devină adevărată ca operația să continue; protocolul: testează-l sub lock, așteaptă în buclă, notifică la orice schimbare care îl poate face adevărat.
- **De ce contează** — fiecare abatere are un mod de eșec cu nume: fără lock → missed signal (așteptare eternă); fără buclă → precondiție falsă la trezire (corupere de stare); notify în loc de notifyAll → semnale livrate thread-urilor greșite.
- **Exemplu de cod**
  ```java
  class Buffer<E> {
      private final Queue<E> items = new ArrayDeque<>();
      private final int capacity = 100;
      public synchronized void put(E e) throws InterruptedException {
          while (items.size() == capacity)   // BUCLĂ, nu if: re-testează după fiecare trezire
              wait();                        // eliberează lock-ul atomic cu suspendarea
          items.add(e);
          notifyAll();                       // starea s-a schimbat: poate fi adevărat notEmpty
      }
      public synchronized E take() throws InterruptedException {
          while (items.isEmpty())
              wait();
          E e = items.remove();
          notifyAll();                       // poate fi adevărat notFull
          return e;
      }
  }
  ```
- **Capcane frecvente** — `if (empty) wait();` (clasicul bug de spurious wakeup/predicat străin); `wait()` fără lock-ul potrivit (IllegalMonitorStateException în cel mai bun caz, missed signal în cel mai rău); notificare uitată pe o cale de modificare a stării.

### Missed signals
- **Definiție** — un thread așteaptă o notificare care a fost deja emisă înainte ca el să intre în așteptare; cauza: testarea predicatului în afara lock-ului sau pe alt lock.
- **De ce contează** — produce blocări eterne fără deadlock vizibil în dump (thread-ul e „WAITING" legitim); printre cele mai greu de diagnosticat bug-uri.
- **Exemplu de cod**
  ```java
  // GREȘIT: fereastră între test și wait
  if (!ready) {              // testat FĂRĂ lock
      synchronized (lock) { lock.wait(); }   // notify-ul a putut veni în fereastră → aștepți degeaba
  }
  // CORECT: test + wait sub același lock, în buclă
  synchronized (lock) { while (!ready) lock.wait(); }
  ```
- **Capcane frecvente** — orice „optimizare" care scoate testul de sub lock; folosirea a două lock-uri diferite pentru stare și pentru wait.

### notify vs. notifyAll; conditional notification
- **Definiție** — `notifyAll` trezește toți (cei fără predicatul adevărat re-adorm); `notify` trezește UNUL, ales de JVM — sigur doar cu uniform waiters (un singur predicat pe coadă) și one-in-one-out; conditional notification: notifici doar la tranziția care poate schimba predicatul (ex. doar când buffer-ul iese din gol).
- **De ce contează** — notify greșit pierde semnale (trezești un producător când trebuia un consumator); notifyAll e corect dar scump la mulți asceptători (N treziri, N re-achiziții, N−1 re-adormiri); conditional notification recuperează performanța păstrând corectitudinea — dar e fragilă la extindere.
- **Exemplu de cod**
  ```java
  public synchronized void put(E e) throws InterruptedException {
      while (isFull()) wait();
      boolean wasEmpty = items.isEmpty();
      items.add(e);
      if (wasEmpty) notifyAll();   // conditional: doar tranziția gol→negol interesează consumatorii
  }
  ```
- **Capcane frecvente** — notify „pentru performanță" într-o clasă cu două predicate pe aceeași coadă (put și take așteaptă amândouă pe this!); conditional notification spart de o subclasă care adaugă alt predicat.

### Condition explicite (multiple cozi per lock)
- **Definiție** — `lock.newCondition()` creează oricâte cozi de condiție pe același `Lock`; `await/signal/signalAll` cu aceleași reguli ca wait/notify.
- **De ce contează** — un predicat per coadă face `signal` (unicast) corect și eficient prin construcție și documentează predicatele în cod (notFull, notEmpty ca obiecte numite); în plus moștenește capabilitățile Lock (await cu timeout, întreruptibil).
- **Exemplu de cod**
  ```java
  class CondBuffer<E> {
      private final Lock lock = new ReentrantLock();
      private final Condition notFull  = lock.newCondition();  // o coadă = un predicat
      private final Condition notEmpty = lock.newCondition();
      private final Object[] items = new Object[100];
      private int head, tail, count;
      public void put(E e) throws InterruptedException {
          lock.lock();
          try {
              while (count == items.length) notFull.await();
              items[tail] = e; tail = (tail + 1) % items.length; count++;
              notEmpty.signal();               // sigur: pe notEmpty așteaptă DOAR consumatori
          } finally { lock.unlock(); }
      }
      // take() simetric: while empty → notEmpty.await(); ... notFull.signal();
  }
  ```
- **Capcane frecvente** — `signal` pe condiția greșită; amestecul wait/notify intrinsec cu Condition pe același obiect; a uita că regula buclei rămâne obligatorie și aici.

### AbstractQueuedSynchronizer (AQS)
- **Definiție** — schelet pentru sincronizatoare: un `int state` manipulat prin `getState/setState/compareAndSetState` + cozi de așteptare gestionate de framework; subclasa definește doar semantica achiziției/eliberării prin `tryAcquire`/`tryRelease` (mod exclusiv) sau `tryAcquireShared`/`tryReleaseShared` (mod partajat).
- **De ce contează** — e fundația ReentrantLock, Semaphore, CountDownLatch, FutureTask, RRWL: o singură implementare corectă a cozilor, blocării și trezirii, refolosită; a o cunoaște explică comportamentele „de la suprafață" (fairness, semantica shared/exclusive).
- **Exemplu de cod**
  ```java
  // Latch binar în stil OneShotLatch (Listing 14.14)
  class OneShotLatch {
      private final Sync sync = new Sync();
      public void signal()  { sync.releaseShared(0); }
      public void await() throws InterruptedException { sync.acquireSharedInterruptibly(0); }
      private static class Sync extends AbstractQueuedSynchronizer {
          protected int tryAcquireShared(int ignored) { return getState() == 1 ? 1 : -1; } // trece doar deschis
          protected boolean tryReleaseShared(int ignored) { setState(1); return true; }     // deschide definitiv
      }
  }
  ```
- **Capcane frecvente** — extinderea AQS direct în clasa publică (expune metode de framework — folosește o subclasă privată delegată, ca toate clasele platformei); a reimplementa cu wait/notify ce există deja pe AQS.

### State-dependent ops în bibliotecă: cum folosesc ReentrantLock și Semaphore AQS-ul
- **Definiție** — ReentrantLock: `state` = numărul de reentrări, owner-ul ținut separat; Semaphore: `state` = permise disponibile, decrementate prin CAS-loop în `tryAcquireShared`.
- **De ce contează** — vezi că „lock-ul" și „semaforul" sunt DOAR interpretări diferite ale aceluiași int + aceleași cozi; demistifică întreaga bibliotecă de sincronizare.
- **Capcane frecvente** — a deduce garanții din implementare în loc de contract (implementările se schimbă; contractul rămâne).

## Listing-uri cheie din carte
- **Listing 14.1/14.2 — structura operației state-dependent + clasa de bază a buffer-ului**: scheletul pe care se construiesc toate variantele.
- **Listing 14.3/14.4 — GrumpyBoundedBuffer + clientul lui**: balking și de ce mută greul la client.
- **Listing 14.5 — SleepyBoundedBuffer**: poll-and-sleep; compromisul imposibil al intervalului.
- **Listing 14.6/14.7 — buffer pe condition queues + forma canonică a metodei state-dependent**: ținta capitolului; forma canonică (lock → while(!predicat) wait → acțiune → notify) e de memorat literal.
- **Listing 14.8 — conditional notification în put**: optimizarea și condițiile ei de valabilitate.
- **Listing 14.9 — ThreadGate**: poarta redeschidibilă cu numărare de generații — de ce predicatul poate fi mai subtil decât un boolean.
- **Listing 14.10/14.11 — interfața Condition + buffer-ul cu notFull/notEmpty**: unicast-ul devine corect prin separarea cozilor.
- **Listing 14.12 — semafor construit pe Lock+Condition**: echivalența constructivă a sincronizatoarelor.
- **Listing 14.13–14.16 — formele canonice AQS + OneShotLatch + fragmente din ReentrantLock/Semaphore**: anatomia întregii biblioteci.

## Citate
Regula de neocolit (14.2, parafrazată): documentează predicatul de condiție, testează-l ținând lock-ul care îl păzește și cheamă wait într-o buclă care îl re-testează după fiecare trezire. Despre alegerea notificării (14.2.4, parafrazată): folosește notifyAll dacă nu poți dovedi că waiters-ii sunt uniformi și că o notificare activează exact un thread.

## Legături
Rezolvă „operațiile state-dependent" amânate încă din capitolul 4 și explică ce era sub `BlockingQueue` (cap. 5) și sub sincronizatoarele folosite peste tot (latch, semaphore, FutureTask). Continuă capitolul 13: `Condition` vine din `Lock`, iar AQS e chiar implementarea ReentrantLock-ului de acolo. Capitolul 15 coboară încă un nivel (CAS-ul pe care stă `compareAndSetState`), iar bug-pattern-urile din capitolul 12 (wait fără buclă, notify unic) sunt exact încălcările protocolului de aici. De stăpânit: forma canonică a metodei state-dependent (literal), diferența notify/notifyAll și ideea că toată biblioteca e AQS interpretat.
