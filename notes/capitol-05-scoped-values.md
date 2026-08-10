# Capitolul 05 — Scoped Values

## Rezumat amplu

Capitolul pornește de la o problemă practică veche: cum partajezi date de context (metadate, identitate de utilizator, tranzacții) de-a lungul unui lanț de apeluri, când argumentele de metodă nu sunt fezabile — situație tipică atunci când codul tău rulează în interiorul unui framework. Autorul construiește un exemplu de framework de job scheduling în care un `JobContext` trebuie plimbat din `schedule()` în codul utilizatorului și înapoi în framework, ceea ce duce la trei boli arhitecturale: „parameter pollution" (fiecare metodă cară un parametru pe care nu-l folosește), „interface brittleness" (orice extindere a contextului se propagă prin toate semnăturile) și cuplare strânsă care complică testarea și migrarea. Soluția clasică este `ThreadLocal`: framework-ul setează contextul pe thread-ul curent înainte de a apela codul utilizatorului, iar semnăturile devin curate — pattern folosit masiv de Spring pentru security/transaction/request context. Însă `ThreadLocal` are defecte de design fundamentale: mutabilitate neconstrânsă (oricine poate face `set()` oricând, deci nu poți urmări unde s-a schimbat valoarea), durată de viață nemărginită (valoarea persistă cât trăiește thread-ul, iar în thread pool-uri un `remove()` uitat scurge date de la un task la altul — risc de memory leak și chiar de securitate) și moștenire costisitoare prin `InheritableThreadLocal`, care copiază referințe în fiecare thread copil. Aceste defecte devin acute odată cu virtual threads (JEP 444, capitolul 2): cu milioane de thread-uri, fiecare cu propria copie de stare, costul de memorie devine nesustenabil. Ceea ce ne trebuie este partajare imutabilă, per-thread, cu durată de viață mărginită — exact ce oferă `ScopedValue`, finalizat în JDK 25. Un `ScopedValue` funcționează ca un parametru de metodă implicit: se declară `static final` prin factory-ul `ScopedValue.newInstance()` (constructorul e privat), se leagă cu `where()` și se execută codul în interiorul scope-ului cu `run()` (pentru `Runnable`) sau `call()` (pentru `Callable`, când vrei un rezultat). Cele trei caracteristici definitorii sunt: immutability (valoarea nu poate fi schimbată în interiorul scope-ului), thread-scoped binding (legarea e vizibilă doar pe thread-ul curent) și bounded lifetime (la ieșirea din `run()`/`call()` legarea dispare automat, fără `remove()` manual). Capitolul demonstrează experimental că valoarea e „bound" doar în interiorul scope-ului dinamic — apelarea aceluiași task în afara `run()` găsește valoarea nelegată, iar un thread pornit din interiorul scope-ului NU moștenește legarea (nici platform, nici virtual). API-ul oferă `isBound()` pentru verificare, `orElse()` pentru fallback, `orElseThrow()` pentru validare și chaining fluent de `where()` pentru legarea mai multor valori simultan. O trăsătură puternică este rebinding-ul în scope-uri imbricate: un subscope poate lega temporar o altă valoare pentru același `ScopedValue`, iar la ieșire valoarea exterioară se restaurează automat — util pentru role switching, configurări contextuale sau override-uri per task. Legătura cu capitolul 4 este directă: deși scoped values nu se moștenesc de thread-uri arbitrare, în interiorul unui `StructuredTaskScope` toate subtask-urile forked moștenesc automat legările părintelui, fără copiere, pentru că scope-ul structurat are granițe clare de viață — la închidere thread-urile copil dispar, deci nu există risc de leak. Din perspectiva performanței, `ScopedValue` partajează o singură instanță imutabilă în loc să multiplice copii, ceea ce contează enorm la mii/milioane de virtual threads. Pe partea de usability: lifecycle explicit vizibil în cod, API concis, funcționare ca „capability object" (cu `private` controlezi cine are acces) și tratarea explicită a lipsei valorii — `get()` pe o valoare nelegată aruncă `NoSuchElementException`, în timp ce `ThreadLocal` returnează `null` ambiguu. Secțiunea de migrare parcurge trei cazuri de utilizare dincolo de „hidden method parameter": detectarea recursiei (un procesor de template-uri cu contor de adâncime prin rebinding repetat), tranzacții aplatizate (o tranzacție imbricată se alătură celei exterioare dacă `isBound()`) și context grafic partajat într-o ierarhie de componente UI (culoare/grosime de linie cu restaurare automată). Concluzia: din JDK 25 `ScopedValue` e API stabil și este soluția recomandată pentru propagarea contextului în aplicațiile Java moderne, în special în combinație cu virtual threads și structured concurrency.

## Concepte explicate

### Parameter passing problem („parameter pollution")

- **Definiție** — Fenomenul prin care un obiect de context (relevant doar framework-ului) trebuie declarat ca parametru în fiecare metodă din lanțul de apeluri, doar ca să poată fi pasat mai departe, deși majoritatea metodelor nu-l folosesc direct.
- **De ce contează** — Poluează semnăturile cu detalii de framework fără sens de business, face interfețele fragile (orice extindere a contextului se propagă prin tot codul utilizatorului), crește cuplarea și complică testarea (trebuie construit mereu un context valid).
- **Exemplu de cod**

```java
// GREȘIT: contextul contaminează tot lanțul
void handleRequest(RequestCtx ctx) { validate(ctx); save(ctx); }
void validate(RequestCtx ctx) { /* nu folosește ctx, doar îl pasează */ audit(ctx); }
void audit(RequestCtx ctx) { log(ctx.userId()); }

// CORECT (cu ScopedValue): semnături curate
static final ScopedValue<RequestCtx> CTX = ScopedValue.newInstance();
void handleRequest(RequestCtx ctx) {
    ScopedValue.where(CTX, ctx).run(() -> { validate(); save(); });
}
void validate() { audit(); }
void audit() { log(CTX.get().userId()); }
```

- **Capcane frecvente** — A „rezolva" problema cu un singleton static mutabil (rupe concurența); a considera că problema e doar estetică — de fapt e o problemă de cuplare și evoluție a API-ului.

### ThreadLocal

- **Definiție** — Variabilă care păstrează câte o valoare separată per thread; codul face `set()`/`get()` pe aceeași referință statică, dar fiecare thread vede propria copie.
- **De ce contează** — E soluția istorică pentru propagarea contextului (Spring o folosește pentru security/transaction/request context), dar are trei defecte: mutabilitate neconstrânsă (orice cod cu acces poate rescrie valoarea oricând), lifetime nemărginit (valoarea trăiește cât thread-ul; în pool-uri, un `remove()` uitat scurge date între task-uri fără legătură — memory leak, date stale, chiar breșe de securitate dacă e vorba de tokenuri) și moștenire scumpă prin `InheritableThreadLocal`.
- **Exemplu de cod**

```java
// GREȘIT: leak într-un thread pool
static final ThreadLocal<String> USER = new ThreadLocal<>();
pool.submit(() -> USER.set("alice"));          // lipsă remove()
pool.submit(() -> System.out.println(USER.get())); // vede "alice"!

// CORECT (dacă tot folosești ThreadLocal): cleanup garantat
pool.submit(() -> {
    USER.set("alice");
    try { doWork(); } finally { USER.remove(); }
});
```

- **Capcane frecvente** — Uitarea `remove()` în `finally`; presupunerea că un thread din pool „începe curat"; folosirea `get()` care returnează `null` fără să poți distinge „setat pe null" de „niciodată setat".

### InheritableThreadLocal

- **Definiție** — Variantă de `ThreadLocal` în care thread-urile copil primesc automat, la creare, valorile din thread-ul părinte.
- **De ce contează** — Convenabilă, dar costisitoare când creezi multe thread-uri copil: fiecare copil păstrează o referință la datele părintelui (iar la obiecte mari, multiplicarea referințelor/copiilor umflă memoria), chiar dacă copiii nu ating niciodată datele.
- **Exemplu de cod**

```java
// PROBLEMATIC: 10MB referențiați de fiecare din sute de copii
static final InheritableThreadLocal<byte[]> BLOB = new InheritableThreadLocal<>();
BLOB.set(new byte[10_000_000]);
for (int i = 0; i < 100; i++) new Thread(() -> use(BLOB.get())).start();
```

- **Capcane frecvente** — Stocarea obiectelor mari sau mutabile; a te baza pe moștenire în thread pool-uri (moștenirea are loc la crearea thread-ului, nu la submit-ul task-ului, deci valorile sunt cele de la momentul creării worker-ului).

### ScopedValue

- **Definiție** — Mecanism (stabil din JDK 25) de a lega o valoare imutabilă de scope-ul dinamic al unei execuții pe thread-ul curent: un „parametru de metodă implicit" vizibil în metoda care rulează sub `run()`/`call()` și în tot ce apelează aceasta, și nicăieri altundeva.
- **De ce contează** — Elimină prin construcție cele trei defecte ale `ThreadLocal`: nu există `set()` (immutability), legarea moare automat la finalul scope-ului (bounded lifetime, deci zero `remove()` uitat), iar partajarea unei singure instanțe imutabile evită multiplicarea copiilor la milioane de virtual threads.
- **Exemplu de cod**

```java
static final ScopedValue<String> TENANT = ScopedValue.newInstance(); // factory, nu new

void serve(Request req) {
    ScopedValue.where(TENANT, req.tenant())
               .run(this::process);           // bound doar aici, în adâncime
}
void process() { repo.find(TENANT.get()); }   // fără parametru de context

// call() când vrei rezultat:
double price = ScopedValue.where(TENANT, "acme").call(this::computePrice);
```

- **Capcane frecvente** — Apelarea `get()` în afara scope-ului → `NoSuchElementException` (spre deosebire de `null`-ul lui `ThreadLocal`); așteptarea ca un `Thread` pornit manual din scope să moștenească legarea (nu o moștenește — doar `StructuredTaskScope.fork()` o moștenește); stocarea de obiecte mutabile în `ScopedValue` (imutabilă e legarea, nu obiectul — dacă obiectul e mutabil, ai pierdut garanțiile).

### Dynamic scope vs. lexical scope

- **Definiție** — Lexical scope e delimitat sintactic de acolade; dynamic scope e delimitat de fluxul de execuție: valoarea e accesibilă pe durata execuției metodei care a legat-o și a tuturor metodelor apelate direct sau indirect din ea, oricât de adânc.
- **De ce contează** — Explică de ce `TENANT.get()` merge cu 5 nivele mai jos în call stack fără niciun parametru, dar eșuează imediat ce `run()` s-a încheiat — și de ce nu există „scurgere" între secțiuni de cod fără legătură.
- **Exemplu de cod**

```java
ScopedValue.where(NAME, "duke").run(task); // în task: NAME.isBound() == true
task.run();                                 // același task, în afara scope-ului: false
```

- **Capcane frecvente** — A memora un `Runnable`/lambda în scope și a-l executa mai târziu (de pe alt thread sau după închiderea scope-ului): legarea nu „călătorește" cu lambda, ci cu execuția.

### run() vs. call()

- **Definiție** — `run()` execută un `Runnable` (nu returnează nimic); `call()` execută un `Callable` și returnează rezultatul acestuia în afara scope-ului.
- **De ce contează** — Fără `call()`, ai fi tentat să scurgi rezultate prin variabile mutabile capturate — exact anti-pattern-ul pe care ScopedValue vrea să-l evite.
- **Exemplu de cod**

```java
// GREȘIT: rezultat scurs printr-un array mutabil
double[] out = new double[1];
ScopedValue.where(RATE, 0.2).run(() -> out[0] = base * (1 - RATE.get()));

// CORECT
double price = ScopedValue.where(RATE, 0.2).call(() -> base * (1 - RATE.get()));
```

- **Capcane frecvente** — Uitarea că `call()` poate arunca excepții checked (semnătura `Callable`); folosirea `run()` + side effects unde `call()` e naturală.

### isBound() / orElse() / orElseThrow()

- **Definiție** — Metode de interogare a stării legării: `isBound()` spune dacă există o legare pe thread-ul curent, `orElse(default)` returnează un fallback dacă nu, `orElseThrow(supplier)` aruncă o excepție personalizată dacă nu.
- **De ce contează** — `get()` pe valoare nelegată aruncă `NoSuchElementException`; aceste metode permit cod robust care distinge explicit între „am context" și „nu am context" — imposibil cu `null`-ul ambiguu al `ThreadLocal`.
- **Exemplu de cod**

```java
Color c = DRAW_COLOR.isBound() ? DRAW_COLOR.get() : Color.BLACK;
String user = USER_NAME.orElse("Guest");
Ctx ctx = CTX.orElseThrow(() -> new IllegalStateException("no context bound"));
```

- **Capcane frecvente** — A împacheta `get()` în try/catch în loc să folosești `isBound()`/`orElse()`; a presupune că `orElse` „setează" valoarea (doar o returnează, nu leagă nimic).

### Chaining de where() (legări multiple)

- **Definiție** — API fluent care permite legarea mai multor `ScopedValue`-uri într-un singur scope: `ScopedValue.where(A, x).where(B, y).run(...)`.
- **De ce contează** — Contextul real e rareori o singură valoare (user + sesiune + trace id); chaining-ul le leagă atomic, cu același lifetime.
- **Exemplu de cod**

```java
ScopedValue.where(USER_ID, "u1")
           .where(TRACE_ID, "t-42")
           .run(() -> handle());
```

- **Capcane frecvente** — A crede că legările pot avea durate diferite în același lanț (au toate același scope); a lega zeci de valori separate în loc să grupezi într-un record de context imutabil.

### Rebinding în scope-uri imbricate

- **Definiție** — Un subscope poate lega o valoare nouă pentru același `ScopedValue`; la ieșirea din subscope, valoarea exterioară se restaurează automat.
- **De ce contează** — Permite override-uri temporare sigure (schimbare temporară de rol, configurare per operație, contor de recursie) fără cod de restaurare manuală — sursa clasică de bug-uri cu `ThreadLocal` (restaurare uitată pe calea de excepție).
- **Exemplu de cod**

```java
ScopedValue.where(ROLE, "admin").run(() -> {
    audit();                                    // vede "admin"
    ScopedValue.where(ROLE, "guest").run(this::audit); // vede "guest"
    audit();                                    // din nou "admin", automat
});
```

- **Capcane frecvente** — A încerca să „modifici" valoarea curentă în loc să rebind-ezi într-un subscope; a uita că restaurarea e per-scope, nu per-apel de metodă.

### Moștenirea prin StructuredTaskScope

- **Definiție** — Excepția de la regula „nu se moștenește": subtask-urile create cu `scope.fork()` într-un `StructuredTaskScope` moștenesc automat legările ScopedValue ale thread-ului părinte, fără copiere (referință partajată la valoarea imutabilă).
- **De ce contează** — Face fan-out-ul concurent (capitolul 4) să funcționeze natural cu context: mii de subtask-uri văd același user/trace fără pasare explicită; granițele stricte ale scope-ului structurat garantează că la închidere thread-urile copil dispar, deci nu există leak.
- **Exemplu de cod**

```java
static final ScopedValue<String> USER = ScopedValue.newInstance();

ScopedValue.where(USER, "alice").run(() -> {
    try (var scope = StructuredTaskScope.open()) {
        var t1 = scope.fork(() -> fetchOrders(USER.get()));  // moștenit
        var t2 = scope.fork(() -> fetchProfile(USER.get())); // moștenit
        scope.join();
        render(t1.get(), t2.get());
    } catch (InterruptedException e) { throw new RuntimeException(e); }
});
```

- **Capcane frecvente** — A generaliza moștenirea la `new Thread(...)` sau la executor-uri clasice (acolo NU se moștenește, nici cu virtual threads); a deschide `StructuredTaskScope` în afara oricărei legări și a te mira de `NoSuchElementException` în subtask-uri.

### ScopedValue ca „capability object"

- **Definiție** — Deoarece accesul la valoare cere referința la obiectul `ScopedValue` însuși, modificatorii de acces (`private`, package-private) controlează exact cine poate citi contextul.
- **De ce contează** — Cu `ThreadLocal` static public, orice cod poate citi ȘI rescrie contextul; cu un `ScopedValue` privat, framework-ul poate expune doar accessor-i controlați — encapsulare + un strat de securitate.
- **Exemplu de cod**

```java
public final class Auth {
    private static final ScopedValue<Token> TOKEN = ScopedValue.newInstance();
    public static <T> T runAs(Token t, Callable<T> body) throws Exception {
        return ScopedValue.where(TOKEN, t).call(body);
    }
    static Token current() { return TOKEN.get(); } // doar package-ul Auth citește
}
```

- **Capcane frecvente** — A face toate ScopedValue-urile `public static final` din reflex, pierzând exact acest beneficiu.

## Listing-uri cheie din carte

Textul EPUB nu numerotează exemplele cu captions „Example 5-N"; le identific după secțiunea în care apar, în ordinea din capitol.

1. **„The Burden of Passing Context" — framework-ul de job scheduling cu context explicit** (`JobContext`, `JobScheduler`, `UserJob`). Demonstrează problema de bază: contextul plimbat prin fiecare semnătură, inclusiv prin helper-e care nu-l folosesc. Instructiv pentru că e punctul de referință față de care se măsoară toate soluțiile ulterioare.
2. **„Introducing ThreadLocal" — JobScheduler rescris cu ThreadLocal** (set în `schedule()`, `remove()` în `finally`, cod utilizator curat). Arată de ce pattern-ul e universal în framework-uri (Spring) și, implicit, câtă disciplină manuală cere (try/finally obligatoriu).
3. **„Limitations of ThreadLocal" — MutableLoggingContext**. Demonstrează mutabilitatea neconstrânsă: log level setabil de oriunde, izolare per-thread care derutează (main rămâne INFO, copilul e DEBUG).
4. **„Limitations of ThreadLocal" — ThreadLocalLeakExample**. Pool cu un singur thread, primul task uită `remove()`, al doilea task vede valoarea scursă („Alice"). Cel mai instructiv exemplu al capitolului pentru riscul de securitate din pool-uri.
5. **„Limitations of ThreadLocal" — InheritanceOverheadExample**. 10MB moșteniți de 100 de thread-uri copil prin `InheritableThreadLocal` — costul de memorie al moștenirii automate.
6. **„Core Components of ScopedValue" — JobScheduler rescris cu ScopedValue** (`newInstance()`, `where().run()`). Închide bucla cu exemplul 1: aceeași funcționalitate, fără mutabilitate și fără cleanup manual.
7. **„Core Components of ScopedValue" — PricingService cu call()**. Arată când folosești `call()` în loc de `run()`: ai nevoie de rezultatul calculat în scope.
8. **„Running ScopedValue" — seria isBound()**: (a) task rulat fără legare → „not bound"; (b) `where().run(task)` → „bound"; (c) același task rulat din nou după scope → „not bound"; (d) thread pornit din interiorul scope-ului → „not bound"; (e) legarea mutată în interiorul thread-ului nou → „bound". Seria aceasta e cea mai valoroasă didactic: fixează exact semantica scope-ului dinamic și ne-moștenirea între thread-uri.
9. **„Running ScopedValue" — MultiScopedExample**. Chaining `where(USER_ID,...).where(SESSION_ID,...)` cu ambele valori vizibile în tot call stack-ul.
10. **„Running ScopedValue" — ScopedValueDefaultsExample**. `orElse("Guest")` și `orElseThrow(...)` în stare nelegată vs. legată.
11. **„Rebinding ScopedValue in nested scopes" — ScopedValueRebindingExample**. Admin → Guest → Admin: restaurarea automată la ieșirea din subscope, aceeași metodă văzând valori diferite după scope.
12. **„ScopedValue and Structured Concurrency" — ScopedValueStructuredConcurrencyExample**. Două subtask-uri `fork()`-uite într-un `StructuredTaskScope` moștenesc legarea USERNAME — singura formă de moștenire.
13. **„Migrating to Scoped Values" — TemplateProcessor (recursion detection)**. Contor de adâncime modelat prin rebinding repetat (`where(RECURSION_DEPTH, depth+1)`), cu limită de siguranță contra template-urilor circulare; la retur, adâncimea anterioară se restaurează singură.
14. **„Migrating to Scoped Values" — FlattenedTransactionExample**. `isBound()` decide dacă operația imbricată se alătură tranzacției exterioare sau pornește una nouă — pattern-ul „flattened transactions".
15. **„Migrating to Scoped Values" — SimpleGraphicsExample**. Context de desenare (culoare, grosime) într-o ierarhie panel → butoane → text, cu override-uri imbricate și restaurare automată; fallback pe defaults prin `isBound()` în afara oricărui context.

## Citate

- „A ScopedValue acts as an implicit method parameter" — secțiunea „Core Components of ScopedValue".
- „they're meant to be a convenient place to hold state for the duration of a task, not a perpetual stash of memory" — secțiunea „Toward Lightweight Sharing".
- „ScopedValue has graduated from preview status and is now a stable API, ready for production use" — secțiunea „In Closing".

## Legături

- **Cap. 2 (Understanding Virtual Threads)** — precondiția întregului argument: abia la milioane de virtual threads defectele de memorie ale `ThreadLocal`/`InheritableThreadLocal` devin nesustenabile; fără a înțelege modelul de virtual threads (thread-uri ieftine, efemere, montate pe puține OS threads), motivația pentru ScopedValue pare doar cosmetică.
- **Cap. 3 (The Mechanics of Modern Concurrency)** — oferă mecanica (executor-uri, pooling) care explică leak-ul din pool-uri: același worker reutilizat între task-uri este exact scenariul în care `ThreadLocal` scapă date.
- **Cap. 4 (Structured Concurrency)** — cuplaj direct și esențial: `StructuredTaskScope.fork()` este singurul mecanism prin care legările ScopedValue se moștenesc în thread-uri copil, iar granițele stricte ale scope-ului structurat sunt cele care fac moștenirea sigură (fără leak la închidere). Cap. 5 este practic „propagarea contextului" pentru arborele de task-uri din cap. 4.
- **Cap. 6 (Reactive Java)** — propagarea contextului este una dintre marile dureri ale stack-urilor reactive (context pierdut între callback-uri/scheduler-e); ScopedValue + virtual threads oferă alternativa imperativă la acele soluții, argument pe care cap. 6 îl dezvoltă.
- **Cap. 7 (Modern Frameworks)** — framework-urile (gen Spring) sunt marii consumatori de `ThreadLocal` pentru security/transaction/request context; migrarea lor la ScopedValue este continuarea naturală a acestui capitol.
- **De stăpânit înainte de a merge mai departe**: semantica scope-ului dinamic (bound doar în interiorul `run()`/`call()` și în ce apelează acestea), regula moștenirii (NU la thread-uri arbitrare, DA la `fork()` în StructuredTaskScope), rebinding-ul cu restaurare automată și diferența de tratare a valorii lipsă (`NoSuchElementException` vs. `null`).
