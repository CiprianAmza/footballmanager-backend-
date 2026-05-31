# Plan: analize de jucător per (jucător, competiție, sezon) — StatsBomb-style (2026-05-31)

Pagini de analiză ca în infografice: badge rating, bare de percentile per-90 (defensive actions,
pressures, counterpressures, pass%, etc.) + heatmap de presing. Scop: plan cu ce avem, ce ne trebuie,
unde vine pe FE, ce servicii backend noi.

## Ce avem (din research)
- **Per (jucător, competiție, sezon)**: goluri, assists, aparișii, rating mediu — agregate în
  `StatsAggregationService` + endpoint `GET /stats/player/{id}/competitionBreakdown` (`{total, byCompetition,
  byTypeAndSeason}`). `Scorer` (model) ține per-meci: goals, assists, rating, position, isSubstitute,
  scor. Scope pe competitionId + seasonNumber.
- **Atribute**: `PlayerSkills` — 36 atribute (tehnice/mentale/fizice/GK) per jucător. `Human`: position,
  rating, age, fitness, morale.
- **Stats de echipă per meci**: `MatchStats` (posesie, șuturi, xG, taclinguri, pase, cartonașe…) — DAR
  **la nivel de echipă**, nu per jucător.
- **Evenimente**: `MatchEvent` doar goal/assist/card/sub (fără șuturi/pressing/coordonate).
- **FE**: pagina `player` are taburi (Overview/Stats/Contract/History/Milestones); `player-competition-
  history` face drill-down per comp+sezon. **Nicio librărie de charting** instalată; **niciun heatmap/SVG**.

## Lacuna fundamentală (decisivă pentru plan)
Engine-ul de meci e **Poisson/two-axis** — **NU simulează poziții, pressing, taclinguri, șuturi per
jucător, coordonate**. Nu există minute jucate per jucător (doar starter/sub). Deci:
> Chiar și „tracking real de evenimente" ar trebui să **fabrice** acele evenimente la momentul meciului
> (din atribute+tactică), pentru că engine-ul nu le produce.

→ Abordarea corectă NU e „adaugă event-tracking real", ci **sintetizează metricile din atribute +
minute + tactică**, eventual acumulate per meci. Asta livrează exact look-ul StatsBomb (care e oricum
**percentile vs peers**), folosind date pe care le avem/derivăm.

## Recomandare: 3 faze

### Faza 1 — Analytics sintetice (fără schimbare de engine) ⭐ cel mai bun raport efort/impact
**Backend** (`service/PlayerAnalyticsService` nou):
- Pentru (player, comp, season): ia atributele (`PlayerSkills`) + aparișii/minute (din `Scorer`; minute
  ≈ aparișii×90 până avem minute reale) + tactica/poziția.
- Calculează metrici **per-90 „expected"** prin formule ponderate pe atribute, config-driven:
  - Defensive Actions ≈ f(tackling, anticipation, positioning, workRate, aggression)
  - Pressures / Counterpressures ≈ f(workRate, stamina, aggression, offTheBall) × factor tactic
    **pressing** (High → mai multe) — leagă-se de Strat 2!
  - Pass% ≈ f(passing, technique, decisions, composure)
  - xG/shots/finishing ≈ f(finishing, offTheBall, composure) pentru atacanți
- **Percentile**: rank vs peers din ACEEAȘI grupă de poziție + competiție + sezon (min. aparișii), cu un
  prag de eșantion. Badge overall = `Human.rating`/percentila.
- **Heatmap**: zone-density sintetic dintr-un template per poziție (ex. winger → benzi laterale + careu)
  modulat de atribute (workRate/pace → mai sus, defensiveLine echipei → mai sus/jos). Grid 6×N de
  opacități, NU coordonate reale.
- Config nou `MatchEngineConfig.Analytics` (sau `analytics.yml`): ponderile atribut→metrică, pragul de
  minute, grupele de poziție, template-urile de heatmap. Tunabil.
- Endpoint: `GET /stats/player/{playerId}/{competitionId}/{seasonNumber}/analytics` →
  `{ overall, percentiles:[{metric,valuePer90,percentile}], heatmap:[[...]], sampleMinutes }`.

**Frontend**: tab nou **„Analytics"** în `player.component` (sau pagină `/player/:id/analytics`):
- Selector (competiție + sezon) — reutilizează datele din `competitionBreakdown`.
- Bare de percentile (SVG/CSS simplu — fără librărie nouă; culori prin pragurile percentilei, ca în poze).
- Badge rating (cerc colorat pe tier — reutilizează `RatingTierService`).
- Heatmap: SVG pitch (reutilizează grila/markajele de la paginile de tactică) cu zone colorate după
  densitate.
- Fără dependențe noi (hand-rolled SVG) SAU `chart.js`+`ng2-charts` dacă vrei radar/bare gata făcute.

### Faza 2 — Acumulare semi-reală per meci (ca metricile să reflecte meciuri jucate)
- Adaugă `minutesPlayed` pe `Scorer` (sau o entitate `PlayerMatchStat` ușoară: playerId, matchKey, comp,
  season, minutes + tally-uri sintetice: pressures, tackles, shots, passesAttempted/Completed).
- La simularea meciului (`MatchRoundSimulator`/live), **derivă tally-uri sintetice** per titular din
  atribute + tactică (pressing/defensiveLine/width) + rezultat + minute, și le persistă. Cheap (numere,
  fără coordonate). Atunci pressing-ul High pe care l-am construit **se vede** în analytics, iar metricile
  variază pe sezon/competiție (adversari, minute).
- `PlayerAnalyticsService` citește datele acumulate când există, altfel cade pe sinteza din Faza 1.

### Faza 3 — Coordonate reale pentru heatmap (opțional, heavy, probabil NU merită)
- Stocare event-level cu coordonate. Dar engine-ul tot le-ar fabrica → cost mare, beneficiu mic peste
  heatmap-ul sintetic. De evitat dacă nu e cerut explicit.

## Unde vine pe FE
- `src/app/player/player.component.*` → tab „Analytics" (între History și Milestones) SAU componentă nouă
  `player-analytics` montată acolo. Reutilizează selectorul comp+sezon + `RatingTierService` + grila SVG
  de teren din paginile de tactică.

## Servicii/fișiere noi (Faza 1)
- `service/PlayerAnalyticsService` (sinteză metrici + percentile + heatmap)
- `controller/StatsController` — endpoint `/analytics`
- `config/MatchEngineConfig.Analytics` (ponderi + praguri + template heatmap) — config-driven, tunabil
- DTO `PlayerAnalyticsView`
- FE: `player-analytics.component` + tab în `player.component`

## Verificare
- Unit: `PlayerAnalyticsServiceTest` (formule deterministe; percentile monotone; pressing tactic↑ →
  pressures↑). `mvn verify` verde (aditiv, nu atinge engine-ul de scor în Faza 1).
- FE: `ng build` verde; smoke vizual (badge, bare, heatmap pe (player,comp,season)).

## Decizii de confirmat
- **Faza 1 (sintetic) acum**, sau direct Faza 2 (acumulare per meci)? Recomand Faza 1 — livrează look-ul
  imediat, zero risc pe engine; Faza 2 după, ca metricile să fie „câștigate" în meci.
- Librărie de charting (hand-rolled SVG, fără dependențe — recomandat) vs `chart.js`/`ng2-charts`.
- Setul de metrici afișate (mă aliniez la pozele tale: Defensive Actions, Def Action Regains, Pressures,
  Pressure Regains, Counterpressures, Counterpressure Regains + heatmap de presing).
