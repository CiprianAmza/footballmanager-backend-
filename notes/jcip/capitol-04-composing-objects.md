# Capitolul 04 — Composing Objects

## Rezumat amplu
Capitolul face saltul de la mecanisme (lock-uri, volatile, publicare) la proiectare: cum construiești clase întregi thread-safe și, mai ales, cum compui componente thread-safe în componente mai mari fără să reiei analiza de la zero. Rețeta de design în trei pași: identifică variabilele care formează starea obiectului (inclusiv starea obiectelor referite, dacă le deții), identifică invarianții care o constrâng, apoi stabilește o politică de sincronizare — cine, cu ce lock, păzește ce — și documenteaz-o. Analiza pornește de la invariante și postcondiții: invarianții care leagă mai multe variabile cer ca toate operațiile care le ating să fie atomice sub același lock; postcondițiile („starea următoare depinde de cea curentă") fac operațiile read-modify-write; precondițiile („nu poți face take pe coadă goală") duc la operații state-dependent, amânate pentru capitolul 14. Contează și ownership-ul: cine deține starea decide politica de locking; ownership-ul se poate transfera (publicare) sau împărți (split ownership la colecții — colecția deține infrastructura, clientul conținutul). Prima strategie concretă: instance confinement — starea e închisă în obiect, accesibilă doar prin metodele lui, care aplică consistent lock-ul; așa devin utile chiar și obiecte ne-thread-safe (un `HashSet` închis într-o clasă sincronizată). Java monitor pattern e forma canonică: toată starea mutabilă păzită de lock-ul intrinsec al obiectului; varianta cu lock privat (`private final Object lock`) împiedică clienții să interfereze cu politica de sincronizare. Exemplul condu­cător al capitolului — vehicle tracker (flota de vehicule cu locații) — e implementat în trei feluri: monitor-based (copiere defensivă adâncă, corect dar scump), apoi prin delegare către `ConcurrentHashMap` cu valori imutabile (`Point` imutabil — starea devine „de unica folosință", se înlocuiește, nu se mută), apoi cu valori mutabile dar thread-safe (`SafePoint`) publicate deliberat, obținând o vedere live. Delegarea funcționează când starea e o singură variabilă thread-safe sau mai multe variabile independente; eșuează când există invarianți între variabile (`NumberRange` cu lower ≤ upper deasupra a două `AtomicInteger` e stricat — check-then-act neatomic) — atunci clasa compusă trebuie să-și aducă propriul lock. Ultimele secțiuni atacă extinderea claselor thread-safe existente cu operații compuse noi (put-if-absent peste o listă sincronizată): extinderea clasei (fragil — politica de lock e împrăștiată), client-side locking (și mai fragil — trebuie să ghicești lock-ul intern și să te ții după el), și compoziția (wrapper cu propriul lock, ca `Collections.synchronizedList` — robust, cu costul unui nivel de locking în plus). Concluzia practică: documentează garanțiile de thread safety pentru clienți și politica de sincronizare pentru mentenanță — sincronizarea nedocumentată e sincronizare pierdută.

## Concepte explicate

### Politica de sincronizare (synchronization policy)
- **Definiție** — regulile care spun ce combinație de confinement, imutabilitate și locking menține invarianții clasei: ce variabile sunt păzite de ce lock-uri.
- **De ce contează** — fără o politică explicită, fiecare modificare de cod re-negociază implicit sincronizarea și o erodează; documentarea (`@GuardedBy`) e parte din corectitudine.
- **Exemplu de cod**
  ```java
  class Ledger {
      private final Object lock = new Object();
      // @GuardedBy("lock") — ambele variabile, un singur invariant: balance == sum(entries)
      private final List<Entry> entries = new ArrayList<>();
      private long balance;
      void post(Entry e) { synchronized (lock) { entries.add(e); balance += e.amount(); } }
  }
  ```
- **Capcane frecvente** — invarianți nedeclarați (nimeni nu știe că `balance` trebuie să corespundă cu `entries`); politici diferite în metode diferite ale aceleiași clase.

### Instance confinement + Java monitor pattern
- **Definiție** — starea (eventual ne-thread-safe) e închisă în obiect și accesată exclusiv prin metode care țin un lock unic; monitor pattern = acel lock e lock-ul intrinsec (sau unul privat).
- **De ce contează** — transformă obiecte ne-safe în blocuri sigure și face analiza locală: verifici o singură clasă, nu tot programul.
- **Exemplu de cod**
  ```java
  class PersonSet {
      private final Object lock = new Object();      // lock privat: clienții nu pot interfera
      private final Set<Person> people = new HashSet<>(); // HashSet ne-safe, dar confined
      void add(Person p)      { synchronized (lock) { people.add(p); } }
      boolean contains(Person p) { synchronized (lock) { return people.contains(p); } }
  }
  ```
- **Capcane frecvente** — scăparea referinței interne (return `people` direct, iterator, `this` în listener) anulează confinement-ul; `synchronized(this)` expune lock-ul — un client care ține lock-ul obiectului tău îți poate bloca toate metodele.

### Delegarea thread safety-ului
- **Definiție** — clasa își reazemă thread safety-ul pe componente deja thread-safe (ex. `ConcurrentHashMap`), fără sincronizare proprie.
- **De ce contează** — mai puțin cod de sincronizare de întreținut și mai multă concurență; dar e validă doar dacă nu există invarianți peste componente și nici tranziții de stare invalide.
- **Exemplu de cod**
  ```java
  // CORECT — delegare la o singură componentă thread-safe, valori imutabile
  class Tracker {
      private final ConcurrentMap<String, Point> locations = new ConcurrentHashMap<>();
      Point get(String id) { return locations.get(id); }            // Point imutabil => safe
      void set(String id, int x, int y) { locations.put(id, new Point(x, y)); } // înlocuire, nu mutare
  }
  // GREȘIT — invariant între două componente „thread-safe" (echivalentul NumberRange, Listing 4.10)
  class BadRange {
      private final AtomicInteger lower = new AtomicInteger();
      private final AtomicInteger upper = new AtomicInteger();
      void setLower(int i) { if (i <= upper.get()) lower.set(i); } // check-then-act neatomic!
  }
  ```
- **Capcane frecvente** — exact `BadRange`: „am folosit doar clase atomice, deci e safe"; delegarea către mai multe variabile care nu sunt cu adevărat independente.

### Publicarea variabilelor de stare
- **Definiție** — o componentă a stării poate fi publicată (dată clienților) doar dacă e thread-safe, nu participă la invarianți ai clasei și nu are tranziții interzise de stare.
- **De ce contează** — permite vederi „live" (mutable `SafePoint` în tracker) în loc de copii; făcută greșit, dă clienților puterea de a strica invarianții.
- **Exemplu de cod**
  ```java
  class SafePoint {                       // mutable, dar thread-safe, cu get atomic pe pereche
      private int x, y;
      synchronized int[] get() { return new int[]{x, y}; }
      synchronized void set(int x, int y) { this.x = x; this.y = y; }
  }
  // Tracker-ul poate publica SafePoint-urile: clientul vede update-uri live, consistente
  ```
- **Capcane frecvente** — get-eri separați `getX()`/`getY()` pe stare mutabilă (poți citi x dintr-o stare și y din alta); publicarea unei componente care participă la un invariant.

### Extinderea claselor thread-safe: extindere vs. client-side locking vs. compoziție
- **Definiție** — trei căi de a adăuga operații compuse (ex. put-if-absent): subclasarea clasei, sincronizarea în client pe lock-ul colecției, sau un wrapper care re-sincronizează totul pe propriul lock.
- **De ce contează** — primele două cuplează codul tău de politica de lock a altcuiva (care se poate schimba silențios); compoziția e robustă pentru că nu presupune nimic despre interior.
- **Exemplu de cod**
  ```java
  // GREȘIT — lock pe obiectul greșit (echivalentul Listing 4.14)
  class BadHelper<E> {
      private final List<E> list = Collections.synchronizedList(new ArrayList<>());
      public synchronized boolean putIfAbsent(E x) {   // lock pe helper, lista folosește ALT lock
          boolean absent = !list.contains(x);
          if (absent) list.add(x);
          return absent;
      }
  }
  // CORECT — client-side locking: același lock ca lista (fragil, dar corect)
  public boolean putIfAbsent(E x) {
      synchronized (list) { boolean a = !list.contains(x); if (a) list.add(x); return a; }
  }
  // MAI BINE — compoziție: wrapper care deține lista și propriul lock pentru TOATE operațiile
  ```
- **Capcane frecvente** — sincronizarea pe `this` al helper-ului crezând că protejezi lista; a uita că `Collections.synchronizedList` se sincronizează pe obiectul-listă însuși (detaliu de implementare documentat, dar ușor de ratat).

## Listing-uri cheie din carte
- **Listing 4.1 — Counter (monitor pattern)**: forma canonică a Java monitor pattern.
- **Listing 4.2 — PersonSet**: instance confinement peste un `HashSet` ne-safe.
- **Listing 4.3 — lock privat**: de ce un `private final Object` e preferabil lock-ului intrinsec expus.
- **Listing 4.4–4.5 — MonitorVehicleTracker + MutablePoint**: varianta monitor cu deep copy; corectă, dar costisitoare și cu date „snapshot".
- **Listing 4.6–4.7 — Point imutabil + DelegatingVehicleTracker**: delegare curată către `ConcurrentHashMap`.
- **Listing 4.9 — VisualComponent**: delegare către mai multe variabile independente (două liste de listeners).
- **Listing 4.10 — NumberRange (Don't)**: contra-exemplul central — delegarea eșuează când există invariant între variabile.
- **Listing 4.11–4.12 — SafePoint + PublishingVehicleTracker**: publicarea sigură a stării mutabile thread-safe.
- **Listing 4.13–4.16 — put-if-absent în patru variante**: extindere, tentativă greșită, client-side locking, compoziție — scara de robustețe a capitolului.

## Citate
Sinteza capitolului (secțiunea 4.1, parafrazată): nu poți asigura thread safety fără să știi exact care e starea obiectului și ce invarianți o constrâng — design-ul concurent începe cu inventarierea stării. Despre delegare (4.3.3, parafrazată): dacă o clasă are invarianți care leagă mai multe variabile de stare, delegarea nu e suficientă — clasa trebuie să-și aducă propria sincronizare.

## Legături
Aplică la nivel de clasă tot ce au construit capitolele 2–3: guarding (cap. 2), confinement, imutabilitate și safe publication (cap. 3). Pregătește direct capitolul 5: colecțiile sincronizate/concurente de acolo sunt exact „componentele thread-safe" către care delegi, iar problema operațiilor compuse (put-if-absent) e rezolvată nativ de `ConcurrentMap`. Operațiile state-dependent amânate aici se tratează în capitolul 14 (condition queues). De stăpânit: diferența delegare validă vs. invariant multi-variabilă și de ce compoziția bate client-side locking-ul.
