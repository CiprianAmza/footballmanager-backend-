# Capitolul 03 — Sharing Objects

## Rezumat amplu
Dacă în capitolul 2 sincronizarea însemna atomicitate, aici apare a doua ei jumătate, la fel de importantă și mult mai contraintuitivă: vizibilitatea memoriei. Fără sincronizare, nu există nicio garanție că o scriere făcută de un thread va fi văzută vreodată de altul, nici că va fi văzută în ordinea din cod. Exemplul `NoVisibility` arată un program care poate rula la infinit sau poate tipări zero — reader thread-ul poate să nu vadă niciodată `ready = true`, sau să vadă `ready` fără `number` (reordonare). Cauzele: cache-uri per-core, registre, optimizări de compilator; JMM-ul permite toate acestea în absența sincronizării. De aici, fenomenul de stale data: citiri învechite, care pot livra valori „din trecut", și cazul special al operațiilor pe 64 de biți (`long`/`double` ne-volatile), unde poți citi 32 de biți dintr-o valoare și 32 din alta. Regula practică: locking-ul nu e doar excludere mutuală, ci și garanție de vizibilitate — tot ce a scris un thread înainte să elibereze un lock devine vizibil thread-ului care dobândește apoi același lock; de aceea și citirile trebuie sincronizate. `volatile` e forma slabă de sincronizare: garantează vizibilitate (și interzice reordonarea în jurul accesului), dar nu atomicitate — bun pentru flag-uri de stare, insuficient pentru `count++`. A doua temă majoră: publicarea și evadarea obiectelor. Publici un obiect când îl faci accesibil în afara scope-ului curent (referință statică, return, transmitere către alien method); un obiect „evadează" când e publicat înainte să fie gata, iar cazul cel mai perfid e evadarea lui `this` din constructor (printr-un inner class, un listener înregistrat în constructor sau un thread pornit din constructor) — obiectul e văzut într-o stare parțial construită. Soluția: constructori care nu lasă `this` să scape și factory methods care termină construcția înainte de înregistrarea listener-ilor. A treia temă: dacă nu partajezi, nu ai probleme — thread confinement. Trei forme: ad-hoc (prin convenție, fragilă), stack confinement (variabile locale, referințe care nu scapă din metodă) și `ThreadLocal` (fiecare thread cu propria copie). A patra temă: imutabilitatea — obiectele imutabile (stare fixată în constructor, câmpuri `final`, `this` nu evadează) sunt automat thread-safe și pot fi partajate liber; combinația „referință `volatile` către un obiect imutabil" rezolvă elegant invariantele multi-variabilă (`OneValueCache` pentru servlet-ul de factorizare). Capitolul se încheie cu sinteza: publicare sigură. Un obiect mutabil trebuie publicat printr-un mecanism cu sincronizare — inițializare statică, câmp `volatile`/`AtomicReference`, câmp `final` al unui obiect corect construit, sau un câmp păzit de lock (inclusiv colecțiile thread-safe). Politicile de partajare rezultate: thread-confined, shared read-only (imutabil), shared thread-safe, guarded.

## Concepte explicate

### Vizibilitatea memoriei și stale data
- **Definiție** — fără sincronizare, o scriere a unui thread poate să nu fie văzută (sau să fie văzută în altă ordine) de alte thread-uri; stale data = citirea unei valori învechite.
- **De ce contează** — programele „aproape corecte" fără sincronizare pot să se blocheze la infinit, să livreze valori vechi sau combinații imposibile de valori; bug-urile sunt nedeterministe și dependente de JIT/hardware.
- **Exemplu de cod**
  ```java
  // GREȘIT — echivalentul NoVisibility (Listing 3.1)
  class NoVisibility {
      private static boolean ready;
      private static int number;
      public static void main(String[] a) {
          new Thread(() -> {
              while (!ready) Thread.yield(); // poate nu se termină niciodată
              System.out.println(number);    // poate tipări 0 (reordonare!)
          }).start();
          number = 42;
          ready = true;
      }
  }
  // CORECT: declară ready (și number, sau doar ready dacă publici înainte) volatile,
  // sau sincronizează ambele accese pe același lock.
  ```
- **Capcane frecvente** — „citirea e doar informativă, nu are nevoie de lock" — ba are; a raționa despre ordine „citind codul", ignorând reordonarea.

### Operații ne-atomice pe 64 de biți
- **Definiție** — JMM permite ca citirea/scrierea unui `long`/`double` ne-volatile să fie făcută ca două operații pe 32 de biți.
- **De ce contează** — poți citi o valoare care n-a existat niciodată (jumătăți din scrieri diferite) — mai rău decât stale data.
- **Exemplu de cod**
  ```java
  class Sampler {
      private volatile long lastTimestamp; // volatile => citire/scriere atomică garantată
  }
  ```
- **Capcane frecvente** — a considera safe „măcar citirea" unui `long` partajat nesincronizat.

### volatile
- **Definiție** — variabilă a cărei citire vede întotdeauna ultima scriere (vizibilitate) și în jurul căreia accesele nu se reordonează; NU oferă atomicitate pentru operații compuse.
- **De ce contează** — unealta potrivită pentru flag-uri de stare (shutdown, completare) și pentru publicarea de referințe imutabile; folosită greșit pe read-modify-write, lasă race-uri.
- **Exemplu de cod**
  ```java
  class Worker implements Runnable {
      private volatile boolean shutdown;          // CORECT: un scriitor, mulți cititori
      public void stop() { shutdown = true; }
      public void run() { while (!shutdown) doWork(); }
  }
  // GREȘIT: private volatile int count; ... count++;  // tot read-modify-write neatomic
  ```
- **Capcane frecvente** — `volatile` pe contoare; a crede că `volatile` pe o referință face și obiectul referit thread-safe (nu face).

### Publicare și evadare (escape); evadarea lui this
- **Definiție** — publicare = a face un obiect accesibil în afara scope-ului; evadare = publicare nedorită sau prematură, în special a lui `this` înainte de finalul constructorului.
- **De ce contează** — un obiect văzut parțial construit încalcă orice invariant; chiar și garanțiile `final` cad dacă `this` scapă din constructor.
- **Exemplu de cod**
  ```java
  // GREȘIT — this scapă prin listener înregistrat în constructor (Listing 3.7)
  class ThisEscape {
      ThisEscape(EventSource src) {
          src.registerListener(e -> doSomething(e)); // lambda capturează this-ul nefinalizat
      }
      void doSomething(Event e) { /* poate rula înainte de finalul constructorului */ }
  }
  // CORECT — factory method (Listing 3.8)
  class SafeListener {
      private final EventListener listener;
      private SafeListener() { listener = e -> doSomething(e); }
      static SafeListener newInstance(EventSource src) {
          SafeListener s = new SafeListener();   // construcția se termină complet
          src.registerListener(s.listener);      // abia apoi publicăm
          return s;
      }
  }
  ```
- **Capcane frecvente** — pornirea unui `Thread` din constructor (`start()` în constructor = evadare); inner classes anonime în constructori; a pasa `this` unui framework în constructor.

### Thread confinement (ad-hoc, stack, ThreadLocal)
- **Definiție** — datele accesate de un singur thread nu au nevoie de sincronizare. Ad-hoc: prin convenție; stack: variabile locale care nu scapă; `ThreadLocal`: o copie per thread.
- **De ce contează** — cea mai simplă strategie de thread safety e să nu partajezi deloc; e modelul Swing (totul pe EDT) și al connection-urilor JDBC per-request.
- **Exemplu de cod**
  ```java
  class ConnectionHolder {
      private static final ThreadLocal<Connection> conn =
          ThreadLocal.withInitial(() -> DriverManager.getConnection(URL));
      static Connection get() { return conn.get(); } // fiecare thread, conexiunea lui
  }
  ```
- **Capcane frecvente** — a lăsa o referință „confined" să scape (return, câmp, colecție partajată) — confinement-ul se pierde silențios; abuzul de `ThreadLocal` ca variabilă globală ascunsă; memory leaks cu `ThreadLocal` în thread pool-uri.

### Imutabilitate și câmpuri final
- **Definiție** — un obiect e imutabil dacă starea nu se poate schimba după construcție, toate câmpurile sunt `final` și `this` nu a evadat în constructor. `final` are și semantici de vizibilitate (initialization safety).
- **De ce contează** — obiectele imutabile sunt thread-safe prin natură, pot fi partajate fără sincronizare și fac invariantele multi-variabilă atomice „prin înlocuire".
- **Exemplu de cod**
  ```java
  // Tiparul volatile + imutabil (Listing 3.12/3.13)
  final class Cache {
      private final BigInteger n;
      private final BigInteger[] factors;
      Cache(BigInteger n, BigInteger[] f) { this.n = n; this.factors = f.clone(); }
      BigInteger[] getIfMatch(BigInteger x) { return n.equals(x) ? factors.clone() : null; }
  }
  class Service {
      private volatile Cache cache = new Cache(null, null); // înlocuire atomică a întregului invariant
      void update(BigInteger n, BigInteger[] f) { cache = new Cache(n, f); }
  }
  ```
- **Capcane frecvente** — „aproape imutabil" nu e imutabil (un singur setter strică tot); a uita copierea defensivă a array-urilor/colecțiilor primite sau întoarse.

### Publicare sigură (safe publication)
- **Definiție** — a face un obiect vizibil altor thread-uri astfel încât ele să-i vadă starea complet construită: static initializer, câmp `volatile`/`AtomicReference`, câmp `final` al unui obiect corect construit, câmp păzit de lock sau colecție thread-safe.
- **De ce contează** — chiar și un obiect „corect" poate fi văzut corupt dacă e publicat printr-o simplă asignare ne-sincronizată; safe publication e podul dintre construcție și partajare.
- **Exemplu de cod**
  ```java
  // GREȘIT (Listing 3.14): public Holder holder; ... holder = new Holder(42);
  // alt thread poate vedea holder != null dar cu câmpuri neinițializate
  // CORECT: oricare din mecanisme, de ex.
  private static final Holder eager = new Holder(42);         // static init
  private final BlockingQueue<Holder> q = new LinkedBlockingQueue<>(); // handoff prin colecție thread-safe
  ```
- **Capcane frecvente** — publicarea prin `HashMap` simplu partajat; a crede că `null`-check-ul din `assertSanity` (Listing 3.15) nu poate eșua — poate.

## Listing-uri cheie din carte
- **Listing 3.1 — NoVisibility (Don't)**: demonstrează că fără sincronizare nici terminarea, nici ordinea nu sunt garantate; cel mai important listing din capitol.
- **Listing 3.2/3.3 — MutableInteger ne-safe vs. SynchronizedInteger**: arată că și get-ul trebuie sincronizat, nu doar set-ul.
- **Listing 3.4 — Counting Sheep**: uz canonic de `volatile` ca flag de ieșire din buclă.
- **Listing 3.5–3.8 — publicare & escape**: de la publicarea simplă la evadarea internelor (`UnsafeStates`), evadarea lui `this` (`ThisEscape`) și fix-ul cu factory method (`SafeListener`).
- **Listing 3.9/3.10 — stack confinement și ThreadLocal**: cele două forme robuste de confinement.
- **Listing 3.11–3.13 — ThreeStooges, OneValueCache, VolatileCachedFactorizer**: construcția tiparului „volatile + imutabil" care rezolvă cache-ul din capitolul 2 fără locking.
- **Listing 3.14/3.15 — publicare improprie și Holder**: ce înseamnă concret să vezi un obiect parțial construit.

## Citate
Ideea centrală (secțiunea 3.1, parafrazată): sincronizarea nu e doar despre atomicitate — e în egală măsură despre vizibilitatea memoriei; fără ea, nu există garanții nici de „când", nici de „dacă". Despre imutabilitate (secțiunea 3.4, parafrazată): obiectele imutabile sunt întotdeauna thread-safe. Despre publicare (3.2, parafrazată): nu lăsa `this` să scape din constructor.

## Legături
Completează capitolul 2 (atomicitate) cu vizibilitatea — abia împreună definesc „sincronizarea". Tiparele de aici (confinement, imutabilitate, safe publication, guarding) devin în capitolul 4 strategii de proiectare a claselor thread-safe. `volatile` și `final` primesc explicația formală (happens-before, initialization safety) abia în capitolul 16 — capitolul 3 e versiunea „reguli practice" a JMM-ului. De stăpânit înainte de a continua: de ce și citirile cer sincronizare, cele patru mecanisme de safe publication și tiparul volatile+imutabil.
