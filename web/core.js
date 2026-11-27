/* Noyau commun : données, utilitaires, graphiques, icônes météo. */
(() => {
'use strict';
const EW = window.EW = { views: {} };

EW.MODELS = ['AROME', 'ICON-D2', 'ICON-CH1', 'ICON-EU', 'ARPEGE', 'IFS', 'Auto'];
EW.LABELS = { AROME: 'AROME HD', 'ICON-D2': 'ICON-D2', 'ICON-CH1': 'ICON-CH1', 'ICON-EU': 'ICON-EU', ARPEGE: 'ARPEGE', IFS: 'ECMWF IFS', Auto: 'Auto Open-Meteo' };
EW.HOUR = 3600;
EW.WET = 0.2;

const $ = EW.$ = (s, r = document) => r.querySelector(s);
EW.$$ = (s, r = document) => [...r.querySelectorAll(s)];
EW.css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
EW.mcolor = m => EW.css('--s' + (EW.MODELS.indexOf(m) + 1));
EW.nf = (v, d = 1) => (v == null || Number.isNaN(v)) ? '—' : v.toLocaleString('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d });
EW.sgn = (v, d = 1) => (v == null || Number.isNaN(v)) ? '—' : (v > 0 ? '+' : '') + EW.nf(v, d);
EW.esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
EW.el = (tag, attrs = {}, html = '') => { const e = document.createElement(tag); for (const k in attrs) e.setAttribute(k, attrs[k]); e.innerHTML = html; return e; };
EW.sum = a => a.reduce((x, y) => x + (y || 0), 0);
EW.nums = a => a.filter(x => x != null && !Number.isNaN(x));
EW.mean = a => { const v = EW.nums(a); return v.length ? EW.sum(v) / v.length : null; };
const CARD = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSO', 'SO', 'OSO', 'O', 'ONO', 'NO', 'NNO'];
EW.cardinal = d => d == null ? '' : CARD[Math.round(((d % 360) + 360) % 360 / 22.5) % 16];

// ───────────── Fuseau horaire de la station ─────────────
EW.tz = 'Europe/Paris';
let dayFmt, hourFmt, partsFmt;
EW.setTz = tz => {
  EW.tz = tz || EW.tz;
  dayFmt = new Intl.DateTimeFormat('sv-SE', { timeZone: EW.tz });
  hourFmt = new Intl.DateTimeFormat('en-GB', { timeZone: EW.tz, hour: 'numeric', hourCycle: 'h23' });
  partsFmt = new Intl.DateTimeFormat('en-GB', { timeZone: EW.tz, year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric', hourCycle: 'h23' });
};
EW.setTz();
EW.dayKey = e => dayFmt.format(e * 1000);
EW.hourOf = e => +hourFmt.format(e * 1000);
EW.tzOffsetMs = ms => { const p = {}; for (const x of partsFmt.formatToParts(new Date(ms))) p[x.type] = +x.value; return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000; };
EW.fmtDT = (ms, o) => new Date(ms).toLocaleString('fr-FR', Object.assign({ timeZone: EW.tz }, o));
EW.fmtT = e => EW.fmtDT(e * 1000, { hour: '2-digit', minute: '2-digit' }).replace(':', ' h ').replace(/ h 00$/, ' h');
EW.todayKey = () => EW.dayKey(Date.now() / 1000);
EW.dayLabel = (k, o = { weekday: 'short', day: 'numeric', month: 'short' }) => new Date(k + 'T12:00:00Z').toLocaleDateString('fr-FR', Object.assign({ timeZone: 'UTC' }, o));
EW.midnightOf = k => { const guess = Date.parse(k + 'T00:00:00Z'); return (guess - EW.tzOffsetMs(guess)) / 1000; };

// ───────────── Données ─────────────
const S = EW.S = { station: new Map(), stArr: [], fc: [], latest: null, hasFc: false, errors: [] };
let tick = Math.floor(Date.now() / 60000);
async function getText(u) { const r = await fetch(u + '?v=' + tick, { cache: 'no-store' }); if (!r.ok) throw new Error(u + ' ' + r.status); return r.text(); }
function parseCSV(text) {
  const lines = text.split('\n'); const head = lines[0].trim().split(','); const out = [];
  for (let i = 1; i < lines.length; i++) { const l = lines[i]; if (!l) continue; const p = l.split(','); const o = {}; for (let k = 0; k < head.length; k++) o[head[k]] = p[k]; out.push(o); }
  return out;
}
const n = v => (v === '' || v == null) ? null : +v;

EW.load = async () => {
  tick = Math.floor(Date.now() / 60000);
  const [st, fc, la] = await Promise.allSettled([getText('data/station.csv'), getText('data/forecasts.csv'), getText('data/latest.json')]);
  if (st.status === 'fulfilled') {
    S.station = new Map(); S.stArr = [];
    for (const r of parseCSV(st.value)) {
      const o = { e: +r.epoch, temp: n(r.temp), feels: n(r.feels), hum: n(r.hum), dew: n(r.dew), press: n(r.press), wind: n(r.wind), gust: n(r.gust), dir: n(r.wind_dir), rain: n(r.rain_h), rainDay: n(r.rain_day), uv: n(r.uv), solar: n(r.solar), tin: n(r.temp_in), hin: n(r.hum_in), tmin: n(r.temp_min), tmax: n(r.temp_max) };
      S.station.set(o.e, o); S.stArr.push(o);
    }
    S.stArr.sort((a, b) => a.e - b.e);
  }
  if (fc.status === 'fulfilled') {
    S.fc = parseCSV(fc.value).map(r => ({ e: +r.epoch, run: r.run, lead: +r.lead_h, ech: r.ech, m: r.model, t: n(r.temp), r: n(r.rain) }));
    S.hasFc = true;
  }
  S.latest = la.status === 'fulfilled' ? JSON.parse(la.value) : null;
  if (S.latest && S.latest.tz) EW.setTz(S.latest.tz);
  buildDaily();
};

// Agrégats journaliers de la station (jour local de la station).
EW.daily = new Map();
function buildDaily() {
  const D = EW.daily = new Map();
  for (const r of S.stArr) {
    const k = EW.dayKey(r.e - 1800);
    let d = D.get(k); if (!d) { d = { k, n: 0, t: [], tmin: null, tmax: null, rain: 0, rainN: 0, gust: null, wind: [], press: [], hum: [], uv: null, solar: [], feels: [], dew: [] }; D.set(k, d); }
    d.n++;
    if (r.temp != null) d.t.push(r.temp);
    const lo = r.tmin ?? r.temp, hi = r.tmax ?? r.temp;
    if (lo != null) d.tmin = d.tmin == null ? lo : Math.min(d.tmin, lo);
    if (hi != null) d.tmax = d.tmax == null ? hi : Math.max(d.tmax, hi);
    if (r.rain != null) { d.rain += r.rain; d.rainN++; }
    if (r.gust != null) d.gust = d.gust == null ? r.gust : Math.max(d.gust, r.gust);
    if (r.wind != null) d.wind.push(r.wind);
    if (r.press != null) d.press.push(r.press);
    if (r.hum != null) d.hum.push(r.hum);
    if (r.uv != null) d.uv = d.uv == null ? r.uv : Math.max(d.uv, r.uv);
    if (r.solar != null) d.solar.push(r.solar);
    if (r.feels != null) d.feels.push(r.feels);
    if (r.dew != null) d.dew.push(r.dew);
  }
  for (const d of D.values()) {
    d.tmean = EW.mean(d.t); d.windMean = EW.mean(d.wind); d.pressMean = EW.mean(d.press); d.humMean = EW.mean(d.hum); d.solarMean = EW.mean(d.solar);
    d.complete = d.n >= 20; d.rain = d.rainN ? d.rain : null;
  }
}

// Prévisions « veille » (J+1) agrégées par jour et par modèle : mini, maxi, pluie.
EW.fcDay = (ech = 'j1') => {
  const out = new Map();
  for (const r of S.fc) {
    if (r.ech !== ech) continue;
    const k = EW.dayKey(r.e - 1800); let by = out.get(k); if (!by) { by = {}; out.set(k, by); }
    let a = by[r.m]; if (!a) a = by[r.m] = { n: 0, tmin: null, tmax: null, rain: 0, rn: 0 };
    a.n++;
    if (r.t != null) { a.tmin = a.tmin == null ? r.t : Math.min(a.tmin, r.t); a.tmax = a.tmax == null ? r.t : Math.max(a.tmax, r.t); }
    if (r.r != null) { a.rain += r.r; a.rn++; }
  }
  return out;
};

// ───────────── Thème & graphiques ─────────────
const charts = EW.charts = {};
EW.applyDefaults = () => {
  Chart.defaults.font.family = '"DM Sans", system-ui, sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.color = EW.css('--t2');
  Chart.defaults.borderColor = EW.css('--grid');
};
EW.mk = (id, cfg) => {
  const cv = document.getElementById(id); if (!cv) return null;
  if (charts[id]) charts[id].destroy();
  EW.applyDefaults();
  charts[id] = new Chart(cv, cfg);
  return charts[id];
};
EW.kill = id => { if (charts[id]) { charts[id].destroy(); delete charts[id]; } };

// Axe temporel : repères réguliers en heure de la station.
EW.timeAxis = (min, max, opt = {}) => ({
  type: 'linear', min, max,
  grid: { color: EW.css('--grid'), drawTicks: false },
  border: { display: false },
  ticks: {
    color: EW.css('--t2'), maxRotation: 0, autoSkip: false, padding: 6,
    callback: v => {
      const span = max - min;
      if (span > 4 * 86400e3) return EW.fmtDT(v, { day: 'numeric', month: 'short' });
      if (span > 30 * 3600e3) return EW.fmtDT(v, { weekday: 'short', hour: '2-digit' }).replace(':00', ' h');
      return EW.fmtDT(v, { hour: '2-digit' }).replace(':00', ' h');
    },
  },
  afterBuildTicks: sc => {
    const span = sc.max - sc.min; const narrow = sc.width < 520 ? 2 : 1;
    let step;
    if (opt.step) step = opt.step;
    else if (span > 100 * 86400e3) step = 30 * 86400e3 * narrow;
    else if (span > 40 * 86400e3) step = 14 * 86400e3 * narrow;
    else if (span > 12 * 86400e3) step = 7 * 86400e3 * (narrow > 1 ? 1 : 1);
    else if (span > 4 * 86400e3) step = 86400e3 * narrow;
    else step = (span > 2 * 86400e3 ? 12 : span > 30 * 3600e3 ? 6 : span > 14 * 3600e3 ? 3 : 2) * 3600e3 * narrow;
    const t = [];
    if (step >= 86400e3) {
      const days = Math.round(step / 86400e3); const next = v => EW.midnightOf(EW.dayKey((v + days * 86400e3 + 12 * 3600e3) / 1000)) * 1000;
      let v = EW.midnightOf(EW.dayKey(sc.min / 1000)) * 1000; if (v < sc.min) v = EW.midnightOf(EW.dayKey((v + 36 * 3600e3) / 1000)) * 1000;
      for (; v <= sc.max; v = next(v)) t.push({ value: v });
    } else { const off = EW.tzOffsetMs(sc.min); for (let v = Math.ceil((sc.min + off) / step) * step - off; v <= sc.max; v += step) t.push({ value: v }); }
    sc.ticks = t;
  },
});
EW.nowPlugin = { id: 'nowLine', beforeDatasetsDraw(c, _a, o) {
  if (!o || o.x == null) return; const { ctx, chartArea: a, scales: { x } } = c; if (o.x < x.min || o.x > x.max) return;
  if (o.shade) { const px = x.getPixelForValue(o.x); ctx.save(); ctx.fillStyle = EW.css('--t2'); ctx.globalAlpha = .06; ctx.fillRect(px, a.top, a.right - px, a.bottom - a.top); ctx.restore(); }
}, afterDatasetsDraw(c, _a, o) {
  if (!o || o.x == null) return; const { ctx, chartArea: a, scales: { x } } = c; if (o.x < x.min || o.x > x.max) return;
  const px = x.getPixelForValue(o.x); ctx.save(); ctx.strokeStyle = EW.css('--t2'); ctx.globalAlpha = .6; ctx.setLineDash([3, 4]); ctx.beginPath(); ctx.moveTo(px, a.top); ctx.lineTo(px, a.bottom); ctx.stroke();
  ctx.setLineDash([]); ctx.fillStyle = EW.css('--t2'); ctx.font = '600 11px "DM Sans"'; ctx.textAlign = 'center'; ctx.fillText('maintenant', Math.min(Math.max(px, a.left + 30), a.right - 30), a.top + 10); ctx.restore();
} };
EW.tip = (unit, d = 1, titleFn) => ({
  mode: 'x', intersect: false, itemSort: (a, b) => b.parsed.y - a.parsed.y,
  backgroundColor: EW.css('--surface'), titleColor: EW.css('--tx'), bodyColor: EW.css('--tx'), borderColor: EW.css('--line'), borderWidth: 1, padding: 10, boxPadding: 4, usePointStyle: true,
  callbacks: {
    title: titleFn || (i => EW.fmtDT(i[0].parsed.x, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })),
    label: c => ' ' + c.dataset.label + ' : ' + EW.nf(c.parsed.y, d) + (unit ? ' ' + unit : ''),
  },
});
EW.yAxis = (title, o = {}) => Object.assign({ grid: { color: EW.css('--grid') }, border: { display: false }, title: { display: !!title, text: title, color: EW.css('--t2') } }, o);
EW.baseOpts = (o = {}) => Object.assign({ responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'x', intersect: false } }, o);

// Légende cliquable (une pastille par modèle ou série)
EW.legend = (box, items, off, onChange) => {
  box.innerHTML = '';
  for (const it of items) {
    const b = EW.el('button', { style: '--c:' + it.color, class: (it.fixed ? 'fixed ' : '') + (off && off.has(it.id) ? 'off' : ''), title: it.fixed ? '' : 'Afficher / masquer' }, '<i style="' + (it.dash ? 'background:repeating-linear-gradient(90deg,var(--c) 0 5px,transparent 5px 8px)' : '') + '"></i>' + it.label);
    if (!it.fixed) b.onclick = () => { off.has(it.id) ? off.delete(it.id) : off.add(it.id); onChange(); };
    box.appendChild(b);
  }
};
EW.seg = (box, items, cur, cb) => {
  box.innerHTML = '';
  for (const [k, lab] of items) { const b = EW.el('button', { 'data-k': k }, lab); if (String(k) === String(cur)) b.className = 'on'; b.onclick = () => { EW.$$('button', box).forEach(x => x.className = x === b ? 'on' : ''); cb(k); }; box.appendChild(b); }
};
EW.card = (cls, title, sub, body, extra = '') => `<div class="card ${cls || ''}"><div class="card-head"><h2>${title}</h2>${sub ? '<span class="sub">' + sub + '</span>' : ''}${extra}</div>${body}</div>`;

// ───────────── Météo : libellés, icônes, lune ─────────────
const WMO = { 0: 'Ciel dégagé', 1: 'Plutôt dégagé', 2: 'Partiellement nuageux', 3: 'Couvert', 45: 'Brouillard', 48: 'Brouillard givrant', 51: 'Bruine légère', 53: 'Bruine', 55: 'Bruine forte', 56: 'Bruine verglaçante', 57: 'Bruine verglaçante', 61: 'Pluie faible', 63: 'Pluie', 65: 'Pluie forte', 66: 'Pluie verglaçante', 67: 'Pluie verglaçante', 71: 'Neige faible', 73: 'Neige', 75: 'Neige forte', 77: 'Grains de neige', 80: 'Averses faibles', 81: 'Averses', 82: 'Averses violentes', 85: 'Averses de neige', 86: 'Averses de neige', 95: 'Orage', 96: 'Orage et grêle', 99: 'Orage et forte grêle' };
EW.wmo = c => WMO[c] || '—';
const SUN = (x, y, r, rays = true) => `<g><circle cx="${x}" cy="${y}" r="${r}" fill="#ffc83d"/>${rays ? [0, 45, 90, 135, 180, 225, 270, 315].map(a => `<line x1="${x}" y1="${y - r - 3}" x2="${x}" y2="${y - r - 6}" stroke="#ffc83d" stroke-width="2" stroke-linecap="round" transform="rotate(${a} ${x} ${y})"/>`).join('') : ''}</g>`;
const MOON = (x, y, r) => `<path d="M${x + r * .35} ${y - r} A${r} ${r} 0 1 0 ${x + r} ${y + r * .3} A${r * .8} ${r * .8} 0 0 1 ${x + r * .35} ${y - r}z" fill="#dfe7f5"/>`;
const CLOUD = (x, y, s, fill) => `<path transform="translate(${x} ${y}) scale(${s})" d="M10 26h24a8 8 0 0 0 0-16 11 11 0 0 0-20.4-2.6A9.4 9.4 0 0 0 10 26z" fill="${fill}"/>`;
EW.icon = (code, day = true, size = 40) => {
  const dark = '#8a97ab', mid = '#b6c1d1', white = '#e6ecf5'; let g = '';
  const rain = n => Array.from({ length: n }, (_, i) => `<line x1="${16 + i * 8}" y1="36" x2="${13 + i * 8}" y2="44" stroke="#4aa3ff" stroke-width="2.6" stroke-linecap="round"/>`).join('');
  const snow = n => Array.from({ length: n }, (_, i) => `<circle cx="${15 + i * 8}" cy="${39 + (i % 2) * 4}" r="2.2" fill="#fff"/>`).join('');
  if (code === 0 || code === 1) g = day ? SUN(24, 24, 9) : MOON(22, 24, 11);
  else if (code === 2) g = (day ? SUN(31, 17, 7) : MOON(30, 17, 8)) + CLOUD(2, 8, 1.1, white);
  else if (code === 3) g = CLOUD(-2, 6, 1.25, mid) + CLOUD(4, 10, 1.1, white);
  else if (code === 45 || code === 48) g = CLOUD(2, 2, 1.1, mid) + [34, 40, 46].map((y, i) => `<line x1="${10 + i * 2}" y1="${y}" x2="${38 - i * 2}" y2="${y}" stroke="${mid}" stroke-width="2.6" stroke-linecap="round"/>`).join('');
  else if ((code >= 51 && code <= 57) || (code >= 61 && code <= 67) || (code >= 80 && code <= 82)) g = CLOUD(2, 2, 1.1, code >= 63 || code >= 81 ? dark : mid) + rain(code >= 63 && code !== 80 ? 4 : 3);
  else if ((code >= 71 && code <= 77) || code === 85 || code === 86) g = CLOUD(2, 2, 1.1, mid) + snow(4);
  else if (code >= 95) g = CLOUD(2, 0, 1.1, dark) + '<path d="M25 28l-6 10h6l-3 9 9-12h-6l4-7z" fill="#ffc83d"/>';
  else g = CLOUD(2, 8, 1.1, mid);
  return `<svg class="wx-ico" viewBox="0 0 48 48" width="${size}" height="${size}" aria-hidden="true">${g}</svg>`;
};
// Phase de lune (cycle synodique)
EW.moon = (ms = Date.now()) => {
  const syn = 29.530588853, ref = Date.UTC(2000, 0, 6, 18, 14); const age = (((ms - ref) / 86400e3) % syn + syn) % syn;
  const ill = (1 - Math.cos(2 * Math.PI * age / syn)) / 2; const names = ['Nouvelle lune', 'Premier croissant', 'Premier quartier', 'Lune gibbeuse croissante', 'Pleine lune', 'Lune gibbeuse décroissante', 'Dernier quartier', 'Dernier croissant'];
  return { age, ill, name: names[Math.floor((age / syn * 8 + .5) % 8)], phase: age / syn };
};
EW.moonSvg = (phase, size = 44) => {
  const r = 18, c = 24; const k = Math.cos(2 * Math.PI * phase); const waxing = phase < .5;
  const rx = Math.abs(k) * r; const lit = '#e8edf7', dark = '#2a3140';
  const path = waxing ? `M${c} ${c - r} A${r} ${r} 0 0 1 ${c} ${c + r} A${rx} ${r} 0 0 ${k > 0 ? 0 : 1} ${c} ${c - r}z` : `M${c} ${c - r} A${r} ${r} 0 0 0 ${c} ${c + r} A${rx} ${r} 0 0 ${k > 0 ? 1 : 0} ${c} ${c - r}z`;
  return `<svg viewBox="0 0 48 48" width="${size}" height="${size}" aria-hidden="true"><circle cx="${c}" cy="${c}" r="${r}" fill="${dark}"/><path d="${path}" fill="${lit}"/><circle cx="${c}" cy="${c}" r="${r}" fill="none" stroke="#5d6a82" stroke-width="1"/></svg>`;
};
EW.arrow = (deg, size = 18) => deg == null ? '' : `<svg class="arrow" viewBox="0 0 24 24" width="${size}" height="${size}" style="transform:rotate(${(deg + 180) % 360}deg)" aria-hidden="true"><path d="M12 3v16M12 3l-5 6M12 3l5 6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
EW.spark = (cv, vals, color) => {
  if (!cv) return; const dpr = window.devicePixelRatio || 1; const w = cv.clientWidth, h = cv.clientHeight; if (!w) return; cv.width = w * dpr; cv.height = h * dpr;
  const c = cv.getContext('2d'); c.scale(dpr, dpr); const v = vals.filter(x => x != null); if (v.length < 2) return;
  const lo = Math.min(...v), hi = Math.max(...v), sp = hi - lo || 1; let k = 0;
  c.beginPath(); vals.forEach((x, i) => { if (x == null) return; const px = i / (vals.length - 1) * (w - 2) + 1, py = h - 3 - (x - lo) / sp * (h - 6); k++ ? c.lineTo(px, py) : c.moveTo(px, py); });
  c.lineTo(w - 1, h); c.lineTo(1, h); c.closePath(); const g = c.createLinearGradient(0, 0, 0, h); g.addColorStop(0, color + '55'); g.addColorStop(1, color + '00'); c.fillStyle = g; c.fill();
  c.strokeStyle = color; c.lineWidth = 1.8; c.lineJoin = 'round'; c.beginPath(); k = 0; vals.forEach((x, i) => { if (x == null) return; const px = i / (vals.length - 1) * (w - 2) + 1, py = h - 3 - (x - lo) / sp * (h - 6); k++ ? c.lineTo(px, py) : c.moveTo(px, py); }); c.stroke();
};
EW.downloadCSV = (name, head, rows) => {
  const esc = v => v == null ? '' : /[",\n;]/.test(String(v)) ? '"' + String(v).replace(/"/g, '""') + '"' : v;
  const txt = [head.join(';'), ...rows.map(r => r.map(esc).join(';'))].join('\n');
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['﻿' + txt], { type: 'text/csv;charset=utf-8' })); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
};
})();
