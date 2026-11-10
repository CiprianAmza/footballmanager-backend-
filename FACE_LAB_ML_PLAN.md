# Face Lab — plan pentru evoluția de specii noi prin mix aleator + voturi

Scop: un sistem care generează fețe de specii noi prin combinarea aleatorie a axelor vizuale
(siluetă cap, ochi, signature feature, palete), le arată într-o galerie, primește note 1–100
de la utilizator, învață preferințele și distilează câștigătorii în renderer-e de specie
gata de pus în producție (TS + cele 2 linii de plumbing).

## Context verificat (nu presupune, reconfirmă la start)

- Backend: `FaceGenerator.java:101` — `NATION_SPECIES = Map.of(1→eleftamide, 2→rokykario,
  3→saurian, 5→crystalline, 6→monument)`; națiile 0/4/7 sunt încă human. `assignFace` e
  determinist pe `player.getId()` și setează `Human.species` (String, fără enum — nimic de migrat).
- Frontend (`/Users/ciprian.amza/Downloads/footballmanager-frontend-test/`):
  `player-face.component.ts:2183` `buildInner()` — dispatch pe specie; **`drawAquanimenti()` există
  și e deja dispatch-uită (linia 2189), dar nemapată în backend** — e o specie „gratis".
- Fiecare renderer de specie: 100–170 linii de SVG prin string concat, canvas fix 100×100,
  3 palete × 12 intrări (indexate de `skinTone`/`eyeColor`/`hairColor`), și același schelet
  în 7 pași: palete cu modulo-wrap → I/IW din `this.style` → uid pentru clipPath → geometrie
  jitterată din `baseFaceId` → path de cap + clipPath → planuri de shading (lt/md/dk/hl) →
  ochi + signature desenată ultima.
- Fricțiune cunoscută: NU există galerie de preview (nicio rută). Se iterează orbește.

## Ideea centrală: genom parametric, nu renderer-e copiate

Speciile existente diferă pe 3 axe (siluetă / ochi / signature) + palete, dar repetă scheletul.
Face Lab introduce **un singur renderer parametric** `drawParametric(genome)` care poate exprima
orice combinație; o „specie" devine un punct în spațiul genomului. Mixul aleator și votarea
operează pe genomi; la final genomul câștigător se îngheață prin codegen într-un `drawX()`
clasic, ca cele existente (nu livrăm renderer-ul parametric în producție).

### Genomul (JSON)
```
{
  silhouette: { family: faceted|beaked|carved|plated|teardrop|dome|smooth,
                width, jawRatio, cranFlat, jitterSeed },   // numerice 0..1
  eyes:       { type: slit|verticalPupil|hollowGlow|molten|raptorRound|sphericalLidless,
                size, spacing, tilt },
  signature:  { type: thirdEyeGem|dorsalCrest|laurel|rockCrest|featherCrest|gills|hornPair|anglerLure|none,
                intensity },
  shading:    { planes: 2..4, contrast },
  palettes:   { skin: [12 hex], shade: [12], accent: [12] },  // sau referință la o familie predefinită
  background: none | preset-id
}
```
Familiile de silvete/ochi/signature pornesc de la cele 6 specii existente (extrase din
renderer-ele lor) + 2–3 noi per axă. Speciile existente devin astfel genomi de referință —
utile și ca sanity check al renderer-ului parametric (trebuie să le poată aproxima).

## Arhitectură

```
frontend (dev-only)                       backend                    face-lab/ (Python)
────────────────────                      ─────────────────────      ──────────────────
/dev/face-gallery  (ruta nouă)            DevFaceLabController       learner.py  (scorer + GA)
  grid 12 fețe × slider 1–100    ──POST──▶  /api/dev/facelab/votes     features din genom
  încarcă generația curentă      ◀──GET───  /api/dev/facelab/batch     export next-gen JSON
drawParametric(genome)  (modul separat)     stochează JSON pe disc    codegen.py → drawX() TS
```

- **Galeria** rezolvă și fricțiunea existentă: un tab „specii existente × palete" (randează
  cele 6 + human) și un tab „evoluție" (generația curentă de genomi).
- **Voturile**: notă 1–100 per față; opțional și A/B pe perechi. Persistate ca JSONL:
  `{genome, rating, ts, generation}`.
- **Learner (Python, scikit-learn)**: features = one-hot pe axele categorice + numericele
  genomului + interacțiuni siluetă×signature; GradientBoosting în ansamblu de 5 pentru
  scor + incertitudine. Date puține ⇒ fără deep learning.
- **Bucla evolutivă (GA ghidat de model)** per generație (~24 genomi):
  - 8 elită mutată ușor (numericele ±10%), 8 crossover între părinți cu note mari
    (axele se moștenesc întregi — exact „mix aleator" cerut), 4 explorare (incertitudine
    maximă după model), 4 complet aleatorii.
  - Constrângeri hard la sampling: contrast minim între skin și accent, signature să nu
    iasă din canvasul 100×100, max un element „glow".
- **Distilare (codegen.py)**: genomul câștigător → emite un `drawX()` TS de formă identică
  cu speciile existente (scheletul în 7 pași, constante în loc de parametri, `uid` corect),
  cele 3 palete × 12, și instrucțiunile de plumbing: o intrare în `NATION_SPECIES`
  (FaceGenerator.java:101) + o linie de dispatch în `buildInner()` (player-face.component.ts:2183).
  Numele speciei poate veni din name-lab (NAME_LAB_ML_PLAN.md) — același flow de votare.

## Faze de livrare

1. **F0 (quick win, independent)**: mapează `aquanimenti` pe una din națiile human
   (4=FootieCup sau 7=Eleven) — 1 linie backend; + ruta `/dev/face-gallery` cu tabul
   „specii existente × 12 palete". Din acest moment orice iterație vizuală e pe minute.
2. **F1**: extrage axele din cele 6 renderer-e existente și scrie `drawParametric(genome)`;
   validare: aproximarea fiecărei specii existente dintr-un genom de referință.
3. **F2**: tabul „evoluție" + votare + `DevFaceLabController` (persistă JSONL, servește batch-ul).
4. **F3**: learner + GA în `face-lab/`; buclă manuală la început (rulezi scriptul, galeria
   încarcă generația nouă), automatizabilă ulterior.
5. **F4**: codegen `drawX()` + palete + plumbing; review vizual în galerie înainte de commit.

## Constrângeri

- Ruta și controllerul sunt dev-only (profil Spring `dev` / environment Angular) — nu intră
  în build-ul de producție.
- Renderer-ul parametric NU înlocuiește speciile existente; producția rămâne pe `drawX()`
  înghețate, deterministe pe `baseFaceId`.
- Seed-uri fixe peste tot (generația N e reproductibilă din JSON-ul ei).
- Doar scikit-learn/pandas pe partea Python; fără DL/GPU.
- Semantica `NATION_SPECIES` rămâne per-nație 1:1; dacă se dorește specie pe altă axă
  (raritate/poziție/individ), aia e o decizie separată de schimbat explicit, nu implicit.

---

## Stare implementare (2026-07-30) — F0…F4 livrate

| Fază | Livrat |
|---|---|
| F0 | `FaceGenerator.java:105` → `4L, "aquanimenti"` (FootieCup); ruta `/dev/face-gallery`, tabul „Specii existente" (7 specii × 12 palete, axă selectabilă skin/hair/eye, slider baseFaceId + stil) |
| F1 | `src/app/face-lab/face-genome.ts` (genom + 8 familii de palete + constrângeri + sampling) și `face-parametric.ts` (`drawParametric`); tabul „Parametric (validare)" pune frozen vs. genom de referință una lângă alta |
| F2 | tabul „Evoluție" (grid 1–100, mod A/B, publicare batch), `DevFaceLabController` (`/api/dev/facelab/{status,batch,generation,votes,pairs}`), JSONL pe disc |
| F3 | `face-lab/` — features, ensemble GradientBoosting (scor + incertitudine), GA 8/8/4/4, CLI `seed / train / top / evolve` |
| F4 | distilare: butonul „îngheață" + `face-codegen.ts` emite `drawX()` complet; `facelab.cli distill` emite paletele rotite + plumbing + genomul |

**Axe** (primele 6 = extrase din speciile livrate, restul noi): 8 siluete
(`faceted beaked carved plated teardrop dome` + `smooth spire`), 8 tipuri de ochi
(`slit verticalPupil hollowGlow molten raptorRound sphericalLidless` + `compound visor`),
9 signature (`thirdEyeGem dorsalCrest laurel rockCrest featherCrest gills` + `hornPair
anglerLure none`), 8 familii de palete (6 + `fungal chrome`).

**Abateri conștiente de la plan:**

1. **Dev-only prin proprietate, nu prin profil Spring.** Nu există profil `dev` în proiect;
   controller-ul e gated cu `@ConditionalOnProperty("facelab.enabled")`, iar
   `facelab.enabled=true` stă doar în `application.properties` (local), nu în
   `src/main/resources/application.yml` (pachetul de producție).
2. **Codegen-ul `drawX()` e în TypeScript, nu în Python.** `face-codegen.ts` rulează
   renderer-ul parametric în *mod simbolic* (aceleași funcții, cu `${body.md}` / `${IW}` /
   `${uid}` în loc de valori) — deci metoda înghețată nu poate diverge de fața votată.
   În Python ar fi însemnat o a doua implementare a renderer-ului. `codegen.py` păstrează
   partea care e pur date: paletele rotite, plumbing-ul și genomul înghețat.
3. **Constrângerile hard se aplică doar la sampling.** `applyConstraints(g, 'reference')`
   face doar clamp de siguranță: crystalline chiar combină ochi-slit luminoși cu o gemă
   luminoasă, iar regula „max un glow" nu are voie să rescrie fețele din care au fost
   extrase axele.
4. **`@Input() faceStyle`** adăugat în `player-face.component.ts` ca alias pentru `style` —
   Angular tratează `[style]` ca binding DOM, nu ca input de componentă. 3 linii aditive,
   niciun apelant existent afectat.

**Efect vizibil în joc**: toți jucătorii FootieCup (nația 4) devin `aquanimenti` la
următoarea generare (`assignFace`). Salvările existente păstrează `species="human"` până
la regenerare.


