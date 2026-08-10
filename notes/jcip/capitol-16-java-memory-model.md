# Capitolul 16 — The Java Memory Model

## Rezumat amplu
Ultimul capitol dă fundamentul teoretic pentru toate regulile practice folosite până acum: Java Memory Model (JMM) — contractul care spune ce valori are voie să vadă o citire. Motivația: JVM-urile, compilatoarele și procesoarele fac reordonări, cache-uri și optimizări agresive; fără un model formal, „ce vede alt thread" ar depinde de arhitectură. JMM specifică minimul garantat, lăsând loc pentru optimizări. Piesa centrală e relația happens-before: dacă acțiunea A happens-before acțiunea B, atunci efectele lui A sunt garantat vizibile pentru B; dacă două acțiuni nu sunt ordonate de happens-before, runtime-ul le poate reordona liber — iar dacă amândouă ating aceeași variabilă și cel puțin una scrie, ai un data race și rezultatul poate fi orice. Regulile happens-before: ordinea programului într-un thread; eliberarea unui monitor happens-before orice achiziție ulterioară a ACELUIAȘI monitor; o scriere `volatile` happens-before orice citire ulterioară a aceleiași variabile; `Thread.start()` happens-before orice acțiune din thread-ul pornit; orice acțiune dintr-un thread happens-before revenirea unui `join()` pe el; întreruperea unui thread happens-before detectarea întreruperii; finalul constructorului happens-before finalizarea obiectului; plus tranzitivitatea. Consecință practică: sincronizarea nu e „doar pentru datele partajate din acel bloc" — dobândirea unui lock face vizibile TOATE scrierile de dinaintea eliberării lui, ceea ce permite piggybacking: te bazezi pe ordonarea creată de un mecanism de sincronizare ca să publici alte date (`FutureTask` și `BlockingQueue` fac asta intern — punerea în coadă happens-before extragerea; de aici garanția de safe publication a colecțiilor thread-safe din capitolul 3). Un exemplu-cheie de „aproape corect": Listing 16.1 arată cum lipsa sincronizării permite rezultate care par imposibile citind codul. A doua mare temă: publicarea, tratată acum riguros. Lazy initialization nesincronizată (Listing 16.3) e nesigură nu doar prin dubla instanțiere, ci pentru că alt thread poate vedea referința nenulă și obiectul parțial construit. Soluțiile ordonate: inițializare sincronizată (simplă, corectă), eager initialization (JVM-ul garantează ordinea la încărcarea clasei), lazy initialization holder class idiom (clasa internă se încarcă la prima folosire — leneș ȘI fără sincronizare, pentru că JVM-ul sincronizează încărcarea claselor). Contra-exemplul faimos: double-checked locking — un antipattern; verificarea nesincronizată poate vedea o referință publicată dar un obiect incomplet; pe Java 5+ merge doar dacă referința e `volatile`, dar oricum nu mai are rost, holder idiom-ul fiind mai simplu. Ultima piesă: initialization safety — garanția specială pentru obiectele imutabile: dacă toate câmpurile sunt `final` și `this` nu evadează din constructor, obiectul poate fi partajat FĂRĂ sincronizare, iar valorile finale (și tot ce e accesibil din ele) sunt văzute corect de orice thread, indiferent cum a ajuns referința la el. Asta explică formal de ce `String` și obiectele imutabile din capitolul 3 se pot publica oricum — dar garanția se aplică doar câmpurilor `final` și doar dacă obiectul e cu adevărat imutabil.

## Concepte explicate

### Happens-before
- **Definiție** — relație de ordonare parțială între acțiuni: dacă A happens-before B, efectele lui A sunt vizibile lui B și nu pot fi reordonate față de el; fără ea, orice reordonare e permisă.
- **De ce contează** — e definiția operațională a „sincronizării corecte"; toate regulile practice (lock, volatile, start/join) sunt cazuri particulare.
- **Exemplu de cod**
  ```java
  // Lanț happens-before prin volatile: scrierea lui `ready` publică și `data`
  int data;                    // NU volatile
  volatile boolean ready;      // volatile: bariera
  // thread A:
  data = 42;                   // (1)
  ready = true;                // (2) — scriere volatile
  // thread B:
  if (ready)                   // (3) — citire volatile: (2) hb (3)
      use(data);               // garantat vede 42, pentru că (1) hb (2) hb (3)
  ```
- **Capcane frecvente** — a crede că `volatile` protejează doar variabila marcată (protejează și tot ce a fost scris înainte de ea, prin lanț hb); a raționa despre ordine fără să identifici perechea de acțiuni care creează relația.

### Data race și „program corect sincronizat"
- **Definiție** — data race: două accese la aceeași variabilă neordonate de happens-before, cel puțin unul scriere. Un program corect sincronizat nu are data race-uri și se comportă secvențial consistent.
- **De ce contează** — e criteriul binar: fie ai sincronizare suficientă și poți raționa simplu, fie ai un data race și nu poți raționa deloc (rezultatele nu trebuie să fie „explicabile").
- **Exemplu de cod**
  ```java
  // Data race clasic: unul scrie, altul citește, nicio relație hb
  boolean stop;                                // fără volatile
  // thread A: while (!stop) work();           // poate fi optimizat în while(true)!
  // thread B: stop = true;                    // poate să nu fie văzut niciodată
  ```
- **Capcane frecvente** — „am testat, merge" pe un JIT care încă n-a optimizat bucla; hoisting-ul citirii în afara buclei e o transformare perfect legală pentru compilator.

### Piggybacking pe ordonarea de sincronizare
- **Definiție** — folosirea unei relații happens-before create de un mecanism (coadă thread-safe, latch, FutureTask, lock) ca să garantezi vizibilitatea unor date care nu sunt ele însele sincronizate.
- **De ce contează** — explică de ce e sigur să pui un obiect mutabil într-o `BlockingQueue` și să-l citești în alt thread; e mecanismul din spatele safe publication (cap. 3).
- **Exemplu de cod**
  ```java
  // Publicare sigură prin coadă: put hb take
  Report r = buildReport();          // scrieri „obișnuite"
  queue.put(r);                      // punerea în coada thread-safe
  // alt thread:
  Report r2 = queue.take();          // vede raportul complet construit
  ```
- **Capcane frecvente** — piggybacking pe garanții nedocumentate (implementarea se poate schimba); a atinge obiectul după ce l-ai pus în coadă (rupe atât hb-ul util, cât și confinement-ul).

### Lazy initialization: variantele corecte
- **Definiție** — patru soluții: sincronizată, eager, holder class idiom, double-checked locking cu `volatile` (nerecomandat).
- **De ce contează** — e cel mai frecvent loc unde publicarea nesigură apare în cod real; holder idiom-ul dă lenevire + zero sincronizare la citire, sprijinit de garanțiile JVM pentru încărcarea claselor.
- **Exemplu de cod**
  ```java
  // CORECT și recomandat — holder class idiom (Listing 16.6)
  class ResourceFactory {
      private static class Holder { static final Resource resource = new Resource(); }
      static Resource getResource() { return Holder.resource; }  // init la prima atingere, thread-safe „gratis"
  }
  // ANTIPATTERN — double-checked locking (Listing 16.7)
  // if (instance == null) { synchronized (lock) { if (instance == null) instance = new Resource(); } }
  // fără `volatile` pe instance: alt thread poate vedea referința, dar obiectul incomplet
  ```
- **Capcane frecvente** — DCL fără `volatile` (bug clasic, invizibil la testare); a alege eager initialization pentru resurse scumpe rar folosite.

### Initialization safety (obiecte imutabile)
- **Definiție** — garanție specială: pentru un obiect corect construit (toate câmpurile `final`, `this` nu evadează), valorile finale și tot ce e accesibil din ele sunt vizibile corect oricărui thread, chiar publicat fără sincronizare.
- **De ce contează** — e temeiul formal al regulii „obiectele imutabile sunt thread-safe și se pot partaja liber"; și motivul pentru care `String` sau un record imutabil nu au nevoie de precauții.
- **Exemplu de cod**
  ```java
  // Sigur de publicat oricum (Listing 16.8 în spirit)
  final class SafeStates {
      private final Map<String, String> states;      // câmp final...
      SafeStates() {
          Map<String, String> m = new HashMap<>();
          m.put("RO", "Romania");
          states = Map.copyOf(m);                     // ...cu conținut imutabil
      }                                               // this nu evadează
      String get(String k) { return states.get(k); }
  }
  ```
- **Capcane frecvente** — un singur câmp ne-`final` anulează garanția pentru acel câmp; câmp `final` care referă un obiect MUTABIL (garanția acoperă referința, nu mutațiile ulterioare); `this` scăpat în constructor (cap. 3) — anulează totul.

## Listing-uri cheie din carte
- **Listing 16.1 — PossibleReordering (Don't)**: programul insuficient sincronizat cu rezultate „imposibile"; demonstrația vie a reordonării.
- **Listing 16.2 — inner class din FutureTask**: piggybacking real în bibliotecă — cum se folosește ordonarea de sincronizare ca să publice rezultatul.
- **Listing 16.3 — lazy initialization nesigură (Don't)**: publicarea unui obiect parțial construit.
- **Listing 16.4/16.5 — inițializare sincronizată și eager**: cele două soluții simple și corecte.
- **Listing 16.6 — holder class idiom**: soluția recomandată — lazy + thread-safe fără sincronizare explicită.
- **Listing 16.7 — double-checked locking (antipattern)**: de citit ca avertisment istoric, nu ca rețetă.
- **Listing 16.8 — SafeStates**: initialization safety pentru obiecte imutabile.

## Citate
Regula centrală (16.1.3, parafrazată): două acțiuni neordonate de happens-before pot fi reordonate liber; când ating aceeași variabilă și una scrie, programul are un data race și nu mai are comportament predictibil. Despre imutabilitate (16.3, parafrazată): obiectele corect construite, cu toate câmpurile final, pot fi partajate în siguranță fără sincronizare, indiferent cum sunt publicate.

## Legături
Închide cercul deschis în capitolul 3: tot ce era acolo „regulă practică" (volatile, safe publication, imutabilitate, `this` care nu evadează) primește aici justificarea formală. Explică ce garantează CAS-ul și `volatile` din capitolul 15 și de ce protocolul wait/notify din capitolul 14 e corect. Nu e un capitol de care ai nevoie ca să scrii cod bun — dacă respecți regulile din capitolele 2–5, JMM-ul lucrează pentru tine — dar e cel care îți spune de ce funcționează și unde sunt marginile. De stăpânit: happens-before și regulile lui, definiția data race-ului, holder idiom-ul și condițiile exacte ale initialization safety.
