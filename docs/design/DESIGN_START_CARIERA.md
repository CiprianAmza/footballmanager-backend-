# Design — începutul carierei și structura de putere din club

Document viu: se completează pe măsură ce discutăm. Ultima actualizare: 2026-11-10.

**Convenție:** „aici” = jocul meu, **Argus Football World (AFW)**. „acolo” = Football Manager oficial.

**Documente legate:** `DESIGN_JUCATORI_SI_PIATA.md` (rating, atribute, transferuri, negocieri).

**Codul verificat:** ultimul push pe GitHub (backend `13b0d86`, frontend `03e3e9f`, 2026-11-10).

---

## 0. Principii

- **Interpretarea mea, nu o copie a FM.** Jocul e o lume de fotbal în care pot fi și manager. Managerul e un rol printre altele (patron, DG etc.), nu centrul jocului. FM e doar un punct de plecare pentru idee, nu un etalon cu care compar fiecare funcție.
- **Lume inventată.** Cluburile sunt create de mine. Pentru că jucătorul nu are un atașament dinainte față de ele, jocul trebuie să-i construiască identitatea fiecărui club (istorie, rivali, vedete, așteptări).
- **Două moduri cu nevoi opuse:**

| | Modul autor (comentez, regizez) | Modul carieră (cu prietenii) |
|---|---|---|
| Plăcerea vine din | control: povestea iese cum am gândit-o | incertitudine: nu știu cum iese |
| Scor setat de mine | esențial (ex.: antrenorul „maestru defensiv” câștigă cu 1-0, 2-1) | oprit, sau vizibil pentru toți |

- **Motorul de meci, tactica și alegerea scorurilor** sunt ideea mea și nu fac obiectul acestei discuții.

---

## 1. Problemele de la start (constatate)

| # | Problemă | Gravitate | Stare |
|---|---|---|---|
| 1 | Nu pot alege ligile în focus; se calculează statistici pentru toate ligile, unele inutile; unele ligi durează mult | mică, nu blochează | de proiectat |
| 2 | Managerul nu se creează la cont; la login nu se încarcă managerul meu | medie | rezolvat în cod (setup după înregistrare, `/api/career/manager/setup`; status la login) |
| 3 | Nu există creare de personaj (aspect + date personale) | medie | de proiectat |
| 4 | Nu pot alege rolul (manager sau altceva) la start | medie | rezolvat în cod (manager / patron la înregistrare) |
| 5 | Ecranul de alegere a echipei e un cul-de-sac: nu pot vedea info despre club, lot, palmares, nici media prediction pe ligă | mare („nu-mi place”) | rezolvat (2026-11-12, §2.3) |

Ce există deja în cod, dar nelegat de parcursul de start:
- Cont: `/api/auth/register`. Legătura cont–manager: `User.managerId`.
- Roluri: `CareerRole` (`MANAGER`, `CHAIRMAN`).
- Pagini de club și pagina `media-prediction`.
- Fețe generate, dar doar pentru jucători. Fața managerului ar fi o funcție nouă.

---

## 2. Parcursul de start dorit

```
Cont nou → Creez managerul → Aleg rolul → Aleg ligile în focus → Răsfoiesc ligi și cluburi → Aleg clubul
Login ulterior → se încarcă managerul meu
```

### 2.1 Ligi în focus

Le aleg o singură dată, la start. Implicit sunt toate selectate.

| Nivel | Ce se simulează |
|---|---|
| În focus (liga mea, ale prietenilor) | simulare completă: statistici, ratinguri, evenimente |
| Fundal | rezultate, clasament, transferuri, jucători: lumea rămâne vie |
| Neîncărcată | nu există |

Scop: viteză la avansarea zilelor și mai puțin zgomot.

**În cod (push 2026-11-10):** există acum un profil opțional `fast-sim`: simularea rulează într-o bază H2 în memorie și salvează un checkpoint în PostgreSQL la fiecare 5 etape (`MemoryCheckpointService`). Atacă viteza din altă direcție. Ligile în focus ar reduce în plus volumul de calcul.

### 2.2 Crearea managerului (la crearea contului)

| Câmp | Consecință posibilă |
|---|---|
| Aspect | apare la presă, în profil, în social feed |
| Nume, prenume | presă, istoric |
| Data nașterii | vârsta: retragere; cât de multă răbdare are patronul |
| Locul nașterii | naționalitate: echipa națională; simpatie la cluburile din țară |
| Echipa preferată | fanii mă iubesc acolo; ostilitate la rivali |
| Tactica preferată | se leagă de `tacticStyle` / `knownTactics` existente |
| Istoric ca fotbalist | reputația de start (`managerReputation`), respectul jucătorilor |
| Licența de antrenor (dacă există, și tipul ei) | vezi §3 |

**Istoricul ca fotbalist (decis):** se alege dintr-o **listă de niveluri** (fără carieră, amator, ligă inferioară, prima ligă, internațional). **Opțional**, îl pot detalia: câte sezoane am jucat, iar pentru fiecare sezon echipa, golurile și assisturile.

**Reputația managerului (decis 2026-11-12):** pe scara cluburilor, 0–10.000. Managerul AI pornește cu reputația clubului lui (înainte: o treime). Managerul meu pornește după istoricul ca fotbalist:

| Istoric | Reputație |
|---|---|
| fără carieră | 1.000 |
| amator | 2.000 |
| ligă inferioară | 3.500 |
| prima ligă | 5.500 |
| internațional | 8.000 |

**Implementat (2026-11-12):** la „New Career” se aleg ziua și luna nașterii, țara, clubul preferat, tactica preferată, istoricul (cu sezoane opționale: club, goluri, assisturi) și licența (fără / portari / C / B / A / Pro — listă aleasă de agent, de confirmat). Se salvează și apar în profilul managerului („Background”). Consecințele (răbdarea patronului, fanii, regula licenței) vin în pașii următori.

**Aspectul (decis 2026-11-12):** un mic editor la creare: pentru fiecare parte a feței (ten, cap, păr, culoarea părului, sprâncene, ochi, culoarea ochilor, nas, gură, trăsături) aleg varianta cu săgeți, plus „Random”. Specia urmează țara de naștere, ca la jucători. Fața apare în profilul managerului.

### 2.3 Alegerea clubului

Din ecranul de alegere trebuie să pot:
- deschide clubul: informații, jucători, palmares;
- deschide liga clubului: media prediction.

**Implementat (2026-11-12):** pe ecranul „New Career”, fiecare club are butoanele Club / Squad / History, iar fiecare ligă „Media prediction”. Pagina se deschide peste ecranul de alegere, cu „Back to club selection”; numele, vârsta și clubul selectat rămân.

---

## 3. Licența de antrenor (aici mai realist decât acolo)

Acolo, licența doar îmi crește atributele și reputația. Aici e o regulă reală.

| Licență | Înregistrat oficial ca |
|---|---|
| Fără licență sau licență insuficientă | antrenor secund; pe hârtie conduce un antrenor de fațadă |
| Licență de portari | antrenor de portari |
| Licența potrivită ligii | antrenor principal |

Consecințe dacă nu am licența potrivită:
1. Unele cluburi mă refuză din start.
2. Patronul are mai puțină răbdare cu mine.
3. Presa mă critică.
4. Antrenorul de fațadă se poate întoarce împotriva mea (§4).

---

## 4. Antrenorul de fațadă

### 4.1 Ce face când nu mă mai ascultă

În meci:
- face schimbări decise de el;
- refuză schimbările impuse de mine;
- nu folosește primul 11 ales de mine.

În afara terenului:
- suporterii mă contestă când află că el e doar de fațadă. La început e un secret, iar descoperirea lui e un eveniment;
- pe rețelele sociale din joc apar comentarii strict despre asta (se leagă de `FanSocialFeedService`).

### 4.2 De ce se revoltă (toate trei se combină)

- **Rezultate proaste:** „eu îmi pun numele pe înfrângerile tale”.
- **Relația cu mine:** îl ignor, îi iau tot meritul în presă.
- **Personalitatea lui:** unii sunt orgolioși, alții acceptă orice pentru bani.

**Cum îl țin de partea mea (decis):** la fel ca pe orice alt membru din staff (§5.2). Antrenorul de fațadă are aceeași loialitate împărțită: de partea mea sau de partea DG-ului. Se mișcă prin aceleași evenimente: rezultate, presă, interacțiuni, influența DG-ului.

**Întrebare deschisă (minoră):** la cluburile fără DG, față de cine e împărțită loialitatea antrenorului de fațadă?

---

## 5. Structura de putere: Patron → Director General → eu

> **Doar la anumite cluburi.** Totul din această secțiune (DG cu agendă proprie, lupta de putere, loialitatea împărțită a staff-ului) se aplică numai cluburilor marcate cu un boolean **„DG conflict club”**. Bifa se setează din **editor**, club cu club.
>
> **Cluburile fără DG (decis):** majoritatea cluburilor nu au DG. La ele vorbesc direct cu patronul.
>
> **Vizibilitate (decis):** bifa e un câmp în baza de date. Jucătorul o află în două feluri:
> - **scrie în descrierea clubului** că e un club cu conflict la conducere;
> - **presa din joc** are articole despre conflict.
>
> **Înainte de semnare (decis):** conflictul e informație publică. Articolele de presă despre el se pot citi din preview-ul clubului (§2.3), deci jucătorul alege clubul în cunoștință de cauză.

### 5.0 Personalitatea DG-ului (decis)

Când un club are DG, acesta are una dintre următoarele personalități:

| Personalitate | Ce face |
|---|---|
| Neutru | doar treaba administrativă, fără agendă |
| Iubitor cu mine | e de partea mea, mă ajută |
| Loial patronului | îmi transmite doar ce vrea cu adevărat patronul; se poate comasa cu „neutru” |
| Cu ambiții personale | vrea ca ideile **lui** să fie puse în practică, nu ale patronului. Dacă nu-l ascult, mă „sapă”; dacă-l ascult, mă lasă în pace |

### 5.0.1 Model revizuit: Chairman interference (decis, înlocuiește ce e mai jos acolo unde se contrazic)

- Clubul are sau nu DG. Un DG **neutru** îmi transmite ce vrea patronul.
- Caz aparte: **DG conflict** și **Chairman interference**. Patronul vrea ceva, iar DG-ul trebuie să-mi transmită. Sunt 3 cazuri:

| Caz | Canal | Ce se întâmplă |
|---|---|---|
| 1 | prin DG, care are o relație bună cu mine | primesc dorința reală a patronului |
| 2 | prin DG cu ambiții personale | DG-ul își pune în aplicare **preferințele lui**, nu ce vrea patronul. Pot coincide cu ale patronului, dar e aleatoriu. Exemplu: are jucători preferați care „trebuie să joace, că așa vrea el” |
| 3 | patronul intervine **direct** | îmi impune echipa: el decide tactica și/sau jucători pe post, iar pozițiile alese de el sunt blocate. Exemplu: dacă a ales tactica și 9 jucători, eu aleg doar cele 2 poziții libere |

**Cazul 3 există deja parțial în cod** (versiunea de pe GitHub): `chairman/mandate` cu tabelele `chairman_tactical_mandate` (formația impusă) și `chairman_tactical_mandate_slot` (jucător impus pe fiecare poziție), plus `ChairmanTacticalMandateEnforcementService`. Mandatul e setat de un profil de patron (`updated_by_profile_id`).

**Cine e patronul (decis):** ambele variante.
- Dacă patronul e **AI**, AI-ul decide ce impune.
- Dacă patronul e un **user** (un prieten), decide el.

**Patronul user (decis):**
- poate **cumpăra o echipă** (există deja în cod partea de achiziție de club, migrarea `V4__regent_phase3_club_acquisition`);
- își poate **impune direct echipa** (cazul 3) sau poate **angaja un DG**;
- dacă se supără pe DG, îl dă afară și aduce altul, **sau** îl dă afară pe antrenor.

**Patronul AI dă afară (decis):** când rezultatele nu vin, îl dă afară pe cel în care are **încredere mai mică**, DG-ul sau antrenorul. Patronul are deci o valoare de încredere față de fiecare dintre ei.

**Ce mișcă încrederea patronului (decis):**

| Eveniment | Efect |
|---|---|
| Rezultate, trofee | cresc sau scad încrederea în antrenor |
| Aleg echipa cum vrea patronul | crește încrederea în mine |
| Nu aleg echipa cum vrea patronul | scade încrederea în mine |
| Ascult de DG, dar patronul voia altceva | scade încrederea în mine |
| Vorbesc direct cu patronul despre DG | pot scădea încrederea lui în DG. **Riscant:** îmi scade și relația cu DG-ul (§5.1) |
| Staff-ul nu e de partea mea | patronul **nu mă crede** când îi vorbesc despre DG |
| Patronul mă crede | scade încrederea lui în DG |
| Patronul nu mă crede | scade **încrederea DG-ului în mine** |

**Escaladarea (decis):**
1. Dacă încrederea DG-ului în mine scade prea mult, DG-ul cere la patron să fiu concediat.
2. Dacă încrederea patronului în DG e suficient de mare, sunt concediat.

**Valori (propunere, de confirmat):** toate pe o scară 0–100 și configurabile, nu scrise în cod.

| Valoare | Start | Efecte |
|---|---|---|
| Încrederea patronului în mine | 50 (mai puțin fără licență, §3) | victorie +2, înfrângere −2, trofeu +15, ascult de patron +3, nu ascult −5, ascult de DG împotriva patronului −5 |
| Încrederea patronului în DG | 50 + vechime (până la 80) | −20 când patronul mă crede |
| Încrederea DG-ului în mine | între 40 și 65, după personalitatea DG-ului | −5 la fiecare discuție directă cu patronul; încă −7 dacă patronul nu mă crede (deci −12 pentru o discuție nereușită) |
| Pragul la care DG-ul cere demiterea mea | sub 20 | discuții nereușite: de la 40 → 2; de la 50 → 3; de la 65 → 4 |
| Pragul la care patronul îl dă afară pe DG | încrederea în DG sub 20 | discuții reușite: DG nou (50) → 2; DG vechi (80) → 4 |

**Decis: discuția directă e foarte periculoasă.** Regula de calibrare: **2–4 discuții directe duc la demiterea cuiva.** Pe mine, dacă patronul nu m-a crezut; pe DG, dacă m-a crezut. Numărul exact depinde de vechimea DG-ului și de personalitatea lui.
| Pragul la care patronul acceptă cererea DG-ului | încrederea în DG ≥ 60 **și** mai mare decât încrederea în mine | altfel cererea e respinsă |
| Șansa ca patronul să mă creadă | procentul mediu de loialitate al staff-ului față de mine | ex.: staff 70% al meu → 70% șanse |

Rezultă bucla centrală: loialitatea staff-ului (§5.2) îmi dă credibilitate în fața patronului. Fără oameni de partea mea, nu pot câștiga lupta cu DG-ul vorbind cu patronul.

**Ce înseamnă „DG conflict” (decis):** conflictul e între **ambițiile DG-ului și ce vrea patronul**. DG-ul cu ambiții personale vrea altceva decât patronul, iar eu sunt prins la mijloc. Lupta de putere de mai jos (mesaje cu sursă incertă, cereri reciproce de demitere, staff loial lui) e cazul acestui DG.

```
Patron vrea A  ←── conflict ──→  DG vrea B
           \                      /
            └────── eu ──────────┘
```

**Din editor (decis):** clubul are sau nu DG, iar dacă are, i se setează personalitatea. Când DG-ul e cel cu ambiții personale, conflictul e public: apare în descrierea clubului și în presă.

**Întrebare deschisă:** dacă urmez ideile DG-ului și ele contrazic ce vrea patronul, patronul se supără pe mine?

```
Patron
  │  (nu vorbește neapărat direct cu mine)
  ▼
Director General: omul patronului, poate cu interese proprii
  │  „patronul ar cam vrea să joace X, că îi place”
  ▼
Eu (antrenor; eventual cu un antrenor de fațadă dedesubt)
```

- Patronul comunică prin DG, nu neapărat direct.
- DG-ul îmi transmite „dorințe” ale patronului. Nu știu dacă sunt ale patronului sau agenda DG-ului (de exemplu, o înțelegere cu agentul jucătorului).
- Dacă nu ascult, scade relația mea cu DG-ul.
- Dacă patronul se bagă peste mine, suporterii contestă, iar pe rețelele sociale din joc apar comentarii despre asta.

### 5.1 Ce pot face cu incertitudinea (decis)

**Verific direct la patron:**
- de fiecare dată, relația mea cu DG-ul scade automat;
- pot cere chiar demiterea DG-ului;
- dar dacă relația scade prea mult, DG-ul cere el demiterea mea la patron.

Rezultă o luptă de putere eu ↔ DG, în care patronul arbitrează.

**Îmi fac oameni de încredere:**
- surse proprii care îmi spun ce se întâmplă de fapt (de exemplu, un om din club sau un jurnalist).

**Întrebare deschisă:** când eu și DG-ul ne cerem reciproc demiterea, după ce decide patronul?

**Întrebare deschisă:** cine pot fi oamenii de încredere și cum îi obțin?

### 5.2 Loialitatea staff-ului (decis)

Fiecare membru din staff are o loialitate împărțită între mine și DG.

| Situație | Loialitate |
|---|---|
| Staff adus de mine | 100% eu |
| Staff găsit la club, angajat recent | aproape 50% eu / 50% DG |
| Staff găsit la club, vechi | înclinat spre DG, cu atât mai mult cu cât e mai vechi la club |

- Valoarea **nu se resetează** decât dacă DG-ul e demis.
- Asta răspunde parțial la „cine sunt oamenii de încredere”: în primul rând staff-ul pe care mi-l aduc.

**Întrebare deschisă:** ce face concret un om din staff care e, de exemplu, 80% al DG-ului?

**Cum se mișcă loialitatea (decis):**

| Eveniment | Efect asupra loialității față de mine |
|---|---|
| Rezultate bune / proaste | crește / scade |
| Vorbesc frumos despre ei în presă | crește |
| Interacțiuni cu ei (bune / rele) | crește / scade |
| DG-ul nu mă suportă și încearcă să-i convingă să fie împotriva mea | scade |

Precizare: „nu se resetează” înseamnă că punctul de plecare (vechimea) rămâne. Valoarea însă se mișcă în timp prin evenimentele de mai sus.

**Concedierea unui om din staff (decis):**
- Pot da afară oameni din staff dacă bănuiesc că nu mai au încredere în mine.
- **Valorile de loialitate sunt ascunse:** ca manager, nu le văd.
- Dacă DG-ul știe că un om e de partea lui, poate **forța păstrarea lui**. Atunci am două opțiuni:
  1. ascult de DG, iar omul rămâne;
  2. vorbesc cu patronul. De aici, două variante:
     - patronul îmi dă dreptate: omul pleacă, dar **scade încrederea DG-ului în mine**;
     - patronul îi dă dreptate DG-ului: omul rămâne.

**Penalizări (decis):** discuția cu patronul despre un om din staff **nu** contează ca o discuție directă (§5.0.1). Singurul efect e cel de mai sus: dacă patronul îmi dă dreptate, scade încrederea DG-ului în mine.

**Întrebare deschisă:** dacă valorile sunt ascunse, din ce indicii îmi dau seama cine nu e de partea mea?

**Când DG-ul e demis (decis):**
- Loialitatea staff-ului devine 100%.
- Aflu **neoficial**, de la asistentul meu, cum a primit fiecare vestea, ca text (de exemplu „s-a bucurat”, „e trist”), nu ca cifre.
- În funcție de asta, îi pot da afară.

Principiu care reiese: **valorile ascunse se dezvăluie prin text calitativ, de la asistent**, nu prin numere.

**Indicii de loialitate scăzută (decis): comportamentul omului.**
- nu vrea să facă ce îi cer;
- felul în care vorbește: îmi răspunde, dar textul arată că nu era încântat (de exemplu, „îți răspunde scurt, vizibil deranjat”).

**De ce l-aș da afară pe cel „trist” (decis):** pentru că m-a enervat. E o alegere de poveste, de manager, fără penalizare ascunsă în joc.

---

## 6. Prima zi după semnare

### 6.1 Articolul de presă despre venirea mea (decis)

Primul lucru după semnare e un articol de presă despre numirea mea. Conține:

1. **Cine sunt:** prezentarea managerului (din profilul creat, §2.2).
2. **Clubul și decizia:** de ce m-a ales clubul / de ce am ales clubul.
3. **Contextul clubului:** ce a făcut în ultima vreme și momente din istorie.
4. **Comentarii ale unor analiști.** Unii sunt de partea mea, alții nu. Tonul depinde de profilul meu. Dacă n-am experiență:
   - „antrenor slab”;
   - „o surpriză / o ciudățenie”;
   - „are potențial, suporterii trebuie să aibă încredere în el”.
5. **Statistici:** cât au rezistat ultimii antrenori la club și cât aș putea rezista eu.
6. **Dacă e DG conflict club:**
   - antrenorul e prezentat ca o rușine, care acceptă să fie marioneta patronului;
   - dacă sunt un antrenor celebru, tonul e și mai șocat: „cum poate un asemenea antrenor să se compromită?”.

**În cod (versiunea de pe GitHub):** `MediaNarrativeService` publică deja reacții după meci și zvonuri de transfer, dar nu un articol la numirea antrenorului.

**Analiștii (decis):** sunt **personaje fixe** ale lumii, cu nume și opinii care se țin minte. Sunt legați de **ligă și reputație**: fiecare ligă are analiștii ei, iar la cluburile și ligile cu reputație mare comentează analiști mai cunoscuți.

**Implementat (2026-11-12):**
- **Analiștii:** 3 pe ligă (critic, echilibrat, binevoitor; nume din țara ligii) + 2 internaționali, care comentează doar la cluburile cu reputația ≥ 8.000. Cam jumătate au un club favorit: la clubul lor vorbesc ca suporteri, la un rival (primele 3 cluburi ale ligii după reputație) cu rivalitate.
- **Tonul:** reputația mea față de a clubului: ≥ 100% → „alegere firească”, 70–100% → păreri împărțite, < 70% → critic „antrenor slab”, echilibrat „surpriză”, binevoitor „are potențial”.
- **Textele (decis de utilizator):** ~1.000 de fragmente scrise dinainte de agenți (985: titluri, cine sunt, decizia, contextul clubului, comentariile analiștilor, statistici despre antrenorii anteriori), în `press/appointment-fragments.json`, încărcate într-o tabelă și rotite (cel mai puțin folosit, apoi cel mai demult folosit). Locurile goale (`{manager}`, `{club}`…) se completează la scriere.
- **Livrarea:** articolul ajunge în inbox, categoria „Press”, la orice numire (carieră nouă cu club, ofertă acceptată, aplicare la un post).
- Secțiunea „DG conflict club” vine cu pasul 8.

### 6.1.1 Reporterii și stilul fiecărui jurnalist (decis și implementat 2026-11-13)

**Fără agent la rulare (decis de utilizator):** textele rămân scrise dinainte, ca la §6.1. Jocul doar alege și compune. Un agent la rulare ar cere cheie de API sau model local, ar încetini „Continue” și ar putea greși scorul.

**Reporterii:** pe lângă analiști (care sunt citați), fiecare ligă are reporteri, care scriu și semnează articolele: 2 pe meciuri și 1 pe transferuri, fiecare cu altă voce. Stau în aceeași tabelă (`analyst`), cu `role` = `PUNDIT` sau `REPORTER`. Cam jumătate au un club favorit, ca analiștii.

**Parametrii de stil** (coloane în `analyst`, se pot modifica direct în bază):

| Coloană | Valori | Ce face |
|---|---|---|
| `voice` | TABLOID, ANALYTICAL, OLD_SCHOOL, POLEMICIST, INSIDER, AGENCY | din ce set de fragmente scrie |
| `harshness` | 0–100 | tonul: 67+ critic, 34–66 echilibrat, sub 34 binevoitor; gol = se folosește `temperament` |
| `sensationalism` | 0–100 | șansa ca titlul să primească un supratitlu („MELTDOWN: …”) |
| `stats_focus` | 0–100 | șansa unui paragraf cu cifrele meciului (șuturi, pe poartă, posesie) |
| `verbosity` | 1–3 | 1 = verdictul; 2 = verdict + concluzie; 3 = două verdicte + concluzie |
| `reliability` | 0–100 | la zvonuri: credibilitatea afișată și cât de aproape e suma de cea reală |
| `stubbornness` | 0–100 | câte puncte de relație îi schimbă tonul cu o treaptă: 10 + valoarea/5 (50 → 20, ca înainte) |
| `beat` | MATCHES, TRANSFERS, BOARDROOM | despre ce scrie un reporter (BOARDROOM: încă nefolosit) |

Valorile de pornire se trag din intervalul vocii (un tabloid e zgomotos și puțin credibil, un reporter de agenție e sec și sigur). Lumile vechi primesc automat stil pentru analiștii existenți și reporteri noi, la primul articol.

**Tonul într-un articol:** tonul propriu, mutat de relația cu managerul (ca la §6.4), apoi o treaptă mai blând la clubul favorit și o treaptă mai dur la un rival al acestuia.

**Fragmentele:** tabela `press_fragment` are două coloane noi: `event_type` (APPOINTMENT, POST_MATCH, TRANSFER_RUMOUR) și `voice` (gol = se potrivește oricărei voci). Jurnalistul ia întâi fragmentele vocii lui; dacă nu are, pe cele comune. Sunt 1.897 de fragmente: 985 de numire, 828 pe voci (138 pe voce), 84 comune (supratitluri și cifre), în `press/appointment-fragments.json`, `press/voices/*.json`, `press/common-fragments.json`.

**Reacția după meci** (`MEDIA_REACTION`): titlu (cu sau fără supratitlu), linia cu scorul, apoi paragrafele după `verbosity`. Verdictul depinde de rezultat (victorie mare, victorie, egal cu multe goluri, egal, înfrângere, înfrângere mare), concluzia de rezultat și de ton. Cifrele apar doar dacă statisticile meciului sunt salvate.

**Zvonul de transfer** (`TRANSFER_RUMOUR`): îl scrie reporterul de transferuri din liga clubului cumpărător. Oferta din spate e reală; reporterul poate greși suma cu cel mult jumătate din ce-i lipsește până la 100 la `reliability` (90 → ±5%, 50 → ±25%). Stadiul (discuții avansate sau abia începute) vine din starea ofertei.

**Rezervă:** dacă liga nu are reporter sau lipsește un fragment, se publică textul simplu, nesemnat, de dinainte.

**Urmează (nedecis):** fragmente pe voci și pentru citatele analiștilor din articolul de numire; articolele despre finanțe scrise de un reporter BOARDROOM; autorul afișat separat în interfață.

### 6.2 Întâlnirea cu patronul și cu DG-ul (decis)

După articol urmează întâlnirea cu patronul și cu DG-ul (dacă clubul are DG):
- fiecare îmi spune câteva cuvinte;
- DG-ul mă poate întreba dacă sunt de partea lui;
- răspunsul meu mișcă **imediat** încrederea DG-ului în mine, în sus sau în jos (§5.0.1).

**Răspunsul la întrebarea DG-ului (decis):**
- e o **discuție privată**: patronul nu află ce i-am răspuns;
- am mai multe variante, fiecare cu alt efect asupra încrederii DG-ului în mine.

| Răspuns | Efect (propunere, de confirmat) |
|---|---|
| „Da, sunt cu tine” | +20 |
| „Depinde ce propui” | +5 |
| Evaziv: „vedem, abia am venit” | −5 |
| „Lucrez pentru club / pentru patron” | −15 |

### 6.3 Prezentarea situației clubului (decis)

După întâlnirea cu patronul și cu DG-ul, mi se prezintă:
- **transferurile în desfășurare;**
- **contractele care expiră curând;**
- **jucătorii nemulțumiți;**
- **cantonamentele care urmează;**
- **cum au loc antrenamentele;**
- **filozofia clubului.**

**În cod (versiunea de pe GitHub)** există deja bucăți de date pentru asta: contractele (`contractEndSeason`), jucătorii care vor să plece (`wantsTransfer`) sau stau pe bancă (`consecutiveBenched`), antrenamentul (`TrainingService`), ofertele de transfer. Lipsește prezentarea lor la sosire.

**În cod (push 2026-11-10):** există acum pagina „Manager day” (`/manager/today`, `home-today`), cu meciul zilei și o listă „Needs attention” (avertismente la primul 11, mesaje necitite, cereri ale conducerii). E o bază bună pentru prezentarea asistentului: aceeași idee de „ce trebuie să știi azi”, extinsă pentru prima zi.

**Cine face prezentarea (decis):** asistentul.

**Ce e asistentul (decis):** asistentul **nu e o entitate** din joc. Nu e un om din staff, nu are atribute, nu intră în sistemul de loialitate. E vocea prin care jocul îmi aduce informații: prezentarea de la sosire, reacțiile neoficiale ale oamenilor, indiciile. E mereu de partea mea, deci informația de la el e sigură.

**Implementat (2026-11-12):** imediat după articolul de presă vine un mesaj „Assistant” în inbox, cu secțiunile: transferuri în desfășurare (ofertele mele deschise, ofertele primite), contracte care expiră (sezonul acesta sau următorul), jucători nemulțumiți (cer transfer sau moral sub 60), cantonamente programate, cine se ocupă de fiecare tip de antrenament și cât de bun e, filozofia clubului.

**Filozofia clubului (decis 2026-11-12, pentru moment):** strategia de transferuri a clubului, spusă ca filozofie (Academy → „ne creștem propriii jucători”, Buy young sell high, Buy free sell high, Buy mid sell mid, Buy top sell worst, Buy top sell top). Doar prezentată, fără obligații.

Atenție la nume: nu e același lucru cu „antrenorul secund” din §3 (rolul în care sunt înregistrat fără licență), nici cu antrenorul secund din staff (în cod există `AssistantManagerService`).

### 6.4 Lotul, staff-ul, conferința de presă (decis)

Ordinea, după prezentarea asistentului:
1. **Întâlnirea cu lotul**
2. **Întâlnirea cu staff-ul**
3. **Conferința de presă**

**Le pot refuza pe toate.** Un refuz îmi scade încrederea sau reputația.

| Refuz | Ce scade (propunere, de confirmat) |
|---|---|
| Întâlnirea cu lotul | încrederea și moralul jucătorilor |
| Întâlnirea cu staff-ul | loialitatea staff-ului față de mine (§5.2) |
| Conferința de presă | reputația mea; presa și analiștii devin mai critici |

**În cod (versiunea de pe GitHub):** există `PressConferenceService`, `TeamTalkService` și opțiunea `attendPressConferences` pe manager.

**Întâlnirea cu lotul (decis):**
- **Obiective pe fiecare competiție:** discutăm fiecare competiție. Eu spun ce obiectiv am, jucătorii spun ce vor ei. Diferența dintre ele crește sau scade încrederea lor în mine.
- **Bonusuri și amenzi:** stabilesc un **cod de conduită**, cu bonusuri și amenzi.

**Obiectivele (decis și implementat 2026-11-12):** pagina „Squad Meeting”, o dată pe sezon. Ligă: titlu / top 3 / prima jumătate / evitarea retrogradării; cupă: câștig / finală / semifinală / sferturi / doar participare. Jucătorii așteaptă: la ligă după locul prezis de presă (1 → titlu, ≤3 → top 3, ≤ jumătate → prima jumătate), la cupă după rangul de reputație printre participanți (1 → câștig, 2 → finală, ≤4 → semifinală, ≤8 → sferturi). Relația fiecărui jucător cu mine: egal +3, o treaptă mai sus +5, două sau mai multe mai sus −5, mai jos −5 pe treaptă (însumat pe competiții).

**Codul de conduită (decis):**
- Se setează **înainte de întâlnire**, apoi se prezintă jucătorilor.
- Pentru fiecare abatere aleg sancțiunea:

| Abatere (exemple) | Sancțiune la alegere |
|---|---|
| Cartonaș galben | nimic / amendă de ½, 1, 2, 3 sau 4 săptămâni de salariu |
| Cartonaș roșu | la fel |
| Lipsă de la antrenament | la fel |
| (lista se completează) | |

**Bonusurile (decis):**
- Sunt **pe competiție**. Pentru fiecare competiție aleg cât dă clubul dacă se îndeplinește obiectivul: **minim / mediu / maxim**.
- **Bonus extra pentru un meci anume:** pot cere patronului un bonus special pentru un meci, ca să-i motivez suplimentar pe jucători.
- **Dacă patronul refuză, pot plăti suma din banii mei** (se leagă de economia personală a managerului).

**Standardul (decis 2026-11-12):** galben → nimic, roșu → 1 săptămână, lipsă de la antrenament → 1 săptămână (lipsa nu există încă ca eveniment în joc; regula așteaptă).

**Implementat (2026-11-12):** pagina „Code of Conduct” (meniu, lângă Training). Amenda = salariul lunar × 12/52 × săptămâni, luată la fiecare cartonaș al unui jucător de-al meu și plătită clubului (o singură dată pe cartonaș). Reacția (implicit ales de agent, de reglat): fiecare jumătate de săptămână peste standard (și peste ce era deja în vigoare) scade moralul fiecărui jucător cu 2, maximum 10, o singură dată, la salvare. Codul se setează acum oricând; legarea lui de întâlnirea cu lotul vine cu restul întâlnirii.

**Reacția la cod (decis):**
- Codul vine **precompletat cu standardul**.
- Jucătorii se supără **doar dacă pun sancțiuni mai mari decât standardul**. Dacă las standardul sau scad sancțiunile, nu se supără.

**Bonusul plătit din banii mei (decis):**
- jucătorii mă apreciază mai mult;
- patronul **nu** se simte umilit, încrederea lui nu se schimbă;
- presa află și scrie despre asta.

**Întâlnirea cu staff-ul (decis):**
- **Le spun ce aștept:** stilul de antrenament, filozofia, rolul fiecăruia. Diferența dintre ce vreau eu și cum lucrau ei mișcă loialitatea (§5.2).
- **Primele indicii:** fiecare îmi răspunde într-un fel, iar textul arată cine e încântat și cine nu, fără cifre (§5.2).
- **Decizii de personal:** cine rămâne, pe cine dau afară, pe cine îmi aduc. La un DG conflict club, DG-ul poate forța păstrarea cuiva (§5.2).

**Prima conferință de presă (decis):**
- **Întrebări legate de articolul de numire** (§6.1). Exemplu: analistul care m-a numit „antrenor slab”, iar eu îi răspund.
- **Întrebări despre obiective.** Răspunsurile mele devin **promisiuni publice**, pe care suporterii și patronul le țin minte.
- **La un DG conflict club, întrebări incomode:** „ești marioneta patronului?”. Spre deosebire de discuția privată cu DG-ul (§6.2), ce spun aici e public și poate ajunge la DG și la patron.

---

## 7. Întrebări deschise (index)

1. ~~Istoricul ca fotbalist?~~ Decis: listă de niveluri, opțional detaliat pe sezoane (§2.2)
2. ~~Cum țin antrenorul de fațadă de partea mea?~~ Decis: ca pe orice om din staff (§4.2). Minor deschis: la cluburile fără DG, față de cine e împărțită loialitatea lui?
3. ~~Cum gestionez incertitudinea mesajelor de la DG?~~ Decis: verific la patron + oameni de încredere (§5.1)
4. ~~Când eu și DG-ul ne cerem reciproc demiterea, după ce decide patronul?~~ Decis: după încredere (§5.0.1)
5. Cine pot fi oamenii de încredere și cum îi obțin? Parțial decis: staff-ul adus de mine (§5.2)
6. Ce face concret un om din staff loial DG-ului? Parțial decis: nu mă susține în fața patronului, deci patronul nu mă crede (§5.0.1)
7. Loialitatea se mută treptat? Decis: da, prin rezultate, presă, interacțiuni și influența DG-ului. Rămâne deschis: ce se întâmplă cu ea la demiterea DG-ului? (§5.2)
8. ~~La cluburile fără „DG conflict club”: fără DG, sau DG neutru?~~ Decis: majoritatea cluburilor nu au DG; cele cu DG au 4 personalități (§5, §5.0)
11. ~~Bifa = „are DG”?~~ Decis: editorul setează DG + personalitate; „DG conflict” = ambiția DG-ului ≠ dorința patronului, conflict public (§5.0)
12. ~~Dacă ascult de DG împotriva patronului, se supără patronul?~~ Decis: da, scade încrederea în mine (§5.0.1)
13. ~~Cazul 3: patronul e AI, un prieten, sau ambele?~~ Decis: ambele; patronul user cumpără clubul, impune echipa sau angajează DG, și poate da afară DG-ul sau antrenorul (§5.0.1)
14. ~~Patronul AI: pe cine dă afară?~~ Decis: pe cel în care are încredere mai mică (§5.0.1)
15. ~~Ce mișcă încrederea patronului?~~ Decis: rezultate, trofee, ascultare, discuții directe, susținerea staff-ului (§5.0.1)
16. ~~Discuția despre un om din staff = discuție directă?~~ Decis: nu (§5.2)
17. ~~Loialitatea e ascunsă: din ce indicii?~~ Decis: comportamentul omului (refuză, răspunde fără chef) și reacțiile raportate neoficial de asistent, totul ca text (§5.2)
18. ~~De ce l-aș concedia pe cel „trist”?~~ Decis: pentru că m-a enervat; alegere de poveste (§5.2)
19. ~~Analiștii: fixi sau generați?~~ Decis: personaje fixe, în funcție de ligă și reputație (§6.1)
20. ~~Prima întâlnire: variante de răspuns? Află patronul?~~ Decis: mai multe variante cu efecte diferite; discuție privată, patronul nu află (§6.2)
21. ~~Cine face prezentarea?~~ Decis: asistentul, care nu e o entitate, ci vocea jocului; mereu de partea mea (§6.3)
22. ~~Ce se întâmplă la întâlnirea cu lotul?~~ Decis: obiective pe competiții (eu vs ei) și cod de conduită cu bonusuri și amenzi (§6.4)
23. ~~Codul de conduită: reacția jucătorilor?~~ Decis: sancțiuni pe abatere (amendă ½–4 săptămâni); codul vine precompletat cu standardul; jucătorii se supără doar peste standard (§6.4)
24. ~~Bonus extra plătit din banii mei: alte efecte?~~ Decis: jucătorii mă apreciază, patronul nu se supără, presa scrie (§6.4)
9. ~~„DG conflict club” e vizibil sau se descoperă?~~ Decis: câmp în baza de date, aflat din presă (§5)
10. ~~Presa despre conflict: înainte sau după semnare?~~ Decis: înainte, e informație publică (§5)
