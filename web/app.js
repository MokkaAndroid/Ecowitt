/* Ecowidget · page web : station + prévisions par modèle + performance des modèles.
   Données : data/station.csv, data/forecasts.csv, data/latest.json (écrits par collect.py). */
(() => {
'use strict';

const MODELS = ['AROME', 'ICON-D2', 'ICON-CH1', 'ICON-EU', 'ARPEGE', 'IFS', 'Auto'];
const LABELS = { AROME: 'AROME HD', 'ICON-D2': 'ICON-D2', 'ICON-CH1': 'ICON-CH1', 'ICON-EU': 'ICON-EU', ARPEGE: 'ARPEGE', IFS: 'ECMWF IFS', Auto: 'Auto Open-Meteo' };
const ECH = [['h1', '+1–2 h'], ['h3', '+3–4 h'], ['h5', '+5–6 h'], ['h11', '+11–12 h'], ['j1', 'J+1'], ['j2', 'J+2'], ['j3', 'J+3'], ['j4', 'J+4'], ['j5', 'J+5']];
const HOUR = 3600;
const WET = 0.2;
const CARD = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSO', 'SO', 'OSO', 'O', 'ONO', 'NO', 'NNO'];
// Modèle réellement utilisé dans l'appli, par grandeur et échéance (pastille « utilisé »).
const USED = { temp: { h: 'ICON-CH1', j: 'Auto' }, rain: { h: 'ICON-D2', j: 'Auto' } };

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const mcolor = m => css('--s' + (MODELS.indexOf(m) + 1));
const nf = (v, d = 1) => (v == null || Number.isNaN(v)) ? '—' : v.toLocaleString('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d });
const sgn = (v, d = 1) => (v == null || Number.isNaN(v)) ? '—' : (v > 0 ? '+' : '') + nf(v, d);
const el = (tag, attrs = {}, html = '') => { const e = document.createElement(tag); for (const k in attrs) e.setAttribute(k, attrs[k]); e.innerHTML = html; return e; };
const dayKey = e => new Date(e * 1000).toLocaleDateString('sv-SE');
const fmtDT = (ms, o) => new Date(ms).toLocaleString('fr-FR', o);
const cardinal = d => d == null ? '' : CARD[Math.round(((d % 360) + 360) % 360 / 22.5) % 16];

// ───────────── Données ─────────────
const S = { station: new Map(), stArr: [], fc: [], latest: null, hasFc: false };
let tick = Math.floor(Date.now() / 60000);

async function getText(u) { const r = await fetch(u + '?v=' + tick, { cache: 'no-store' }); if (!r.ok) throw new Error(u + ' ' + r.status); return r.text(); }
function parseCSV(text) {
  const lines = text.split('\n'); const head = lines[0].trim().split(','); const out = [];
  for (let i = 1; i < lines.length; i++) { const l = lines[i]; if (!l) continue; const p = l.split(','); const o = {}; for (let k = 0; k < head.length; k++) o[head[k]] = p[k]; out.push(o); }
  return out;
}
const n = v => (v === '' || v == null) ? null : +v;

async function load() {
  tick = Math.floor(Date.now() / 60000);
  const [st, fc, la] = await Promise.allSettled([getText('data/station.csv'), getText('data/forecasts.csv'), getText('data/latest.json')]);
  if (st.status === 'fulfilled') {
    S.station = new Map(); S.stArr = [];
    for (const r of parseCSV(st.value)) {
      const o = { e: +r.epoch, temp: n(r.temp), feels: n(r.feels), hum: n(r.hum), dew: n(r.dew), press: n(r.press), wind: n(r.wind), gust: n(r.gust), dir: n(r.wind_dir), rain: n(r.rain_h), rainDay: n(r.rain_day), uv: n(r.uv), solar: n(r.solar), tin: n(r.temp_in), hin: n(r.hum_in) };
      S.station.set(o.e, o); S.stArr.push(o);
    }
    S.stArr.sort((a, b) => a.e - b.e);
  }
  if (fc.status === 'fulfilled') {
    S.fc = parseCSV(fc.value).map(r => ({ e: +r.epoch, run: r.run, lead: +r.lead_h, ech: r.ech, m: r.model, t: n(r.temp), r: n(r.rain) }));
    S.hasFc = true;
  }
  S.latest = la.status === 'fulfilled' ? JSON.parse(la.value) : null;
}

// ───────────── Thème & graphiques ─────────────
const charts = {};
function applyChartDefaults() {
  Chart.defaults.font.family = '"DM Sans", system-ui, sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.color = css('--t2');
  Chart.defaults.borderColor = css('--grid');
}
function mk(id, cfg) {
  if (charts[id]) charts[id].destroy();
  applyChartDefaults();
  charts[id] = new Chart($('#' + id), cfg);
  return charts[id];
}
const tzOff = () => -new Date().getTimezoneOffset() * 60000;
// Repères de l'axe du temps toutes les 3 / 6 / 12 / 24 h (heure locale).
function timeAxis(min, max, unit) {
  return {
    type: 'linear', min, max,
    grid: { color: css('--grid'), drawTicks: false },
    ticks: {
      color: css('--t2'), maxRotation: 0, autoSkip: false, padding: 6,
      callback: v => { const d = new Date(v); if (max - min > 5 * 86400e3) return d.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric' }); return (max - min > 36 * 3600e3) ? d.toLocaleString('fr-FR', { weekday: 'short', day: 'numeric', hour: '2-digit' }).replace(':00', ' h') : d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }).replace(':00', ' h'); },
    },
    afterBuildTicks: sc => {
      const span = sc.max - sc.min; const narrow = sc.width < 520 ? 2 : 1; const step = (span > 5 * 86400e3 ? 24 * narrow : span > 2 * 86400e3 ? 12 * narrow : span > 30 * 3600e3 ? 6 * narrow : 3 * narrow) * 3600e3; const off = tzOff();
      const t = []; for (let v = Math.ceil((sc.min + off) / step) * step - off; v <= sc.max; v += step) t.push({ value: v });
      sc.ticks = t;
    },
  };
}
const nowLine = { id: 'nowLine', afterDatasetsDraw(c, _a, o) {
  if (!o || o.x == null) return; const { ctx, chartArea: a, scales: { x } } = c; if (o.x < x.min || o.x > x.max) return;
  const px = x.getPixelForValue(o.x); ctx.save(); ctx.strokeStyle = css('--t2'); ctx.globalAlpha = .55; ctx.setLineDash([3, 4]); ctx.beginPath(); ctx.moveTo(px, a.top); ctx.lineTo(px, a.bottom); ctx.stroke();
  ctx.setLineDash([]); ctx.fillStyle = css('--t2'); ctx.font = '600 11px "DM Sans"'; ctx.textAlign = 'center'; ctx.fillText('maintenant', px, a.top + 10); ctx.restore();
} };
const tipBase = (unit, d = 1) => ({
  mode: 'x', intersect: false, itemSort: (a, b) => b.parsed.y - a.parsed.y,
  backgroundColor: css('--surface'), titleColor: css('--tx'), bodyColor: css('--tx'), borderColor: css('--line'), borderWidth: 1, padding: 10, boxPadding: 4,
  callbacks: {
    title: i => fmtDT(i[0].parsed.x, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }),
    label: c => ' ' + c.dataset.label + ' : ' + nf(c.parsed.y, d) + ' ' + unit,
  },
});

// ───────────── Onglet Météo ─────────────
const VARS = {
  temp: { label: 'Température', unit: '°C', st: 'temp', d: 1 },
  rain: { label: 'Pluie', unit: 'mm', st: 'rain', d: 1, zero: true },
  wind: { label: 'Vent', unit: 'km/h', st: 'wind', d: 0, zero: true },
  gust: { label: 'Rafales', unit: 'km/h', st: 'gust', d: 0, zero: true },
  hum: { label: 'Humidité', unit: '%', st: 'hum', d: 0 },
  cloud: { label: 'Nuages', unit: '%', st: null, d: 0, zero: true },
};
const W = { v: 'temp', h: 48, mode: 'hour', off: new Set(), histDays: 7, histVar: 'temp' };

function buildSeg(id, items, key, cb) {
  const seg = $('#' + id); seg.innerHTML = '';
  for (const [k, lab] of items) { const b = el('button', { 'data-k': k }, lab); if (k === W[key]) b.className = 'on'; b.onclick = () => { W[key] = k; $$('button', seg).forEach(x => x.className = x.dataset.k === String(k) ? 'on' : ''); cb(); }; seg.appendChild(b); }
}

function renderHeader() {
  const L = S.latest, st = L && L.station;
  const obs = st && st.observed; const age = obs ? Math.max(0, Math.round((Date.now() / 1000 - obs) / 60)) : null;
  const chip = $('#age'); chip.className = 'chip ' + (age == null ? 'bad' : age <= 20 ? 'ok' : age <= 90 ? 'old' : 'bad');
  $('span', chip).textContent = age == null ? 'pas de mesure' : age < 60 ? 'il y a ' + Math.max(age, 1) + ' min' : 'il y a ' + Math.floor(age / 60) + ' h ' + String(age % 60).padStart(2, '0');
  const g = L && L.generated;
  $('#sub').textContent = S.stArr.length ? 'Station · ' + S.stArr.length.toLocaleString('fr-FR') + ' h de mesures' + (S.hasFc ? ' · ' + S.fc.length.toLocaleString('fr-FR') + ' prévisions archivées' : '') : 'Aucune donnée';
  $('#gen').textContent = g ? 'Collecte du ' + fmtDT(g * 1000, { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) : 'Aucune collecte';
  const nt = $('#notice'); const errs = (L && L.errors) || [];
  nt.hidden = !errs.length && !!L; if (!L) { nt.hidden = false; nt.textContent = 'latest.json introuvable : la collecte n\'a pas encore tourné.'; } else if (errs.length) nt.innerHTML = '<b>Collecte :</b> ' + errs.map(e => e.replace(/</g, '&lt;')).join(' · ');
}

function spark(cv, vals, color) {
  const dpr = window.devicePixelRatio || 1; const w = cv.clientWidth, h = cv.clientHeight; cv.width = w * dpr; cv.height = h * dpr;
  const c = cv.getContext('2d'); c.scale(dpr, dpr); const v = vals.filter(x => x != null); if (v.length < 2) return;
  const lo = Math.min(...v), hi = Math.max(...v), sp = hi - lo || 1;
  c.strokeStyle = color; c.lineWidth = 1.8; c.lineJoin = 'round'; c.beginPath(); let k = 0;
  vals.forEach((x, i) => { if (x == null) return; const px = i / (vals.length - 1) * (w - 2) + 1, py = h - 3 - (x - lo) / sp * (h - 6); k++ ? c.lineTo(px, py) : c.moveTo(px, py); }); c.stroke();
}

function renderKpis() {
  const box = $('#kpis'); box.innerHTML = '';
  const st = (S.latest && S.latest.station) || {}; const now = Date.now() / 1000;
  const last24 = S.stArr.filter(r => r.e > now - 24 * HOUR);
  const ser = k => last24.map(r => r[k]);
  const tmin = Math.min(...last24.map(r => r.temp).filter(x => x != null), st.temp ?? 99);
  const tmax = Math.max(...last24.map(r => r.temp).filter(x => x != null), st.temp ?? -99);
  const rain24 = last24.reduce((a, r) => a + (r.rain || 0), 0);
  const add = (cls, lab, val, unit, det, key, color, extra = '') => {
    const k = el('div', { class: 'kpi ' + cls }, `<div class="lab"><span>${lab}</span>${extra}</div><div class="val">${val}<small>${unit}</small></div><div class="det">${det}</div>${key ? '<canvas></canvas>' : ''}`);
    box.appendChild(k); if (key) spark($('canvas', k), ser(key), color || css('--accent'));
  };
  const th = css('--s1');
  add('big', 'Température', nf(st.temp), '°C', `Ressenti <b>${nf(st.feels)}°</b> · min <b>${nf(Number.isFinite(tmin) && tmin < 90 ? tmin : null)}°</b> · max <b>${nf(Number.isFinite(tmax) && tmax > -90 ? tmax : null)}°</b> (24 h)`, 'temp', css('--s2'));
  add('', 'Humidité', nf(st.hum, 0), '%', `Point de rosée <b>${nf(st.dew)}°</b>`, 'hum', th);
  add('', 'Pression', nf(st.press, 0), 'hPa', S.stArr.length > 3 && st.press != null ? (() => { const p3 = last24.find(r => r.e >= now - 3.5 * HOUR && r.press != null); return p3 ? `<b>${sgn(st.press - p3.press, 1)}</b> hPa en 3 h` : 'relative'; })() : 'relative', 'press', th);
  add('', 'Vent', nf(st.wind, 0), 'km/h', `Rafale <b>${nf(st.gust, 0)}</b> · <b>${cardinal(st.wind_dir)}</b>`, 'wind', th, st.wind_dir != null ? `<span class="arrow" style="transform:rotate(${st.wind_dir + 180}deg)">↑</span>` : '');
  add('', 'Pluie', nf(st.rain_day, 1), 'mm', `Aujourd'hui · débit <b>${nf(st.rain_rate, 1)}</b> mm/h · 24 h <b>${nf(rain24, 1)}</b> mm`, null);
  add('', 'UV', nf(st.uv, 1), '', `Solaire <b>${nf(st.solar, 0)}</b> W/m²`, 'uv', css('--s4'));
  add('', 'Intérieur', nf(st.temp_in), '°C', `Humidité <b>${nf(st.hum_in, 0)}</b> %`, 'tin', th);
}

function modelSeries(key) {
  const f = S.latest && S.latest.forecast; if (!f) return [];
  return MODELS.filter(m => f.series[m] && f.series[m][key]).map(m => ({ m, times: f.times, vals: f.series[m][key] }));
}

function renderMain() {
  const V = VARS[W.v]; const now = Date.now(); const min = now - 24 * 3600e3, max = now + W.h * 3600e3;
  const cum = W.v === 'rain' && W.mode === 'cum';
  $('#mode-seg').hidden = W.v !== 'rain';
  const key = { temp: 'temp', rain: 'rain', wind: 'wind', gust: 'gust', hum: 'hum', cloud: 'cloud' }[W.v];
  const mseries = modelSeries(key);
  // Légende
  const lg = $('#legend'); lg.innerHTML = '';
  if (V.st) lg.appendChild(el('button', { class: 'fixed', style: '--c:' + css('--obs') }, '<i style="height:4px"></i>Station'));
  for (const s of mseries) {
    const b = el('button', { style: '--c:' + mcolor(s.m), class: W.off.has(s.m) ? 'off' : '', title: 'Afficher / masquer' }, '<i></i>' + LABELS[s.m]);
    b.onclick = () => { W.off.has(s.m) ? W.off.delete(s.m) : W.off.add(s.m); renderMain(); renderLead(); }; lg.appendChild(b);
  }
  const ds = [];
  const acc = (pts) => { if (!cum) return pts; let a = 0; return pts.map(p => ({ x: p.x, y: p.y == null ? null : (a += p.y) })); };
  if (V.st) {
    const pts = S.stArr.filter(r => r.e * 1000 >= min && r[V.st] != null).map(r => ({ x: r.e * 1000, y: r[V.st] }));
    const st = S.latest && S.latest.station; if (st && W.v === 'temp' && st.temp != null && st.observed && st.observed * 1000 > (pts.length ? pts[pts.length - 1].x : 0)) pts.push({ x: st.observed * 1000, y: st.temp });
    if (W.v === 'rain') ds.push({ label: 'Station', data: acc(pts), borderColor: css('--obs'), backgroundColor: css('--obs') + '33', fill: true, borderWidth: 2.4, stepped: 'before', pointRadius: 0, order: 0 });
    else ds.push({ label: 'Station', data: pts, borderColor: css('--obs'), borderWidth: 2.8, pointRadius: 0, tension: .25, order: 0 });
  }
  for (const s of mseries) {
    if (W.off.has(s.m)) continue;
    const pts = []; s.times.forEach((t, i) => { const x = t * 1000; if (x >= min && x <= max) pts.push({ x, y: s.vals[i] }); });
    ds.push({ label: LABELS[s.m], data: acc(pts), borderColor: mcolor(s.m), borderWidth: 1.7, pointRadius: 0, tension: W.v === 'rain' ? 0 : .25, stepped: W.v === 'rain' ? 'before' : false, spanGaps: false, borderDash: s.m === 'Auto' ? [5, 3] : [], order: 1 });
  }
  const unit = cum ? 'mm cumulés' : V.unit;
  mk('c-main', {
    type: 'line', data: { datasets: ds },
    options: {
      responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false },
      scales: { x: timeAxis(min, max), y: { beginPaddingZero: true, beginAtZero: !!V.zero, grid: { color: css('--grid') }, title: { display: true, text: unit, color: css('--t2') }, border: { display: false } } },
      plugins: { legend: { display: false }, tooltip: tipBase(V.unit, V.d), nowLine: { x: now } },
    },
    plugins: [nowLine],
  });
  const f = S.latest && S.latest.forecast;
  $('#main-foot').textContent = !mseries.length && !V.st ? 'Pas de prévision.' : (f ? 'Prévisions du ' + fmtDT(S.latest.generated * 1000, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) + ' · ' + (V.st ? 'trait épais = mesures de la station' : 'cette grandeur n\'est pas mesurée par la station') + (W.v === 'rain' ? ' · pluie de l\'heure qui se termine' : '') : 'Prévisions indisponibles (voir le bandeau).');
}

const LEAD_H = [1, 3, 6, 12, 24, 48, 72];
function renderLead() {
  const t = $('#t-lead'); const key = { temp: 'temp', rain: 'rain', wind: 'wind', gust: 'gust', hum: 'hum', cloud: 'cloud' }[W.v]; const V = VARS[W.v];
  const ms = modelSeries(key); $('#tbl-sub').textContent = W.v === 'rain' ? 'cumul depuis maintenant (mm)' : V.label + ' (' + V.unit + ')';
  if (!ms.length) { t.innerHTML = '<tbody><tr><td>Pas de prévision</td></tr></tbody>'; return; }
  const t0 = Math.floor(Date.now() / 1000 / HOUR) * HOUR;
  let html = '<thead><tr><th>Modèle</th>' + LEAD_H.map(h => `<th>+${h < 24 ? h + ' h' : h / 24 + ' j'}</th>`).join('') + '</tr></thead><tbody>';
  const rows = ms.map(s => {
    const idx = new Map(s.times.map((t, i) => [t, i]));
    const cells = LEAD_H.map(h => {
      if (W.v === 'rain') { let a = 0, any = false; for (let k = 1; k <= h; k++) { const i = idx.get(t0 + k * HOUR); if (i != null && s.vals[i] != null) { a += s.vals[i]; any = true; } } return any ? a : null; }
      const i = idx.get(t0 + h * HOUR); return i != null ? s.vals[i] : null;
    });
    return { m: s.m, cells };
  });
  const spread = LEAD_H.map((_, c) => { const v = rows.filter(r => !W.off.has(r.m)).map(r => r.cells[c]).filter(x => x != null); return v.length ? [Math.min(...v), Math.max(...v)] : null; });
  html += rows.map(r => `<tr style="${W.off.has(r.m) ? 'opacity:.4' : ''}"><td><span class="m" style="--c:${mcolor(r.m)}"><i></i>${LABELS[r.m]}</span></td>` + r.cells.map(v => `<td>${v == null ? '<span class="dim">—</span>' : nf(v, V.d)}</td>`).join('') + '</tr>').join('');
  html += '<tr class="group"><td>Écart entre modèles</td>' + spread.map(s => `<td>${s ? nf(s[1] - s[0], V.d) : '—'}</td>`).join('') + '</tr></tbody>';
  t.innerHTML = html;
}

function renderDays() {
  const t = $('#t-day'); const f = S.latest && S.latest.forecast; if (!f) { t.innerHTML = '<tbody><tr><td>Pas de prévision</td></tr></tbody>'; return; }
  const nowE = Date.now() / 1000; const today = dayKey(nowE); const days = [];
  for (let k = 0; k < 6; k++) days.push(dayKey(nowE + k * 86400));
  const lab = (d, k) => k === 0 ? 'Auj.' : k === 1 ? 'Dem.' : new Date(d + 'T12:00').toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric' });
  let html = '<thead><tr><th>Modèle</th>' + days.map((d, k) => `<th>${lab(d, k)}</th>`).join('') + '</tr></thead><tbody>';
  const cells = {};
  for (const m of MODELS) {
    const s = f.series[m]; if (!s || !s.temp) continue;
    cells[m] = days.map(d => {
      let lo = null, hi = null, rn = 0, nr = 0, cnt = 0;
      f.times.forEach((tt, i) => { if (dayKey(tt) !== d) return; const v = s.temp[i]; if (v != null) { cnt++; lo = lo == null ? v : Math.min(lo, v); hi = hi == null ? v : Math.max(hi, v); } if (s.rain && s.rain[i] != null) { rn += s.rain[i]; nr++; } });
      return cnt >= 6 ? { lo, hi, rn: nr ? rn : null } : null;
    });
  }
  html += Object.keys(cells).map(m => `<tr><td><span class="m" style="--c:${mcolor(m)}"><i></i>${LABELS[m]}</span></td>` + cells[m].map(c => c ? `<td>${nf(c.lo, 0)}° / <b>${nf(c.hi, 0)}°</b> · <span style="color:${c.rn >= .2 ? css('--s1') : css('--t2')}">${c.rn == null ? '—' : nf(c.rn, 1)} mm</span></td>` : '<td><span class="dim">—</span></td>').join('') + '</tr>').join('');
  t.innerHTML = html + '</tbody>';
}

function renderHist() {
  const now = Date.now(); const min = now - W.histDays * 86400e3; const V = { temp: ['Température', '°C', 'temp', 'line'], rain: ['Pluie', 'mm/h', 'rain', 'step'], wind: ['Vent', 'km/h', 'wind', 'line'], hum: ['Humidité', '%', 'hum', 'line'], press: ['Pression', 'hPa', 'press', 'line'] }[W.histVar];
  const rows = S.stArr.filter(r => r.e * 1000 >= min && r[V[2]] != null);
  const ds = [{ label: V[0], data: rows.map(r => ({ x: r.e * 1000, y: r[V[2]] })), borderColor: W.histVar === 'rain' ? css('--s1') : css('--s2'), backgroundColor: css('--s1') + '33', fill: W.histVar === 'rain', borderWidth: 1.8, pointRadius: 0, tension: .2, stepped: W.histVar === 'rain' ? 'before' : false }];
  if (W.histVar === 'wind') ds.push({ label: 'Rafales', data: rows.filter(r => r.gust != null).map(r => ({ x: r.e * 1000, y: r.gust })), borderColor: css('--s4'), borderWidth: 1.2, pointRadius: 0, tension: .2 });
  mk('c-hist', { type: 'line', data: { datasets: ds }, options: { responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false }, scales: { x: timeAxis(min, now), y: { beginAtZero: W.histVar === 'rain' || W.histVar === 'wind', grid: { color: css('--grid') }, border: { display: false }, title: { display: true, text: V[1], color: css('--t2') } } }, plugins: { legend: { display: ds.length > 1, labels: { usePointStyle: true, boxWidth: 8 } }, tooltip: tipBase(V[1], W.histVar === 'press' || W.histVar === 'temp' ? 1 : 1) } } });
}

function renderRaw() {
  const raw = (S.latest && S.latest.raw) || {}; const keys = Object.keys(raw).sort();
  $('#t-raw').innerHTML = keys.length ? '<thead><tr><th>Mesure</th><th>Valeur</th><th>Unité</th></tr></thead><tbody>' + keys.map(k => `<tr><td>${k}</td><td>${raw[k][0]}</td><td>${raw[k][1] || ''}</td></tr>`).join('') + '</tbody>' : '<tbody><tr><td>Aucune mesure reçue</td></tr></tbody>';
}

function renderWx() { renderHeader(); renderKpis(); renderMain(); renderLead(); renderDays(); renderHist(); renderRaw(); }

// ───────────── Onglet Performance ─────────────
const P = { v: 'temp', ech: 'h1', d: 60, met: 'mae', tol: false };
const METRICS = { temp: [['mae', 'Écart moyen'], ['bias', 'Biais']], rain: [['csi', 'Score'], ['pod', 'Détection'], ['far', 'Fausses alertes'], ['ratio', 'Quantité']] };

function perfData() {
  const rows = S.fc.filter(r => r.ech === P.ech);
  if (!rows.length) return null;
  const maxE = Math.max(...rows.map(r => r.e)); const cut = P.d >= 9999 ? 0 : maxE - P.d * 86400;
  const sel = rows.filter(r => r.e >= cut);
  const near = new Map(); if (P.v === 'rain') for (const r of rows) near.set(r.m + '|' + r.run + '|' + r.e, r.r);
  const out = {};
  for (const m of MODELS) out[m] = { n: 0, e: 0, ae: 0, se: 0, nd: 0, ed: 0, nn: 0, en: 0, hit: 0, miss: 0, fa: 0, rm: 0, rs: 0, nr: 0, ar: 0, wet: 0, cum: [], days: new Map(), hrs: Array.from({ length: 24 }, () => [0, 0]) };
  const per = new Map();
  for (const r of sel) {
    const o = out[r.m]; if (!o) continue; const s = S.station.get(r.e); if (!s) continue;
    const dk = dayKey(r.e); let D = o.days.get(dk); if (!D) { D = { ts: r.e, n: 0, ae: 0, e: 0, hit: 0, miss: 0, fa: 0, rm: 0, rs: 0 }; o.days.set(dk, D); }
    if (P.v === 'temp') {
      if (r.t == null || s.temp == null) continue;
      const e = r.t - s.temp; o.n++; o.e += e; o.ae += Math.abs(e); o.se += e * e; D.n++; D.ae += Math.abs(e); D.e += e;
      const hr = new Date(r.e * 1000).getHours(); o.hrs[hr][0] += e; o.hrs[hr][1]++;
      if (hr >= 8 && hr <= 19) { o.nd++; o.ed += e; } else { o.nn++; o.en += e; }
    } else {
      if (r.r == null || s.rain == null) continue;
      const sw = s.rain >= WET, mw = r.r >= WET; o.n++; o.rm += r.r; o.rs += s.rain; o.ar += Math.abs(r.r - s.rain); D.n++; D.rm += r.r; D.rs += s.rain;
      let hit = false, miss = false, fa = false;
      if (!P.tol) { hit = sw && mw; miss = sw && !mw; fa = mw && !sw; }
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

const stat = (o) => ({
  n: o.n, mae: o.n ? o.ae / o.n : null, bias: o.n ? o.e / o.n : null, rmse: o.n ? Math.sqrt(o.se / o.n) : null,
  biasDay: o.nd ? o.ed / o.nd : null, biasNight: o.nn ? o.en / o.nn : null,
  wet: o.wet, pod: (o.hit + o.miss) ? o.hit / (o.hit + o.miss) : null, far: (o.hit + o.fa) ? o.fa / (o.hit + o.fa) : null,
  csi: (o.hit + o.miss + o.fa) ? o.hit / (o.hit + o.miss + o.fa) : null, ratio: o.rs > 0 ? o.rm / o.rs : null, mad: o.n ? o.ar / o.n : null, hit: o.hit, miss: o.miss, fa: o.fa,
});

function renderRank(data) {
  const t = $('#t-rank'); const ms = MODELS.filter(m => data[m].n > 0); const st = Object.fromEntries(ms.map(m => [m, stat(data[m])]));
  const used = USED[P.v][P.ech[0] === 'h' ? 'h' : 'j'];
  const best = (k, f) => { const v = ms.filter(m => st[m][k] != null).map(m => [m, f(st[m][k])]); return v.length ? v.sort((a, b) => a[1] - b[1])[0][0] : null; };
  let cols, rank;
  if (P.v === 'temp') {
    cols = [['n', 'Heures', null, 0], ['mae', 'Écart moyen (°)', x => x, 2], ['bias', 'Biais (°)', Math.abs, 2, true], ['biasDay', 'Jour 8–20 h', Math.abs, 2, true], ['biasNight', 'Nuit', Math.abs, 2, true], ['rmse', 'RMSE (°)', x => x, 2]];
    rank = 'mae';
  } else {
    cols = [['wet', 'Heures de pluie', null, 0], ['pod', 'Détectées', x => -x, 0, false, true], ['far', 'Fausses alertes', x => x, 0, false, true], ['csi', 'Score', x => -x, 2], ['ratio', 'Prévu / mesuré', x => Math.abs(x - 1), 2], ['mad', 'Écart (mm/h)', x => x, 2]];
    rank = 'csi';
  }
  const order = [...ms].sort((a, b) => P.v === 'temp' ? (st[a].mae ?? 9e9) - (st[b].mae ?? 9e9) : (st[b].csi ?? -1) - (st[a].csi ?? -1));
  const bests = Object.fromEntries(cols.filter(c => c[2]).map(c => [c[0], best(c[0], c[2])]));
  let h = '<thead><tr><th>Modèle</th>' + cols.map(c => `<th>${c[1]}</th>`).join('') + '</tr></thead><tbody>';
  order.forEach((m, i) => {
    h += `<tr><td><span class="rk">${i + 1}</span><span class="m" style="--c:${mcolor(m)}"><i></i>${LABELS[m]}</span>${m === used ? '<span class="badge" title="Modèle utilisé dans l\'appli pour cette échéance">utilisé</span>' : ''}</td>`;
    for (const c of cols) { const v = st[m][c[0]]; const txt = v == null ? '—' : c[5] ? nf(v * 100, 0) + ' %' : c[4] ? sgn(v, c[3]) : nf(v, c[3]); h += `<td class="${bests[c[0]] === m ? 'best' : ''}">${txt}</td>`; }
    h += '</tr>';
  });
  t.innerHTML = h + '</tbody>';
  const ech = ECH.find(e => e[0] === P.ech)[1];
  if (order.length) {
    const a = st[order[0]], b = st[order[1]];
    $('#verdict').innerHTML = P.v === 'temp'
      ? `Température ${ech} : <b>${LABELS[order[0]]}</b> en tête avec ${nf(a.mae, 2)} ° d'écart moyen${b ? `, devant ${LABELS[order[1]]} (${nf(b.mae, 2)} °)` : ''}.`
      : `Pluie ${ech} : <b>${LABELS[order[0]]}</b> a le meilleur score (${nf(a.csi, 2)}) : ${nf(a.pod * 100, 0)} % des heures de pluie détectées, ${nf(a.far * 100, 0)} % de fausses alertes${a.ratio != null ? `, ${nf(a.ratio, 2)} fois la pluie mesurée` : ''}.`;
  } else $('#verdict').textContent = 'Pas encore de données pour cette échéance.';
  $('#rank-foot').textContent = P.v === 'temp'
    ? 'Prévision moins mesure de la station, heure par heure. Biais positif : le modèle voit trop chaud. En vert : le meilleur de chaque colonne (biais : le plus proche de 0).'
    : `Heure de pluie = au moins ${WET} mm. Détectées : part des heures de pluie annoncées. Fausses alertes : part des heures annoncées sans pluie mesurée. Score = détectées / (détectées + manquées + fausses). Quantité idéale : 1.`;
  return st;
}

function rolling(o, k, win) {
  const keys = [...o.days.keys()].sort(); const pts = [];
  keys.forEach((dk, i) => {
    const sl = keys.slice(Math.max(0, i - win + 1), i + 1).map(x => o.days.get(x)); const sum = f => sl.reduce((a, d) => a + d[f], 0);
    let y = null;
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

function renderPerfCharts(data) {
  const ms = MODELS.filter(m => data[m].n > 0);
  const lg = $('#p-legend'); lg.innerHTML = '';
  for (const m of ms) { const b = el('button', { style: '--c:' + mcolor(m), class: P.off && P.off.has(m) ? 'off' : '' }, '<i></i>' + LABELS[m]); b.onclick = () => { P.off = P.off || new Set(); P.off.has(m) ? P.off.delete(m) : P.off.add(m); renderPerfCharts(data); }; lg.appendChild(b); }
  const show = ms.filter(m => !(P.off && P.off.has(m)));
  const ml = METRICS[P.v].find(x => x[0] === P.met) || METRICS[P.v][0]; P.met = ml[0];
  const unit = { mae: '°C', bias: '°C', pod: '%', far: '%', csi: '', ratio: '× mesuré' }[P.met]; const dec = { mae: 2, bias: 2, pod: 0, far: 0, csi: 2, ratio: 2 }[P.met];
  $('#pa-title').textContent = ml[1] + ' au fil du temps';
  const win = P.d <= 7 ? 3 : 7;
  const ds = show.map(m => ({ label: LABELS[m], data: rolling(data[m], P.met, win), borderColor: mcolor(m), borderWidth: 1.9, pointRadius: 0, tension: .25, spanGaps: true, borderDash: m === 'Auto' ? [5, 3] : [] }));
  const xs = ds.flatMap(d => d.data.map(p => p.x)); const xmin = Math.min(...xs), xmax = Math.max(...xs);
  const sc = { x: { ...timeAxis(xmin, xmax), ticks: { color: css('--t2'), maxRotation: 0, callback: v => new Date(v).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' }) }, afterBuildTicks: s => { const stp = Math.max(1, Math.round((s.max - s.min) / 86400e3 / 7)) * 86400e3; const off = tzOff(); const t = []; for (let v = Math.ceil((s.min + off) / stp) * stp - off; v <= s.max; v += stp) t.push({ value: v }); s.ticks = t; } }, y: { grid: { color: css('--grid') }, border: { display: false }, title: { display: true, text: unit, color: css('--t2') }, beginAtZero: P.met !== 'bias' } };
  if (ds.length && isFinite(xmin)) {
    mk('c-pa', { type: 'line', data: { datasets: ds }, options: { responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false }, scales: sc, plugins: { legend: { display: false }, tooltip: { ...tipBase(unit, dec), title: i => fmtDT(i[0].parsed.x, { day: 'numeric', month: 'long' }) } } } });
  } else if (charts['c-pa']) { charts['c-pa'].destroy(); delete charts['c-pa']; }
  $('#pa-foot').textContent = `Moyenne glissante sur ${win} jours${P.met === 'ratio' ? ' · idéal : 1' : ''}${P.v === 'rain' ? ' · une valeur par jour glissant : à lire en tendance (peu de jours de pluie)' : ''}.`;

  // Second graphique
  if (P.v === 'temp') {
    $('#pb-title').textContent = 'Biais selon l\'heure de la journée';
    const ds2 = show.map(m => ({ label: LABELS[m], data: data[m].hrs.map((a, h) => ({ x: h, y: a[1] >= 3 ? a[0] / a[1] : null })), borderColor: mcolor(m), borderWidth: 1.9, pointRadius: 0, tension: .3, spanGaps: true, borderDash: m === 'Auto' ? [5, 3] : [] }));
    mk('c-pb', { type: 'line', data: { datasets: ds2 }, options: { responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false }, scales: { x: { type: 'linear', min: 0, max: 23, grid: { color: css('--grid') }, ticks: { stepSize: 3, callback: v => v + ' h', color: css('--t2') } }, y: { grid: { color: css('--grid') }, border: { display: false }, title: { display: true, text: '°C (prévu − mesuré)', color: css('--t2') } } }, plugins: { legend: { display: false }, tooltip: { ...tipBase('°C', 2), title: i => i[0].parsed.x + ' h (heure locale)' } } } });
    $('#pb-foot').textContent = 'Au-dessus de 0 : le modèle voit trop chaud à cette heure. Un biais plus fort la nuit trahit souvent des inversions thermiques que le modèle ne rend pas.';
  } else {
    $('#pb-title').textContent = 'Pluie cumulée : prévu contre mesuré';
    const ds2 = [];
    const base = ms.find(m => data[m].cum.length);
    if (base) { let a = 0; ds2.push({ label: 'Station', data: data[base].cum.sort((x, y) => x[0] - y[0]).map(p => ({ x: p[0] * 1000, y: (a += p[2]) })), borderColor: css('--obs'), borderWidth: 2.8, pointRadius: 0, stepped: 'before' }); }
    for (const m of show) { let a = 0; ds2.push({ label: LABELS[m], data: data[m].cum.sort((x, y) => x[0] - y[0]).map(p => ({ x: p[0] * 1000, y: (a += p[1]) })), borderColor: mcolor(m), borderWidth: 1.7, pointRadius: 0, stepped: 'before', borderDash: m === 'Auto' ? [5, 3] : [] }); }
    const xs2 = ds2.flatMap(d => d.data.map(p => p.x));
    mk('c-pb', { type: 'line', data: { datasets: ds2 }, options: { responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false }, scales: { x: { ...timeAxis(Math.min(...xs2), Math.max(...xs2)), ticks: { color: css('--t2'), maxRotation: 0, callback: v => new Date(v).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' }) }, afterBuildTicks: s => { const stp = Math.max(1, Math.round((s.max - s.min) / 86400e3 / 7)) * 86400e3; const off = tzOff(); const t = []; for (let v = Math.ceil((s.min + off) / stp) * stp - off; v <= s.max; v += stp) t.push({ value: v }); s.ticks = t; } }, y: { beginAtZero: true, grid: { color: css('--grid') }, border: { display: false }, title: { display: true, text: 'mm cumulés', color: css('--t2') } } }, plugins: { legend: { display: false }, tooltip: { ...tipBase('mm', 1), title: i => fmtDT(i[0].parsed.x, { day: 'numeric', month: 'long', hour: '2-digit' }) } } } });
    $('#pb-foot').textContent = 'Cumul des heures comparées uniquement (mêmes heures pour la station et les modèles). Au-dessus du trait épais : le modèle voit trop de pluie.';
  }
}

function renderPerf() {
  if (!S.hasFc || !S.fc.length) { $('#verdict').textContent = 'Aucune prévision archivée pour le moment.'; $('#t-rank').innerHTML = ''; return; }
  const all = S.fc.filter(r => r.ech === P.ech); const lo = all.length ? Math.min(...all.map(r => r.e)) : null, hi = all.length ? Math.max(...all.map(r => r.e)) : null;
  $('#perf-span').textContent = lo ? 'Données du ' + fmtDT(lo * 1000, { day: 'numeric', month: 'short', year: 'numeric' }) + ' au ' + fmtDT(hi * 1000, { day: 'numeric', month: 'short' }) + ' · ' + all.length.toLocaleString('fr-FR') + ' prévisions' : '';
  $('#tol-wrap').hidden = P.v !== 'rain';
  const data = perfData(); if (!data) { $('#verdict').textContent = 'Pas encore de données pour cette échéance.'; $('#t-rank').innerHTML = ''; return; }
  renderRank(data); renderPerfCharts(data);
  // Indicateurs disponibles selon la grandeur
  const seg = $('#p-met'); seg.innerHTML = '';
  for (const [k, lab] of METRICS[P.v]) { const b = el('button', {}, lab); if (k === P.met) b.className = 'on'; b.onclick = () => { P.met = k; renderPerf(); }; seg.appendChild(b); }
}

function setupPerf() {
  const seg = $('#p-ech'); seg.innerHTML = '';
  for (const [k, lab] of ECH) { const b = el('button', { 'data-e': k }, lab); if (k === P.ech) b.className = 'on'; b.onclick = () => { P.ech = k; $$('button', seg).forEach(x => x.className = x.dataset.e === k ? 'on' : ''); renderPerf(); }; seg.appendChild(b); }
  $$('#p-var button').forEach(b => b.onclick = () => { P.v = b.dataset.v; P.met = METRICS[P.v][0][0]; $$('#p-var button').forEach(x => x.className = x === b ? 'on' : ''); renderPerf(); });
  $$('#p-per button').forEach(b => b.onclick = () => { P.d = +b.dataset.d; $$('#p-per button').forEach(x => x.className = x === b ? 'on' : ''); renderPerf(); });
  $('#tol').onchange = e => { P.tol = e.target.checked; renderPerf(); };
}

// ───────────── Mise en route ─────────────
function setupWx() {
  buildSeg('var-seg', Object.entries(VARS).map(([k, v]) => [k, v.label]), 'v', () => { renderMain(); renderLead(); });
  $$('#hz-seg button').forEach(b => b.onclick = () => { W.h = +b.dataset.h; $$('#hz-seg button').forEach(x => x.className = x === b ? 'on' : ''); renderMain(); });
  $$('#mode-seg button').forEach(b => b.onclick = () => { W.mode = b.dataset.m; $$('#mode-seg button').forEach(x => x.className = x === b ? 'on' : ''); renderMain(); });
  buildSeg('hist-seg', [['temp', 'Température'], ['rain', 'Pluie'], ['wind', 'Vent'], ['hum', 'Humidité'], ['press', 'Pression']], 'histVar', renderHist);
}

function tab(which) {
  const wx = which === 'wx'; $('#view-wx').hidden = !wx; $('#view-perf').hidden = wx;
  $('#tab-wx').setAttribute('aria-selected', wx); $('#tab-perf').setAttribute('aria-selected', !wx);
  history.replaceState(null, '', wx ? '#meteo' : '#performance'); (wx ? renderWx : renderPerf)();
}

function theme() {
  const r = document.documentElement; const cur = r.getAttribute('data-theme');
  const next = cur === 'dark' ? 'light' : cur === 'light' ? null : 'dark';
  next ? r.setAttribute('data-theme', next) : r.removeAttribute('data-theme');
  try { next ? localStorage.setItem('theme', next) : localStorage.removeItem('theme'); } catch (e) { /* stockage indisponible */ }
  redraw();
}
const redraw = () => ($('#view-wx').hidden ? renderPerf : renderWx)();

async function boot() {
  try { const t = localStorage.getItem('theme'); if (t) document.documentElement.setAttribute('data-theme', t); } catch (e) { /* ignoré */ }
  setupWx(); setupPerf();
  $('#tab-wx').onclick = () => tab('wx'); $('#tab-perf').onclick = () => tab('perf');
  $('#theme').onclick = theme; $('#reload').onclick = async () => { await load(); redraw(); };
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', redraw);
  try { await load(); } catch (e) { /* affiché par le bandeau */ }
  tab(location.hash === '#performance' ? 'perf' : 'wx');
  setInterval(async () => { if (document.visibilityState === 'visible') { await load(); redraw(); } }, 5 * 60 * 1000);
}
boot();
})();
