# Page web Ecowidget

Tableau de bord de la station et des modèles météo (onglets « Météo » et « Performance des modèles »).

- `collect.py` : collecte (Python standard, sans dépendance) → `station.csv`, `forecasts.csv`, `latest.json`.
- `index.html`, `app.js`, `style.css`, `vendor/` : la page (Chart.js et la police sont embarqués, aucun CDN).
- `seed/` : historique de départ (étude du 27 sept. au 26 nov. 2026), recopié si la branche `data` est vide.
- Workflow : `.github/workflows/web.yml` (toutes les 30 min). Les CSV grandissent sur la branche `data`.

Secrets du dépôt (Settings › Secrets and variables › Actions) : `ECOWITT_APP_KEY`, `ECOWITT_API_KEY`, `ECOWITT_MAC` ; facultatifs : `LAT`, `LON`, `ELEVATION` (685 par défaut).
Sans secrets, la page publie quand même les prévisions des modèles.
Attention : le dépôt étant public, la page et les CSV le sont aussi.

Test en local : `python3 collect.py --data /tmp/d --seed seed`, puis servir un dossier contenant la page et `data/`.
