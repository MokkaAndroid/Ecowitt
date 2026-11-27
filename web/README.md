# Page web Ecowidget

Tableau de bord de la station et des modèles météo (onglets « Météo » et « Performance des modèles »).

- `collect.py` : collecte (Python standard, sans dépendance) → `station.csv`, `forecasts.csv`, `latest.json`.
- `index.html`, `style.css`, `core.js` (données, graphiques), `today.js`, `perf.js`, `history.js`, `boot.js`, `vendor/` : la page (Chart.js et la police sont embarqués, aucun CDN).
- `seed/` : historique de départ (étude du 27 sept. au 26 nov. 2026), recopié si la branche `data` est vide.
- Workflow : `.github/workflows/web.yml` (toutes les 30 min). Les CSV grandissent sur la branche `data`.

**Rattrapage de l'historique** : à chaque collecte, tant qu'il n'est pas terminé, `collect.py` relit l'historique 30 min du cloud Ecowitt par tranches de 6 jours en remontant (30 tranches max par passage, curseur dans `state.json`) et s'arrête après 3 tranches vides (avant l'installation de la station) ou à 400 jours (`BACKFILL_DAYS`). Le cloud Ecowitt ne garde le pas de 30 min qu'environ 1 an.

Secrets du dépôt (Settings › Secrets and variables › Actions) : `ECOWITT_APP_KEY`, `ECOWITT_API_KEY`, `ECOWITT_MAC` ; facultatifs : `LAT`, `LON`, `ELEVATION` (685 par défaut).
Sans secrets, la page publie quand même les prévisions des modèles.
Attention : le dépôt étant public, la page et les CSV le sont aussi.

Test en local : `python3 collect.py --data /tmp/d --seed seed`, puis servir un dossier contenant la page et `data/`.
