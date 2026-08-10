# Capitolul 02 — Thread Safety

## Rezumat amplu
Capitolul definește riguros ce înseamnă „thread-safe" și construiește primele unelte pentru a obține această proprietate. Punctul de plecare: a scrie cod concurent corect înseamnă, în esență, a gestiona accesul la stare mutabilă partajată — nu thread-urile sunt problema, ci starea. Dacă mai multe thread-uri accesează aceeași variabilă mutabilă și cel puțin unul scrie, accesul trebuie coordonat, fără excepție; alternativele sunt trei: nu partaja starea, fă-o imutabilă sau sincronizează accesul. Definiția de lucru a thread safety-ului: o clasă e thread-safe dacă se comportă corect când e accesată din mai multe thread-uri, indiferent de intercalarea operațiilor și fără ca apelanții să aibă nevoie de sincronizare suplimentară. Corolar important: clasele stateless sunt întotdeauna thread-safe — exemplul `StatelessFactorizer` (un servlet care doar calculează din parametri locali) nu are ce corupe. Problemele apar odată cu starea: un contor `++count` pare o operație, dar e read-modify-write, deci două request-uri pot pierde incremente. De aici, definiția race condition-ului: corectitudinea depinde de intercalarea norocoasă a operațiilor; cele două tipare clasice sunt check-then-act (verifici o condiție, apoi acționezi pe baza unei observații deja învechite — ex. lazy initialization dublă) și read-modify-write. Operațiile compuse trebuie făcute atomice; pentru o singură variabilă merg clasele atomice (`AtomicLong`), dar când invariantul leagă mai multe variabile (numărul cache-uit și factorii lui), atomicitatea per-variabilă nu ajunge — trebuie o singură protecție pentru tot invariantul. Aici intră lock-urile intrinseci: fiecare obiect Java are un monitor; `synchronized` îl dobândește la intrare și îl eliberează la ieșire, garantând excludere mutuală. Lock-urile intrinseci sunt reentrant (per-thread, nu per-invocație), altfel un apel `synchronized` către o metodă `synchronized` a aceleiași clase (ex. suprascriere care cheamă `super`) ar bloca definitiv. Regula de disciplină: fiecare variabilă mutabilă partajată trebuie păzită de exact un lock, același peste tot, iar toate variabilele unui invariant — de același lock; convenția trebuie documentată. Capitolul se încheie cu echilibrul dintre corectitudine și performanță: sincronizarea întregii metode `service` face servlet-ul corect dar serial (throughput dezastruos); soluția e îngustarea secțiunilor `synchronized` la operațiile pe stare, lăsând calculul lung (factorizarea) în afara lor. Avertisment final: nu sacrifica simplitatea pentru optimizări premature și evită să ții lock-uri în timpul operațiilor lungi sau de I/O.

## Concepte explicate

### Thread safety (definiția)
- **Definiție** — clasa se comportă conform specificației sub orice intercalare de accese concurente, fără sincronizare cerută apelantului.
- **De ce contează** — mută responsabilitatea în interiorul clasei: apelanții nu pot fi obligați să „știe" să se sincronizeze; encapsularea sincronizării e singura apărare scalabilă.
- **Exemplu de cod**
  ```java
  // stateless => thread-safe fără nicio sincronizare
  class Calculator {
      int add(int a, int b) { return a + b; } // doar variabile locale, pe stivă
  }
  ```
- **Capcane frecvente** — a declara o clasă „thread-safe" pentru că fiecare metodă e sincronizată, deși apelanții compun metodele în secvențe neatomice (vezi cap. 4/5).

### Race condition: check-then-act și read-modify-write
- **Definiție** — check-then-act: decizi pe baza unei observații care poate deveni falsă între verificare și acțiune; read-modify-write: noua valoare depinde de cea veche, iar între citire și scriere se poate strecura alt thread.
- **De ce contează** — sunt cele două forme sub care apar aproape toate race-urile reale: lazy init, contoare, „if absent then put".
- **Exemplu de cod**
  ```java
  // GREȘIT — check-then-act (lazy initialization)
  class LazyHolder {
      private static Expensive instance;
      static Expensive get() {
          if (instance == null)          // check: doi pot vedea null simultan
              instance = new Expensive(); // act: se creează două instanțe
          return instance;
      }
  }
  // CORECT (simplu și suficient de des)
  class SafeHolder {
      private static Expensive instance;
      static synchronized Expensive get() {
          if (instance == null) instance = new Expensive();
          return instance;
      }
  }
  ```
- **Capcane frecvente** — a „repara" doar scrierea, nu și citirea; ambele trebuie sub același lock.

### Atomicitate și variabile atomice
- **Definiție** — o operație e atomică față de altele dacă, din perspectiva lor, fie s-a executat complet, fie deloc. `AtomicLong` & co. oferă read-modify-write atomic pe o singură variabilă.
- **De ce contează** — elimină race-urile pe contoare/referințe fără lock; dar atomicitatea per-variabilă nu compune: două variabile atomice actualizate separat pot fi văzute într-o stare combinată inconsistentă.
- **Exemplu de cod**
  ```java
  class HitCounter {
      private final AtomicLong hits = new AtomicLong();
      void hit() { hits.incrementAndGet(); }   // atomic, corect
  }
  // GREȘIT — două atomice, un invariant comun (echivalentul Listing 2.5)
  class BadCache {
      private final AtomicReference<BigInteger> lastN = new AtomicReference<>();
      private final AtomicReference<BigInteger[]> lastF = new AtomicReference<>();
      void update(BigInteger n, BigInteger[] f) { lastN.set(n); lastF.set(f); } // fereastră inconsistentă între cele două set-uri
  }
  ```
- **Capcane frecvente** — exact acest tipar: „am pus Atomic peste tot, deci e safe". Invariantul multi-variabilă cere un singur lock.

### Lock intrinsec (monitor) și reentranța
- **Definiție** — fiecare obiect are un lock încorporat; `synchronized(obj)`/metodele `synchronized` îl dobândesc exclusiv. Reentranța: lock-ul e deținut per-thread cu un contor, deci același thread îl poate re-dobândi.
- **De ce contează** — excluderea mutuală face secvențele compuse atomice; reentranța previne self-deadlock la apeluri imbricate (subclasă → `super`).
- **Exemplu de cod**
  ```java
  class Base {
      synchronized void doWork() { /* ... */ }
  }
  class Derived extends Base {
      @Override synchronized void doWork() {
          super.doWork(); // fără reentranță, aici ar fi deadlock cu sine însuși
      }
  }
  ```
- **Capcane frecvente** — sincronizarea pe obiecte diferite crezând că e același lock (ex. `synchronized(this)` într-o clasă, dar acces la aceeași stare din altă clasă fără lock).

### Guarding state / politica de locking
- **Definiție** — convenția prin care fiecare variabilă mutabilă partajată e asociată cu exact un lock care o păzește la fiecare acces (citire și scriere).
- **De ce contează** — sincronizarea „pe alocuri" nu ajută: e suficient un singur acces nesincronizat ca toate garanțiile să dispară.
- **Exemplu de cod**
  ```java
  class Inventory {
      private final Object lock = new Object();
      private int stock;                 // @GuardedBy("lock")
      void add(int n)  { synchronized (lock) { stock += n; } }
      int  peek()      { synchronized (lock) { return stock; } } // și citirea!
  }
  ```
- **Capcane frecvente** — citiri „doar informative" fără lock (stale data, vezi cap. 3); păzirea unui invariant multi-variabilă cu lock-uri diferite.

### Îngustarea secțiunilor critice
- **Definiție** — ține sub lock doar operațiile pe stare partajată, nu calculele lungi sau I/O-ul.
- **De ce contează** — echilibrul safety/performanță: lock pe toată metoda = serializare completă; lock-uri prea fine = complexitate și risc. Regula practică: exclude din secțiunea critică munca ce nu atinge starea.
- **Exemplu de cod**
  ```java
  void service(Request req) {
      Data d = expensiveCompute(req);      // în afara lock-ului
      synchronized (this) { cache = d; }   // doar publicarea stării sub lock
  }
  ```
- **Capcane frecvente** — I/O (rețea, disc, log) în interiorul `synchronized`; „micro-optimizarea" care sparge atomicitatea unui invariant în două blocuri sincronizate separate.

## Listing-uri cheie din carte
- **Listing 2.1 — A Stateless Servlet**: `StatelessFactorizer`; demonstrează că fără stare nu există problemă de concurență.
- **Listing 2.2 — contor fără sincronizare (Don't)**: `++count` pierde incremente; imaginea canonică a read-modify-write.
- **Listing 2.3 — Race în lazy initialization (Don't)**: check-then-act care poate întoarce instanțe diferite.
- **Listing 2.4 — contor cu AtomicLong**: fix corect când starea e o singură variabilă.
- **Listing 2.5 — cache fără atomicitate adecvată (Don't)**: două `AtomicReference` cu invariant comun; atomicitatea nu compune.
- **Listing 2.6 — cache corect dar cu concurență inacceptabilă (Don't)**: `synchronized` pe toată metoda `service`; corect, dar serial.
- **Listing 2.7 — cod care ar bloca fără reentranță**: subclasa care cheamă metoda sincronizată a părintelui.
- **Listing 2.8 — servlet cu cache corect și performant**: blocuri `synchronized` înguste + contoare actualizate atomic împreună; sinteza capitolului.

## Citate
Ideea-pivot a capitolului (secțiunea 2.1, parafrazată): a scrie programe concurente corecte înseamnă a gestiona accesul la starea mutabilă partajată — restul e detaliu. A doua maximă (secțiunea 2.4, parafrazată): pentru fiecare invariant care implică mai multe variabile, toate variabilele lui trebuie păzite de același lock.

## Legături
Construiește direct pe riscurile de safety din capitolul 1. Deliberat, capitolul tratează sincronizarea doar ca atomicitate; capitolul 3 adaugă a doua jumătate a poveștii — vizibilitatea (memory visibility), fără de care nici măcar scrierile „păzite" nu sunt garantat văzute. Capitolul 4 va arăta cum se proiectează clase întregi în jurul politicilor de locking schițate aici, iar 5 ce blocuri gata construite te scutesc de lock-uri manuale. De stăpânit înainte de a merge mai departe: cele două tipare de race, reentranța și regula „un invariant = un lock".
