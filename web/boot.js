/* Démarrage : en-tête, onglets, thème, actualisation. */
(() => {
'use strict';
const EW = window.EW; const { $, $$, S, nf } = EW;
const TABS = ['today', 'perf', 'hist']; const HASH = { today: '#aujourdhui', perf: '#performance', hist: '#historique' };
let cur = 'today';

function header() {
  const L = S.latest, st = L && L.station; const obs = st && st.observed; const age = obs ? Math.max(0, Math.round((Date.now() / 1000 - obs) / 60)) : null;
  const chip = $('#age'); chip.className = 'chip ' + (age == null ? 'bad' : age <= 20 ? 'ok' : age <= 90 ? 'old' : 'bad');
  $('span', chip).textContent = age == null ? 'pas de mesure' : age < 60 ? 'il y a ' + Math.max(age, 1) + ' min' : 'il y a ' + Math.floor(age / 60) + ' h ' + String(age % 60).padStart(2, '0');
  const elev = L && L.om && L.om.elevation;
  $('#sub').textContent = (elev != null ? 'Altitude ' + nf(elev, 0) + ' m · ' : '') + (S.stArr.length ? S.stArr.length.toLocaleString('fr-FR') + ' h de mesures' : 'aucune mesure') + (S.hasFc ? ' · ' + S.fc.length.toLocaleString('fr-FR') + ' prévisions archivées' : '');
  $('#gen').textContent = L && L.generated ? 'Collecte du ' + EW.fmtDT(L.generated * 1000, { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) : 'Aucune collecte';
  const nt = $('#notice'); const errs = (L && L.errors) || [];
  if (!L) { nt.hidden = false; nt.textContent = 'latest.json introuvable : la collecte n\'a pas encore tourné.'; } else if (errs.length) { nt.hidden = false; nt.innerHTML = '<b>Collecte :</b> ' + errs.map(EW.esc).join(' · '); } else nt.hidden = true;
}
function show(which, push = true) {
  cur = which;
  TABS.forEach(t => { const on = t === which; $('#view-' + t).hidden = !on; $('#tab-' + t).setAttribute('aria-selected', on); });
  if (push) history.replaceState(null, '', HASH[which]);
  try { EW.views[which].render(); } catch (e) { console.error(e); $('#view-' + which).innerHTML = '<div class="card s12"><p class="verdict">Erreur d\'affichage : ' + EW.esc(e.message) + '</p></div>'; }
}
const redraw = () => { header(); show(cur, false); };
function theme() {
  const r = document.documentElement; const c = r.getAttribute('data-theme'); const next = c === 'dark' ? 'light' : c === 'light' ? null : 'dark';
  next ? r.setAttribute('data-theme', next) : r.removeAttribute('data-theme');
  try { next ? localStorage.setItem('theme', next) : localStorage.removeItem('theme'); } catch (e) { /* ignoré */ }
  redraw();
}
async function boot() {
  try { const t = localStorage.getItem('theme'); if (t) document.documentElement.setAttribute('data-theme', t); } catch (e) { /* ignoré */ }
  TABS.forEach(t => $('#tab-' + t).onclick = () => show(t));
  $('#theme').onclick = theme; $('#reload').onclick = async () => { await EW.load(); redraw(); };
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', redraw);
  window.addEventListener('hashchange', () => { const t = TABS.find(x => HASH[x] === location.hash); if (t && t !== cur) show(t, false); });
  try { await EW.load(); } catch (e) { console.error(e); }
  header(); show(TABS.find(t => HASH[t] === location.hash) || 'today', false);
  setInterval(async () => { if (document.visibilityState === 'visible') { try { await EW.load(); redraw(); } catch (e) { /* réseau */ } } }, 5 * 60 * 1000);
}
boot();
})();
