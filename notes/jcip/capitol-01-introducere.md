# Capitolul 01 — Introduction

## Rezumat amplu
Capitolul deschide cartea explicând de ce concurența există și de ce e grea. Istoric, sistemele de operare au introdus procese ca să exploateze resursele (CPU-ul nu stă degeaba cât aștepți I/O), să ofere echitate între utilizatori și să simplifice programarea prin descompunerea muncii în unități independente. Thread-urile duc aceeași idee un nivel mai jos: mai multe fluxuri de execuție în interiorul aceluiași proces, care împart heap-ul, dar au stive și program countere proprii. Tocmai această partajare a memoriei este și marele avantaj (comunicare ieftină), și marea sursă de pericol (acces necoordonat la aceleași date). Beneficiile enumerate: exploatarea procesoarelor multiple (un program single-threaded folosește un singur core), simplitatea modelării (fiecare task ca un flux secvențial, ca în modelul servlet sau RMI), tratarea mai simplă a evenimentelor asincrone (I/O sincron pe thread dedicat în loc de I/O non-blocant complicat) și interfețe utilizator mai responsive (event dispatch thread separat de munca lungă). Riscurile sunt organizate pe trei axe care structurează, de fapt, toată cartea. Riscuri de safety („nimic rău nu se întâmplă"): fără sincronizare, ordinea operațiilor între thread-uri e imprevizibilă, iar exemplul `UnsafeSequence` arată cum două thread-uri pot primi aceeași valoare dintr-un generator de secvențe, pentru că `next++` nu e o operație atomică, ci citire-modificare-scriere. Riscuri de liveness („ceva bun se întâmplă până la urmă"): deadlock, starvation, livelock — programul nu mai progresează. Riscuri de performanță: context switch-uri, sincronizarea care inhibă optimizări de compilator și invalidează cache-uri, deci concurența are costuri chiar când e corectă. Un punct subtil: compilatorul și runtime-ul au voie să reordoneze operații în absența sincronizării, deci nu poți raționa „citind codul de sus în jos" despre ce vede alt thread. Finalul capitolului subliniază că nu poți evita subiectul: chiar dacă nu creezi tu thread-uri, framework-urile o fac pentru tine — Timer, servlet-uri și JSP-uri, RMI, Swing/AWT toate îți cheamă codul din thread-uri pe care nu le controlezi. De aceea thread safety e o proprietate contagioasă: dacă un framework îți accesează starea din alt thread, tot lanțul de obiecte accesibil de acolo trebuie să fie thread-safe. Capitolul setează astfel tonul: concurența nu e o funcționalitate opțională, ci o realitate a platformei, iar restul cărții construiește disciplina necesară ca să o stăpânești.

## Concepte explicate

### Thread vs. proces
- **Definiție** — procesul e o unitate de execuție izolată, cu memorie proprie; thread-urile sunt fluxuri de execuție „ușoare" în interiorul unui proces, care partajează heap-ul și variabilele, dar au stivă și registre proprii.
- **De ce contează** — partajarea memoriei face comunicarea între thread-uri ieftină, dar elimină izolarea: orice mutare de stare e potențial vizibilă (sau, mai rău, parțial vizibilă) altui thread.
- **Exemplu de cod**
  ```java
  // două thread-uri care partajează același obiect — comunicare ieftină, risc mare
  var shared = new StringBuilder();
  Runnable r = () -> shared.append("x"); // StringBuilder NU e thread-safe
  new Thread(r).start();
  new Thread(r).start(); // rezultat imprevizibil: lungime 0, 1 sau 2, sau stare coruptă
  ```
- **Capcane frecvente** — a presupune că „merge pe mașina mea" înseamnă corect; erorile de concurență sunt probabilistice, nu deterministe.

### Race condition (prima expunere)
- **Definiție** — corectitudinea programului depinde de ordinea relativă (norocul planificatorului) în care se intercalează operațiile mai multor thread-uri.
- **De ce contează** — produce bug-uri rare, nereproductibile, care apar tipic doar sub load, în producție.
- **Exemplu de cod**
  ```java
  // GREȘIT — echivalentul UnsafeSequence din carte
  class UnsafeIdGen {
      private int next;
      public int next() { return next++; } // citire-adună-scrie: 3 pași, nu unul
  }
  // CORECT
  class SafeIdGen {
      private int next;
      public synchronized int next() { return next++; }
  }
  ```
- **Capcane frecvente** — a crede că `x++` e atomic; a crede că un bug care nu apare la testare nu există.

### Safety vs. liveness vs. performance
- **Definiție** — safety: nu se produc rezultate greșite; liveness: programul ajunge să progreseze (fără deadlock/starvation); performance: progresul e suficient de rapid.
- **De ce contează** — soluțiile pentru una pot strica alta: mai mult locking dă safety dar poate produce deadlock (liveness) sau contenție (performance). Tot design-ul concurent e un echilibru între cele trei.
- **Exemplu de cod**
  ```java
  // safety obținută brutal: un singur lock global — corect, dar serializezi tot
  synchronized (GLOBAL_LOCK) { doEverything(); } // liveness/performanță sacrificate
  ```
- **Capcane frecvente** — optimizarea prematură prin eliminarea sincronizării „ca să fie rapid"; cartea insistă: întâi corect, apoi rapid.

### Thread-urile sunt peste tot (frameworks & contagiune)
- **Definiție** — framework-urile (servlet containers, RMI, Swing, Timer) creează thread-uri și îți apelează codul din ele; thread safety devine cerință implicită.
- **De ce contează** — dacă un callback accesează starea aplicației, toate obiectele atinse din acel call path trebuie să fie thread-safe, nu doar clasa apelată direct.
- **Exemplu de cod**
  ```java
  // un servlet e apelat concurent de containerul web — fără să fi creat tu vreun thread
  public class CounterServlet extends HttpServlet {
      private long hits; // stare partajată între toate request-urile!
      protected void doGet(HttpServletRequest rq, HttpServletResponse rs) {
          hits++; // race condition „gratuită", oferită de container
      }
  }
  ```
- **Capcane frecvente** — „aplicația mea e single-threaded" — aproape niciodată adevărat pe JVM (GC, finalizers, AWT, Timer).

## Listing-uri cheie din carte
- **Listing 1.1 — Non-thread-safe Sequence Generator**: `UnsafeSequence.getNext()` cu `value++`; demonstrează cum o singură linie de cod ascunde trei operații și cum două thread-uri pot obține aceeași valoare. E imaginea canonică a race condition-ului.
- **Listing 1.2 — Thread-safe Sequence Generator**: aceeași clasă cu `synchronized`; arată că fix-ul minimal e coordonarea accesului, nu rescrierea logicii.

## Citate
Cartea condensează riscul central în ideea că, fără sincronizare, nu există nicio garanție de ordine între operațiile thread-urilor — compilatorul și runtime-ul pot reordona liber (secțiunea 1.3.1). A doua idee memorabilă (secțiunea 1.4): thread-urile sunt omniprezente în platformă — dacă un framework îți cheamă codul, cerința de thread safety e a ta, nu a framework-ului. (Parafrazări; citatele directe sunt limitate din motive de copyright.)

## Legături
Capitolul e fundația pentru tot ce urmează: capitolul 2 definește riguros thread safety și atomicitatea schițate aici; capitolul 3 detaliază vizibilitatea și reordonarea doar menționate în 1.3.1; capitolele 10–11 dezvoltă riscurile de liveness și performanță enumerate în 1.3. Înainte de a merge mai departe trebuie stăpânite: diferența proces/thread, cele trei categorii de risc și ideea că `++` nu e atomic.
