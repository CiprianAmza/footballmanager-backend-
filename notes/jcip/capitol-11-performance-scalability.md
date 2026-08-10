# Capitolul 11 — Performance and Scalability

## Rezumat amplu
Capitolul disciplinează discuția despre „rapid": definește termenii, demontează intuițiile și dă un meniu concret de tehnici de reducere a contenției pe lock-uri. Distincția fundamentală: performanță „cât de repede" (latency, service time) vs. „cât de mult" (throughput, capacity) — iar scalabilitatea e a doua: capacitatea de a crește throughput-ul când adaugi resurse (core-uri, memorie). Cele două se bat cap în cap: tehnicile de single-thread performance (cache-uri, batching) adesea strică scalabilitatea, și invers — designurile scalabile (pipeline-uri de task-uri, cozi) fac fiecare cerere individuală mai scumpă. Pentru aplicații server, cartea alege explicit scalabilitatea. Disciplina evaluării: evită optimizarea prematură — fă întâi corect, apoi rapid, și doar pe bază de măsurători, nu de intuiție („cheia încrucișată": presupunerile despre unde e bottleneck-ul sunt de obicei greșite); orice optimizare are întrebările ei — ce înseamnă „mai rapid", în ce condiții, cât de des apare cazul, cu ce cost în complexitate. Legea lui Amdahl pune plafonul: speedup ≤ 1 / (F + (1−F)/N), unde F e fracția serială — cu 10% cod serial, maximum 10× oricâte core-uri ai; și, subtil, serializarea se ascunde în locuri nevăzute: coada comună a unui pool de thread-uri e cod serial, la fel handoff-ul rezultatelor — comparația `synchronizedLinkedList` vs `ConcurrentLinkedQueue` arată cum structura de date decide fracția serială. Costurile concurenței: context switch-uri (10k+ cicluri, cache-uri golite), sincronizarea cu efecte de vizibilitate (bariere de memorie care inhibă optimizări), lock-uri necontestate ieftine (JVM-ul le optimizează: lock elision prin escape analysis, lock coarsening) versus lock-uri contestate scumpe (blocare, context switch, trafic de coerență de cache). De aici programul capitolului: redu contenția pe lock-uri. Cei doi factori ai contenției: cât de des e cerut lock-ul × cât timp e ținut. Tehnicile: (1) îngustează secțiunile critice — mută din blocul sincronizat tot ce nu atinge starea partajată (`AttributeStore` → `BetterAttributeStore`); (2) micșorează granularitatea — lock splitting: variabile independente păzite de lock-uri separate (`ServerStatus` cu lock pentru users și altul pentru queries); lock striping: un set de N lock-uri pentru partițiile unei colecții (ConcurrentHashMap cu 16 stripes — scriitorii concurenți devin posibili, cu prețul complicației operațiilor globale gen size/rehash); (3) evită hot fields — un contor global de `size` cache-uit transformă o structură scalabilă înapoi în una serială (ConcurrentHashMap ține numărătoarea per-stripe); (4) alternative la lock-urile exclusive: `ReadWriteLock`, obiecte imutabile, variabile atomice; (5) nu preda scheduler-ului decizii — renunță la monitorizarea prin lock-uri lungi. Monitorizarea: utilizarea CPU sub-completă indică load insuficient, I/O bound, sau contenție; thread dump-urile arată lock-urile „fierbinți". Verdict împotriva object pooling-ului: alocarea în Java e mai ieftină decât sincronizarea unui pool — pooling-ul pentru obiecte ușoare e anti-optimizare. Studiul de caz final compară trei implementări de Map sub load (Hashtable, synchronizedMap, ConcurrentHashMap) — striping-ul câștigă categoric. Coda: reducerea costului context switch-urilor prin mutarea I/O-ului (logging) într-un singur thread dedicat cu coadă — mai puține thread-uri blocate pe I/O, mai puține switch-uri.

## Concepte explicate

### Throughput vs. latency; scalabilitate
- **Definiție** — latency/service time: cât durează o cerere; throughput/capacity: câte cereri pe unitate de timp; scalabilitate: panta throughput-ului când adaugi resurse.
- **De ce contează** — „mai rapid" fără precizare duce la optimizări care ajută metrica greșită; serverele se optimizează de regulă pentru throughput sub load, chiar cu latențe individuale ușor mai mari.
- **Exemplu de cod**
  ```java
  // Design „three-tier" per cerere: mai mult de lucru per cerere decât monolitul,
  // dar fiecare etaj scalează orizontal — schimbi latență pe capacitate.
  ```
- **Capcane frecvente** — a compara design-uri pe o singură cerere fără load; a raporta doar media latenței (percentilele mor primele sub contenție).

### Legea lui Amdahl și serializarea ascunsă
- **Definiție** — speedup maxim = 1 / (F + (1−F)/N) pentru fracția serială F și N procesoare; fracția serială include orice punct în care thread-urile se coordonează: cozi comune, contoare globale, handoff de rezultate.
- **De ce contează** — dă plafonul teoretic și direcția practică: nu „mai multe thread-uri", ci „mai puțină serialitate"; explică de ce alegerea structurii de date (synchronized list vs. ConcurrentLinkedQueue) schimbă curba de scalare.
- **Exemplu de cod**
  ```java
  // F = 0.1 (10% serial) => speedup max 10x, chiar cu 1000 de core-uri:
  double speedup(double F, int N) { return 1.0 / (F + (1 - F) / N); }
  // speedup(0.1, 1000) ≈ 9.9
  ```
- **Capcane frecvente** — a număra ca „serial" doar blocurile synchronized vizibile; coada de task-uri, log-ul comun și alocatorul contestat sunt tot F.

### Costurile sincronizării; lock elision & coarsening
- **Definiție** — lock-urile necontestate costă puțin (fast path optimizat; JVM-ul poate elimina lock-uri pe obiecte care nu scapă — elision — sau uni blocuri adiacente — coarsening); lock-urile contestate costă mult (blocare, context switch, invalidare de cache).
- **De ce contează** — mută discuția de la „sincronizarea e scumpă" la „contenția e scumpă": nu elimina sincronizarea corectă, elimină contenția.
- **Exemplu de cod**
  ```java
  // Vector local unei metode: JIT-ul poate elimina complet lock-urile (escape analysis)
  String getNames() {
      List<String> v = new Vector<>();   // nu scapă din metodă
      v.add("a"); v.add("b");            // lock elision + coarsening
      return v.toString();
  }
  ```
- **Capcane frecvente** — micro-benchmark-uri care măsoară lock-uri necontestate și trag concluzii despre producție; „optimizarea" prin scoaterea sincronizării necesare.

### Îngustarea secțiunilor critice („get in, get out")
- **Definiție** — ține sub lock doar accesul la starea partajată; calculele, parsarea, I/O-ul ies în afară.
- **De ce contează** — timpul de ținere e unul din cei doi factori ai contenției; reducerea lui e cea mai ieftină optimizare de scalabilitate.
- **Exemplu de cod**
  ```java
  // GREȘIT (AttributeStore): tot match-ul sub lock
  synchronized boolean userLocationMatches(String name, String regexp) {
      String key = "users." + name + ".location";
      String location = attributes.get(key);
      return location != null && Pattern.matches(regexp, location);
  }
  // CORECT: doar get-ul sub lock; regex-ul afară
  boolean userLocationMatches(String name, String regexp) {
      String key = "users." + name + ".location";
      String location;
      synchronized (this) { location = attributes.get(key); }
      return location != null && Pattern.matches(regexp, location);
  }
  ```
- **Capcane frecvente** — spargerea unei operații ATOMICE în două blocuri sincronizate ca s-o „îngustezi" (corectitudinea înainte de toate); sincronizarea sub care se face logging.

### Lock splitting și lock striping
- **Definiție** — splitting: variabile de stare independente primesc lock-uri separate; striping: o familie de N lock-uri păzește partițiile aceleiași structuri (bucket-urile unui hash map: lock-ul `locks[hash % N]`).
- **De ce contează** — înjumătățește (splitting) sau împarte la N (striping) frecvența cererii fiecărui lock; e exact mecanismul care face `ConcurrentHashMap` scalabil.
- **Exemplu de cod**
  ```java
  // Striped map în spiritul Listing 11.8
  class StripedMap<K, V> {
      private static final int N = 16;
      private final Object[] locks = new Object[N];
      private final Map<K, V>[] buckets = /* N hărți simple */;
      { for (int i = 0; i < N; i++) locks[i] = new Object(); }
      V get(K k) {
          int s = Math.abs(k.hashCode() % N);
          synchronized (locks[s]) { return buckets[s].get(k); }   // doar stripe-ul lui
      }
      void clear() {                                              // operație globală: pe rând
          for (int i = 0; i < N; i++) synchronized (locks[i]) { buckets[i].clear(); }
      }
  }
  ```
- **Capcane frecvente** — splitting pe variabile care NU sunt independente (invariant comun → race); operațiile globale (size, clear atomic) devin scumpe sau imposibile — acceptă semantici slăbite.

### Hot fields
- **Definiție** — o variabilă unică atinsă de toate operațiile (contor global, cache de size, statistici) care re-serializează un design altfel partiționat.
- **De ce contează** — anulează beneficiul striping-ului: toate thread-urile se reîntâlnesc la același câmp; soluția e partiționarea metadatelor (numărători per-stripe, agregare la citire).
- **Exemplu de cod**
  ```java
  // GREȘIT: private long size;  actualizat la fiecare put/remove sub un lock global
  // CORECT (spiritul ConcurrentHashMap): int[] counts per stripe; size() = suma lor (aproximativă)
  // Modern: LongAdder — exact acest tipar, gata făcut
  ```
- **Capcane frecvente** — a adăuga „doar un contor de statistici" pe calea fierbinte; size()/isEmpty() exacte cerute unde ar ajunge aproximările.

### Object pooling — anti-optimizarea
- **Definiție** — reciclarea obiectelor într-un pool ca să eviți alocarea.
- **De ce contează** — în Java modern alocarea e foarte ieftină (bump-the-pointer, ~zeci de instrucțiuni), iar pool-ul introduce sincronizare pe fiecare acces — pool-ul unui obiect ușor e mai scump decât alocarea + GC; mai mult, cere management corect al ciclului de viață și poate crea contenție nouă.
- **Capcane frecvente** — pooling importat din alte limbaje ca reflex; excepția reală rămân obiectele autentic scumpe (conexiuni DB, thread-uri — de-asta există pool-uri pentru ELE).

## Listing-uri cheie din carte
- **Listing 11.1 — coada serializată de task-uri**: serializarea ascunsă care intră în F-ul lui Amdahl.
- **Listing 11.2 — sincronizare fără efect (Don't)**: sync pe obiect local nou — zero protecție (dar și candidat la elision).
- **Listing 11.3 — candidat la lock elision**: Vector care nu scapă din metodă; JVM-ul optimizează singur.
- **Listing 11.4/11.5 — AttributeStore → BetterAttributeStore**: îngustarea secțiunii critice, pasul 1 al oricărei detensionări.
- **Listing 11.6/11.7 — ServerStatus cu lock splitting**: users și queries pe lock-uri separate.
- **Listing 11.8 — StripedMap**: striping-ul demonstrat pe un hash map minimal; cheia scalabilității ConcurrentHashMap.

## Citate
Disciplina capitolului (11.1, parafrazată): întâi fă-l corect, apoi fă-l rapid — și doar dacă măsurătorile, nu presimțirile, spun că nu e destul de rapid. Cei doi factori (11.4, parafrazată): contenția pe un lock e produsul dintre frecvența cererii și durata ținerii — atacă pe oricare din ei.

## Legături
Formalizează „riscurile de performanță" din capitolul 1 și explică de ce structurile din capitolul 5 (ConcurrentHashMap, CopyOnWriteArrayList) sunt construite cum sunt. Îngustarea secțiunilor reia finalul capitolului 2 cu motivație cantitativă; dimensionarea pool-urilor din capitolul 8 se sprijină pe aceleași noțiuni de utilizare. Capitolul 12 arată cum MĂSORI ce aici doar proiectezi, iar capitolul 15 duce reducerea contenției la extrem (fără lock-uri deloc, CAS). De stăpânit: Amdahl cu serializarea ascunsă, cei doi factori ai contenției și scara îngustare → splitting → striping → evitarea hot fields.
