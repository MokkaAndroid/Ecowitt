/* Onglet « Aujourd'hui » : conditions actuelles, journée en cours, 48 h, 7 jours, soleil, air, modèles. */
(() => {
'use strict';
const { $, $$, el, nf, sgn, css, S, HOUR, MODELS, LABELS } = Object.assign({}, window.EW, { S: window.EW.S });
const EW = window.EW;

const T = { v: 'temp', off: new Set(MODELS.filter(m => m !== 'Auto')), mv: 'temp', mh: 48, mmode: 'hour', moff: new Set() };

// ───────────── Accès aux données ─────────────
const L = () => S.latest || {};
const OM = () => L().om || null;
const hourlyOM = key => { const h = OM() && OM().hourly; return h && h[key] ? h.time.map((t, i) => ({ x: t * 1000, y: h[key][i] })) : []; };
const dailyOM = () => {
  const d = OM() && OM().daily; if (!d || !d.time) return [];
  const k0 = EW.todayKey(); const i0 = d.time.findIndex(t => EW.dayKey(t) >= k0);
  const keys = Object.keys(d).filter(k => k !== 'time');
  const out = []; for (let i = Math.max(i0, 0); i < d.time.length; i++) { const o = { time: d.time[i] }; keys.forEach(k => o[k] = d[k][i]); out.push(o); }
  return out;
};
const nowIdx = (times) => { const t = Date.now() / 1000; let b = 0; for (let i = 0; i < times.length; i++) if (times[i] <= t) b = i; return b; };
const omNow = key => { const h = OM() && OM().hourly; if (!h || !h[key]) return null; return h[key][nowIdx(h.time)]; };
const fmtHM = sec => sec == null ? '—' : EW.fmtDT(sec * 1000, { hour: '2-digit', minute: '2-digit' });
const dur = sec => sec == null ? '—' : Math.floor(sec / 3600) + ' h ' + String(Math.round(sec % 3600 / 60)).padStart(2, '0');

// ───────────── Héros ─────────────
function hero() {
  const st = L().station || {}, cur = (OM() && OM().current) || {}, td = L().today;
  const code = cur.weather_code, day = cur.is_day !== 0;
  const temp = st.temp ?? cur.temperature_2m;
  const obs = td && EW.nums(td.temp); const omd = dailyOM()[0] || {};
  const lo = obs && obs.length ? Math.min(...obs) : null, hi = obs && obs.length ? Math.max(...obs) : null;
  const sky = code == null ? 'none' : [45, 48].includes(code) ? 'fog' : (code >= 51 && code <= 67) || (code >= 80 && code <= 82) ? 'rain' : (code >= 71 && code <= 77) || code === 85 || code === 86 ? 'snow' : code >= 95 ? 'storm' : code >= 2 ? 'cloud' : (day ? 'sun' : 'night');
  const dTemp = (st.temp != null && cur.temperature_2m != null) ? st.temp - cur.temperature_2m : null;
  const press = (() => { if (!td || st.press == null) return null; const t = td.t, p = td.press; for (let i = t.length - 1; i >= 0; i--) if (t[i] <= Date.now() / 1000 - 3 * 3600 && p[i] != null) return st.press - p[i]; return null; })();
  const fact = (lab, val, unit, det) => `<div class="fact"><span class="lab">${lab}</span><span class="v">${val}<small>${unit}</small></span><span class="d">${det || ''}</span></div>`;
  return `<div class="card hero s7" data-sky="${sky}">
    <div class="hero-top">
      <div class="hero-ico">${EW.icon(code ?? 3, day, 84)}</div>
      <div class="hero-txt">
        <span class="eyebrow">${code == null ? 'Station' : EW.wmo(code)}${omd.precipitation_probability_max != null ? ' · pluie ' + nf(omd.precipitation_probability_max, 0) + ' %' : ''}</span>
        <div class="bigT">${nf(temp)}<small>°C</small></div>
        <span class="feel">Ressenti <b>${nf(st.feels ?? cur.apparent_temperature)}°</b>${dTemp != null ? ' · écart modèle <b>' + sgn(dTemp) + '°</b>' : ''}</span>
      </div>
      <div class="hero-mm">
        <div><span class="lab">Aujourd'hui</span><span class="mm"><i class="dn">▼</i> ${nf(lo ?? omd.temperature_2m_min, 0)}°  <i class="up">▲</i> ${nf(hi ?? omd.temperature_2m_max, 0)}°</span></div>
        <div><span class="lab">Prévu</span><span class="mm dim"><i class="dn">▼</i> ${nf(omd.temperature_2m_min, 0)}°  <i class="up">▲</i> ${nf(omd.temperature_2m_max, 0)}°</span></div>
      </div>
    </div>
    <div class="facts">
      ${fact('Vent', nf(st.wind ?? cur.wind_speed_10m, 0), 'km/h', EW.arrow(st.wind_dir ?? cur.wind_direction_10m, 14) + ' ' + EW.cardinal(st.wind_dir ?? cur.wind_direction_10m) + ' · rafale ' + nf(st.gust ?? cur.wind_gusts_10m, 0))}
      ${fact('Humidité', nf(st.hum ?? cur.relative_humidity_2m, 0), '%', 'rosée ' + nf(st.dew, 1) + '°')}
      ${fact('Pression', nf(st.press ?? cur.pressure_msl, 0), 'hPa', press == null ? 'relative' : sgn(press, 1) + ' en 3 h')}
      ${fact('Pluie du jour', nf(st.rain_day, 1), 'mm', 'débit ' + nf(st.rain_rate, 1) + ' mm/h')}
    </div>
  </div>`;
}

// ───────────── Soleil & ciel ─────────────
function sunCard() {
  const d = dailyOM()[0]; const h = OM() && OM().hourly; const i = h ? nowIdx(h.time) : 0;
  if (!d) return EW.card('s5', 'Soleil et ciel', '', '<p class="foot">Données Open-Meteo indisponibles.</p>');
  const now = Date.now() / 1000, rise = d.sunrise, set = d.sunset;
  const f = rise && set ? Math.min(1, Math.max(0, (now - rise) / (set - rise))) : null;
  const tt = f ?? 0, bx = (1 - tt) * (1 - tt) * 30 + 2 * (1 - tt) * tt * 150 + tt * tt * 270, by = (1 - tt) * (1 - tt) * 100 + 2 * (1 - tt) * tt * -38 + tt * tt * 100;
  const up = f != null && now >= rise && now <= set;
  const arcSvg = `<svg class="sunarc" viewBox="0 0 300 120" role="img" aria-label="Course du soleil"><line x1="14" y1="100" x2="286" y2="100" stroke="var(--line)"/><path d="M30 100 Q150 -38 270 100" fill="none" stroke="var(--line)" stroke-width="2" stroke-dasharray="4 5"/>${up ? `<path d="M30 100 Q150 -38 270 100" fill="none" stroke="#ffc83d" stroke-width="3" stroke-linecap="round" pathLength="1" stroke-dasharray="${tt} 2"/><circle cx="${bx}" cy="${by}" r="9" fill="#ffc83d" stroke="var(--surface)" stroke-width="3"/>` : ''}</svg>`;
  const mo = EW.moon();
  const lay = (lab, v) => `<div class="lay"><span>${lab}</span><div class="bar"><i style="width:${v ?? 0}%"></i></div><b>${nf(v, 0)} %</b></div>`;
  return EW.card('s5', 'Soleil et ciel', EW.fmtDT(Date.now(), { weekday: 'long', day: 'numeric', month: 'long' }), `
    ${arcSvg}
    <div class="sunrow"><div><span class="lab">Lever</span><b>${fmtHM(rise)}</b></div><div class="ctr"><span class="lab">Jour</span><b>${dur(d.daylight_duration)}</b></div><div class="rt"><span class="lab">Coucher</span><b>${fmtHM(set)}</b></div></div>
    <div class="mini-grid">
      <div><span class="lab">Ensoleillement prévu</span><b>${dur(d.sunshine_duration)}</b></div>
      <div><span class="lab">UV max prévu</span><b>${nf(d.uv_index_max, 1)}</b></div>
      <div><span class="lab">Isotherme 0 °C</span><b>${h && h.freezing_level_height ? nf(h.freezing_level_height[i], 0) + ' m' : '—'}</b></div>
      <div><span class="lab">Visibilité</span><b>${h && h.visibility ? nf(h.visibility[i] / 1000, 0) + ' km' : '—'}</b></div>
    </div>
    <div class="lays">${lay('Nuages bas', h && h.cloud_cover_low ? h.cloud_cover_low[i] : null)}${lay('Nuages moyens', h && h.cloud_cover_mid ? h.cloud_cover_mid[i] : null)}${lay('Nuages hauts', h && h.cloud_cover_high ? h.cloud_cover_high[i] : null)}</div>
    <div class="moonrow">${EW.moonSvg(mo.phase, 38)}<div><b>${mo.name}</b><span class="sub">${nf(mo.ill * 100, 0)} % éclairée · âge ${nf(mo.age, 0)} j</span></div></div>`);
}

// ───────────── Tuiles de mesures ─────────────
let tiles = [];
function kpiTiles() {
  const st = L().station || {}, td = L().today, h = OM() && OM().hourly;
  const dayRange = key => { if (!h || !h[key]) return []; const m0 = td ? td.midnight : EW.midnightOf(EW.todayKey()); return h.time.map((t, i) => t >= m0 && t < m0 + 86400 ? h[key][i] : null).filter(x => x != null); };
  const tdv = k => td && td[k] ? td[k] : [];
  const mx = a => { const v = EW.nums(a); return v.length ? Math.max(...v) : null; };
  const all = [
    ['Point de rosée', nf(st.dew), '°C', 'Humidité ' + nf(st.hum, 0) + ' %', tdv('dew'), '--s3'],
    ['Rafale max', nf(mx(tdv('gust')), 0), 'km/h', 'Vent moyen max ' + nf(mx(tdv('wind')), 0), tdv('gust'), '--s2'],
    ['Intensité de pluie', nf(st.rain_rate, 1), 'mm/h', 'Cumul du jour ' + nf(st.rain_day, 1) + ' mm', tdv('rain_day'), '--s1'],
    ['UV', nf(st.uv, 1), '', 'max du jour ' + nf(mx(tdv('uv')), 1) + (OM() ? ' · prévu ' + nf((dailyOM()[0] || {}).uv_index_max, 1) : ''), tdv('uv'), '--s4'],
    ['Rayonnement solaire', nf(st.solar, 0), 'W/m²', 'max du jour ' + nf(mx(tdv('solar')), 0), tdv('solar'), '--s4'],
    ['Pression absolue', nf(st.press_abs, 0), 'hPa', 'Niveau mer ' + nf(st.press, 0) + ' hPa', tdv('press'), '--s7'],
    ['Intérieur', nf(st.temp_in), '°C', 'Humidité ' + nf(st.hum_in, 0) + ' %', [], '--s5'],
    ['Nébulosité', nf(omNow('cloud_cover'), 0), '%', 'bas ' + nf(omNow('cloud_cover_low'), 0) + ' · moy. ' + nf(omNow('cloud_cover_mid'), 0) + ' · haut ' + nf(omNow('cloud_cover_high'), 0), dayRange('cloud_cover'), '--s7'],
    ['Probabilité de pluie', nf(omNow('precipitation_probability'), 0), '%', 'sur la journée : max ' + nf(mx(dayRange('precipitation_probability')), 0) + ' %', dayRange('precipitation_probability'), '--s1'],
    ['CAPE (orage)', nf(omNow('cape'), 0), 'J/kg', mx(dayRange('cape')) > 500 ? 'risque d\'orage possible' : 'instabilité faible', dayRange('cape'), '--s6'],
  ].filter(t => t[1] !== '—');
  tiles = all;
  return `<div class="kpis s12">${all.map((t, i) => `<div class="kpi"><div class="lab"><span>${t[0]}</span></div><div class="val">${t[1]}<small>${t[2]}</small></div><div class="det">${t[3]}</div><canvas data-i="${i}"></canvas></div>`).join('')}</div>`;
}
function drawSparks() { const box = $('#view-today .kpis'); if (!box) return; tiles.forEach((t, i) => EW.spark($('canvas[data-i="' + i + '"]', box), t[4], css(t[5]))); }

// ───────────── Journée en cours ─────────────
const DV = {
  temp: { label: 'Température', unit: '°C', st: 'temp', om: 'temperature_2m', d: 1, fc: 'temp' },
  feels: { label: 'Ressenti', unit: '°C', st: 'feels', om: 'apparent_temperature', d: 1 },
  hum: { label: 'Humidité', unit: '%', st: 'hum', om: 'relative_humidity_2m', d: 0, fc: 'hum' },
  press: { label: 'Pression', unit: 'hPa', st: 'press', om: 'pressure_msl', d: 1 },
  wind: { label: 'Vent', unit: 'km/h', st: 'wind', st2: 'gust', om: 'wind_speed_10m', om2: 'wind_gusts_10m', d: 0, fc: 'wind', zero: true },
  rain: { label: 'Pluie cumulée', unit: 'mm', st: 'rain_day', om: 'precipitation', d: 1, fc: 'rain', cum: true, zero: true },
  solar: { label: 'Solaire', unit: 'W/m²', st: 'solar', om: 'shortwave_radiation', d: 0, zero: true },
};
function dayChart() {
  const V = DV[T.v]; const td = L().today; const m0 = (td ? td.midnight : EW.midnightOf(EW.todayKey())) * 1000; const m1 = m0 + 86400e3;
  const ds = []; const now = Date.now();
  const cum = pts => { if (!V.cum) return pts; let a = 0; return [{ x: m0, y: 0 }, ...pts.filter(p => p.x > m0).map(p => ({ x: p.x, y: p.y == null ? null : (a += p.y) }))]; };
  const stPts = k => td && td[k] ? td.t.map((t, i) => ({ x: t * 1000, y: td[k][i] })).filter(p => p.y != null) : [];
  const legend = [];
  const sPts = stPts(V.st);
  if (sPts.length) { legend.push({ id: 'st', label: 'Station', color: css('--obs'), fixed: true }); ds.push({ label: 'Station', data: sPts, borderColor: css('--obs'), borderWidth: 2.8, pointRadius: 0, tension: V.cum ? 0 : .25, stepped: V.cum ? 'before' : false, order: 0 }); }
  if (V.st2) { const g = stPts(V.st2); if (g.length) { legend.push({ id: 'st2', label: 'Rafales (station)', color: css('--s4'), fixed: true }); ds.push({ label: 'Rafales station', data: g, borderColor: css('--s4'), borderWidth: 1.3, pointRadius: 0, tension: .2, order: 1 }); } }
  const omP = hourlyOM(V.om).filter(p => p.x >= m0 && p.x <= m1);
  if (omP.length) {
    legend.push({ id: 'Auto', label: LABELS.Auto, color: EW.mcolor('Auto'), dash: true });
    if (!T.off.has('Auto')) ds.push({ label: LABELS.Auto, data: cum(omP), borderColor: EW.mcolor('Auto'), borderWidth: 1.9, borderDash: [5, 3], pointRadius: 0, tension: V.cum ? 0 : .25, stepped: V.cum ? 'before' : false, order: 2 });
    if (V.om2 && !T.off.has('Auto')) ds.push({ label: 'Rafales ' + LABELS.Auto, data: hourlyOM(V.om2).filter(p => p.x >= m0 && p.x <= m1), borderColor: EW.mcolor('Auto'), borderWidth: 1, borderDash: [2, 3], pointRadius: 0, tension: .2, order: 3 });
  }
  const f = L().forecast;
  if (f && V.fc) for (const m of MODELS) {
    if (m === 'Auto' || !f.series[m] || !f.series[m][V.fc]) continue;
    legend.push({ id: m, label: LABELS[m], color: EW.mcolor(m) });
    if (T.off.has(m)) continue;
    const pts = []; f.times.forEach((t, i) => { const x = t * 1000; if (x >= m0 && x <= m1) pts.push({ x, y: f.series[m][V.fc][i] }); });
    ds.push({ label: LABELS[m], data: cum(pts), borderColor: EW.mcolor(m), borderWidth: 1.5, pointRadius: 0, tension: V.cum ? 0 : .25, stepped: V.cum ? 'before' : false, order: 4 });
  }
  EW.legend($('#d-legend'), legend, T.off, dayChart);
  if (!ds.length) { EW.kill('c-day'); $('#d-foot').textContent = 'Pas de données pour cette grandeur.'; return; }
  EW.mk('c-day', { type: 'line', data: { datasets: ds }, options: EW.baseOpts({ scales: { x: EW.timeAxis(m0, m1, { step: 3 * HOUR * 1000 }), y: EW.yAxis(V.unit, { beginAtZero: !!V.zero }) }, plugins: { legend: { display: false }, tooltip: EW.tip(V.unit, V.d), nowLine: { x: now, shade: true } } }), plugins: [EW.nowPlugin] });
  $('#d-foot').textContent = 'Trait épais : mesures de la station (pas de 5 min). Pointillés : prévision.' + (V.cum ? ' Cumul depuis minuit.' : '');
}

// ───────────── 48 h ─────────────
function next48() {
  const h = OM() && OM().hourly; if (!h) return EW.card('s12', 'Prochaines 48 heures', '', '<p class="foot">Données Open-Meteo indisponibles.</p>');
  const i0 = nowIdx(h.time); const items = []; const day = k => EW.dayKey(h.time[k]);
  for (let i = i0; i < Math.min(h.time.length, i0 + 49); i++) {
    const hr = EW.hourOf(h.time[i]); const isDay = h.is_day ? h.is_day[i] !== 0 : (hr >= 7 && hr <= 18);
    const newDay = i > i0 && day(i) !== day(i - 1);
    items.push(`${newDay ? `<div class="hday">${EW.dayLabel(day(i), { weekday: 'short', day: 'numeric' })}</div>` : ''}<div class="hr${i === i0 ? ' now' : ''}"><span class="t">${i === i0 ? 'Maint.' : hr + ' h'}</span>${EW.icon(h.weather_code ? h.weather_code[i] : 3, isDay, 30)}<b>${nf(h.temperature_2m[i], 0)}°</b><span class="p">${h.precipitation_probability && h.precipitation_probability[i] > 0 ? nf(h.precipitation_probability[i], 0) + ' %' : '&nbsp;'}</span><span class="r">${h.precipitation[i] > 0 ? nf(h.precipitation[i], 1) + ' mm' : '&nbsp;'}</span><span class="w">${nf(h.wind_speed_10m[i], 0)}</span></div>`);
  }
  return EW.card('s12', 'Prochaines 48 heures', 'Open-Meteo · modèle Auto', `
    <div class="hours-wrap"><div class="hours-legend"><span>Heure</span><span></span><span>Temp.</span><span>Prob.</span><span>Pluie</span><span>Vent km/h</span></div><div class="hours">${items.join('')}</div></div>
    <div class="g3"><div><h3>Température et ressenti</h3><div class="chart sm"><canvas id="c-48t"></canvas></div></div><div><h3>Précipitations</h3><div class="chart sm"><canvas id="c-48r"></canvas></div></div><div><h3>Vent et rafales</h3><div class="chart sm"><canvas id="c-48w"></canvas></div></div></div>`);
}
function draw48() {
  const h = OM() && OM().hourly; if (!h) return; const now = Date.now(); const a = Math.floor(now / 3600e3) * 3600e3, b = a + 48 * 3600e3;
  const P = key => hourlyOM(key).filter(p => p.x >= a && p.x <= b);
  const line = (label, key, color, w = 2, dash) => ({ label, data: P(key), borderColor: color, borderWidth: w, pointRadius: 0, tension: .3, borderDash: dash || [] });
  const base = (unit, y, extra = {}) => EW.baseOpts({ scales: { x: EW.timeAxis(a, b, { step: 12 * 3600e3 }), y }, plugins: { legend: { display: Object.keys(extra).length > 0 && extra.legend, labels: { usePointStyle: true, boxWidth: 8, boxHeight: 8 } }, tooltip: EW.tip(unit, 1) } });
  EW.mk('c-48t', { type: 'line', data: { datasets: [line('Température', 'temperature_2m', css('--s2'), 2.4), line('Ressenti', 'apparent_temperature', css('--s4'), 1.4, [4, 3])] }, options: base('°C', EW.yAxis('°C'), { legend: true }) });
  EW.mk('c-48r', { type: 'bar', data: { datasets: [{ label: 'Pluie', data: P('precipitation'), backgroundColor: css('--s1'), borderRadius: 3, barPercentage: .9, categoryPercentage: 1 }] }, options: Object.assign(base('mm', EW.yAxis('mm', { beginAtZero: true })), { plugins: { legend: { display: false }, tooltip: Object.assign(EW.tip('mm', 1), { callbacks: { title: i => EW.fmtDT(i[0].parsed.x, { weekday: 'short', hour: '2-digit' }), label: c => ' ' + EW.nf(c.parsed.y, 1) + ' mm · probabilité ' + EW.nf((h.precipitation_probability || [])[(OM().hourly.time || []).indexOf(c.parsed.x / 1000)], 0) + ' %' } }) } }) });
  EW.mk('c-48w', { type: 'line', data: { datasets: [line('Vent', 'wind_speed_10m', css('--s1'), 2.2), line('Rafales', 'wind_gusts_10m', css('--s5'), 1.3, [4, 3])] }, options: base('km/h', EW.yAxis('km/h', { beginAtZero: true }), { legend: true }) });
}

// ───────────── 7 jours ─────────────
function week() {
  const ds = dailyOM().slice(0, 7); if (!ds.length) return EW.card('s7', '7 prochains jours', '', '<p class="foot">Données indisponibles.</p>');
  const lo = Math.min(...ds.map(d => d.temperature_2m_min)), hi = Math.max(...ds.map(d => d.temperature_2m_max)), sp = hi - lo || 1;
  const rows = ds.map((d, k) => {
    const a = (d.temperature_2m_min - lo) / sp * 100, b = (d.temperature_2m_max - lo) / sp * 100;
    return `<div class="dayrow"><span class="dn">${k === 0 ? 'Aujourd\'hui' : k === 1 ? 'Demain' : EW.dayLabel(EW.dayKey(d.time), { weekday: 'long', day: 'numeric' })}</span>${EW.icon(d.weather_code, true, 30)}<span class="cond">${EW.wmo(d.weather_code)}</span>
      <span class="rn">${d.precipitation_sum > 0 ? '<b>' + nf(d.precipitation_sum, 1) + '</b> mm' : '<i class="dim">—</i>'}${d.precipitation_probability_max != null ? '<small>' + nf(d.precipitation_probability_max, 0) + ' %</small>' : ''}</span>
      <span class="rg"><b>${nf(d.temperature_2m_min, 0)}°</b><span class="rgbar"><i style="left:${a}%;width:${Math.max(b - a, 4)}%"></i></span><b>${nf(d.temperature_2m_max, 0)}°</b></span>
      <span class="wd">${EW.arrow(d.wind_direction_10m_dominant, 13)} ${nf(d.wind_gusts_10m_max, 0)}<small>km/h</small></span></div>`;
  }).join('');
  return EW.card('s7', '7 prochains jours', 'min · max · pluie · rafales', `<div class="week">${rows}</div>`);
}

// ───────────── Air & pollens ─────────────
const AQI = [[20, 'Bon', '--good'], [40, 'Correct', '--good'], [60, 'Moyen', '--warn'], [80, 'Médiocre', '--warn'], [100, 'Mauvais', '--bad'], [1e9, 'Très mauvais', '--bad']];
function airCard() {
  const a = L().air; if (!a) return EW.card('s4', 'Air et pollens', '', '<p class="foot">Qualité de l\'air indisponible.</p>');
  const c = a.current || {}; const q = c.european_aqi; const lv = q == null ? null : AQI.find(x => q <= x[0]);
  const pol = [['alder_pollen', 'Aulne'], ['birch_pollen', 'Bouleau'], ['grass_pollen', 'Graminées'], ['mugwort_pollen', 'Armoise'], ['olive_pollen', 'Olivier'], ['ragweed_pollen', 'Ambroisie']].filter(p => c[p[0]] != null);
  const pm = (lab, v, unit, lim) => `<div class="pm"><span class="lab">${lab}</span><b style="${v != null && v > lim ? 'color:var(--warn)' : ''}">${nf(v, 0)}</b><small>${unit}</small></div>`;
  return EW.card('s4', 'Air et pollens', 'Open-Meteo · UE', `
    <div class="aqi"><div class="aqi-v" style="color:var(${lv ? lv[2] : '--t2'})">${nf(q, 0)}</div><div><b>${lv ? lv[1] : '—'}</b><span class="sub">Indice européen de qualité de l'air</span></div></div>
    <div class="aqi-bar"><i style="left:${Math.min(100, (q || 0) / 120 * 100)}%"></i></div>
    <div class="pms">${pm('PM2.5', c.pm2_5, 'µg/m³', 15)}${pm('PM10', c.pm10, 'µg/m³', 45)}${pm('Ozone', c.ozone, 'µg/m³', 100)}${pm('NO₂', c.nitrogen_dioxide, 'µg/m³', 25)}</div>
    <h3>Pollens</h3>
    ${pol.length ? pol.map(p => `<div class="lay"><span>${p[1]}</span><div class="bar"><i style="width:${Math.min(100, Math.sqrt(c[p[0]]) * 14)}%"></i></div><b>${nf(c[p[0]], 0)}</b></div>`).join('') + '<p class="foot">grains / m³</p>' : '<p class="foot">Aucun pollen annoncé : hors saison ou hors couverture du modèle.</p>'}`);
}

// ───────────── Modèles côte à côte ─────────────
const MV = {
  temp: { label: 'Température', unit: '°C', st: 'temp', d: 1 }, rain: { label: 'Pluie', unit: 'mm', st: 'rain', d: 1, zero: true }, wind: { label: 'Vent', unit: 'km/h', st: 'wind', d: 0, zero: true },
  gust: { label: 'Rafales', unit: 'km/h', st: 'gust', d: 0, zero: true }, hum: { label: 'Humidité', unit: '%', st: 'hum', d: 0 }, cloud: { label: 'Nuages', unit: '%', st: null, d: 0, zero: true },
};
const mseries = key => { const f = L().forecast; if (!f) return []; return MODELS.filter(m => f.series[m] && f.series[m][key]).map(m => ({ m, times: f.times, vals: f.series[m][key] })); };
function modelsCard() {
  return `<div class="card s12"><div class="card-head"><h2>Les modèles côte à côte</h2><div class="seg" id="m-var"></div></div>
    <div class="controls"><div class="seg" id="m-hz"></div><div class="seg" id="m-mode" hidden></div></div>
    <div class="legend" id="m-legend"></div><div class="chart tall"><canvas id="c-models"></canvas></div><p class="foot" id="m-foot"></p>
    <div class="g2"><div><h3 id="m-t1">Comparaison par échéance</h3><div class="scroll"><table class="tbl" id="t-lead"></table></div></div><div><h3>Jour par jour</h3><div class="scroll"><table class="tbl" id="t-day"></table></div></div></div></div>`;
}
function drawModels() {
  const V = MV[T.mv]; const now = Date.now(); const min = now - 24 * 3600e3, max = now + T.mh * 3600e3; const cum = T.mv === 'rain' && T.mmode === 'cum';
  $('#m-mode').hidden = T.mv !== 'rain';
  const ms = mseries(T.mv); const legend = [];
  if (V.st) legend.push({ id: 'st', label: 'Station', color: css('--obs'), fixed: true });
  ms.forEach(s => legend.push({ id: s.m, label: LABELS[s.m], color: EW.mcolor(s.m), dash: s.m === 'Auto' }));
  EW.legend($('#m-legend'), legend, T.moff, () => { drawModels(); }); const acc = pts => { if (!cum) return pts; let a = 0; return pts.map(p => ({ x: p.x, y: p.y == null ? null : (a += p.y) })); };
  const ds = [];
  if (V.st) { const pts = S.stArr.filter(r => r.e * 1000 >= min && r[V.st] != null).map(r => ({ x: r.e * 1000, y: r[V.st] })); ds.push({ label: 'Station', data: acc(pts), borderColor: css('--obs'), backgroundColor: css('--obs') + '22', fill: T.mv === 'rain', borderWidth: 2.6, stepped: T.mv === 'rain' ? 'before' : false, pointRadius: 0, tension: .25, order: 0 }); }
  for (const s of ms) { if (T.moff.has(s.m)) continue; const pts = []; s.times.forEach((t, i) => { const x = t * 1000; if (x >= min && x <= max) pts.push({ x, y: s.vals[i] }); }); ds.push({ label: LABELS[s.m], data: acc(pts), borderColor: EW.mcolor(s.m), borderWidth: 1.7, pointRadius: 0, tension: T.mv === 'rain' ? 0 : .25, stepped: T.mv === 'rain' ? 'before' : false, borderDash: s.m === 'Auto' ? [5, 3] : [], order: 1 }); }
  EW.mk('c-models', { type: 'line', data: { datasets: ds }, options: EW.baseOpts({ scales: { x: EW.timeAxis(min, max), y: EW.yAxis(cum ? 'mm cumulés' : V.unit, { beginAtZero: !!V.zero }) }, plugins: { legend: { display: false }, tooltip: EW.tip(V.unit, V.d), nowLine: { x: now } } }), plugins: [EW.nowPlugin] });
  $('#m-foot').textContent = L().forecast ? 'Prévisions du ' + EW.fmtDT(L().generated * 1000, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) + (V.st ? ' · trait épais = station' : '') : 'Prévisions indisponibles.';
  leadTable(); dayTable();
}
const LEAD_H = [1, 3, 6, 12, 24, 48, 72];
function leadTable() {
  const t = $('#t-lead'); const V = MV[T.mv]; const ms = mseries(T.mv); if (!ms.length) { t.innerHTML = '<tbody><tr><td>Pas de prévision</td></tr></tbody>'; return; }
  const t0 = Math.floor(Date.now() / 1000 / HOUR) * HOUR; $('#m-t1').textContent = T.mv === 'rain' ? 'Cumul depuis maintenant (mm)' : 'Valeur à +1 h … +3 j (' + V.unit + ')';
  const rows = ms.map(s => { const idx = new Map(s.times.map((x, i) => [x, i])); return { m: s.m, cells: LEAD_H.map(h => { if (T.mv === 'rain') { let a = 0, any = false; for (let k = 1; k <= h; k++) { const i = idx.get(t0 + k * HOUR); if (i != null && s.vals[i] != null) { a += s.vals[i]; any = true; } } return any ? a : null; } const i = idx.get(t0 + h * HOUR); return i != null ? s.vals[i] : null; }) }; });
  const vis = rows.filter(r => !T.moff.has(r.m)); const spread = LEAD_H.map((_, c) => { const v = vis.map(r => r.cells[c]).filter(x => x != null); return v.length ? Math.max(...v) - Math.min(...v) : null; });
  t.innerHTML = '<thead><tr><th>Modèle</th>' + LEAD_H.map(h => `<th>+${h < 24 ? h + ' h' : h / 24 + ' j'}</th>`).join('') + '</tr></thead><tbody>' + rows.map(r => `<tr style="${T.moff.has(r.m) ? 'opacity:.4' : ''}"><td><span class="m" style="--c:${EW.mcolor(r.m)}"><i></i>${LABELS[r.m]}</span></td>${r.cells.map(v => `<td>${v == null ? '<span class="dim">—</span>' : nf(v, V.d)}</td>`).join('')}</tr>`).join('') + '<tr class="group"><td>Écart entre modèles</td>' + spread.map(s => `<td>${s == null ? '—' : nf(s, V.d)}</td>`).join('') + '</tr></tbody>';
}
function dayTable() {
  const t = $('#t-day'); const f = L().forecast; if (!f) { t.innerHTML = '<tbody><tr><td>Pas de prévision</td></tr></tbody>'; return; }
  const nowE = Date.now() / 1000; const days = []; for (let k = 0; k < 6; k++) days.push(EW.dayKey(nowE + k * 86400));
  const cells = {};
  for (const m of MODELS) { const s = f.series[m]; if (!s || !s.temp) continue; cells[m] = days.map(d => { let lo = null, hi = null, rn = 0, nr = 0, c = 0; f.times.forEach((tt, i) => { if (EW.dayKey(tt - 1800) !== d) return; const v = s.temp[i]; if (v != null) { c++; lo = lo == null ? v : Math.min(lo, v); hi = hi == null ? v : Math.max(hi, v); } if (s.rain && s.rain[i] != null) { rn += s.rain[i]; nr++; } }); return c >= 6 ? { lo, hi, rn: nr ? rn : null } : null; }); }
  t.innerHTML = '<thead><tr><th>Modèle</th>' + days.map((d, k) => `<th>${k === 0 ? 'Auj.' : k === 1 ? 'Dem.' : EW.dayLabel(d, { weekday: 'short', day: 'numeric' })}</th>`).join('') + '</tr></thead><tbody>' + Object.keys(cells).map(m => `<tr><td><span class="m" style="--c:${EW.mcolor(m)}"><i></i>${LABELS[m]}</span></td>${cells[m].map(c => c ? `<td>${nf(c.lo, 0)}° / <b>${nf(c.hi, 0)}°</b><br><span style="color:${c.rn >= .2 ? css('--s1') : css('--t2')}">${c.rn == null ? '—' : nf(c.rn, 1)} mm</span></td>` : '<td><span class="dim">—</span></td>').join('')}</tr>`).join('') + '</tbody>';
}

function rawCard() {
  const raw = L().raw || {}; const keys = Object.keys(raw).sort();
  return `<details class="card s12 raw"><summary>Toutes les mesures en direct (${keys.length})</summary><div class="scroll"><table class="tbl">${keys.length ? '<thead><tr><th>Mesure</th><th>Valeur</th><th>Unité</th></tr></thead><tbody>' + keys.map(k => `<tr><td>${EW.esc(k)}</td><td>${EW.esc(raw[k][0])}</td><td>${EW.esc(raw[k][1] || '')}</td></tr>`).join('') + '</tbody>' : '<tbody><tr><td>Aucune mesure reçue</td></tr></tbody>'}</table></div></details>`;
}

// ───────────── Assemblage ─────────────
EW.views.today = {
  render() {
    const root = $('#view-today');
    // Reconstruit la structure (les valeurs changent à chaque collecte).
    const kp = kpiTiles();
    root.innerHTML = hero() + sunCard() + kp +
      EW.card('s8', 'Journée en cours', 'station contre prévisions', `<div class="controls"><div class="seg" id="d-seg"></div></div><div class="legend" id="d-legend"></div><div class="chart tall"><canvas id="c-day" role="img" aria-label="Courbe de la journée"></canvas></div><p class="foot" id="d-foot"></p>`) +
      airCard() + next48() + week() + EW.card('s5', 'Résumé de la journée', 'mesures de la station', daySummary()) + modelsCard() + rawCard();
    // Tuiles : on garde la liste pour dessiner les mini-courbes.
    drawSparks();
    EW.seg($('#d-seg'), Object.entries(DV).map(([k, v]) => [k, v.label]), T.v, k => { T.v = k; dayChart(); });
    dayChart(); draw48();
    EW.seg($('#m-var'), Object.entries(MV).map(([k, v]) => [k, v.label]), T.mv, k => { T.mv = k; drawModels(); });
    EW.seg($('#m-hz'), [[24, '+24 h'], [48, '+48 h'], [72, '+3 j'], [144, '+6 j']], T.mh, k => { T.mh = +k; drawModels(); });
    EW.seg($('#m-mode'), [['hour', 'Par heure'], ['cum', 'Cumul']], T.mmode, k => { T.mmode = k; drawModels(); });
    drawModels();
  },
};
function daySummary() {
  const td = L().today; const d = EW.daily.get(EW.todayKey()); const st = L().station || {};
  const obs = td ? EW.nums(td.temp) : []; const row = (a, b, c) => `<div class="sumrow"><span>${a}</span><b>${b}</b><small>${c || ''}</small></div>`;
  const tmin = obs.length ? Math.min(...obs) : (d && d.tmin), tmax = obs.length ? Math.max(...obs) : (d && d.tmax);
  const tm = td ? EW.mean(td.temp) : (d && d.tmean); const tAt = (a) => { if (!td || !obs.length) return ''; const i = td.temp.indexOf(a); return i >= 0 ? 'à ' + EW.fmtT(td.t[i]) : ''; };
  const g = td ? EW.nums(td.gust) : []; const gi = g.length ? td.gust.indexOf(Math.max(...g)) : -1;
  const p = td ? EW.nums(td.press) : [];
  return `<div class="sum">${row('Température mini', nf(tmin) + ' °C', tAt(tmin))}${row('Température maxi', nf(tmax) + ' °C', tAt(tmax))}${row('Température moyenne', nf(tm) + ' °C')}${row('Pluie', nf(st.rain_day, 1) + ' mm')}${row('Rafale maxi', g.length ? nf(Math.max(...g), 0) + ' km/h' : '—', gi >= 0 ? 'à ' + EW.fmtT(td.t[gi]) : '')}${row('Pression', p.length ? nf(Math.min(...p), 0) + ' → ' + nf(Math.max(...p), 0) + ' hPa' : '—', 'mini → maxi')}${row('Mesures 5 min', td ? td.t.length : '—', 'depuis minuit')}</div>`;
}
})();
