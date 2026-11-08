# Limite pluie / neige : sources possibles (gratuites) pour Villarlurin

Besoin : savoir, quand des précipitations sont attendues, à quelle altitude la pluie devient neige, et comment cette limite évolue dans la journée, pour ton secteur (Savoie, ~685 m).

## Ce que fait l'app aujourd'hui (v0.5.4)

Estimation = isotherme 0 °C d'Open-Meteo (`freezing_level_height`) − 300 m, arrondie à 100 m.
C'est une règle de pouce. Les bulletins Météo-France montrent des écarts bien plus variables entre l'isotherme 0 °C et la limite pluie-neige (de ~200 m à ~1 000 m selon l'air plus ou moins sec et l'intensité des précipitations).
Fiabilité : moyenne, à prendre comme un ordre de grandeur.

## Sources étudiées

| # | Source | Donnée | Gratuit | Couverture / finesse | Fiabilité attendue | Effort |
|---|--------|--------|---------|----------------------|--------------------|--------|
| A | **Météo-France BRA** (bulletin neige & avalanches), API « DonneesPubliquesBRA » | `PLUIENEIGE` et `ISO0` (m), −1 si temps sec ; 4 créneaux (06 h, 12 h, 18 h, 00 h) | Oui (données publiques gratuites depuis 2024, compte portail gratuit) | Par massif (Vanoise, n° 10, à confirmer pour ta vallée) ; publié vers 16 h ; **saison début novembre → fin mai** | Élevée (prévisionniste humain, référence locale), mais grossière en temps et en espace | Moyen : même authentification que PIAF (Application ID), lecture d'un XML |
| B | **Open-Meteo `snowfall_height`** (ICON-D2, ICON-EU, MétéoSuisse ICON-CH1/CH2) | Altitude où la température du thermomètre mouillé dépasse 1,3 °C, horaire (D2 : aussi 15 min) | Oui, même API qu'aujourd'hui | Modèles 1–7 km. **À vérifier : couverture de la Savoie** (ICON-D2 et CH1 sont centrés Allemagne / Suisse) | Bonne si le modèle couvre la zone ; plus physique que « iso 0 °C − 300 m » | Faible |
| C | **Calcul local** sur AROME (Open-Meteo, niveaux de pression : température, humidité, hauteur géopotentielle à 950/925/900/850/800 hPa) | Altitude où Tw franchit 1,3 °C, calculée par l'app | Oui | AROME 2,5 km, meilleur modèle sur les Alpes françaises | Bonne en principe, dépend de mon calcul (à valider) | Moyen à élevé |
| D | **Ta station** (température + humidité à 685 m) | Température du thermomètre mouillé chez toi → « pluie / neige mouillée / neige » et limite extrapolée (~0,55 °C / 100 m) | Oui, aucune API | Temps réel, exactement chez toi ; seulement pendant les précipitations | Bonne pour « qu'est-ce qui tombe chez moi maintenant », moyenne pour la limite extrapolée | Faible |

## Écartées

- **Meteoblue** (ligne de neige) : clé API, offre gratuite très limitée / non commerciale.
- **Windy API** : clé, quotas, pas de variable de limite pluie-neige directe.
- **MétéoSuisse open data brut** et **paquets AROME/ARPEGE Météo-France** (GRIB) : fichiers volumineux, décodage GRIB impraticable sur un téléphone. Open-Meteo les encapsule déjà (B et C).
- **GeoSphere Autriche** : hors zone.

## Recommandation

1. **B + test sur ton téléphone** : le bouton « Tester la limite pluie / neige (modèles) » (Réglages → Diagnostic, v0.5.5) interroge ICON-D2, ICON-EU, ICON-CH1, ICON-CH2 et le modèle automatique pour ta position et affiche leur `snowfall_height` et leur isotherme 0 °C sur 24 h. On saura quels modèles couvrent vraiment ton secteur.
2. **A (BRA)** comme valeur « officielle » pendant la saison (nov. → mai), affichée à côté de la valeur modèle quand elles divergent. Nécessite de s'abonner à l'API « DonneesPubliquesBRA » avec ton compte portail (même Application ID que PIAF) et de confirmer le massif.
3. **D** en complément temps réel : « chez toi : pluie / neige mouillée / neige », sans API.

L'adresse exacte de l'API BRA n'est pas dans la documentation publique (elle est visible une fois connecté au portail) : elle sera à confirmer.

## Sources

- Descriptif technique du BRA XML (Météo-France) : attributs `PLUIENEIGE` et `ISO0` de `<ECHEANCE>`, 4 créneaux, publication vers 16 h, de début novembre à fin mai.
- Documentation Open-Meteo, API DWD : `snowfall_height` = hauteur où la température du thermomètre mouillé dépasse 1,3 °C ; absente d'ICON Global.
- Documentation Open-Meteo, API Météo-France : niveaux de pression disponibles (1000 → 10 hPa), pas de `snowfall_height`.
- Documentation Open-Meteo, API MétéoSuisse : `snowfall_height` listé, domaine « Europe centrale » sans bornes précises.
- Bulletins BRA publics (Météo-France) : exemples de limites pluie-neige et d'isothermes 0 °C.
