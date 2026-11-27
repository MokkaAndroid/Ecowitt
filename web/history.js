/* Onglet « Historique » : jours écoulés, valeurs constatées et prévisions de la veille. */
(() => {
'use strict';
const EW = window.EW; const { $, $$, nf, sgn, css, S, MODELS, LABELS } = EW;

const H = { d: 90, v: 'temp', model: 'Auto', cum: false, sort: { k: 'k', dir: -1 }, rows: 31, cal: 'tmean' };
const HV = {
  temp: { label: 'Température', unit: '°C' }, rain: { label: 'Pluie', unit: 'mm' }, wind: { label: 'Vent', unit: 'km/h' },
  press: { label: 'Pression', unit: 'hPa' }, hum: { label: 'Humidité', unit: '%' }, solar: { label: 'Soleil et UV', unit: 'W/m²' },
};
const periodDays = () => {
  const today = EW.todayKey(); const all = [...EW.daily.values()].filter(d => d.n >= 12 && d.k < today).sort((a, b) => a.k < b.k ? -1 : 1);
  if (H.d >= 9999) return { cur: all, prev: [] };
  const cur = all.slice(-H.d); const prevAll = all.slice(0, Math.max(0, all.length - H.d)); return { cur, prev: prevAll.slice(-H.d) };
};
const fc = () => EW.fcDay('j1');
const pick = (d, key, agg) => { const v = EW.nums(d.map(x => x[key])); return v.length ? agg(v) : null; };
const dateOf = (days, key, f) => { let best = null; for (const d of days) if (d[key] != null && (best == null || f(d[key], best[key]))) best = d; return best; };

function kpis(cur, prev) {
  const tm = EW.mean(cur.map(d => d.tmean)), tmP = EW.mean(prev.map(d => d.tmean)); const rain = EW.sum(cur.map(d => d.rain)), rainP = EW.sum(prev.map(d => d.rain));
  const hot = dateOf(cur, 'tmax', (a, b) => a > b), cold = dateOf(cur, 'tmin', (a, b) => a < b), gust = dateOf(cur, 'gust', (a, b) => a > b);
  const rd = cur.filter(d => d.rain != null && d.rain >= 1).length, frost = cur.filter(d => d.tmin != null && d.tmin < 0).length, summer = cur.filter(d => d.tmax != null && d.tmax >= 25).length;
  const dl = (a, b, u, inv) => a == null || b == null ? '' : `<span class="delta ${((a - b) * (inv ? -1 : 1)) > 0 ? 'up' : 'dn'}">${sgn(a - b, 1)} ${u}</span> vs période précédente`;
  const k = (lab, val, unit, det) => `<div class="kpi"><div class="lab"><span>${lab}</span></div><div class="val">${val}<small>${unit}</small></div><div class="det">${det || ''}</div></div>`;
  return `<div class="kpis s12">
    ${k('Température moyenne', nf(tm), '°C', dl(tm, tmP, '°C'))}
    ${k('Maximum absolu', nf(hot && hot.tmax), '°C', hot ? EW.dayLabel(hot.k) : '')}
    ${k('Minimum absolu', nf(cold && cold.tmin), '°C', cold ? EW.dayLabel(cold.k) : '')}
    ${k('Pluie cumulée', nf(rain, 0), 'mm', cur.some(d => d.rain != null) ? (prev.length ? dl(rain, rainP, 'mm') : '') : 'pas de mesure')}
    ${k('Jours de pluie', rd, 'j', '≥ 1 mm · ' + cur.filter(d => d.rain != null && d.rain >= 0.2).length + ' j ≥ 0,2 mm')}
    ${k('Jours de gel', frost, 'j', 'mini < 0 °C · ' + summer + ' j ≥ 25 °C')}
    ${k('Rafale maximale', nf(gust && gust.gust, 0), 'km/h', gust ? EW.dayLabel(gust.k) : 'pas de mesure')}
    ${k('Pression moyenne', nf(pick(cur, 'pressMean', EW.mean), 0), 'hPa', cur.some(d => d.pressMean != null) ? 'de ' + nf(Math.min(...EW.nums(cur.map(d => d.pressMean))), 0) + ' à ' + nf(Math.max(...EW.nums(cur.map(d => d.pressMean))), 0) : 'pas de mesure')}
  </div>`;
}

// ───────────── Graphique principal ─────────────
const catAxis = labels => ({ type: 'category', grid: { display: false }, border: { display: false }, ticks: { color: css('--t2'), maxRotation: 0, autoSkip: true, maxTicksLimit: window.innerWidth < 600 ? 5 : 10, callback: function (v) { const l = this.getLabelForValue(v); return EW.dayLabel(l, { day: 'numeric', month: 'short' }); } } });
const tipDay = (unit, d) => Object.assign(EW.tip(unit, d, i => EW.dayLabel(i[0].label, { weekday: 'long', day: 'numeric', month: 'long' })), { itemSort: undefined, mode: 'index' });
function mainChart(cur) {
  const labels = cur.map(d => d.k); const F = fc(); const fm = k => cur.map(d => { const a = (F.get(d.k) || {})[H.model]; return a && a.n >= 12 ? a[k] : null; });
  const mc = EW.mcolor(H.model); const ds = []; let unit = HV[H.v].unit, dec = 1, stacked = false; let note = '';
  const has = (arr) => arr.some(x => x != null);
  if (H.v === 'temp') {
    ds.push({ type: 'bar', label: 'Min–max constatés', data: cur.map(d => d.tmin == null ? null : [d.tmin, d.tmax]), backgroundColor: css('--s2') + '88', borderRadius: 3, borderSkipped: false, barPercentage: .75, categoryPercentage: 1, order: 3 });
    ds.push({ type: 'line', label: 'Moyenne constatée', data: cur.map(d => d.tmean), borderColor: css('--obs'), borderWidth: 2, pointRadius: 0, tension: .3, order: 1 });
    const mx = fm('tmax'), mn = fm('tmin');
    if (has(mx)) { ds.push({ type: 'line', label: 'Maxi prévu (veille)', data: mx, borderColor: mc, borderWidth: 1.5, borderDash: [4, 3], pointRadius: 0, tension: .3, order: 2 }); ds.push({ type: 'line', label: 'Mini prévu (veille)', data: mn, borderColor: mc, borderWidth: 1.5, borderDash: [1, 3], pointRadius: 0, tension: .3, order: 2 }); }
    note = 'Barres : de la mini à la maxi constatées. Pointillés : prévision de la veille du modèle choisi.';
  } else if (H.v === 'rain') {
    let a = 0, b = 0; const obs = cur.map(d => d.rain), mod = fm('rain');
    const cumu = arr => arr.map(x => x == null ? null : (a += x)); const cum2 = arr => arr.map(x => x == null ? null : (b += x));
    if (H.cum) { ds.push({ type: 'line', label: 'Constaté (cumul)', data: cumu(obs), borderColor: css('--obs'), borderWidth: 2.6, pointRadius: 0, stepped: 'middle', order: 1 }); if (has(mod)) ds.push({ type: 'line', label: 'Prévu veille (cumul)', data: cum2(mod), borderColor: mc, borderWidth: 1.8, borderDash: [5, 3], pointRadius: 0, stepped: 'middle', order: 2 }); unit = 'mm cumulés'; }
    else { ds.push({ type: 'bar', label: 'Pluie constatée', data: obs, backgroundColor: css('--s1'), borderRadius: 3, barPercentage: .8, categoryPercentage: 1, order: 2 }); if (has(mod)) ds.push({ type: 'line', label: 'Prévue (veille)', data: mod, borderColor: mc, backgroundColor: mc, borderWidth: 1.4, pointRadius: 2.5, showLine: false, order: 1 }); unit = 'mm'; }
    note = 'Pluie du jour (de minuit à minuit). Points : cumul prévu la veille par le modèle choisi.';
  } else if (H.v === 'wind') {
    ds.push({ type: 'bar', label: 'Rafale maxi', data: cur.map(d => d.gust), backgroundColor: css('--s4') + 'aa', borderRadius: 3, barPercentage: .75, categoryPercentage: 1, order: 2 });
    ds.push({ type: 'line', label: 'Vent moyen', data: cur.map(d => d.windMean), borderColor: css('--s1'), borderWidth: 2, pointRadius: 0, tension: .3, order: 1 }); dec = 0; unit = 'km/h';
  } else if (H.v === 'press') { ds.push({ type: 'line', label: 'Pression moyenne', data: cur.map(d => d.pressMean), borderColor: css('--s7'), backgroundColor: css('--s7') + '22', fill: true, borderWidth: 2, pointRadius: 0, tension: .3 }); dec = 1; unit = 'hPa'; }
  else if (H.v === 'hum') { ds.push({ type: 'line', label: 'Humidité moyenne', data: cur.map(d => d.humMean), borderColor: css('--s3'), backgroundColor: css('--s3') + '22', fill: true, borderWidth: 2, pointRadius: 0, tension: .3 }); dec = 0; unit = '%'; }
  else { ds.push({ type: 'bar', label: 'Rayonnement moyen', data: cur.map(d => d.solarMean), backgroundColor: css('--s4') + 'aa', borderRadius: 3, barPercentage: .75, categoryPercentage: 1 }); dec = 0; unit = 'W/m²'; }
  const any = ds.some(s => has(s.data.map(x => Array.isArray(x) ? x[0] : x)));
  $('#h-empty').hidden = any; $('#c-hmain').parentElement.style.visibility = any ? 'visible' : 'hidden';
  const lg = ds.map(s => ({ label: s.label, color: s.borderColor && s.type === 'line' ? s.borderColor : (s.backgroundColor || '').slice(0, 7), fixed: true, dash: s.borderDash && s.borderDash.length })); EW.legend($('#h-legend'), lg.map((x, i) => Object.assign(x, { id: i })), new Set(), () => { });
  EW.mk('c-hmain', { type: 'bar', data: { labels, datasets: ds }, options: EW.baseOpts({ scales: { x: catAxis(labels), y: EW.yAxis(unit, { beginAtZero: ['rain', 'wind', 'solar'].includes(H.v) }) }, plugins: { legend: { display: false }, tooltip: Object.assign(tipDay(unit, dec), { callbacks: { title: i => EW.dayLabel(i[0].label, { weekday: 'long', day: 'numeric', month: 'long' }), label: c => ' ' + c.dataset.label + ' : ' + (Array.isArray(c.raw) ? nf(c.raw[0], 1) + ' → ' + nf(c.raw[1], 1) : nf(c.parsed.y, dec)) + ' ' + unit.replace(' cumulés', '') } }) } }) });
  $('#h-foot').textContent = note;
}
function errChart(cur) {
  const F = fc(); const labels = cur.map(d => d.k); const mc = EW.mcolor(H.model); const ds = [];
  const e = (k, kf) => cur.map(d => { const a = (F.get(d.k) || {})[H.model]; return a && a.n >= 12 && d[k] != null && a[kf] != null ? a[kf] - d[k] : null; });
  const t = H.v === 'rain';
  if (t) ds.push({ label: 'Pluie prévue − constatée', data: e('rain', 'rain'), backgroundColor: cur.map((d, i) => mc), borderRadius: 3, barPercentage: .8, categoryPercentage: 1 });
  else { ds.push({ label: 'Erreur sur la maxi', data: e('tmax', 'tmax'), backgroundColor: css('--s2'), borderRadius: 3, barPercentage: .8, categoryPercentage: .9 }); ds.push({ label: 'Erreur sur la mini', data: e('tmin', 'tmin'), backgroundColor: css('--s1'), borderRadius: 3, barPercentage: .8, categoryPercentage: .9 }); }
  const unit = t ? 'mm' : '°C';
  $('#h-err-title').textContent = 'Écart de la prévision de la veille · ' + LABELS[H.model];
  const any = ds.some(s => s.data.some(x => x != null)); $('#c-herr').parentElement.style.visibility = any ? 'visible' : 'hidden'; $('#h-err-empty').hidden = any;
  EW.mk('c-herr', { type: 'bar', data: { labels, datasets: ds }, options: EW.baseOpts({ scales: { x: catAxis(labels), y: EW.yAxis(unit + ' (prévu − constaté)') }, plugins: { legend: { display: ds.length > 1, labels: { usePointStyle: true, boxWidth: 8, boxHeight: 8 } }, tooltip: Object.assign(tipDay(unit, 1), { callbacks: { title: i => EW.dayLabel(i[0].label, { weekday: 'long', day: 'numeric', month: 'long' }), label: c => ' ' + c.dataset.label + ' : ' + sgn(c.parsed.y, 1) + ' ' + unit } }) } }) });
}

// ───────────── Calendrier ─────────────
const CAL = { tmean: ['Température moyenne', '°C', 'temp'], rain: ['Pluie', 'mm', 'rain'], gust: ['Rafale maxi', 'km/h', 'wind'] };
function calendar(cur) {
  const box = $('#h-cal'); if (!cur.length) { box.innerHTML = ''; return; }
  const key = H.cal; const vals = new Map(cur.map(d => [d.k, d[key]])); const nn = EW.nums([...vals.values()]); if (!nn.length) { box.innerHTML = '<p class="foot">Pas de mesure pour cette grandeur.</p>'; return; }
  const lo = Math.min(...nn), hi = Math.max(...nn), sp = hi - lo || 1; const first = new Date(cur[0].k + 'T12:00:00Z'); const dow = (first.getUTCDay() + 6) % 7; const cells = [];
  for (let i = 0; i < dow; i++) cells.push('<i class="cell none"></i>');
  const color = q => key === 'tmean' ? `color-mix(in srgb, #e24d3b ${Math.round(q * 100)}%, #338ced)` : `color-mix(in srgb, var(--s1) ${Math.round(8 + q * 92)}%, var(--surface-2))`;
  for (const d of cur) { const v = d[key]; const q = v == null ? null : (v - lo) / sp; cells.push(`<i class="cell" style="${q == null ? '' : 'background:' + color(q)}" title="${EW.dayLabel(d.k, { weekday: 'long', day: 'numeric', month: 'long' })} : ${v == null ? '—' : nf(v, 1) + ' ' + CAL[key][1]}"></i>`); }
  box.innerHTML = `<div class="calgrid">${cells.join('')}</div><div class="calleg"><span>${nf(lo, 0)} ${CAL[key][1]}</span><i style="background:linear-gradient(90deg,${color(0)},${color(1)})"></i><span>${nf(hi, 0)} ${CAL[key][1]}</span></div>`;
}

// ───────────── Records & mois ─────────────
function records(cur) {
  const r = (lab, d, key, unit, f, dec = 1) => { const x = dateOf(cur, key, f); return `<div class="rec"><span>${lab}</span><b>${x ? nf(x[key], dec) + ' ' + unit : '—'}</b><small>${x ? EW.dayLabel(x.k, { weekday: 'short', day: 'numeric', month: 'short' }) : ''}</small></div>`; };
  const amp = cur.filter(d => d.tmax != null && d.tmin != null).map(d => Object.assign({ amp: d.tmax - d.tmin }, d));
  const ax = dateOf(amp, 'amp', (a, b) => a > b);
  const warm = dateOf(cur.filter(d => d.tmin != null), 'tmin', (a, b) => a > b), cool = dateOf(cur.filter(d => d.tmax != null), 'tmax', (a, b) => a < b);
  return `<div class="recs">${r('Journée la plus chaude', 0, 'tmax', '°C', (a, b) => a > b)}${r('Nuit la plus froide', 0, 'tmin', '°C', (a, b) => a < b)}
    <div class="rec"><span>Nuit la plus douce</span><b>${warm ? nf(warm.tmin) + ' °C' : '—'}</b><small>${warm ? EW.dayLabel(warm.k, { weekday: 'short', day: 'numeric', month: 'short' }) : ''}</small></div>
    <div class="rec"><span>Journée la plus fraîche</span><b>${cool ? nf(cool.tmax) + ' °C' : '—'}</b><small>${cool ? EW.dayLabel(cool.k, { weekday: 'short', day: 'numeric', month: 'short' }) : ''}</small></div>
    <div class="rec"><span>Plus forte amplitude</span><b>${ax ? nf(ax.amp) + ' °C' : '—'}</b><small>${ax ? EW.dayLabel(ax.k, { weekday: 'short', day: 'numeric', month: 'short' }) : ''}</small></div>
    ${r('Journée la plus pluvieuse', 0, 'rain', 'mm', (a, b) => a > b)}${r('Rafale la plus forte', 0, 'gust', 'km/h', (a, b) => a > b, 0)}${r('Pression la plus haute', 0, 'pressMean', 'hPa', (a, b) => a > b, 0)}${r('Pression la plus basse', 0, 'pressMean', 'hPa', (a, b) => a < b, 0)}</div>`;
}
function months() {
  const all = [...EW.daily.values()].filter(d => d.n >= 12 && d.k < EW.todayKey()); const by = new Map(); for (const d of all) { const m = d.k.slice(0, 7); if (!by.has(m)) by.set(m, []); by.get(m).push(d); }
  const rows = [...by.entries()].sort((a, b) => a[0] < b[0] ? 1 : -1).slice(0, 14).map(([m, ds]) => ({ m, n: ds.length, tm: EW.mean(ds.map(d => d.tmean)), lo: Math.min(...EW.nums(ds.map(d => d.tmin)).concat([99])), hi: Math.max(...EW.nums(ds.map(d => d.tmax)).concat([-99])), rain: ds.some(d => d.rain != null) ? EW.sum(ds.map(d => d.rain)) : null, rd: ds.filter(d => d.rain >= 1).length, g: pick(ds, 'gust', a => Math.max(...a)) }));
  return '<thead><tr><th>Mois</th><th>Jours</th><th>T moy.</th><th>Mini</th><th>Maxi</th><th>Pluie</th><th>Jours de pluie</th><th>Rafale</th></tr></thead><tbody>' + rows.map(r => `<tr><td>${new Date(r.m + '-15T12:00:00Z').toLocaleDateString('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' })}</td><td>${r.n}</td><td>${nf(r.tm)}°</td><td>${r.lo > 90 ? '—' : nf(r.lo) + '°'}</td><td>${r.hi < -90 ? '—' : nf(r.hi) + '°'}</td><td>${r.rain == null ? '—' : nf(r.rain, 0) + ' mm'}</td><td>${r.rain == null ? '—' : r.rd}</td><td>${r.g == null ? '—' : nf(r.g, 0)}</td></tr>`).join('') + '</tbody>';
}

// ───────────── Tableau jour par jour ─────────────
const COLS = [
  ['k', 'Date', d => d.k, d => EW.dayLabel(d.k, { weekday: 'short', day: 'numeric', month: 'short' })],
  ['tmin', 'Mini', d => d.tmin, d => nf(d.tmin)], ['tmax', 'Maxi', d => d.tmax, d => nf(d.tmax)], ['tmean', 'Moy.', d => d.tmean, d => nf(d.tmean)],
  ['ftmin', 'Mini prévue', d => d.ftmin, d => nf(d.ftmin), 'fc'], ['ftmax', 'Maxi prévue', d => d.ftmax, d => nf(d.ftmax), 'fc'],
  ['etmax', 'Écart maxi', d => d.etmax, d => d.etmax == null ? '—' : `<span class="${Math.abs(d.etmax) <= 1 ? 'ok' : Math.abs(d.etmax) >= 3 ? 'bad' : ''}">${sgn(d.etmax)}</span>`],
  ['rain', 'Pluie (mm)', d => d.rain, d => nf(d.rain)], ['frain', 'Prévue (mm)', d => d.frain, d => nf(d.frain), 'fc'],
  ['erain', 'Écart pluie', d => d.erain, d => d.erain == null ? '—' : `<span class="${Math.abs(d.erain) <= 1 ? 'ok' : Math.abs(d.erain) >= 5 ? 'bad' : ''}">${sgn(d.erain)}</span>`],
  ['gust', 'Rafale', d => d.gust, d => nf(d.gust, 0)], ['windMean', 'Vent moy.', d => d.windMean, d => nf(d.windMean, 0)], ['pressMean', 'Pression', d => d.pressMean, d => nf(d.pressMean, 0)], ['humMean', 'Humidité', d => d.humMean, d => nf(d.humMean, 0)],
];
function tableRows(cur) {
  const F = fc();
  return cur.map(d => { const a = (F.get(d.k) || {})[H.model]; const ok = a && a.n >= 12; const o = Object.assign({}, d); o.ftmin = ok ? a.tmin : null; o.ftmax = ok ? a.tmax : null; o.frain = ok && a.rn ? a.rain : null; o.etmax = ok && d.tmax != null && a.tmax != null ? a.tmax - d.tmax : null; o.erain = ok && d.rain != null && o.frain != null ? o.frain - d.rain : null; return o; });
}
function drawTable(cur) {
  const rows = tableRows(cur); const c = COLS.find(x => x[0] === H.sort.k) || COLS[0];
  rows.sort((a, b) => { const x = c[2](a), y = c[2](b); if (x == null && y == null) return 0; if (x == null) return 1; if (y == null) return -1; return (x < y ? -1 : x > y ? 1 : 0) * H.sort.dir; });
  const shown = rows.slice(0, H.rows);
  $('#h-table').innerHTML = '<thead><tr>' + COLS.map(x => `<th data-k="${x[0]}" class="sortable ${H.sort.k === x[0] ? 'on' : ''}${x[4] ? ' fcol' : ''}">${x[1]}${H.sort.k === x[0] ? (H.sort.dir > 0 ? ' ▲' : ' ▼') : ''}</th>`).join('') + '</tr></thead><tbody>' + shown.map(r => '<tr>' + COLS.map(x => `<td${x[4] ? ' class="fcol"' : ''}>${x[3](r)}</td>`).join('') + '</tr>').join('') + '</tbody>';
  $$('#h-table th').forEach(th => th.onclick = () => { const k = th.dataset.k; H.sort = { k, dir: H.sort.k === k ? -H.sort.dir : (k === 'k' ? -1 : -1) }; drawTable(cur); });
  const more = $('#h-more'); more.hidden = rows.length <= H.rows; more.textContent = 'Voir les ' + Math.min(31, rows.length - H.rows) + ' jours suivants (' + rows.length + ' au total)'; more.onclick = () => { H.rows += 31; drawTable(cur); };
  $('#h-csv').onclick = () => EW.downloadCSV('historique-journalier.csv', COLS.map(x => x[1]), rows.map(r => COLS.map(x => { const v = x[2](r); return typeof v === 'number' ? Math.round(v * 100) / 100 : v; })));
}

// ───────────── Assemblage ─────────────
EW.views.hist = {
  render() {
    const root = $('#view-hist'); const { cur, prev } = periodDays();
    if (!cur.length) { root.innerHTML = EW.card('s12', 'Historique', '', '<p class="verdict">Pas encore de jours complets dans <code>station.csv</code>.</p>'); return; }
    const fmodels = MODELS.filter(m => [...fc().values()].some(by => by[m]));
    if (!fmodels.includes(H.model)) H.model = fmodels[0] || 'Auto';
    root.innerHTML = `<div class="card s12 ctl"><div class="controls wrap"><div class="seg" id="h-per"></div><span class="sub">${cur.length} jours · du ${EW.dayLabel(cur[0].k, { day: 'numeric', month: 'short', year: 'numeric' })} au ${EW.dayLabel(cur[cur.length - 1].k, { day: 'numeric', month: 'short', year: 'numeric' })}</span><span class="spacer"></span><span class="sub">Prévision de la veille :</span><div class="seg" id="h-model"></div></div></div>` + kpis(cur, prev) +
      `<div class="card s12"><div class="card-head"><h2>Évolution jour après jour</h2><div class="seg" id="h-var"></div></div><div class="controls"><div class="seg" id="h-cumseg" hidden></div></div><div class="legend" id="h-legend"></div><div class="chart tall"><canvas id="c-hmain" role="img" aria-label="Historique journalier"></canvas></div><p class="empty" id="h-empty" hidden>Pas encore de mesure pour cette grandeur : elle apparaîtra dès que la collecte de la station aura tourné.</p><p class="foot" id="h-foot"></p></div>` +
      `<div class="card s7"><div class="card-head"><h2 id="h-err-title">Écart de la prévision</h2><span class="sub">prévu − constaté, veille</span></div><div class="chart"><canvas id="c-herr" role="img" aria-label="Écart des prévisions"></canvas></div><p class="empty" id="h-err-empty" hidden>Pas assez de prévisions archivées pour ce modèle sur la période.</p></div>` +
      `<div class="card s5"><div class="card-head"><h2>Records de la période</h2></div>${records(cur)}</div>` +
      `<div class="card s7"><div class="card-head"><h2>Calendrier</h2><div class="seg" id="h-calseg"></div></div><div id="h-cal"></div></div>` +
      `<div class="card s5"><div class="card-head"><h2>Mois par mois</h2></div><div class="scroll"><table class="tbl">${months()}</table></div></div>` +
      `<div class="card s12"><div class="card-head"><h2>Jour par jour</h2><div class="tools"><span class="sub" id="h-hint">clic sur un titre pour trier</span><button class="btn" id="h-csv">Exporter en CSV</button></div></div><div class="scroll tall-scroll"><table class="tbl dense" id="h-table"></table></div><button class="btn wide" id="h-more" hidden></button><p class="foot">Mini et maxi issues des relevés horaires de la station ; prévisions de la veille (J+1) du modèle choisi, agrégées de minuit à minuit.</p></div>`;
    EW.seg($('#h-per'), [[30, '30 j'], [90, '90 j'], [365, '1 an'], [9999, 'Tout']], H.d, k => { H.d = +k; H.rows = 31; EW.views.hist.render(); });
    EW.seg($('#h-model'), fmodels.map(m => [m, LABELS[m]]), H.model, k => { H.model = k; EW.views.hist.render(); });
    EW.seg($('#h-var'), Object.entries(HV).map(([k, v]) => [k, v.label]), H.v, k => { H.v = k; $('#h-cumseg').hidden = k !== 'rain'; mainChart(cur); errChart(cur); });
    $('#h-cumseg').hidden = H.v !== 'rain'; EW.seg($('#h-cumseg'), [['0', 'Par jour'], ['1', 'Cumul']], H.cum ? '1' : '0', k => { H.cum = k === '1'; mainChart(cur); });
    EW.seg($('#h-calseg'), Object.entries(CAL).map(([k, v]) => [k, v[0]]), H.cal, k => { H.cal = k; calendar(cur); });
    mainChart(cur); errChart(cur); calendar(cur); drawTable(cur);
  },
};
})();
