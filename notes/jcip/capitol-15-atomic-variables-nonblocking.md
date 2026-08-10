# Capitolul 15 — Atomic Variables and Nonblocking Synchronization

## Rezumat amplu
Capitolul coboară sub nivelul lock-urilor și arată din ce sunt făcute clasele concurente moderne: instrucțiuni atomice hardware și algoritmi nonblocking construiți pe ele. Motivația e limita lock-urilor: la contenție, un thread care nu obține lock-ul e suspendat, iar suspendarea/reluarea costă context switch-uri și trafic de cache; mai grav, cu lock-uri o întârziere a deținătorului (page fault, preemptare, chiar deces) blochează pe toți ceilalți — lock-urile nu sunt tolerante la întârzieri, iar prioritatea joasă a deținătorului poate produce priority inversion. Alternativa e sincronizarea optimistă: încearcă operația și verifică la scriere dacă nimeni nu a intervenit între timp. Primitivă: compare-and-swap (CAS) — „dacă valoarea de la adresa V este A, pune B; oricum, spune-mi ce era acolo" — o instrucțiune atomică prezentă pe practic toate procesoarele moderne (cmpxchg pe x86), pe care JVM-ul o expune prin clasele atomice. Tiparul canonic: citește valoarea curentă, calculează noua valoare, încearcă CAS, iar la eșec reia bucla (eșecul înseamnă că altcineva a reușit — deci sistemul a progresat, spre deosebire de deadlock). Costul: CAS necontestat e mult mai ieftin decât un lock necontestat; sub contenție moderată CAS bate lock-urile pentru că eșecul nu suspendă thread-ul, doar îl face să reîncerce; la contenție foarte mare CAS-urile pot irosi cicluri, dar în practică rămân competitive. Nota istorică: până în Java 5 CAS nu era accesibil din cod Java (doar JVM-ul îl folosea intern); `java.util.concurrent.atomic` l-a deschis, iar întreaga bibliotecă (AQS, ConcurrentHashMap, ConcurrentLinkedQueue) e construită pe el. Clasele atomice: scalare (`AtomicInteger`, `AtomicLong`, `AtomicBoolean`, `AtomicReference`), plus variante field-updater, array și „stamped/marked reference" (`AtomicStampedReference`/`AtomicMarkableReference` — un contor sau un bit atașat referinței, exact antidotul pentru problema ABA: o valoare care a fost A, apoi B, apoi iar A poate păcăli un CAS naiv). Comparație instructivă: un generator de numere pseudo-aleatoare implementat cu ReentrantLock vs. cu AtomicInteger — atomicele scalează mai bine, mai ales când există „muncă locală" între accese (contenția realistă contează, exact avertismentul din capitolul 12). A doua jumătate construiește algoritmi nonblocking veritabili: definiția — un algoritm e nonblocking dacă eșecul sau suspendarea oricărui thread nu poate împiedica progresul celorlalți; lock-free — la fiecare pas, cel puțin un thread progresează. Exemple: contor nonblocking (bucla CAS), stivă Treiber (push/pop prin CAS pe vârf, cu noduri imutabile), și coada Michael-Scott (`ConcurrentLinkedQueue`), unde apare tehnica-cheie a algoritmilor lock-free: pentru că nu poți actualiza atomic două referințe (tail și next), algoritmul acceptă stări intermediare vizibile și cere ca ORICE thread care le observă să le „repare" (helping) înainte să-și facă treaba — invariantul devine „coada e fie în stare stabilă, fie în stare intermediară recunoscută, pe care oricine o poate finaliza". Finalul: atomic field updaters (actualizare CAS pe câmpuri `volatile` obișnuite prin reflecție — folosite în ConcurrentLinkedQueue ca să evite un obiect AtomicReference per nod), problema ABA cu soluția version-stamping, și încheierea: aceste tehnici sunt materia primă a bibliotecii — le folosești în mod normal prin `java.util.concurrent`, nu scriindu-ți proprii algoritmi lock-free.

## Concepte explicate

### Compare-and-swap (CAS)
- **Definiție** — operație atomică hardware: compară conținutul unei locații cu o valoare așteptată și, doar dacă sunt egale, o înlocuiește; raportează ce a găsit (sau succes/eșec).
- **De ce contează** — e primitiva pe care se construiește tot ce e nonblocking; permite read-modify-write atomic fără suspendare, deci fără riscul ca un thread întârziat să blocheze restul.
- **Exemplu de cod**
  ```java
  // Semantica CAS, exprimată sincronizat (echivalentul Listing 15.1)
  class SimulatedCAS {
      private int value;
      synchronized int compareAndSwap(int expected, int newValue) {
          int old = value;
          if (old == expected) value = newValue;
          return old;               // apelantul compară cu expected ca să afle dacă a reușit
      }
  }
  ```
- **Capcane frecvente** — a presupune că un CAS reușit înseamnă „nimeni n-a atins valoarea" (vezi ABA); a construi bucle CAS peste operații scumpe (fiecare eșec repetă calculul).

### Bucla CAS (sincronizare optimistă)
- **Definiție** — citește, calculează, încearcă CAS, repetă la eșec; eșecul nu e eroare, e semnalul că altcineva a progresat.
- **De ce contează** — obține atomicitate fără lock; e exact ce fac `incrementAndGet`, `updateAndGet`, `accumulateAndGet`.
- **Exemplu de cod**
  ```java
  // Contor nonblocking (spiritul Listing 15.2)
  class CasCounter {
      private final AtomicInteger value = new AtomicInteger();
      int increment() {
          int v;
          do { v = value.get(); } while (!value.compareAndSet(v, v + 1));  // reia până reușește
          return v + 1;
      }
  }
  // Invariant multi-variabilă cu CAS: pune-le într-un obiect imutabil (Listing 15.3)
  record Pair(int lower, int upper) {}
  AtomicReference<Pair> range = new AtomicReference<>(new Pair(0, 0));
  void setLower(int i) {
      Pair cur, next;
      do { cur = range.get();
           if (i > cur.upper()) throw new IllegalArgumentException();
           next = new Pair(i, cur.upper());
      } while (!range.compareAndSet(cur, next));   // înlocuire atomică a ÎNTREGII stări
  }
  ```
- **Capcane frecvente** — bucla fără re-citire a valorii la fiecare iterație (CAS etern eșuat); efecte secundare în corpul buclei (se repetă la fiecare retry — bucla trebuie să fie pură).

### Clasele atomice și când bat lock-urile
- **Definiție** — `AtomicInteger/Long/Boolean/Reference` + variante array/field-updater; „volatile cu operații atomice".
- **De ce contează** — pentru contoare, statistici, referințe de stare imutabilă și acumulatori sunt mai rapide și mai scalabile decât lock-urile; nu suspendă thread-uri, deci nici deadlock, nici priority inversion.
- **Exemplu de cod**
  ```java
  // Alegere corectă: o singură variabilă, operație simplă
  private final AtomicLong requests = new AtomicLong();
  void onRequest() { requests.incrementAndGet(); }
  // Java modern, pentru contoare foarte fierbinți: LongAdder (striping intern, cf. cap. 11)
  ```
- **Capcane frecvente** — atomice multiple cu invariant comun (cap. 4 — `NumberRange`); folosirea lor pentru secvențe complexe unde un lock ar fi mai simplu și la fel de rapid.

### Problema ABA
- **Definiție** — valoarea se schimbă din A în B și înapoi în A între citirea ta și CAS; CAS-ul reușește deși starea logică s-a schimbat (nod reciclat, listă modificată).
- **De ce contează** — corupe algoritmii care deduc „neschimbat" din „aceeași valoare"; apare tipic la structuri cu noduri reutilizate.
- **Exemplu de cod**
  ```java
  // Antidot: atașează o versiune referinței
  AtomicStampedReference<Node> top = new AtomicStampedReference<>(head, 0);
  int[] stampHolder = new int[1];
  Node cur = top.getReference(); int stamp = top.getStamp();
  // ... calcul ...
  top.compareAndSet(cur, next, stamp, stamp + 1);   // reușește doar dacă NICI versiunea nu s-a schimbat
  ```
- **Capcane frecvente** — a ignora ABA pentru că „în Java avem GC" (adevărat pentru multe cazuri, fals când reciclezi noduri sau codifici stare în referință).

### Algoritmi nonblocking: lock-free, stiva Treiber, coada Michael-Scott, helping
- **Definiție** — nonblocking: eșecul/întârzierea unui thread nu blochează progresul altora; lock-free: la orice moment, cel puțin un thread avansează. Helping: un thread care găsește structura într-o stare intermediară o finalizează în locul celui întârziat.
- **De ce contează** — explică de ce `ConcurrentLinkedQueue` e rapidă și rezistentă la întârzieri; și de ce nu-ți scrii singur asemenea algoritmi (corectitudinea lor e la limita a ce se poate dovedi manual).
- **Exemplu de cod**
  ```java
  // Stivă Treiber (spiritul Listing 15.6): un singur punct de mutație — vârful
  class TreiberStack<E> {
      private static final class Node<E> { final E item; Node<E> next; Node(E i) { item = i; } }
      private final AtomicReference<Node<E>> top = new AtomicReference<>();
      public void push(E item) {
          Node<E> n = new Node<>(item), cur;
          do { cur = top.get(); n.next = cur; } while (!top.compareAndSet(cur, n));
      }
      public E pop() {
          Node<E> cur, next;
          do { cur = top.get(); if (cur == null) return null; next = cur.next; }
          while (!top.compareAndSet(cur, next));
          return cur.item;
      }
  }
  ```
- **Capcane frecvente** — a crede că poți extinde simplu tiparul la structuri cu două puncte de mutație (coada cere protocolul de helping); a testa asemenea cod doar „la mine pe laptop" (vezi cap. 12).

### Atomic field updaters
- **Definiție** — actualizare CAS pe câmpuri `volatile` obișnuite ale unei clase, prin obiecte updater bazate pe reflecție.
- **De ce contează** — economisește un obiect atomic per instanță acolo unde contează (milioane de noduri); folosit intern de `ConcurrentLinkedQueue`.
- **Exemplu de cod**
  ```java
  private static final AtomicReferenceFieldUpdater<Node, Node> NEXT =
      AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "next");
  // NEXT.compareAndSet(node, expectedNext, newNext);   // CAS pe un câmp volatile normal
  ```
- **Capcane frecvente** — optimizare prematură (complică codul pentru un câștig care contează doar la scară); câmpul trebuie să fie `volatile` și accesibil — altfel eșec la runtime. În Java modern, `VarHandle` e succesorul recomandat.

## Listing-uri cheie din carte
- **Listing 15.1 — CAS simulat**: semantica primitivei, exprimată în Java lizibil; punctul de plecare conceptual.
- **Listing 15.2 — CasCounter**: bucla CAS canonică; de reținut ca șablon.
- **Listing 15.3 — invariant multi-variabilă cu CAS pe obiect imutabil**: rezolvarea nonblocking a problemei `NumberRange` din capitolul 4.
- **Listing 15.4/15.5 — PRNG cu ReentrantLock vs. AtomicInteger**: comparația care motivează atomicele, cu „muncă locală" pentru contenție realistă.
- **Listing 15.6 — stiva Treiber**: cel mai simplu algoritm lock-free complet; de studiat linie cu linie.
- **Listing 15.7 — inserția în coada Michael-Scott**: stările intermediare și helping-ul — ideea centrală a algoritmilor lock-free cu mai multe puncte de mutație.
- **Listing 15.8 — atomic field updaters în ConcurrentLinkedQueue**: optimizarea de memorie folosită de bibliotecă.

## Citate
Definiția de lucru (15.4, parafrazată): un algoritm e nonblocking dacă eșecul sau suspendarea oricărui thread nu poate împiedica alt thread să progreseze. Despre limita lock-urilor (15.1, parafrazată): dacă deținătorul lock-ului e întârziat — page fault, preemptare, orice — niciun thread care are nevoie de acel lock nu mai avansează.

## Legături
Explică fundația capitolului 14 (`compareAndSetState` din AQS e exact CAS-ul de aici) și de ce structurile din capitolul 5 scalează așa cum am văzut în capitolul 11. Rezolvă cu alte mijloace problema invarianților multi-variabilă din capitolul 4 (obiect imutabil + AtomicReference). Ultima piesă lipsă — de ce funcționează `volatile` și ce garantează exact CAS-ul la nivel de memorie — vine în capitolul 16 (Java Memory Model). De stăpânit: bucla CAS, ABA + version-stamping și distincția lock-free vs. blocking.
