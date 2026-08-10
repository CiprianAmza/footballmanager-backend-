# Capitolul 09 — GUI Applications

## Rezumat amplu
Capitolul aplică tot ce s-a construit până acum pe un caz special dar universal: aplicațiile cu interfață grafică, unde framework-ul (Swing/AWT) e single-threaded prin design. De ce single-threaded? Încercările istorice de toolkit-uri GUI multithreaded s-au lovit sistematic de race-uri și deadlock-uri: fluxul de evenimente input merge de jos în sus (OS → aplicație), iar acțiunile aplicației asupra componentelor merg de sus în jos (aplicație → OS) — două direcții opuse peste aceleași obiecte, plus interacțiunea cu MVC (model-view-controller cu lanțuri de callback-uri) fac ordinea lock-urilor imposibil de impus; rezultatul istoric a fost deadlock-ul cronic. Soluția adoptată de toate toolkit-urile moderne: un singur event dispatch thread (EDT) care procesează secvențial evenimentele dintr-o coadă, iar toate componentele GUI sunt thread-confined la EDT. Regula Swing: componentele și modelele se ating NUMAI de pe EDT; celelalte thread-uri comunică prin `invokeLater` (pune un task în coada de evenimente) și `invokeAndWait` (pune și așteaptă — doar de pe non-EDT). Consecința arhitecturală: EDT-ul e o resursă critică — orice task lung rulat pe el îngheață interfața (nu se mai procesează nici măcar repaint-urile); pragul de percepție e de ordinul zecilor-sutelor de milisecunde. De aici structura standard: task-urile scurte (actualizări de UI declanșate de evenimente) rămân pe EDT — confinement pur, fără sincronizare; task-urile lungi (căutări, descărcări, spell-check) pleacă pe un Executor, iar rezultatele revin pe EDT prin `invokeLater` — un „ping-pong" disciplinat de thread hopping. Capitolul construiește incremental: listener care submite task lung la executor; feedback vizual (buton dezactivat, label „în lucru") cu re-activare la final — trei niveluri de inner classes care sar între thread-uri; anulare — task-ul lung ținut într-un `Future`, butonul cancel cheamă `cancel(true)`, task-ul cooperează verificând întreruperea; și, în final, abstracția `BackgroundTask` (echivalentul de dinaintea `SwingWorker`-ului modern): o clasă cu `compute()` pe worker thread și hook-uri `onProgress`/`onCompletion` livrate garantat pe EDT, construită pe `FutureTask` + `done()` care face hop pe EDT. Ultima parte tratează modelele de date partajate: dacă un model e atins și de EDT și de alte thread-uri (ex. tabel actualizat live dintr-un fir de date), ai trei opțiuni — mutarea tuturor actualizărilor pe EDT (cel mai simplu), model thread-safe veritabil, sau split model: modelul de prezentare e confined la EDT și e o proiecție a modelului partajat aplicației, sincronizate prin evenimente (snapshot sau incremental). Închide cu generalizarea: orice subsistem single-threaded cu regula „doar thread-ul X atinge starea Y" e același tipar de thread confinement, iar tehnicile din capitol (cozi de intrare, thread hopping, split models) se aplică identic.

## Concepte explicate

### Event dispatch thread (EDT) și thread confinement în GUI
- **Definiție** — un singur thread dedicat procesează secvențial coada de evenimente și e singurul care are voie să atingă componentele GUI și modelele lor.
- **De ce contează** — elimină prin construcție race-urile și deadlock-urile care au ucis toolkit-urile multithreaded; în schimb îți transferă ție responsabilitatea: orice atingere a UI-ului de pe alt thread e un bug (adesea nesemnalat, manifestat ca glitch-uri sporadice).
- **Exemplu de cod**
  ```java
  // GREȘIT — actualizare de UI de pe un thread oarecare
  new Thread(() -> label.setText("done")).start();      // race nedetectat, corupe starea Swing
  // CORECT — hop pe EDT
  new Thread(() -> {
      String result = compute();
      SwingUtilities.invokeLater(() -> label.setText(result));
  }).start();
  ```
- **Capcane frecvente** — inițializarea UI-ului pe thread-ul main în loc de EDT; apeluri „inofensive" (`getText`) de pe alte thread-uri — regula acoperă și citirile.

### invokeLater / invokeAndWait
- **Definiție** — `invokeLater(r)`: pune task-ul în coada de evenimente (asincron); `invokeAndWait(r)`: îl pune și blochează până se execută (doar de pe non-EDT).
- **De ce contează** — sunt puntea oficială între lumea multithreaded și confinement-ul EDT; echivalentul unui `Executor` cu un singur thread — de altfel capitolul arată că cele două formulări sunt interschimbabile.
- **Exemplu de cod**
  ```java
  // invokeAndWait de pe EDT = deadlock garantat (aștepți coada pe care o blochezi)
  // corect: doar de pe worker threads, pentru rezultate care CER sincronizare cu UI-ul
  SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
  ```
- **Capcane frecvente** — `invokeAndWait` de pe EDT; a folosi `invokeLater` ca „sincronizare" crezând că garantează ceva despre starea worker-ului.

### Task lung + feedback + anulare (tiparul complet)
- **Definiție** — listener-ul (pe EDT) dezactivează controalele și submite task-ul pe un Executor; task-ul lucrează pe worker thread; la final (sau la progres) face `invokeLater` ca să actualizeze UI-ul; un `Future` ținut de UI permite `cancel(true)`.
- **De ce contează** — e scheletul oricărei operații de durată din GUI; fiecare săritură de thread e explicită, deci analizabilă.
- **Exemplu de cod**
  ```java
  button.addActionListener(e -> {                      // EDT
      button.setEnabled(false); status.setText("Searching...");
      runningTask = exec.submit(() -> {                // worker thread
          try {
              Result r = longSearch();                 // verifică întreruperea în buclă!
              SwingUtilities.invokeLater(() -> show(r));// înapoi pe EDT
          } finally {
              SwingUtilities.invokeLater(() -> button.setEnabled(true));
          }
      });
  });
  cancelButton.addActionListener(e -> runningTask.cancel(true)); // EDT cere anularea
  ```
- **Capcane frecvente** — actualizarea UI direct din worker („doar un setText"); task lung care nu verifică întreruperea — cancel-ul devine decorativ; a uita re-activarea controalelor pe căile de eroare.

### BackgroundTask / SwingWorker
- **Definiție** — clasă-schelet care fixează protocolul: `compute()` pe worker, `onProgress`/`onCompletion` garantat pe EDT, anulare integrată prin `FutureTask`; `SwingWorker`-ul din platformă oferă azi exact asta.
- **De ce contează** — scoate thread hopping-ul repetitiv din fiecare listener și îl face imposibil de greșit; separă „ce calculez" de „cum comunic cu UI-ul".
- **Exemplu de cod**
  ```java
  // schiță în spiritul Listing 9.7
  abstract class BackgroundTask<V> implements Runnable, Future<V> {
      private final FutureTask<V> computation = new FutureTask<>(this::compute) {
          @Override protected void done() {
              SwingUtilities.invokeLater(() -> onCompletion());  // hop garantat pe EDT
          }
      };
      protected abstract V compute() throws Exception;           // worker thread
      protected void onCompletion() {}                            // EDT
      public void run() { computation.run(); }
      // Future delegat către computation...
  }
  ```
- **Capcane frecvente** — logică de UI strecurată în `compute()`; a rata că `done()`/`onCompletion` rulează și la anulare — verifică `isCancelled()`.

### Modele de date partajate și split model
- **Definiție** — când datele sunt necesare și UI-ului și altor thread-uri: fie toate actualizările pe EDT, fie model thread-safe, fie două modele — cel de prezentare confined la EDT, cel partajat thread-safe, sincronizate prin evenimente (snapshot sau delte incrementale).
- **De ce contează** — modelele Swing (TableModel etc.) NU sunt thread-safe; split model păstrează simplitatea confinement-ului fără să blocheze aplicația pe lock-urile UI-ului.
- **Exemplu de cod**
  ```java
  // fluxul de date scrie în modelul partajat; UI-ul primește delte pe EDT
  void onVehicleMoved(VehicleEvent ev) {               // thread-ul fluxului de date
      sharedModel.update(ev);                           // model thread-safe (cap. 4!)
      SwingUtilities.invokeLater(() -> tableModel.applyDelta(ev)); // proiecția pe EDT
  }
  ```
- **Capcane frecvente** — legarea directă a unui `JTable` de un model actualizat de alte thread-uri; delte aplicate în altă ordine decât au fost emise (folosește coada de evenimente ca ordine unică).

## Listing-uri cheie din carte
- **Listing 9.1/9.2 — SwingUtilities ca Executor și invers**: demonstrează că EDT-ul e exact un single-thread executor; echivalența conceptuală e punctul teoretic al capitolului.
- **Listing 9.3/9.4 — listener simplu și legarea unui task lung de o componentă**: primul pas greșit-dar-instructiv (task lung pe EDT) și mutarea lui pe executor.
- **Listing 9.5 — task lung cu feedback**: cele trei niveluri de inner classes — motivația pentru o abstracție dedicată.
- **Listing 9.6 — anularea unui task lung**: Future + cancel(true) integrat cu butonul de cancel.
- **Listing 9.7/9.8 — BackgroundTask**: scheletul reutilizabil cu completare/progres pe EDT; strămoșul direct al `SwingWorker`.

## Citate
Regula unică a Swing-ului (9.1.2, parafrazată): componentele și modelele Swing se creează, se modifică și se interoghează exclusiv de pe event dispatch thread. Despre responsivitate (9.3, parafrazată): task-urile de pe EDT trebuie să se termine repede, altfel interfața îngheață — coada de evenimente nu iartă.

## Legături
E capitolul-aplicație al întregii părți a doua: thread confinement (cap. 3) devine arhitectură de framework, Executor și Future (cap. 6) devin mecanica task-urilor lungi, anularea (cap. 7) devine butonul Cancel, iar split model reia delegarea și publicarea din capitolul 4. Închide partea a doua; partea a treia (cap. 10–12) se întoarce la pericole: liveness, performanță, testare. De stăpânit: regula EDT, tiparul complet task-lung-cu-anulare și ideea că orice subsistem single-threaded se tratează identic.
