# Name Lab — plan pentru un tool Python de învățare a preferințelor de nume

Scop: un program interactiv care propune nume inventate construite din familii de fragmente,
primește note 1–100 de la utilizator, învață ce îi place și distilează rezultatul în
pattern-uri per nație, exportabile ca `NameStyle` pentru backend-ul Java
(`com.footballmanagergamesimulator.nameGenerator.NameStyles`).

## Context din backend (nu presupune, verifică)

- Java 17, motorul de nume e config-driven: `NameStyle.of(id, pools, patterns)` cu
  pool-uri `PREFIX/VOWEL/MIDDLE/SUFFIX` și pattern-uri ponderate (`NameStyle.Pattern(weight, poolKeys)`).
  Duplicarea unui fragment într-un pool = probabilitate mai mare.
- Nații (NationService): 1=Gallactick, 2=Dong, 3=Khess, 4=FootieCup, 5=Cards, 6=Literature, 7=Eleven.
  Stiluri existente: ELEVEN (latin), KESS (gutural), VARD (nordic), LIRA (melodic). Lipsesc: 4, 5, 7.

## Arhitectură propusă

Proiect separat `name-lab/` (Python 3.11, fără legătură de build cu Maven):

```
name-lab/
  fragments/           # familii de fragmente per stil, JSON (sursa de adevăr)
    anglo.json, card.json, nippo.json, ...
  namelab/
    generator.py       # generatori de candidați
    features.py        # extragere de trăsături
    model.py           # scorer + incertitudine
    active.py          # selecția următorului batch
    store.py           # persistența notelor (SQLite)
    export.py          # distilare -> JSON NameStyle + codegen Java
    app.py             # UI de notare (Streamlit)
  data/ratings.db
  exports/<style>.json / .java
```

### 1. Generatorul de candidați (două moduri)
- **Fragment mode** (paritate cu Java): concatenare pool-uri după un pattern; fiecare nume
  păstrează proveniența (stil, pattern, fragmentele folosite) — esențial pentru distilare.
- **Markov mode** (opțional, faza 4): lanț Markov de ordin 2–3 pe caractere, antrenat pe
  numele cu note mari, ca sursă de fragmente/nume noi în afara pool-urilor inițiale.
- Filtru de pronunțabilitate la generare: max 2 consoane consecutive la joncțiuni
  (configurabil per stil), lungime 3–16, fără triple litere.

### 2. Trăsături (features.py)
Per nume: n-grame de caractere (2–3), raport vocale/consoane, lungime, silabe estimate,
id-urile fragmentelor folosite (one-hot), pattern-ul folosit, bigrame de joncțiune
(ultimul caracter al unui fragment + primul al următorului — acolo se strică pronunția).

### 3. Modelul (model.py)
Date puține (sute de note), deci NU deep learning:
- Scorer: **Ridge regression** sau **GradientBoostingRegressor** (scikit-learn) pe features → notă prezisă 1–100.
- Incertitudine: ansamblu mic (5 modele pe bootstrap) → varianța predicțiilor.
- Alternativă dacă notele absolute sunt zgomotoase: perechi „care din două?” + model
  Bradley–Terry (preference learning). UI-ul poate colecta ambele; începe cu note 1–100.

### 4. Bucla activă (active.py + app.py)
- Batch de 10: ~6 exploatare (scor prezis mare), ~3 explorare (varianță mare), ~1 complet
  aleator. Re-antrenare după fiecare batch (datele sunt mici, e instant).
- UI Streamlit: numele mare pe ecran, slider 1–100, context de nație selectabil, istoric.
- Stocare: SQLite `ratings(name, style, nation, pattern, fragments_json, rating, ts)`.

### 5. Profiluri per nație
Un model per nație (nu unul global cu tag): gusturile pot diferi complet între nații și
datele nu se contaminează. Pornește fiecare nație de la 1–2 familii de fragmente candidate.

### 6. Distilare + export (export.py) — puntea către Java
Modelul învață pe nume, dar Java vrea pool-uri și pattern-uri. Distilarea:
1. Scor mediu prezis per **fragment** (media numelor care îl conțin, controlat pe pattern).
2. Taie fragmentele sub prag; pe restul, greutate ∝ scor (implementată prin duplicare în pool,
   rotunjită la 1–3 apariții — exact mecanismul suportat de `NameStyle`).
3. Scor mediu per **pattern** → weight-urile pattern-urilor.
4. Emite: `exports/<style>.json` (pools + patterns) și un bloc Java gata de lipit în
   `NameStyles` (constanta `NameStyle.of(...)`).
5. Validare: rulează generatorul fragment-mode pe configul distilat și afișează 30 de nume
   pentru un ultim ochi uman înainte de export.

### Faze de livrare
1. **F1**: fragments JSON + generator fragment-mode + UI notare + SQLite. (utilizabil imediat)
2. **F2**: features + model + bucla activă.
3. **F3**: profiluri per nație + distilare + export JSON/Java.
4. **F4** (opțional): Markov mode pentru fragmente noi; import automat în backend.

### Constrângeri
- Doar Python standard + scikit-learn, pandas, streamlit. Fără DL/GPU.
- Tool-ul NU modifică backend-ul; exportul e copy-paste (sau PR separat).
- Seed-uri fixe peste tot ca sesiunile să fie reproductibile.

## Familii de fragmente de pornire (pentru națiile fără stil)

- **FootieCup (4) — „anglo”**: PREFIX Har, Wil, Ash, Bram, Clif, Dun, Fen, Gar, Hol, Mor, Stan, Wes;
  MIDDLE l, r, n, rd, st, mb; VOWEL a,e,i,o; SUFFIX ton, by, son, field, ley, ford, wick, ham.
  Ex: Harleton, Wesford, Ashenby, Morston.
- **Cards (5) — „card” (scurt, tăios)**: PREFIX Az, Rex, Kar, Dam, Jov, Quin, Tarr, Vex, Zan;
  MIDDLE k, d, rd, x; VOWEL a,e,o,u; SUFFIX ex, ok, ard, ux, is, an.
  Ex: Kardex, Azekan, Vexudis, Quinard.
- **Eleven (7) — „nippo” (echipa Inazuma Japan există deja în seed)**: PREFIX Ka, Ta, Shi, Ma, Hi, Ren, Go, Yu;
  MIDDLE k, nd, m, sh, z; VOWEL a,i,o,u; SUFFIX moto, ura, shima, da, ro, ki.
  Ex: Kamoto, Tashima, Renzuki, Hindaro.
