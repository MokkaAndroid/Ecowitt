# Ecowidget — Roadmap

Légende : ✅ terminé · 🧪 écrit, **à tester** · ⏳ à faire · ⛔ hors périmètre

> État au 08/11/2026 : **v0.1** installée et validée sur téléphone (température, pluie, humidité OK).
> **v0.2** (Open-Meteo + courbe 24 h + couleurs dynamiques + tendances) : compile, **pas encore testée sur téléphone**.
> **v0.3** (catalogue Open-Meteo par catégories, variable personnalisée, message pluie AROME, air/pollens) : code écrit, **compile**
> (build GitHub Actions réussi le 08/11/2026), **pas encore testée sur téléphone**. Voir la checklist en bas de page.
> **v0.4** (refonte de l'affichage, direction « B · Héros + pluie » choisie le 08/11/2026) : compile, **pas encore testée sur téléphone**.
> **v0.5** (08/11/2026 : bascule sur la direction « A · Sections » pour les grands widgets, infos de la maquette activées d'office) : code écrit, compilation à confirmer, **pas encore testée sur téléphone**.

## Phase 0 — Cadrage
- ✅ Station : Ecowitt HP2564 (console Wi-Fi → cloud ecowitt.net)
- ✅ Sources de données : API cloud Ecowitt v3 (`real_time`, `history`, `device/list`) + Open-Meteo (prévisions)
- ✅ Cible : Android 8.0+ (API 26), widget natif Kotlin + Jetpack Glance
- ✅ Livraison : projet Gradle + build APK automatique via GitHub Actions

## Phase 1 — Socle du projet
- ✅ Structure Gradle (wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21, catalogue de versions)
- ✅ Manifeste, permissions minimales (Internet, alarmes exactes, démarrage)
- ✅ Icône, thème sombre, ressources
- ✅ Première compilation (GitHub Actions) réussie le 07/11/2026 — APK produit (≈10,9 Mo)

## Phase 2 — Données Ecowitt
- ✅ Client API (unités métriques demandées : °C, hPa, km/h, mm, W/m²)
- ✅ Lecture tolérante du JSON (capteur absent = valeur vide)
- ✅ Détection « il pleut » (`rain_rate` > 0, ou état du pluviomètre piézo)
- ✅ Validé avec la HP2564 de Mickaël (température, humidité, pluie) — v0.1
- ✅ Cache de la dernière réponse (le widget reste affiché hors ligne)
- ✅ Messages d'erreur sans fuite des clés
- 🧪 **Historique 24 h** (`/device/history`, pas de 30 min, repli sur `auto`) : structure JSON à confirmer sur ton compte
- 🧪 **Position de la station** (`/device/list`) pour alimenter Open-Meteo
- 🧪 Min/max du jour calculés depuis l'historique (depuis minuit, heure locale)
- 🧪 Tendances : température sur 1 h, pression sur 3 h

## Phase 3 — Open-Meteo (nouveau)
- 🧪 Prévisions `forecast` : condition actuelle (code WMO), min/max, probabilité de pluie, cumul, neige, UV, lever/coucher, vent/rafales max, isotherme 0 °C, neige au sol
- 🧪 Pluie à court terme (pas de 15 min) : « Prochaine pluie dans X min »
- 🧪 Bandeau des 8 prochaines heures (icône + température)
- 🧪 Recherche de ville (géocodage) + altitude de la station (correction en montagne)
- 🧪 Cache 10 min, repli silencieux si Open-Meteo est injoignable (le bloc du bas n'est jamais impacté)
- ✅ Attribution « Open-Meteo » dans le widget, l'écran de réglages et le README (licence CC BY 4.0, usage non commercial)
- ✅ Vie privée : coordonnées arrondies à ~1 km, jamais de clé Ecowitt envoyée
- 🧪 **Catalogue par catégories** (v0.3) : ≈ 85 infos classées en 12 catégories (Spécial · Température & humidité · Vent · Précipitations · Ciel & visibilité · Pression · Soleil & UV · Orage & stabilité · Sol · Astro · Qualité de l'air · Pollens), recherche, unités lues dans la réponse de l'API
- 🧪 « Variable personnalisée » : saisie du nom d'une variable Open-Meteo (prévisions ou air/pollens ; actuel / horaire / quotidien) pour couvrir le reste
- 🧪 Qualité de l'air / pollens (API air-quality, Europe, pollens saisonniers) : requête séparée, cache 60 min, uniquement si une info de cette catégorie est choisie
- 🧪 Variables demandées = union de tous les widgets posés ; si Open-Meteo refuse une variable (erreur 400), repli sur les infos de base + raison affichée dans Diagnostic
- ⏳ Alertes météo (vigilance) — non couvertes par Open-Meteo, source à étudier (Météo-France)

## Phase 3 bis — Pluie imminente (message unique)
- ✅ v0.2 : « Prochaine pluie » via `minutely_15` de l'endpoint générique `/v1/forecast` (modèle « best match », pas forcément AROME)
- 🧪 v0.3 : « Prochaine pluie » et message pluie via `/v1/meteofrance` (AROME France / HD, `minutely_15`, horizon 6 h, mise à jour **horaire**) — modèle numérique, **pas du radar** ; repli sur le modèle par défaut si indisponible
- 🧪 v0.3 : info « Message pluie » (« Pluie dans ~20 min », « Pas de pluie dans les 6 h »…) en bandeau sous l'en-tête, ou en tête des infos de droite si la hauteur manque
- ⏳ Météo-France **PIAF** (fusion extrapolation radar + AROME-PI, pas de 5 min, horizon 3 h, GRIB2 + WCS/WMS via portail-api.meteofrance.fr) : à valider avant dev — souscrire à l'API, lire la documentation (extraction par point via WCS ?), jeton (1 h) / Application ID, quotas, couverture radar en vallée alpine
- 🧪 v0.3.1 : réglage « Météo-France · pluie imminente (test) » : Application ID chiffré (Keystore), jeton OAuth2 (`POST portail-api.meteofrance.fr/token`, renouvelé automatiquement), lecture des catalogues WCS AROME-PI et PIAF (adresse PIAF supposée, adresse personnalisée possible) — résultats affichés dans l'écran de réglages
- ✅ 08/11/2026 : Application ID accepté (jeton OK) ; **PIAF accessible** sur `https://api.meteofrance.fr/pro/piaf/1.0/wcs/MF-NWP-HIGHRES-PIAF-001-FRANCE-WCS/` (compte gratuit, 100 requêtes/min) ; couche `TOTAL_PRECIPITATION_RATE__GROUND_OR_WATER_SURFACE___<run>_PT…` (6315 couches sur 5 jours) ; AROME-PI : 403 (pas d'abonnement, inutile) ; serveur public : 404
- 🧪 v0.3.5 : lecture ponctuelle PIAF au point de la station (DescribeCoverage + GetCoverage GeoTIFF sur ±0,025°, 3 échéances) dans le test des Réglages — valeurs brutes affichées pour fixer l'unité (mm ou kg/m²/s) et la durée de cumul (PT5M/PT15M…)
- 🧪 v0.3.7 : position précise de la station — recherche d'**adresse** (Géoplateforme IGN, `data.geopf.fr/geocodage/search`, seule la commune est affichée sur le widget) ou **coordonnées** collées (« 45.50, 6.30 » ou lien Google Maps) ; position **exacte** stockée chiffrée, arrondie à 0,01° pour Open-Meteo, maille de 0,01° demandée à PIAF puis pixel exact choisi sur le téléphone
- ⏳ Intégration PIAF au widget : identifiant de couche construit par calcul (run = maintenant − ~10 min arrondi à 5 min, repli sur le run précédent) pour éviter le GetCapabilities de 6 000 couches ; message « Pluie dans X min » ; priorité station > PIAF > AROME
- ⏳ Message unique à priorité : pluie mesurée par la station > PIAF > AROME > modèle par défaut (« Pluie dans ~20 min », « Pas de pluie dans les 3 h »)
- ⛔ API non publique de l'app Météo-France (« pluie dans l'heure ») : non documentée, peut casser sans préavis

## Phase 4 — Sécurité
- ✅ Clés stockées chiffrées (AES-256, Android Keystore) ; position également chiffrée
- ✅ HTTPS uniquement, pas de trafic en clair, redirections désactivées
- ✅ Aucune clé dans les journaux ni les messages d'erreur
- ✅ Sauvegarde Android désactivée (les clés ne partent pas dans un backup)
- ✅ Récepteur d'alarme non exporté
- ⏳ Build « release » avec R8 (réduction/obfuscation) — après validation fonctionnelle

## Phase 5 — Widget
- ✅ 17 mesures Ecowitt (dont Mini/Maxi du jour) · 🧪 ≈ 85 informations Open-Meteo classées par catégorie + variable personnalisée
- ✅ Mise en page en deux blocs : **Open-Meteo en haut, station Ecowitt en bas**
- ✅ Mise en page adaptative (format court / large ; 1, 2 ou 3 colonnes ; ce qui ne rentre pas est masqué par priorité)
- ✅ 5 thèmes : Nuit, Océan, Aurore, Clair, Verre
- 🧪 Courbe de température 24 h (avec ligne de gel à 0 °C) à côté de la valeur principale
- 🧪 Couleur de la température selon le thermomètre (bleu glacé → vert → orange → rouge)
- 🧪 Fond bleuté quand il pleut + valeurs « pluie » en bleu
- 🧪 Badge « Risque de gel » (≤ 3 °C) / « Gel » (≤ 0 °C)
- 🧪 Flèches de tendance ▲ ▼ (température 1 h, pression 3 h)
- ✅ Heure de la mesure + indicateur « hors ligne » / « ancien »
- ✅ Appui sur le widget = actualisation immédiate (contourne les délais, 1 min minimum entre deux appels)
- 🧪 Rendu réel sur ton lanceur et à plusieurs tailles (4×1, 4×2, 4×3, 4×4)

## Phase 5 bis — Refonte de l'affichage (v0.4, direction B)
Maquettes : canevas « Ecowidget · 3 propositions d'affichage » (A sections · B héros + pluie · C tableau de bord). Choix : **B**, avec gabarits par taille, barres de pluie et valeurs à zéro estompées.
- 🧪 Héros : mesure principale en grand (jusqu'à 60 sp selon la hauteur), météo du moment (icône, libellé, mini · maxi) à droite, courbe 24 h entre les deux si la largeur le permet
- 🧪 Carte pluie : message + **12 barres de 5 min** sur l'heure à venir (échelle racine carrée, 2 mm/h = hauteur pleine) ; source affichée (AROME aujourd'hui, PIAF ensuite). Passe avant le reste quand de la pluie est prévue ou en cours ; un jour sec elle apparaît dès qu'il y a la place (barres plates), sinon simple ligne
- 🧪 Prochaines heures : bandeau (icône + température), ou carte avec **courbe** des températures quand la hauteur le permet ; si l'info « Bandeau des prochaines heures » n'est pas choisie, les heures n'apparaissent qu'en bonus quand il reste de la place
- 🧪 v0.4.1 (retour du 08/11 : grand widget avec un vide au milieu, très loin des maquettes) : la hauteur libre est répartie entre les blocs (espaceurs élastiques) au lieu d'un seul vide ; héros jusqu'à 72 sp, icône météo proportionnelle, graphiques un peu plus hauts ; courbe 24 h pleine largeur sous le héros ; lieu réduit à la commune (« Moûtiers »)
- 🧪 Bandeau station : étiquette au-dessus, valeur dessous, filets verticaux (jusqu'à 5 colonnes, 3 lignes)
- 🧪 Infos Open-Meteo restantes (air, pollens, isotherme…) en pastilles sous la station
- 🧪 **Gabarits par taille** : budget de hauteur par priorité — héros + 1 ligne station (obligatoires) → message pluie (dans la colonne météo, puis ligne, puis carte) → heures → infos → en-tête (lieu + heure) → courbe des heures → lignes station supplémentaires → héros plus grand
- 🧪 Valeurs à zéro estompées (0,0 mm, pollens 0, « Aucune ») ; 0 °C reste affiché normalement
- 🧪 Si les prévisions manquent : une ligne explique pourquoi (position absente, erreur, chargement)
- ⏳ Brancher les barres sur PIAF (pas de 5 min) : dépend du test Météo-France (unité mm ou kg/m²/s, durée de cumul)
- ⏳ Aperçu en direct dans l'écran de personnalisation ; thème clair/sombre manuel en plus du thème par widget

## Phase 5 ter — Affichage en cartes « A · Sections » (v0.5)
Retour du 08/11 : le rendu B était « très loin » des maquettes ; Mickaël demande de basculer sur **A** et d'activer directement les infos de la maquette.
- 🧪 Trois gabarits selon la hauteur du widget : **< 100 dp** format court (inchangé) · **100–270 dp** tableau de bord (v0.4 : héros + météo + bandeau station) · **≥ 270 dp** **sections**
- 🧪 Sections : en-tête (commune, date) · carte prévisions (icône, libellé, ressenti, max / min, heures avec maxi en couleur) · tuiles Open-Meteo (pluie, vent, précipitations, air, pollens, isotherme) · carte station (mesure en grand + « Ressenti · tendance horaire » + courbe 24 h + tuiles humidité, vent, pluie du jour, pression, UV, point de rosée)
- 🧪 Alerte pluie contextuelle : s'il pleut ou si de la pluie est prévue, la carte pluie (message + barres de 5 min) apparaît en haut ; sinon la tuile « Pluie · Aucune ≤ 6 h » suffit
- 🧪 Hauteur libre répartie entre les blocs (espaceurs élastiques) ; priorités : alerte → carte prévisions → tuiles station → tuiles Open-Meteo → heures → en-tête → lignes de tuiles en plus → mesure principale plus grande
- 🧪 Nouvelle info « Pollens (le plus élevé des six) » : max des six espèces de l'API air-quality
- 🧪 **Configuration préremplie** : nouveaux widgets et widgets déjà posés (une seule fois, thème et heure conservés) reçoivent min/max, ressenti, heures, message pluie, prochaine pluie, vent, précipitations, qualité de l'air, pollens, isotherme 0 °C ; station : humidité, vent, pluie du jour, pression, UV, point de rosée. Modifiable ensuite par « Reconfigurer »

## Phase 6 — Configuration
- ✅ Écran de connexion (clés + MAC, validation du format, test en direct)
- ✅ Écran de personnalisation par widget (thème, mesures station, infos Open-Meteo, ordre, options)
- ✅ « Reconfigurer » via appui long sur le widget
- ✅ Bouton « Ajouter à l'écran d'accueil »
- 🧪 Écran « Position » : position de la station, recherche de ville, altitude
- 🧪 Écran « Diagnostic » : état de chaque source (direct / historique 24 h / Open-Meteo / pluie à court terme AROME / air et pollens) + bouton « Tout actualiser »
- ⏳ Aperçu en direct du widget dans l'écran de personnalisation

## Phase 7 — Actualisation toutes les 2 min
- ✅ Chaîne d'alarmes exactes (2 / 5 / 10 / 15 min au choix)
- ✅ Demande de la permission « Alarmes et rappels » (Android 12+)
- ✅ Relance après redémarrage et mise à jour de l'app
- ✅ Filet de sécurité système toutes les 30 min
- 🧪 Rythme des sources : direct à chaque cycle, historique 24 h toutes les 15 min, Open-Meteo toutes les 10 min
- 🧪 Mesurer la régularité réelle (écran allumé / éteint / téléphone immobile)
- 🧪 Mesurer l'impact batterie sur 24 h
- ⏳ Plage de repos configurable (ex. pas d'actualisation 23h–6h)

## Phase 8 — Livraison
- ✅ Workflow GitHub Actions → APK téléchargeable
- ✅ Signature stable optionnelle via secrets GitHub (mises à jour sans désinstaller)
- ✅ APK publié en release « latest » à chaque build (téléchargement direct, sans zip) — v0.5.1
- ✅ v0.5.2 : « Il pleut » (fond bleu, message) seulement si la station est corroborée (débit ≥ 0,5 mm/h ou humidité ≥ 65 %) ; la barre « maintenant » du graphique suit la station ; ligne de diagnostic « Pluie station » dans les réglages
- ✅ v0.5.3 : correction d'altitude des températures prévues (−0,65 °C / 100 m entre le point de prévision et l'altitude saisie dans les réglages), flèche ⇄ sur le widget pour basculer corrigé / modèle
- ✅ v0.5.4 : bascule corrigé / modèle réparée (le widget relit désormais ses données dans la composition : une session Glance encore ouverte gardait l'ancien affichage), pastille ⇄ plus grande, interrupteur + compteur d'appuis dans les réglages
- ✅ v0.5.4 : nouvelles infos « Pluie aujourd'hui » (cumul jusqu'à minuit) et « Limite neige » (estimée : isotherme 0 °C − 300 m, au début et à la fin de la pluie), ajoutées aux widgets existants (config v3)
- ✅ v0.5.5 : la courbe de température est remplacée par le min / max du jour de la station (carte station et format large)
- ✅ v0.5.5 : analyse des sources de limite pluie / neige (`docs/limite-pluie-neige.md`) + bouton de test « Tester la limite pluie / neige (modèles) » dans Réglages → Diagnostic
- 🔲 À tester v0.5.5 : min / max affichés (valeurs cohérentes avec l'app Ecowitt), bouton de test limite pluie / neige : relever les lignes affichées
- ⏳ Limite pluie / neige : choisir la source (Open-Meteo `snowfall_height` selon le test, Météo-France BRA en saison, station en temps réel)
- 🔲 À tester v0.5.4 : altitude 685 m saisie, appui sur la pastille ⇄ (valeurs et libellé « 685 m » / « modèle » changent, compteur d'appuis dans les réglages), tuiles « Pluie aujourd'hui » et « Limite neige » un jour de pluie / de neige
- 🔲 À tester : clé stable active (secrets en place), installer v0.5.1 puis la suivante par-dessus sans désinstaller
- ✅ README (installation pas à pas)
- ⏳ Image d'aperçu dans le sélecteur de widgets

## Phase 9 — Idées pour la suite
- ⏳ Flèche de direction du vent
- ⏳ Notification « il commence à pleuvoir »
- ⏳ Données du réseau local de la console (mode sans cloud)
- ⛔ Version iOS (WidgetKit, nécessite un Mac et Xcode) : projet séparé à lancer si besoin

## Checklist de test sur téléphone (v0.2 + v0.3)
1. [ ] Installer l'APK **par-dessus** la v0.1 (même signature ? sinon désinstaller d'abord) puis ouvrir l'app
2. [ ] Réglages → « Enregistrer et tester » : message « position de la station détectée » (sinon, rechercher ta ville)
3. [ ] Réglages → Diagnostic → « Tout actualiser » : toutes les lignes passent à OK (noter précisément toute ligne en échec ; « Pluie à court terme (AROME) » peut être indisponible hors France/Europe centrale)
4. [ ] Ajouter / reconfigurer le widget : bloc Open-Meteo en haut, Ecowitt en bas
5. [ ] Courbe 24 h visible à côté de la température (widget assez large, température en 1re position)
6. [ ] Comparer min/max du jour et prévisions avec l'app Ecowitt et une autre source
7. [ ] Régler l'altitude de la station et vérifier que la température prévue est cohérente
8. [ ] Redimensionner le widget (petit → grand) : la mise en page s'adapte, rien n'est coupé
9. [ ] Couper le Wi-Fi/données : le widget garde les dernières valeurs + « hors ligne »
10. [ ] Pluie : fond bleuté + « En cours » en bleu ; température proche de 0 °C : badge de gel
11. [ ] Vérifier la régularité de l'actualisation (écran allumé, puis éteint 10 min) et la batterie sur 24 h
12. [ ] Reconfigurer le widget → section Prévisions : catégories dépliables, recherche, ajout / retrait / ordre des infos
13. [ ] Ajouter « Message pluie » : texte cohérent avec la météo réelle (pluie dans X min / pas de pluie), s'affiche en bandeau si le widget est assez haut
14. [ ] Ajouter une info « Qualité de l'air » et un pollen : valeurs plausibles (ligne « Air et pollens » dans Diagnostic) ; hors saison un pollen peut afficher « — »
15. [ ] Variable personnalisée : saisir `wet_bulb_temperature_2m` (horaire) → valeur affichée ; saisir un nom faux → message de refus dans Diagnostic, le widget garde les infos de base
16. [ ] Réglages → « Météo-France · pluie imminente (test) » : coller l'Application ID, « Enregistrer et tester » → ligne « Jeton : OK », puis AROME-PI et PIAF (noter toute ligne en échec ; si PIAF = « adresse introuvable », coller l'adresse GetCapabilities exacte copiée depuis le portail, sans jeton)
17. [ ] Réglages → Position : « Utiliser la position de ma station » (Ecowitt) ; sinon adresse précise ou coordonnées ; puis relancer le test Météo-France → 3 lignes « PIAF · HH:MM (+X min) » avec une valeur de pluie
18. [ ] **v0.4** — Widget 4×1 / 4×2 / 4×3 / 4×4 : héros lisible, rien de coupé, station toujours visible en bas ; plus le widget est haut, plus il montre de blocs (pluie, heures, infos, en-tête, courbe)
19. [ ] **v0.4** — Carte pluie : jour sec = ligne simple ; quand de la pluie est prévue (ou qu'il pleut) = carte avec barres ; comparer le message et les barres avec le radar
20. [ ] **v0.4** — Valeurs à zéro estompées (pluie du jour 0,0 mm, pollens 0) ; température 0 °C affichée normalement
21. [ ] **v0.5** — Grand widget : cartes prévisions / tuiles / station, rien de coupé, pas de grand vide ; infos de la maquette présentes (ressenti, heures, vent, précip., air, pollens, isotherme)
22. [ ] **v0.5** — Les valeurs « Open-Meteo » manquantes (« — ») se remplissent après la première actualisation (appui sur le widget) ; ligne « Air et pollens » OK dans Diagnostic
