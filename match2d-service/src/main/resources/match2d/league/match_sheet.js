// Fisa unui meci incheiat (statisticile echipelor si notele celor 22), comuna televizorului si
// paginii de clasament. Serverul (league_tv.py) trimite obiectul gata facut, cu tot cu lista
// coloanelor si etichetele lor; aici doar se deseneaza.
//   MatchSheet.html(sheet, {countId: 'count', closable: true})  -> sir HTML
//   MatchSheet.dec(6.25, 1)                                       -> "6,3"
(function (root) {
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
  const chip = color => `<span class="ms-chip" style="background:${esc(color)}"></span>`;
  const dec = (x, d) => (x == null || isNaN(x)) ? '' : Number(x).toFixed(d).replace('.', ',');
  const has = v => v !== null && v !== undefined && v !== '';

  const CSS = `
  .ms { width:min(1560px,100%); text-align:left; color:#e8edf2; font:13px/1.35 system-ui, "Segoe UI", sans-serif; }
  .ms-head { display:flex; align-items:center; justify-content:center; gap:16px; flex-wrap:wrap; }
  .ms-kicker { text-align:center; color:#f4d35e; letter-spacing:.14em; text-transform:uppercase; font-size:12px; font-weight:600; margin-bottom:4px; }
  .ms-links { text-align:center; font-size:12px; color:#93a1b0; margin-bottom:4px; }
  .ms-links a { color:#93a1b0; text-decoration:underline; text-underline-offset:2px; }
  .ms-links a:hover { color:#f4d35e; }
  .ms-team { display:flex; align-items:center; gap:8px; font-size:22px; font-weight:700; }
  .ms-score { font-size:38px; font-weight:700; font-variant-numeric:tabular-nums; min-width:110px; text-align:center; }
  .ms-chip { width:14px; height:14px; border-radius:50%; border:2px solid #fff; display:inline-block; flex:none; }
  .ms-goals { display:grid; grid-template-columns:1fr 1fr; gap:4px 40px; margin:6px auto 0; max-width:900px; font-size:13px; }
  .ms-goals .h { text-align:right; } .ms-goals .m { color:#f4d35e; font-variant-numeric:tabular-nums; margin:0 4px; }
  .ms-goals small, .ms small { color:#93a1b0; }
  .ms-motm { text-align:center; margin-top:6px; color:#93a1b0; }
  .ms-motm b { color:#f4d35e; }
  .ms-strip { display:flex; justify-content:center; flex-wrap:wrap; gap:6px; margin:10px 0; }
  .ms-strip div { background:#161d27; border:1px solid #2b3745; border-radius:8px; padding:4px 10px; text-align:center; min-width:96px; }
  .ms-strip span { display:block; color:#93a1b0; font-size:11px; text-transform:uppercase; letter-spacing:.04em; }
  .ms-strip b { font-variant-numeric:tabular-nums; font-size:15px; }
  .ms-tables { display:grid; grid-template-columns:repeat(auto-fit, minmax(min(660px,100%), 1fr)); gap:12px; }
  .ms-side { background:#161d27; border:1px solid #2b3745; border-radius:8px; padding:6px 8px; min-width:0; overflow-x:auto; }
  .ms-side h4 { margin:0 0 4px; display:flex; align-items:center; gap:8px; font-size:14px; }
  .ms table { width:100%; border-collapse:collapse; font-variant-numeric:tabular-nums; }
  .ms th { color:#93a1b0; font-size:11px; font-weight:500; padding:2px 3px; text-align:center; white-space:nowrap; cursor:help; }
  .ms td { padding:2px 3px; text-align:center; border-top:1px solid #222c38; white-space:nowrap; }
  .ms td.n { color:#93a1b0; text-align:right; width:24px; }
  .ms td.nm, .ms th.nm { text-align:left; max-width:170px; overflow:hidden; text-overflow:ellipsis; }
  .ms td.ps { color:#93a1b0; font-size:11px; }
  .ms td.z { color:#55606d; }
  .ms tr.motm td { background:rgba(244,211,94,.13); }
  .ms .ms-om { display:inline-block; margin-left:6px; padding:0 5px; border-radius:3px; background:#f4d35e; color:#111; font-size:10px; font-weight:700; vertical-align:1px; }
  .ms .rt { display:inline-block; min-width:34px; padding:1px 4px; border-radius:4px; font-weight:700; color:#111; }
  .ms .rt.r1 { background:#4cc38a; } .ms .rt.r2 { background:#a6d96a; } .ms .rt.r3 { background:#c8d0d9; }
  .ms .rt.r4 { background:#ffb35c; } .ms .rt.r5 { background:#ff7b72; }
  .ms .yc, .ms .rc { display:inline-block; width:8px; height:11px; border-radius:1.5px; background:#f4d000; vertical-align:-1px; margin:0 1px; }
  .ms .rc { background:#e5383b; }
  .ms-legend { color:#93a1b0; font-size:11px; margin-top:8px; line-height:1.5; }
  .ms-legend b { color:#c8d0d9; font-weight:600; }
  .ms-foot { display:flex; align-items:center; justify-content:center; gap:12px; margin-top:8px; font-size:15px; color:#93a1b0; }
  .ms-foot b { color:#e8edf2; font-variant-numeric:tabular-nums; }
  .ms-close { background:#263242; color:#e8edf2; border:1px solid #2b3745; border-radius:6px; padding:6px 12px; cursor:pointer; font:inherit; }
  .ms-close:hover { background:#324155; }
  @media (max-width:700px) { .ms-team { font-size:17px; } .ms-score { font-size:28px; min-width:80px; } .ms-goals { grid-template-columns:1fr; } .ms-goals .h { text-align:left; } }
  `;
  function injectStyle() {
    if (typeof document === 'undefined' || document.getElementById('ms-style')) return;
    const st = document.createElement('style');
    st.id = 'ms-style';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  // insigna notei: mare = verde, medie = neutra, mica = portocaliu / rosu
  const ratingClass = r => r >= 8 ? 'r1' : r >= 7 ? 'r2' : r >= 6 ? 'r3' : r >= 5 ? 'r4' : 'r5';
  const badge = r => has(r) ? `<span class="rt ${ratingClass(r)}">${dec(r, 1)}</span>` : '';

  function cell(col, line) {
    const a = line[col.key], b = col.key2 ? line[col.key2] : undefined;
    if (col.kind === 'rating') return `<td>${badge(a)}</td>`;
    if (col.kind === 'card') {
      const y = a || 0, r = b || 0;
      const marks = '<span class="yc"></span>'.repeat(Math.min(y, 2)) + '<span class="rc"></span>'.repeat(Math.min(r, 1));
      return marks ? `<td>${marks}</td>` : '<td class="z">·</td>';
    }
    if (col.kind === 'paren') return (a || b) ? `<td>${a || 0} <small>(${b || 0})</small></td>` : '<td class="z">0</td>';
    if (col.kind === 'ratio') return b ? `<td>${a || 0}/${b}</td>` : '<td class="z">0</td>';
    return a ? `<td>${a}</td>` : '<td class="z">0</td>';
  }
  function head(col) {
    const title = col.key2 ? `${col.long} / ${col.long2}` : col.long;
    const text = col.kind === 'paren' ? `${col.short} (${col.short2})` : col.kind === 'ratio' ? `${col.short}/${col.short2}` : col.short;
    return `<th title="${esc(title)}">${esc(text)}</th>`;
  }
  function sideTable(team, columns) {
    const rows = (team.players || []).map(l => `<tr class="${l.motm ? 'motm' : ''}"><td class="n">${esc(l.number)}</td>` +
      `<td class="nm" title="${esc(l.name)}">${esc(l.name)}${l.motm ? '<span class="ms-om">Omul meciului</span>' : ''}</td>` +
      `<td class="ps">${esc(l.pos)}</td>` + columns.map(c => cell(c, l)).join('') + '</tr>').join('');
    return `<div class="ms-side"><h4>${chip(team.color)}${esc(team.name)}</h4><table>
      <tr><th>#</th><th class="nm">Jucător</th><th>Post</th>${columns.map(head).join('')}</tr>${rows}</table></div>`;
  }
  // statisticile de echipa: ce lipseste din rezultat nu se afiseaza
  function strip(teams) {
    const s = [teams[0].stats || {}, teams[1].stats || {}];
    const items = [];
    const add = (label, f) => {
      const a = f(s[0]), b = f(s[1]);
      if (has(a) && has(b)) items.push(`<div><span>${label}</span><b>${a} – ${b}</b></div>`);
    };
    add('Posesie', t => has(t.poss_pct) ? Math.round(t.poss_pct) + '%' : null);
    add('Șuturi (pe poartă)', t => has(t.shots) ? `${t.shots} (${has(t.on_target) ? t.on_target : 0})` : null);
    add('xG', t => has(t.xg) ? dec(t.xg, 2) : null);
    add('Pase reușite', t => has(t.passes) && t.passes > 0 && has(t.passes_ok) ? Math.round(100 * t.passes_ok / t.passes) + '%' : null);
    add('Cornere', t => has(t.corners) ? t.corners : null);
    add('Faulturi', t => has(t.fouls) ? t.fouls : null);
    add('Cartonașe galbene', t => has(t.yellow_cards) ? t.yellow_cards : null);
    return items.length ? `<div class="ms-strip">${items.join('')}</div>` : '';
  }
  function goals(sheet) {
    const g = sheet.goals || [];
    if (!g.length) return '';
    const line = x => `<span class="m">${x.minute}'</span>${esc(x.scorer || (x.own ? 'autogol ' + x.own : 'autogol'))}` +
      `${x.how === 'penalty' ? ' <small>(pen.)</small>' : ''}${x.assist ? ` <small>pasă ${esc(x.assist)}</small>` : ''}`;
    const home = g.filter(x => x.team === 0).map(line).join('<br>'), away = g.filter(x => x.team === 1).map(line).join('<br>');
    return `<div class="ms-goals"><div class="h">${home}</div><div>${away}</div></div>`;
  }
  function legend(columns) {
    const seen = {}, parts = [];
    for (const c of columns) {
      if (c.kind === 'rating') continue;
      for (const [s, l] of [[c.short, c.long], [c.short2, c.long2]]) {
        if (!s || seen[s]) continue;
        seen[s] = 1;
        parts.push(`<b>${esc(s)}</b> ${esc(l)}`);
      }
    }
    return `<div class="ms-legend">${parts.join(' · ')} · nota de la 1 la 10</div>`;
  }
  function html(sheet, opts) {
    opts = opts || {};
    injectStyle();
    const t = sheet.teams || [{}, {}], columns = sheet.columns || [];
    const m = sheet.motm;
    const foot = (opts.countId || opts.closable)
      ? `<div class="ms-foot">${opts.countId ? `<span id="${esc(opts.countId)}"></span>` : ''}${opts.closable ? '<button class="ms-close" data-close="1">Închide</button>' : ''}</div>` : '';
    return `<div class="ms">
      <div class="ms-kicker">Statisticile meciului · Final · Etapa ${esc(sheet.round)}</div>
      ${sheet.index != null ? `<div class="ms-links"><a href="/replay/${+sheet.index}" target="_blank" rel="noopener">Revezi meciul</a> · <a href="/replay/${+sheet.index}/save" download>Salvează</a></div>` : ''}
      <div class="ms-head"><span class="ms-team">${chip(t[0].color)}${esc(t[0].name)}</span>
        <span class="ms-score">${sheet.score[0]} – ${sheet.score[1]}</span>
        <span class="ms-team">${esc(t[1].name)}${chip(t[1].color)}</span></div>
      ${goals(sheet)}
      ${m ? `<div class="ms-motm">Omul meciului: <b>${esc(m.name)}</b> (${esc(m.teamName)}) ${badge(m.rating)}</div>` : ''}
      ${strip(t)}
      <div class="ms-tables">${sideTable(t[0], columns)}${sideTable(t[1], columns)}</div>
      ${legend(columns)}${foot}</div>`;
  }
  root.MatchSheet = {html, dec, esc, injectStyle, ratingClass};
})(typeof window !== 'undefined' ? window : globalThis);
