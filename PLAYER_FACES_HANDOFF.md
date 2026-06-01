# Player Faces / Nations / Species — Handoff (sesiune 2026-06-01)

Sistem de **fețe procedurale** de jucător (SVG determinist, fără assets externe), cu identitate
**per-națiune** și **specii exotice**. Două repo-uri:
- **Backend** (git, master): `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-`
- **Frontend** (Angular 15, NgModule, **NU e git**): `/Users/ciprian.amza/Downloads/footballmanager-frontend-test/`
- **Demo galleries** (throwaway, NU în repo): `/tmp/face-variants/` (`variant-*.html`, `shot-*.png`, `face-lib.js`, `gen-*.js`).

Build: backend `mvn verify`; frontend `npx ng build` (din rădăcina FE).

---

## 1. Ce s-a făcut în această sesiune

### Backend (comis pe master)
| Commit | Ce |
|---|---|
| `85008a9` | Card FUT: `PlayerCardService.extractFaceDescriptor` populează descriptorul din câmpurile de față (era mereu null). |
| `1f5fbbd` | Indici de formă independenți per jucător: `faceShape/noseShape/eyeShape/mouthShape` (Human + FaceGenerator + PlayerView + populate + descriptor). |
| `304fe8f` | Forme ponderate pe națiune (regiune „alien") + `nationId` setat și pe calea squad/pitch (`TacticController.adaptPlayer`). |
| `f5fa92e` | Identitate structurală per-națiune: pool-uri `NATION_HEAD`/`NATION_HAIR`/`NATION_BROW` + câmp nou `browShape`. |
| `27d90f6` | Literature: pool de cap „lunar" (crescent-moon / onion-dome). |

`mvn verify` verde după fiecare (92 IT + unit, 0 fail). `prebuilt-data.sql` regenerat după fiecare schimbare de schemă (Human a primit coloane).

### Frontend (pe disc, FE necomitabil de aici)
- Componenta reutilizabilă **`<app-player-face>`** (`src/app/player-face/`): randează fața din descriptor ca SVG (injectat sanitizat prin `[innerHTML]`).
- 3 stiluri anime mature masculine: `@Input() style: 'seinen'|'sports'|'premium'` (default **`sports`**).
- Catalog forme: faceShape **18**, noseShape 10, eyeShape 10, hairStyle 20, browShape 9, earShape 5, mouthShape 5, wrinkle 3; palete vii pentru skin/hair/eye.
- **Semnături per-națiune** (8 națiuni, randate din `@Input() nationId`): International (neutru), Gallactick (alien+antenă), Dong (markaje tribale), Khess (war-paint+urechi ascuțite), FootieCup (sheen auriu), Cards (iris-stea/hex + ♠), Literature (glif „A"), Eleven („11"/cyber). Tabel `NATION_STRUCTURE` (head/brow/hair favorizate per națiune).
- **5 specii exotice** via `@Input() species` (default `'human'` → totul existent neatins):
  `'crystalline'` (gem-golem fațetat), `'saurian'` (reptilian), `'monument'` (statuie clasică marmură/bronz = „Indimenticabili"), `'rokykario'` (rocă vulcanică/lavă), `'eleftamide'` (avian/spirit liber). Fiecare = `drawXxx()` self-contained + paletele ei.
- Pozele pe **squad** mărite (size 54). Cablat la call-sites: `[faceShape]…[mouthShape] [browShape] [nationId]` în player/card/squad/tactics5.
- Galerii demo per stil + per-națiune + per-specie în `/tmp/face-variants/` (`shot-nations.png`, `shot-species{,2,3,4,5}.png`).

---

## 2. Arhitectură (modelul descriptorului)

Fața fiecărui jucător = un set de **indici independenți**, stocați pe `Human`, randați prin straturi SVG.
Orice combinație e validă (orice nas cu orice păr etc.).

**Câmpuri pe `Human`** (`model/Human.java`, toate `int`, `@Column default 0`):
`baseFaceId, skinTone, hairStyle, hairColor, eyeColor, faceShape, noseShape, eyeShape, mouthShape, browShape`.

**Generare** — `service/FaceGenerator.java` `assignFace(Human player, long nationId)`:
- Seed determinist = `new Random(player.getId())`.
- Culori ponderate pe națiune: `SKIN_TONE_WEIGHTS`, `HAIR_COLOR_WEIGHTS`.
- Forme structurale din pool-uri per-națiune: `NATION_HEAD[nation]` → faceShape, `NATION_HAIR[nation]` → hairStyle, `NATION_BROW[nation]` → browShape (via `pickFrom`).
- Nas/ochi: split human(low)/alien(high) cu probabilitate `alienness(nationId)` (Gallactick 0.90, restul ~0.04) via `pickShape`.
- mouthShape uniform.
- Apelat în `service/SquadGenerationService.buildOnePlayer` (după save, seed=id), `nationId` din `NationService.nationIdForTeam`.

**Națiune** — `service/NationService.java`: 8 națiuni (id 0-7), derivat `Team.competitionId → Competition.nationId`; `infoForTeam` → `{id, name, flagCode}`.

**Expunere** — `frontend/PlayerView.java` are toate câmpurile de față + nation; populat în:
- `controller/HumanController.buildPlayerView` (player detail, /humans),
- `controller/TacticController.adaptPlayer` (squad `/tactic/getPlayers`, pitch).
- `service/PlayerCardService.extractFaceDescriptor` → map pentru `/humans/{id}/card`.

**Randare FE** — `src/app/player-face/player-face.component.ts`:
- `@Input`: `baseFaceId, skinTone, hairStyle, hairColor, eyeColor, faceShape, noseShape, eyeShape, mouthShape, browShape, nationId, size, style, species`.
- `NATION_STRUCTURE` (head/brow/hair favorizate per națiune) + `nationSignature()` (overlay per națiune).
- `drawCrystalline/drawSaurian/drawMonument/drawRokykario/drawEleftamide` (species) + dispatch.
- **Mirror demo:** `/tmp/face-variants/face-lib.js` re-implementează aceeași logică pentru galeriile standalone.

---

## 3. Pași pentru a adăuga o NAȚIUNE NOUĂ (identitate vizuală)

> NB: o națiune **jucabilă** nouă (a 9-a ligă) necesită și schimbări de structură de joc în
> `BootstrapService.initializeCompetitions` + competiții — în afara sistemului de fețe. Mai jos doar
> identitatea VIZUALĂ a unei națiuni (presupunând că `nationId` există).

1. **Backend `NationService`**: adaugă intrarea în `NATIONS` (`id`, `name`, `flagCode`).
2. **Backend `FaceGenerator`**: adaugă pentru noul `nationId`:
   - `SKIN_TONE_WEIGHTS`, `HAIR_COLOR_WEIGHTS` (culori),
   - `NATION_HEAD`, `NATION_HAIR`, `NATION_BROW` (pool-uri de forme favorizate),
   - eventual o intrare în `alienness()`.
3. **FE `player-face.component.ts`**:
   - adaugă rândul corespunzător în `NATION_STRUCTURE` (head/brow/hair — pentru preview),
   - adaugă o **semnătură** în `nationSignature()`/helperele `sig*` (marker distinctiv: iris special, markaje, glif etc.).
   - Mirror identic în `/tmp/face-variants/face-lib.js`.
4. **Demo**: regenerează galeria per-națiune (`/tmp/face-variants/gen-galleries.js` → `variant-nations.html` + `shot-nations.png`).
5. **Verify + commit + snapshot**: `mvn verify` verde → commit backend → regenerează `prebuilt-data.sql`
   (`--bootstrap.use-pre-built-data=true --bootstrap.rebuild-pre-built-data=true`, așteaptă log
   „Saved pre-built data", oprește app). `npx ng build` verde pe FE.

---

## 4. Pași pentru a adăuga o SPECIE NOUĂ

1. **FE `player-face.component.ts`**:
   - extinde uniunea `@Input() species` cu noua valoare (ex. `'cyborg'`),
   - adaugă paletele ei (ex. `CYB_*`) + funcția `drawCyborg(): string` **self-contained** (nu atinge celelalte specii / human),
   - adaugă în dispatch: `if (this.species === 'cyborg') return this.drawCyborg();`.
   - Culorile o modulează: `skinTone`→material, `eyeColor`→glow, `hairColor`→accent, `baseFaceId`→jitter determinist.
   - **Mirror identic** în `/tmp/face-variants/face-lib.js` (palete + `drawCyborg` + dispatch).
2. **Demo**: nou `/tmp/face-variants/gen-speciesN.js` (după modelul `gen-species5.js`) → `variant-speciesN.html` + `shot-speciesN.png` (screenshot headless-Chrome **time-boxed ~60s** ca să nu stăluiască). `npx ng build` verde.
3. **(Plasare în joc — încă NEFĂCUTĂ, vezi §5):** ca specia să apară pe jucători reali trebuie:
   - câmp `species` (String) pe `Human` + în `PlayerView` + populat în `buildPlayerView`/`adaptPlayer` + în `faceDescriptor`,
   - `FaceGenerator` setează `species` per națiune/raritate (ex. o națiune nouă = o specie, sau un mic procent random),
   - FE call-sites pasează `[species]="...||'human'"` (player/card/squad/tactics4/tactics5),
   - `mvn verify` + commit + regen snapshot.

---

## 5. Stadiu / goluri cunoscute (NEXT)

- **Speciile NU sunt încă în joc.** Cele 5 (`crystalline/saurian/monument/rokykario/eleftamide`) există în
  componentă + demo, dar **niciun call-site nu pasează `[species]`** și **nu există câmp `species` pe Human/PlayerView** → în app toți sunt `'human'`. De cablat (vezi §4 pasul 3) + decisă plasarea (națiune/raritate). User-ul a sugerat: poate **națiuni noi** pentru specii (Crystalline + restul).
- **tactics4** (pagina principală de tactică, randată de un agent separat) — în face-cards trebuie să paseze și `[browShape]` și `[nationId]` (altfel sprâncenele/semnătura nu apar pe pitch-ul principal). tactics5 le pasează deja.
- O **națiune jucabilă** nouă cere și `BootstrapService`/competiții (structură de joc), nu doar identitatea vizuală.
- Galeriile `/tmp/face-variants/` sunt throwaway (nu în repo). `face-lib.js` e mirror manual al componentei — ușor de desincronizat; ține-le identice.

---

## 6. PROMPT DE CONTINUARE (pentru altă sesiune)

```
Continuă feature-ul de FEȚE DE JUCĂTOR / NAȚIUNI / SPECII din proiectul football-manager.
Backend (git, master): /Users/ciprian.amza/IdeaProjects/footballmanager-backend- (Java + Spring Boot, `mvn verify`).
Frontend (Angular 15, NgModule, NU e git): /Users/ciprian.amza/Downloads/footballmanager-frontend-test/ (`npx ng build`).
Demo galleries throwaway: /tmp/face-variants/.

CITEȘTE ÎNTÂI: PLAYER_FACES_HANDOFF.md din rădăcina backend — descrie tot sistemul (descriptor pe Human,
FaceGenerator cu pool-uri/weights per-națiune, NationService, PlayerView/adaptPlayer, PlayerCardService,
componenta FE player-face cu @Input style/species + NATION_STRUCTURE + semnături, mirror face-lib.js,
și pașii de adăugare nație/specie).

STARE: 8 națiuni cu identitate vizuală (cap/păr/sprâncene per-națiune + semnătură), 5 specii exotice în
componentă (crystalline/saurian/monument/rokykario/eleftamide) DOAR ca demo (default species='human').
Build verde pe ambele.

TASK PRINCIPAL (rămas): adu speciile ÎN JOC. Adaugă câmp `species` (String) pe Human + PlayerView +
populare în HumanController.buildPlayerView și TacticController.adaptPlayer + în PlayerCardService
faceDescriptor; FaceGenerator setează `species` per națiune/raritate (confirmă cu userul maparea
națiune→specie sau procentul de raritate ÎNAINTE); FE: pasează [species] la TOATE call-site-urile
app-player-face (player/card/squad/tactics5; tactics4 e separat — coordonează). De asemenea, în tactics4
adaugă [browShape] și [nationId] în face-cards.

CONVENȚII: userul face de regulă commit-urile, dar în runda asta a delegat commit-urile de fețe — confirmă.
Oprește-te la `mvn verify` + `npx ng build` VERZI. După schimbări de schemă Human, regenerează
prebuilt-data.sql (rulează jar-ul cu --bootstrap.use-pre-built-data=true --bootstrap.rebuild-pre-built-data=true,
așteaptă logul „Saved pre-built data", oprește procesul). NU atinge src/app/player-face/ dacă rulează alt
agent pe el. Răspunsuri scurte.
```
