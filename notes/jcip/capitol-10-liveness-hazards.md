# Capitolul 10 — Avoiding Liveness Hazards

## Rezumat amplu
Capitolul inventariază pericolele de liveness — situațiile în care programul nu mai progresează deși nimic nu e „greșit" logic — cu deadlock-ul în rol principal. Punctul de plecare e un paradox al locking-ului: folosim lock-uri ca să fim safe, dar cu cât mai multe lock-uri, cu atât mai multe șanse ca două thread-uri să le dobândească în ordini diferite și să se aștepte reciproc pentru totdeauna. Spre deosebire de baze de date, care detectează deadlock-ul și avortează o tranzacție, JVM-ul nu face nimic: thread-urile blocate rămân blocate definitiv, iar aplicația trebuie repornită. Forma pură: lock-ordering deadlock — `LeftRightDeadlock` ia lock-urile left→right pe un thread și right→left pe altul; condiția necesară și suficientă de evitare: toate thread-urile dobândesc lock-urile în aceeași ordine globală. Forma perfidă: dynamic lock-ordering — `transferMoney(from, to)` ia lock-urile în ordinea argumentelor, deci două transferuri în sensuri opuse (A→B și B→A) se blochează reciproc; ordinea lock-urilor depinde de datele de intrare, nu de cod. Fix-ul: impune tu o ordine canonică — `System.identityHashCode` compară obiectele și decide cine se ia primul, cu un „tie-breaker" (un al treilea lock global) pentru coliziunile de hash; sau, dacă entitățile au chei unice (ID de cont), ordonează după ele. Demonstrația cu driver-ul care rulează transferuri aleatoare arată cât de repede moare în practică un asemenea cod. Forma cea mai greu de văzut: deadlock între obiecte cooperante — `Taxi.setLocation()` (sincronizat pe taxi) cheamă `dispatcher.notifyAvailable(this)` (are nevoie de lock-ul dispatcher-ului), în timp ce `Dispatcher.getImage()` (sincronizat pe dispatcher) cheamă `taxi.getLocation()` — două call path-uri aparent nevinovate, în clase diferite, cu ordini de lock opuse; nimic nu ia explicit două lock-uri, dar apelul de metodă „străină" sub lock o face implicit. Soluția structurală: open calls — nu chema metode alien (ale altor obiecte, sau overridable) ținând un lock; restrânge secțiunile sincronizate la manipularea stării proprii și fă apelurile externe în afara lor (cu prețul unei atomicități mai slabe: operația devine „stare actualizată, apoi notificare", ceea ce de cele mai multe ori e exact ce vrei). Există și resource deadlocks: două thread-uri care rețin câte o resursă din pool-uri diferite și o așteaptă pe a celuilalt, sau thread starvation deadlock-ul din capitolul 8. Diagnoza și prevenția: redu numărul de lock-uri ținute simultan (ideal unul), identifică locurile unde se iau mai multe și impune ordinea, audită cu open calls; timed tryLock (capitolul 13) dă o ieșire probabilistică; thread dump-urile sunt unealta post-mortem — JVM-ul include informația de deadlock intrinsec (cine ține ce, cine așteaptă ce), iar exemplul real din carte (aplicație J2EE + container + driver JDBC) arată cum trei produse „testate" fac împreună un deadlock pe care doar dump-ul îl explică. Capitolul se închide cu pericolele minore: starvation (thread-uri care nu primesc niciodată CPU — nu umbla la priorități, lasă-le pe default), poor responsiveness (task-uri de fundal lacome sau lock-uri ținute lung) și livelock (thread-urile lucrează continuu dar nu progresează — mesajul „otrăvit" reprocesat la infinit, sau retry-urile sincronizate care se ciocnesc mereu; fix: randomizează retry-ul, ca la Ethernet).

## Concepte explicate

### Lock-ordering deadlock
- **Definiție** — ciclu de așteptare: thread-ul A ține lock-ul 1 și vrea 2, thread-ul B ține 2 și vrea 1; apare exact când ordinile de dobândire diferă.
- **De ce contează** — e permanent (JVM-ul nu avortează pe nimeni) și probabilistic (supraviețuiește testării, lovește în producție).
- **Exemplu de cod**
  ```java
  // GREȘIT — două ordini de dobândire (echivalentul LeftRightDeadlock)
  void metodaA() { synchronized (left)  { synchronized (right) { work(); } } }
  void metodaB() { synchronized (right) { synchronized (left)  { work(); } } }
  // CORECT: ambele metode iau ÎNTOTDEAUNA left, apoi right
  ```
- **Capcane frecvente** — ordinea pare unică pentru că citești o singură clasă; deadlock-ul trăiește în COMBINAȚIA call path-urilor din tot programul.

### Dynamic lock-ordering și ordinea indusă
- **Definiție** — ordinea lock-urilor vine din ordinea argumentelor/datelor; fix-ul: sortează lock-urile după un criteriu global (identity hash + tie-breaker, sau cheie de business unică).
- **De ce contează** — `transfer(a, b)` vs `transfer(b, a)` e cel mai realist deadlock din codul enterprise (conturi, decontări, mutări de stoc).
- **Exemplu de cod**
  ```java
  private static final Object TIE = new Object();
  void transfer(Account from, Account to, long amount) {
      int h1 = System.identityHashCode(from), h2 = System.identityHashCode(to);
      if (h1 < h2)      { synchronized (from) { synchronized (to)   { move(from, to, amount); } } }
      else if (h1 > h2) { synchronized (to)   { synchronized (from) { move(from, to, amount); } } }
      else { synchronized (TIE) { synchronized (from) { synchronized (to) { move(from, to, amount); } } } }
  }
  ```
- **Capcane frecvente** — a ordona după `hashCode()` suprascris (poate fi egal des); a uita tie-breaker-ul; a ordona doar în „metoda principală" și a lăsa alt call path nesortat.

### Deadlock între obiecte cooperante și open calls
- **Definiție** — apelul unei metode a altui obiect în timp ce ții un lock ia, implicit, și lock-urile acelei metode; open call = apel făcut FĂRĂ niciun lock ținut.
- **De ce contează** — face analiza de deadlock locală și scalabilă: dacă toate apelurile externe sunt open, ciclurile de lock-uri se văd în fiecare clasă în parte, nu doar în combinatorică globală.
- **Exemplu de cod**
  ```java
  // GREȘIT: notificarea sub lock
  synchronized void setLocation(Point p) { location = p; dispatcher.notifyAvailable(this); }
  // CORECT: open call — restrângi lock-ul la stare, notifici în afara lui
  void setLocation(Point p) {
      boolean reached;
      synchronized (this) { location = p; reached = p.equals(destination); }
      if (reached) dispatcher.notifyAvailable(this);   // fără lock ținut
  }
  ```
- **Capcane frecvente** — listeners/callback-uri chemate din blocuri sincronizate (cel mai frecvent deadlock din codul cu observer pattern); a nu observa că metoda „proprie" e overridable — și ea e alien.

### Resource deadlock & starvation deadlock
- **Definiție** — același ciclu de așteptare, dar pe resurse (conexiuni din pool-uri diferite, în ordini diferite) sau pe thread-uri (task dependent de alt task în același pool plin — cap. 8).
- **De ce contează** — nu vezi niciun `synchronized`, dar semantica e identică; pool-urile mărginite + dependențele ascunse sunt combinația letală.
- **Exemplu de cod**
  ```java
  // thread 1: conn1 = poolA.take(); conn2 = poolB.take();
  // thread 2: conn2 = poolB.take(); conn1 = poolA.take();  // aceeași poveste, altă „monedă"
  ```
- **Capcane frecvente** — a crede că doar lock-urile fac deadlock; dimensionarea pool-urilor fără analiza dependențelor dintre task-uri.

### Thread dump ca unealtă de diagnoză
- **Definiție** — snapshot al stivelor tuturor thread-urilor + starea lock-urilor (cine ține, cine așteaptă); JVM-ul marchează explicit deadlock-urile intrinseci găsite.
- **De ce contează** — e singura fereastră post-factum într-un deadlock de producție; cu lock-uri explicite (`ReentrantLock`) informația e mai săracă decât cu `synchronized`.
- **Exemplu de cod**
  ```bash
  jstack <pid>          # sau kill -3 <pid> — dump pe stderr-ul JVM-ului
  # caută secțiunea "Found one Java-level deadlock:"
  ```
- **Capcane frecvente** — a repora serverul înainte să iei dump-ul (pierzi proba); a căuta bug-ul doar în codul propriu — exemplul din carte e un deadlock ÎNTRE trei produse diferite.

### Starvation, poor responsiveness, livelock
- **Definiție** — starvation: un thread nu primește CPU (tipic din cauza priorităților modificate); responsiveness slab: task-uri de fundal lacome sau lock-uri ținute lung sufocă thread-urile interactive; livelock: thread-urile rulează, dar starea nu avansează (retry-uri care se re-ciocnesc, mesaj otrăvit reprocesat la nesfârșit).
- **De ce contează** — toate trei sunt „programul viu dar inutil"; livelock-ul e insidios pentru că CPU-ul arată ocupat.
- **Exemplu de cod**
  ```java
  // Anti-livelock: retry cu backoff randomizat
  long backoff = ThreadLocalRandom.current().nextLong(10, 50);
  while (!tryOperation()) {
      Thread.sleep(backoff);
      backoff = Math.min(backoff * 2, MAX) + ThreadLocalRandom.current().nextLong(20);
  }
  ```
- **Capcane frecvente** — `setPriority` ca unealtă de tuning (nedeterministă, dependentă de platformă — lasă prioritățile în pace); retry imediat și sincron la conflict (toți re-încearcă în același moment).

## Listing-uri cheie din carte
- **Listing 10.1 — LeftRightDeadlock (Don't)**: forma pură a inversiunii de ordine; diagrama de așteptare circulară aferentă e imaginea-cheie a capitolului.
- **Listing 10.2 — transferMoney dinamic (Don't)**: ordinea vine din argumente; deadlock-ul „din date".
- **Listing 10.3 — ordinea indusă cu identityHashCode + tie-breaker**: fix-ul canonic.
- **Listing 10.4 — driver-ul care declanșează deadlock-ul**: cât de puțin trafic e nevoie ca să moară.
- **Listing 10.5 — Taxi/Dispatcher (Don't)**: deadlock-ul cooperant — niciun loc nu ia vizibil două lock-uri.
- **Listing 10.6 — aceleași clase cu open calls**: restructurarea care face analiza locală.
- **Listing 10.7 — thread dump real**: cum se citește proba unui deadlock J2EE + JDBC.

## Citate
Regula de aur (10.1, parafrazată): un program în care toate thread-urile dobândesc lock-urile într-o ordine globală fixă nu poate avea lock-ordering deadlock. Recomandarea structurală (10.1.4, parafrazată): folosește open calls — nu chema metode străine cu lock-ul în mână; analiza deadlock-ului devine locală în loc de globală.

## Legături
Reia „liveness hazards" anunțate în capitolul 1 și thread starvation deadlock-ul din capitolul 8. Open calls rafinează disciplina secțiunilor critice din capitolele 2 și 4 (îngustarea lock-urilor capătă aici o a doua motivație: nu doar performanță, ci și evitarea deadlock-ului). Timed/polled lock ca plasă de siguranță e dezvoltat în capitolul 13 (`tryLock`), iar alternativa radicală — algoritmi fără lock-uri — în capitolul 15. De stăpânit: ordinea globală de lock-uri, tiparul identityHashCode și reflexul open calls la orice callback.
