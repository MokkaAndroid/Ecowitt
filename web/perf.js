/* Onglet « Performance des modèles » : prévisions archivées contre mesures de la station. */
(() => {
'use strict';
const EW = window.EW; const { $, $$, nf, sgn, css, S, MODELS, LABELS, HOUR, WET } = EW;

const ECH = [['h1', '+1–2 h'], ['h3', '+3–4 h'], ['h5', '+5–6 h'], ['h11', '+11–12 h'], ['j1', 'J+1'], ['j2', 'J+2'], ['j3', 'J+3'], ['j4', 'J+4'], ['j5', 'J+5']];
const USED = { temp: { h: 'ICON-CH1', j: 'Auto' }, rain: { h: 'ICON-D2', j: 'Auto' } };
const P = { v: 'temp', ech: 'h1', d: 60, met: 'mae', tol: false, off: new Set() };
const METRICS = { temp: [['mae', 'Écart moyen'], ['bias', 'Biais']], rain: [['csi', 'Score'], ['pod', 'Détection'], ['far', 'Fausses alertes'], ['ratio', 'Quantité']] };

// ───────────── Calcul ─────────────
function compute(v, ech, days, tol) {
  const rows = S.fc.filter(r => r.ech === ech); if (!rows.length) return null;
  const maxE = Math.max(...rows.map(r => r.e)); const cut = days >= 9999 ? 0 : maxE - days * 86400; const sel = rows.filter(r => r.e >= cut);
  const near = new Map(); if (v === 'rain' && tol) for (const r of rows) near.set(r.m + '|' + r.run + '|' + r.e, r.r);
  const out = {};
  for (const m of MODELS) out[m] = { n: 0, e: 0, ae: 0, se: 0, nd: 0, ed: 0, nn: 0, en: 0, hit: 0, miss: 0, fa: 0, rm: 0, rs: 0, ar: 0, wet: 0, cum: [], days: new Map(), hrs: Array.from({ length: 24 }, () => [0, 0]), errs: [] };
  for (const r of sel) {
    const o = out[r.m]; if (!o) continue; const s = S.station.get(r.e); if (!s) continue;
    const dk = EW.dayKey(r.e - 1800); let D = o.days.get(dk); if (!D) { D = { ts: r.e, n: 0, ae: 0, e: 0, hit: 0, miss: 0, fa: 0, rm: 0, rs: 0 }; o.days.set(dk, D); }
    if (v === 'temp') {
      if (r.t == null || s.temp == null) continue;
      const e = r.t - s.temp; o.n++; o.e += e; o.ae += Math.abs(e); o.se += e * e; D.n++; D.ae += Math.abs(e); D.e += e; o.errs.push(e);
      const hr = EW.hourOf(r.e); o.hrs[hr][0] += e; o.hrs[hr][1]++;
      if (hr >= 8 && hr <= 19) { o.nd++; o.ed += e; } else { o.nn++; o.en += e; }
    } else {
      if (r.r == null || s.rain == null) continue;
      const sw = s.rain >= WET, mw = r.r >= WET; o.n++; o.rm += r.r; o.rs += s.rain; o.ar += Math.abs(r.r - s.rain); D.n++; D.rm += r.r; D.rs += s.rain;
      let hit, miss, fa;
      if (!tol) { hit = sw && mw; miss = sw && !mw; fa = mw && !sw; }
      else {
        const sN = Math.max(s.rain, (S.station.get(r.e - HOUR) || {}).rain ?? 0, (S.station.get(r.e + HOUR) || {}).rain ?? 0);
        const mN = Math.max(r.r, near.get(r.m + '|' + r.run + '|' + (r.e - HOUR)) ?? 0, near.get(r.m + '|' + r.run + '|' + (r.e + HOUR)) ?? 0);
        hit = sw && mN >= WET; miss = sw && !(mN >= WET); fa = mw && !(sN >= WET);
      }
      if (sw) o.wet++;
      if (hit) { o.hit++; D.hit++; } if (miss) { o.miss++; D.miss++; } if (fa) { o.fa++; D.fa++; }
      o.cum.push([r.e, r.r, s.rain]);
    }
  }
  return out;
}
const stat = o => ({
  n: o.n, mae: o.n ? o.ae / o.n : null, bias: o.n ? o.e / o.n : null, rmse: o.n ? Math.sqrt(o.se / o.n) : null,
  biasDay: o.nd ? o.ed / o.nd : null, biasNight: o.nn ? o.en / o.nn : null,
  wet: o.wet, pod: (o.hit + o.miss) ? o.hit / (o.hit + o.miss) : null, far: (o.hit + o.fa) ? o.fa / (o.hit + o.fa) : null,
  csi: (o.hit + o.miss + o.fa) ? o.hit / (o.hit + o.miss + o.fa) : null, ratio: o.rs > 0 ? o.rm / o.rs : null, mad: o.n ? o.ar / o.n : null,
});
const primary = v => v === 'temp' ? 'mae' : 'csi';
const better = (v, a, b) => v === 'temp' ? a < b : a > b;
function bestOf(v, data) {
  let best = null, bv = null; for (const m of MODELS) { if (!data[m] || data[m].n < 10) continue; const x = stat(data[m])[primary(v)]; if (x == null) continue; if (bv == null || better(v, x, bv)) { best = m; bv = x; } }
  return best ? { m: best, val: bv } : null;
}

// ───────────── Rendu ─────────────
const specs = () => ECH.find(e => e[0] === P.ech)[1];
function kpis() {
  const dT = compute('temp', P.ech, P.d, false), dR = compute('rain', P.ech, P.d, P.tol);
  const bt = dT && bestOf('temp', dT), br = dR && bestOf('rain', dR); const n = dT ? Math.max(...MODELS.map(m => dT[m].n)) : 0, nr = dR ? Math.max(...MODELS.map(m => dR[m].wet)) : 0;
  const k = (lab, val, det, c) => `<div class="kpi"><div class="lab"><span>${lab}</span></div><div class="val" style="${c ? '--c:' + c : ''}">${val}</div><div class="det">${det}</div></div>`;
  const all = S.fc.filter(r => r.ech === P.ech); const days = all.length ? new Set(all.map(r => EW.dayKey(r.e - 1800))).size : 0;
  return `<div class="kpis s12">
    ${k('Meilleur en température', bt ? LABELS[bt.m] : '—', bt ? specs() + ' · écart moyen ' + nf(bt.val, 2) + ' °C' : 'pas de données', bt && EW.mcolor(bt.m))}
    ${k('Meilleur en pluie', br ? LABELS[br.m] : '—', br ? specs() + ' · score ' + nf(br.val, 2) + ' (1 = parfait)' : 'pas de données', br && EW.mcolor(br.m))}
    ${k('Heures comparées', n.toLocaleString('fr-FR'), 'par modèle · température')}
    ${k('Heures de pluie', nr.toLocaleString('fr-FR'), 'mesurées ≥ ' + WET + ' mm')}
    ${k('Jours d\'archive', days.toLocaleString('fr-FR'), 'pour cette échéance')}
  </div>`;
}

function rankCard() {
  return `<div class="card s12"><div class="card-head"><h2>Qui prévoit le mieux ?</h2><span class="sub" id="perf-span"></span></div>
    <div class="controls wrap"><div class="seg" id="p-var"></div><div class="seg" id="p-ech"></div><div class="seg" id="p-per"></div><label class="check" id="tol-wrap" hidden><input type="checkbox" id="tol"> tolérance ±1 h</label></div>
    <p class="verdict" id="verdict"></p><div class="scroll"><table class="tbl rank" id="t-rank"></table></div><p class="foot" id="rank-foot"></p></div>`;
}

function renderRank(data) {
  const t = $('#t-rank'); const ms = MODELS.filter(m => data[m].n > 0); const st = Object.fromEntries(ms.map(m => [m, stat(data[m])]));
  const used = USED[P.v][P.ech[0] === 'h' ? 'h' : 'j'];
  const best = (k, f) => { const v = ms.filter(m => st[m][k] != null && data[m].n >= 10).map(m => [m, f(st[m][k])]); return v.length ? v.sort((a, b) => a[1] - b[1])[0][0] : null; };
  let cols;
  if (P.v === 'temp') cols = [['n', 'Heures', null, 0], ['mae', 'Écart moyen (°)', x => x, 2], ['bias', 'Biais (°)', Math.abs, 2, 1], ['biasDay', 'Jour 8–20 h', Math.abs, 2, 1], ['biasNight', 'Nuit', Math.abs, 2, 1], ['rmse', 'RMSE (°)', x => x, 2]];
  else cols = [['wet', 'Heures de pluie', null, 0], ['pod', 'Détectées', x => -x, 0, 0, 1], ['far', 'Fausses alertes', x => x, 0, 0, 1], ['csi', 'Score', x => -x, 2], ['ratio', 'Prévu / mesuré', x => Math.abs(x - 1), 2], ['mad', 'Écart (mm/h)', x => x, 2]];
  const order = [...ms].sort((a, b) => P.v === 'temp' ? (st[a].mae ?? 9e9) - (st[b].mae ?? 9e9) : (st[b].csi ?? -1) - (st[a].csi ?? -1));
  const bests = Object.fromEntries(cols.filter(c => c[2]).map(c => [c[0], best(c[0], c[2])]));
  const pk = primary(P.v); const pvals = order.map(m => st[m][pk]).filter(x => x != null); const pmax = Math.max(...pvals, 1e-9);
  let h = '<thead><tr><th>Modèle</th>' + cols.map(c => `<th>${c[1]}</th>`).join('') + '<th class="bcol"></th></tr></thead><tbody>';
  order.forEach((m, i) => {
    h += `<tr><td><span class="rk">${i + 1}</span><span class="m" style="--c:${EW.mcolor(m)}"><i></i>${LABELS[m]}</span>${m === used ? '<span class="badge" title="Modèle utilisé dans l\'appli pour cette échéance">utilisé</span>' : ''}</td>`;
    for (const c of cols) { const v = st[m][c[0]]; const txt = v == null ? '—' : c[5] ? nf(v * 100, 0) + ' %' : c[4] ? sgn(v, c[3]) : nf(v, c[3]); h += `<td class="${bests[c[0]] === m ? 'best' : ''}">${txt}</td>`; }
    const w = st[m][pk] == null ? 0 : (P.v === 'temp' ? (1 - st[m][pk] / (pmax * 1.15)) : st[m][pk] / pmax) * 100;
    h += `<td class="bcol"><span class="mbar"><i style="width:${Math.max(w, 4)}%;background:${EW.mcolor(m)}"></i></span></td></tr>`;
  });
  t.innerHTML = h + '</tbody>';
  const ech = specs();
  if (order.length) {
    const a = st[order[0]], b = st[order[1]];
    $('#verdict').innerHTML = P.v === 'temp'
      ? `Température ${ech} : <b>${LABELS[order[0]]}</b> en tête avec ${nf(a.mae, 2)} ° d'écart moyen${b ? `, devant ${LABELS[order[1]]} (${nf(b.mae, 2)} °)` : ''}.`
      : `Pluie ${ech} : <b>${LABELS[order[0]]}</b> a le meilleur score (${nf(a.csi, 2)}) : ${nf(a.pod * 100, 0)} % des heures de pluie détectées, ${nf(a.far * 100, 0)} % de fausses alertes${a.ratio != null ? `, ${nf(a.ratio, 2)} fois la pluie mesurée` : ''}.`;
  } else $('#verdict').textContent = 'Pas encore de données pour cette échéance.';
  $('#rank-foot').textContent = P.v === 'temp'
    ? 'Prévision moins mesure de la station, heure par heure. Biais positif : le modèle voit trop chaud. En vert : le meilleur de chaque colonne (biais : le plus proche de 0). La barre résume le classement.'
    : `Heure de pluie = au moins ${WET} mm. Détectées : part des heures de pluie annoncées. Fausses alertes : part des heures annoncées sans pluie mesurée. Score = détectées / (détectées + manquées + fausses). Quantité idéale : 1.`;
}

// Carte des échéances : un modèle par ligne, une échéance par colonne.
function matrixCard() { return EW.card('s6', 'Quelle échéance, quel modèle ?', P.v === 'temp' ? 'écart moyen (°C), plus bas = mieux' : 'score de détection, plus haut = mieux', '<div class="scroll"><table class="tbl heat" id="t-heat"></table></div><p class="foot">Teinte : meilleur de la colonne en vert, plus faible en rouge. Cases vides : pas assez de données.</p>'); }
function drawMatrix() {
  const v = P.v; const rows = {}; const colsData = ECH.map(([e]) => compute(v, e, P.d, P.tol));
  const ms = MODELS.filter(m => colsData.some(d => d && d[m].n >= 10)); const ok = (d, m) => d && d[m].n >= 10 ? stat(d[m])[primary(v)] : null;
  for (const m of ms) rows[m] = colsData.map(d => ok(d, m));
  const range = colsData.map((_, c) => { const x = ms.map(m => rows[m][c]).filter(y => y != null); return x.length ? [Math.min(...x), Math.max(...x)] : null; });
  const cell = (m, c) => { const y = rows[m][c]; if (y == null) return '<td><span class="dim">—</span></td>'; const r = range[c]; let q = r[1] === r[0] ? .5 : (y - r[0]) / (r[1] - r[0]); if (v === 'rain') q = 1 - q; const col = q < .02 ? css('--good') : q > .98 ? css('--bad') : null; const mix = Math.round((1 - q) * 100);
    return `<td style="background:color-mix(in srgb, var(--good) ${mix * .45}%, color-mix(in srgb, var(--bad) ${(100 - mix) * .45}%, transparent));${q < .02 ? 'font-weight:700' : ''}" ${col ? '' : ''}>${nf(y, 2)}</td>`; };
  $('#t-heat').innerHTML = '<thead><tr><th>Modèle</th>' + ECH.map(e => `<th>${e[1]}</th>`).join('') + '</tr></thead><tbody>' + ms.map(m => `<tr><td><span class="m" style="--c:${EW.mcolor(m)}"><i></i>${LABELS[m]}</span></td>${ECH.map((_, c) => cell(m, c)).join('')}</tr>`).join('') + '</tbody>';
  // dégradation selon l'échéance
  const ds = ms.filter(m => !P.off.has(m)).map(m => ({ label: LABELS[m], data: rows[m].map((y, i) => ({ x: i, y })), borderColor: EW.mcolor(m), backgroundColor: EW.mcolor(m), borderWidth: 1.9, pointRadius: 3, pointHoverRadius: 5, tension: .2, spanGaps: true, borderDash: m === 'Auto' ? [5, 3] : [] }));
  EW.mk('c-deg', { type: 'line', data: { datasets: ds }, options: EW.baseOpts({ scales: { x: { type: 'linear', min: -.2, max: ECH.length - .8, grid: { color: css('--grid') }, border: { display: false }, ticks: { callback: x => (ECH[x] || [])[1] || '', color: css('--t2'), maxRotation: 0, font: { size: 11 } }, afterBuildTicks: sc => { sc.ticks = ECH.map((_, i) => ({ value: i })); } }, y: EW.yAxis(v === 'temp' ? '°C d\'écart moyen' : 'score', { beginAtZero: true }) }, plugins: { legend: { display: false }, tooltip: EW.tip(v === 'temp' ? '°C' : '', 2, i => (ECH[Math.round(i[0].parsed.x)] || ['', ''])[1]) } }) });
}

function rolling(o, k, win) {
  const keys = [...o.days.keys()].sort(); const pts = [];
  keys.forEach((dk, i) => {
    const sl = keys.slice(Math.max(0, i - win + 1), i + 1).map(x => o.days.get(x)); const sum = f => sl.reduce((a, d) => a + d[f], 0); let y = null;
    if (k === 'mae') y = sum('n') ? sum('ae') / sum('n') : null;
    else if (k === 'bias') y = sum('n') ? sum('e') / sum('n') : null;
    else if (k === 'pod') y = (sum('hit') + sum('miss')) ? sum('hit') / (sum('hit') + sum('miss')) * 100 : null;
    else if (k === 'far') y = (sum('hit') + sum('fa')) ? sum('fa') / (sum('hit') + sum('fa')) * 100 : null;
    else if (k === 'csi') y = (sum('hit') + sum('miss') + sum('fa')) ? sum('hit') / (sum('hit') + sum('miss') + sum('fa')) : null;
    else if (k === 'ratio') y = sum('rs') > 0.2 ? sum('rm') / sum('rs') : null;
    if (y != null) pts.push({ x: o.days.get(dk).ts * 1000, y });
  });
  return pts;
}
const dateAxis = (a, b) => Object.assign(EW.timeAxis(a, b), { ticks: { color: css('--t2'), maxRotation: 0, callback: v => EW.fmtDT(v, { day: 'numeric', month: 'short' }) } });

function drawSeries(data) {
  const ms = MODELS.filter(m => data[m].n > 0); const show = ms.filter(m => !P.off.has(m));
  EW.legend($('#p-legend'), ms.map(m => ({ id: m, label: LABELS[m], color: EW.mcolor(m), dash: m === 'Auto' })), P.off, () => { drawSeries(data); drawMatrix(); drawDist(data); });
  const ml = METRICS[P.v].find(x => x[0] === P.met) || METRICS[P.v][0]; P.met = ml[0];
  const unit = { mae: '°C', bias: '°C', pod: '%', far: '%', csi: '', ratio: '× mesuré' }[P.met], dec = { mae: 2, bias: 2, pod: 0, far: 0, csi: 2, ratio: 2 }[P.met];
  $('#pa-title').textContent = ml[1] + ' au fil du temps'; const win = P.d <= 7 ? 3 : 7;
  const ds = show.map(m => ({ label: LABELS[m], data: rolling(data[m], P.met, win), borderColor: EW.mcolor(m), borderWidth: 1.9, pointRadius: 0, tension: .25, spanGaps: true, borderDash: m === 'Auto' ? [5, 3] : [] }));
  const xs = ds.flatMap(d => d.data.map(p => p.x));
  if (xs.length) EW.mk('c-pa', { type: 'line', data: { datasets: ds }, options: EW.baseOpts({ scales: { x: dateAxis(Math.min(...xs), Math.max(...xs)), y: EW.yAxis(unit, { beginAtZero: P.met !== 'bias' }) }, plugins: { legend: { display: false }, tooltip: EW.tip(unit, dec, i => EW.fmtDT(i[0].parsed.x, { day: 'numeric', month: 'long' })) } }) }); else EW.kill('c-pa');
  $('#pa-foot').textContent = `Moyenne glissante sur ${win} jours${P.met === 'ratio' ? ' · idéal : 1' : ''}${P.v === 'rain' ? ' · à lire en tendance (peu de jours de pluie)' : ''}.`;
  if (P.v === 'temp') {
    $('#pb-title').textContent = 'Biais selon l\'heure de la journée';
    const d2 = show.map(m => ({ label: LABELS[m], data: data[m].hrs.map((a, h) => ({ x: h, y: a[1] >= 3 ? a[0] / a[1] : null })), borderColor: EW.mcolor(m), borderWidth: 1.9, pointRadius: 0, tension: .3, spanGaps: true, borderDash: m === 'Auto' ? [5, 3] : [] }));
    EW.mk('c-pb', { type: 'line', data: { datasets: d2 }, options: EW.baseOpts({ scales: { x: { type: 'linear', min: 0, max: 23, grid: { color: css('--grid') }, border: { display: false }, ticks: { stepSize: 3, callback: v => v + ' h', color: css('--t2') } }, y: EW.yAxis('°C (prévu − mesuré)') }, plugins: { legend: { display: false }, tooltip: EW.tip('°C', 2, i => i[0].parsed.x + ' h') } }) });
    $('#pb-foot').textContent = 'Au-dessus de 0 : le modèle voit trop chaud à cette heure. Un biais plus fort la nuit trahit souvent des inversions thermiques mal rendues.';
  } else {
    $('#pb-title').textContent = 'Pluie cumulée : prévu contre mesuré'; const d2 = []; const base = ms.find(m => data[m].cum.length);
    if (base) { let a = 0; d2.push({ label: 'Station', data: data[base].cum.sort((x, y) => x[0] - y[0]).map(p => ({ x: p[0] * 1000, y: (a += p[2]) })), borderColor: css('--obs'), borderWidth: 2.8, pointRadius: 0, stepped: 'before' }); }
    for (const m of show) { let a = 0; d2.push({ label: LABELS[m], data: data[m].cum.sort((x, y) => x[0] - y[0]).map(p => ({ x: p[0] * 1000, y: (a += p[1]) })), borderColor: EW.mcolor(m), borderWidth: 1.7, pointRadius: 0, stepped: 'before', borderDash: m === 'Auto' ? [5, 3] : [] }); }
    const xs2 = d2.flatMap(d => d.data.map(p => p.x));
    if (xs2.length) EW.mk('c-pb', { type: 'line', data: { datasets: d2 }, options: EW.baseOpts({ scales: { x: dateAxis(Math.min(...xs2), Math.max(...xs2)), y: EW.yAxis('mm cumulés', { beginAtZero: true }) }, plugins: { legend: { display: false }, tooltip: EW.tip('mm', 1, i => EW.fmtDT(i[0].parsed.x, { day: 'numeric', month: 'long', hour: '2-digit' })) } }) }); else EW.kill('c-pb');
    $('#pb-foot').textContent = 'Cumul des heures comparées uniquement. Au-dessus du trait épais : le modèle voit trop de pluie.';
  }
}
// Distribution des erreurs (température) : part des heures par classe d'erreur.
function drawDist(data) {
  const card = $('#dist-card'); card.hidden = P.v !== 'temp'; if (P.v !== 'temp') { EW.kill('c-dist'); return; }
  const bins = []; for (let b = -6; b <= 6; b += 0.5) bins.push(b);
  const ds = MODELS.filter(m => data[m].n > 20 && !P.off.has(m)).map(m => { const cnt = bins.map(() => 0); for (const e of data[m].errs) { const k = Math.min(bins.length - 1, Math.max(0, Math.round((e + 6) / 0.5))); cnt[k]++; } return { label: LABELS[m], data: bins.map((b, i) => ({ x: b, y: cnt[i] / data[m].n * 100 })), borderColor: EW.mcolor(m), borderWidth: 1.9, pointRadius: 0, tension: .35, borderDash: m === 'Auto' ? [5, 3] : [] }; });
  EW.mk('c-dist', { type: 'line', data: { datasets: ds }, options: EW.baseOpts({ scales: { x: { type: 'linear', min: -6, max: 6, grid: { color: css('--grid') }, border: { display: false }, ticks: { stepSize: 1, callback: v => sgn(v, 0) + '°', color: css('--t2') }, title: { display: true, text: 'erreur (prévu − mesuré)', color: css('--t2') } }, y: EW.yAxis('% des heures', { beginAtZero: true }) }, plugins: { legend: { display: false }, tooltip: EW.tip('%', 1, i => 'Erreur ≈ ' + sgn(i[0].parsed.x, 1) + ' °C') } }) });
}

EW.views.perf = {
  render() {
    const root = $('#view-perf');
    if (!S.hasFc || !S.fc.length) { root.innerHTML = EW.card('s12', 'Performance des modèles', '', '<p class="verdict">Aucune prévision archivée pour le moment : la collecte alimente <code>forecasts.csv</code> toutes les 30 minutes.</p>'); return; }
    root.innerHTML = kpis() + rankCard() + matrixCard() + EW.card('s6', 'Dégradation selon l\'échéance', 'même indicateur', '<div class="chart"><canvas id="c-deg" role="img" aria-label="Performance selon l\'échéance"></canvas></div>') +
      `<div class="card s12"><div class="card-head"><h2 id="pa-title">Au fil du temps</h2><div class="seg" id="p-met"></div></div><div class="legend" id="p-legend"></div><div class="g2"><div><div class="chart"><canvas id="c-pa" role="img" aria-label="Évolution des performances"></canvas></div><p class="foot" id="pa-foot"></p></div><div><h3 id="pb-title">—</h3><div class="chart"><canvas id="c-pb" role="img" aria-label="Second graphique"></canvas></div><p class="foot" id="pb-foot"></p></div></div></div>` +
      EW.card('s12', 'Distribution des erreurs de température', 'part des heures par classe d\'erreur', '<div class="chart"><canvas id="c-dist" role="img" aria-label="Distribution des erreurs"></canvas></div><p class="foot">Une courbe étroite centrée sur 0 = un modèle fiable ; décalée à droite = trop chaud ; large = erreurs fréquentes.</p>').replace('class="card s12"', 'class="card s12" id="dist-card"');
    EW.seg($('#p-var'), [['temp', 'Température'], ['rain', 'Pluie']], P.v, k => { P.v = k; P.met = METRICS[k][0][0]; EW.views.perf.render(); });
    EW.seg($('#p-ech'), ECH, P.ech, k => { P.ech = k; EW.views.perf.render(); });
    EW.seg($('#p-per'), [[7, '7 j'], [30, '30 j'], [60, '60 j'], [9999, 'Tout']], P.d, k => { P.d = +k; EW.views.perf.render(); });
    $('#tol-wrap').hidden = P.v !== 'rain'; $('#tol').checked = P.tol; $('#tol').onchange = e => { P.tol = e.target.checked; EW.views.perf.render(); };
    const all = S.fc.filter(r => r.ech === P.ech);
    $('#perf-span').textContent = all.length ? 'Archive du ' + EW.fmtDT(Math.min(...all.map(r => r.e)) * 1000, { day: 'numeric', month: 'short', year: 'numeric' }) + ' au ' + EW.fmtDT(Math.max(...all.map(r => r.e)) * 1000, { day: 'numeric', month: 'short' }) + ' · ' + all.length.toLocaleString('fr-FR') + ' prévisions' : '';
    const data = compute(P.v, P.ech, P.d, P.tol); if (!data) { $('#verdict').textContent = 'Pas encore de données pour cette échéance.'; return; }
    renderRank(data); drawMatrix(); drawSeries(data); drawDist(data);
    EW.seg($('#p-met'), METRICS[P.v], P.met, k => { P.met = k; drawSeries(data); });
  },
};
})();
