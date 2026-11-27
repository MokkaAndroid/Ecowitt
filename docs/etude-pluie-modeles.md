# Étude pluie : modèles contre station (CSV du 26/11/2026)

Source : « Comparer par échéance » (Réglages › Diagnostic), station à 685 m, 60 jours (27/09 → 26/11), 1 438 h comparées, 236 h de pluie (≥ 0,2 mm), 271 mm. Heures 30 jours (runs de 0, 6, 12, 18 h UTC, 2 h par fenêtre). Jours : 59 jours locaux complets dont 29 pluvieux. « Existant » : barres horaires = Auto (≈ ICON-D2 à court terme) ; pluie 60 min = le plus pluvieux de AROME et du modèle générique.

## Quantité et détection horaire (0–12 h)

| Échéance | Modèle | Quantité prévue ÷ mesurée | Écart moyen (mm/h) | Détectées | Fausses alertes | Score (CSI) |
|---|---|---|---|---|---|---|
| +1–2 h | ICON-D2 (existant) | 1,17 | 0,22 | 78 % | 22 % | 0,63 |
| | AROME | 0,66 | 0,21 | 62 % | 10 % | 0,58 |
| | ICON-CH1 | 0,58 | 0,23 | 71 % | 15 % | 0,63 |
| | max AROME + D2 (nowcast actuel) | 1,32 | 0,24 | 81 % | 24 % | 0,64 |
| | moyenne des 3 | 0,80 | 0,16 | 81 % | 13 % | 0,72 |
| +3–4 h | ICON-D2 | 1,35 | 0,26 | 83 % | 30 % | 0,61 |
| | moyenne des 3 | 1,03 | 0,17 | 86 % | 21 % | 0,70 |
| +5–6 h | ICON-D2 | 1,61 | 0,27 | 85 % | 29 % | 0,63 |
| | moyenne des 3 | 1,37 | 0,23 | 83 % | 30 % | 0,62 |
| +11–12 h | ICON-D2 | 1,45 | 0,28 | 74 % | 33 % | 0,54 |
| | moyenne des 3 | 1,26 | 0,22 | 82 % | 27 % | 0,63 |

Recalage seul (ICON-D2 ÷ son ratio, sur ces données) : écart moyen 0,22 → 0,19 (+1–2 h), 0,26 → 0,19, 0,27 → 0,18, 0,28 → 0,21.

## Début et fin de pluie dans la fenêtre (petits échantillons : 16 / 9 / 5 / 6 débuts ; 7 / 9 / 12 / 12 fins)

| Échéance | Modèle | Débuts détectés | Fausses alertes de début | Fins détectées |
|---|---|---|---|---|
| +1–2 h | ICON-D2 | 75 % | 29 % | 71 % |
| | AROME | 50 % | 11 % | 86 % |
| | max AROME + D2 | 75 % | 29 % | 57 % |
| +3–4 h | ICON-D2 | 78 % | 46 % | 56 % |
| | AROME | 44 % | 0 % | 78 % |
| +5–6 h | ICON-D2 | 80 % | 60 % | 67 % |
| | AROME | 40 % | 60 % | 75 % |
| +11–12 h | ICON-D2 | 33 % | 71 % | 58 % |
| | ICON-CH1 | 83 % | 50 % | 67 % |
| | AROME | 50 % | 50 % | 92 % |

## Jours J+1 à J+5 (par jour local)

| Échéance | Modèle | Jours de pluie détectés | Faux jours de pluie | Cumul prévu ÷ mesuré | Écart moyen (mm/jour) | Corrélation | Début à ±1 h | Fin à ±1 h |
|---|---|---|---|---|---|---|---|---|
| J+1 | Auto (existant) | 86 % | 11 % | 1,17 | 2,38 | 0,89 | 76 % | 56 % |
| | ICON-D2 | 86 % | 11 % | 1,09 | 2,20 | 0,90 | 88 % | 60 % |
| | AROME | 79 % | 4 % | 0,93 | 2,26 | 0,86 | 65 % | 57 % |
| J+2 | Auto | 86 % | 17 % | 1,30 | 3,11 | 0,80 | 56 % | 48 % |
| | ARPEGE | 90 % | 19 % | 1,46 | 2,75 | 0,88 | 62 % | 62 % |
| J+3 | Auto | 90 % | 21 % | 1,41 | 3,96 | 0,75 | 54 % | 58 % |
| | ARPEGE | 90 % | 13 % | 1,25 | 2,42 | 0,90 | 42 % | 35 % |
| J+4 | Auto | 79 % | 26 % | 1,42 | 4,78 | 0,68 | 39 % | 39 % |
| | IFS | 93 % | 21 % | 1,67 | 4,27 | 0,82 | 48 % | 48 % |
| J+5 | Auto | 72 % | 25 % | 1,04 | 4,29 | 0,54 | 48 % | 24 % |
| | IFS | 83 % | 23 % | 1,49 | 4,57 | 0,66 | 42 % | 50 % |

Réserves : échantillons courts (surtout début / fin), pluviomètre possiblement sous-estimé par vent ou neige (les modèles paraissent alors trop pluvieux), résultats sur une seule saison.
