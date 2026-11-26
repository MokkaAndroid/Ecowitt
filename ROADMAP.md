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
- ✅ **v0.6.5 · Actualisation fiabilisée** : (1) une alarme tombée sans réseau (veille profonde Doze, mode avion) relance l'actualisation dès que le réseau revient (WorkManager, contrainte « réseau disponible », 4 nouvelles tentatives espacées) ; (2) tâche périodique de secours toutes les 15 min qui actualise et relance la chaîne d'alarmes si l'échéance est dépassée de plus de 10 min (alarme supprimée par le système, par exemple « nettoyage » MIUI) ; (3) Réglages › Actualisation : bouton « Ne pas restreindre la batterie » (exclut l'app de l'optimisation de batterie, donc du blocage réseau de Doze)
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
- ✅ Test des modèles sur le téléphone : ICON-D2 / EU / CH1 / CH2 couvrent la Savoie et s'accordent à 100–200 m près (voir `docs/limite-pluie-neige.md`)
- ✅ v0.5.6 : « Limite neige » utilise `snowfall_height` (Open-Meteo, modèle automatique), repli isotherme 0 °C − 300 m
- 🔲 À tester v0.5.6 : un jour de pluie, comparer « Limite neige » avec ce qui tombe réellement (voir le bulletin Météo-France du massif)
- ⏳ Option : limite officielle Météo-France (BRA, saison nov.–mai) à côté de la valeur modèle ; « chez toi : pluie / neige » depuis la station
- 🔲 À tester v0.5.4 : altitude 685 m saisie, appui sur la pastille ⇄ (valeurs et libellé « 685 m » / « modèle » changent, compteur d'appuis dans les réglages), tuiles « Pluie aujourd'hui » et « Limite neige » un jour de pluie / de neige
- 🔲 À tester : clé stable active (secrets en place), installer v0.5.1 puis la suivante par-dessus sans désinstaller
- ✅ README (installation pas à pas)
- ⏳ Image d'aperçu dans le sélecteur de widgets

## Phase 8 bis — Widget compact + détail au toucher (maquettes « H » et « W3 »)
Un réglage dans l'application, « Mode du widget », choisit entre trois variantes :
- **Mode 1 · Actuel** : le grand widget d'aujourd'hui (≈ 610 dp), sans interaction. Reste disponible.
- **Mode 2 · Compact + fiche plein écran** : tuiles 4 × 2 ; un appui ouvre un volet par-dessus l'écran d'accueil (maquettes H).
- **Mode 3 · Compact + détail dans le widget** : tuiles 4 × 2 ; un appui remplace le contenu du widget par le détail, sur place (maquettes W3). Un bouton ⤢ ouvre la fiche plein écran du mode 2.

Avancement :
- ✅ Maquettes mode 2 : widget 4 × 2 (7 tuiles + alerte), variante 4 × 3, 9 fiches détaillées
- ✅ Maquettes mode 3 : même widget 4 × 2, 7 détails sur place (17 pages avec onglets)
- ✅ Validation des maquettes par Mickaël (« Lance les 3 variantes »)
- ✅ Étape 0 · **v0.6.0** : réglage « Mode du widget » (Complet / Tuiles + page / Tuiles + détail) dans l'application, stocké dans `Storage`, relu par le widget via `WidgetRefresh.version` (changement de mode sans reposer le widget)
- ✅ Étape 1 · **v0.6.0** : widget compact à 7 tuiles (Température, Pluie, Limite neige, Vent, Humidité, Pression, Prévisions) ; mode 3 : détail dans le widget (onglets, flèche retour, ⤢ vers la fiche plein écran, retour automatique après 30 s par alarme) ; mode 2 : fiche plein écran `DetailActivity` (passage d'un sujet à l'autre, actualisation, thème du widget)
- ✅ Données · **v0.6.0** : prévisions 8 jours, séries horaires (ressenti, pluie, neige, limite neige, isotherme, vent, rafales, humidité, rosée, pression, probabilité de pluie), historique station étendu à 50 h avec vent, pluie et humidité (repli sur l'ancien historique si l'API refuse)
- ✅ Étape 2 · **v0.6.0** : les 7 sujets ont leurs fiches (19 onglets) ; graphiques dessinés en bitmap (courbes, barres pluie / neige / mélange hachuré selon la règle limite neige + température)
- 🔲 Tests sur téléphone : voir les points 23 à 30 de la checklist (tailles réelles 4×2 / 4×3, appuis, retour auto, historique vent / pluie station)
- ✅ **v0.6.1 · Recalage sur les maquettes** (la v0.6.0 avait été codée depuis un résumé écrit, sans rouvrir les maquettes : l'écart a été signalé par Mickaël) : en-tête « Lieu · heure » + pastille d'alerte (« Pluie 17 h 45 · neige 5 h », « Sec jusqu'à demain », « Il pleut ») + actualisation ; tuile Extérieur large (température colorée, tendance, max / min / ressenti) ; tuiles à sous-lignes (Prévision, Pluie « +4,6 prévus », Vent « raf. 31 · SO », Humidité « rosée », Pression ▼ « −3,4 / 3 h », Lim. neige teintée quand elle atteint ton altitude) ; rangée Demain / Coucher / Air / Intérieur
- ✅ **v0.6.1** : nouvelles fiches Soleil et UV, Air (indice européen + pollens), Intérieur ; la tuile Demain ouvre la fiche Température sur l'onglet « Demain » (remplacé en v0.6.2)
- ✅ **v0.6.2 · Fiches plein écran refaites à l'identique des maquettes « H »** (même constat : les fiches v0.6.0 / v0.6.1 avaient été codées « dans l'esprit » des maquettes). Nouveau moteur de fiches : modèle en blocs (`data/Sheet.kt`), contenu des 9 fiches (`data/SheetBuilder.kt`), rendu Compose (`ui/SheetScreen.kt`) avec les cotes des maquettes (poignée, pastille d'icône, bandeau de fiches, héros, pastille, cartes 20 dp, statistiques, légendes, cellules d'heures, échelle de l'air, segments de tendance…), palette sombre fixe. Graphiques dessinés en dp (`Plot`, même mécanique que les maquettes) et adaptés à la largeur réelle : température (min / max, 0 °C, prévu corrigé à l'altitude), pluie (cumul, barres hachurées, 60 min, 7 jours), limite neige (isotherme, ton altitude, bande de ce qui tombe chez toi), vent (rafales, direction), humidité (+ température / rosée, brouillard), pression (−24 h … +24 h), prévisions (24 h, 7 jours), soleil (arc, rayonnement), air (échelle, polluants, 24 h, pollens)
- ✅ **v0.6.2** : l'onglet Intérieur disparaît (comme dans les maquettes, la tuile Intérieur ouvre Humidité et la tuile Demain ouvre Prévisions) ; données ajoutées : UV et rayonnement horaires, ensoleillement, code météo journalier, PM2,5 / PM10 / ozone / NO₂
- ⚠️ v0.6.2 — non repris des maquettes faute de source de données : cartes « Les modèles, à 5 h » (comparaison des modèles de limite neige) et « Bulletin Météo-France · Vanoise » ; courbe « ciel dégagé » du rayonnement (Open-Meteo n'en fournit pas) : le rayonnement affiché est celui du modèle
- ✅ **v0.6.3 · Détail dans le widget (mode 3) refait à l'identique des maquettes « W3 »** : nouvelle `widget/W3Pages.kt` (15 pages dessinées en dp avec la même mécanique que les fiches : héros + lignes à droite, cellules d'heures, cumuls, 60 min, barres heure par heure, 7 jours, limite neige 24 h, vent, humidité / rosée, pression 48 h / tendance, prévisions). En-tête des maquettes : retour rond 28 dp, icône du sujet, onglets en pastilles (actif = teinte du sujet), ⤢ rond ; corps = carte sombre arrondie 16 dp, marge 8 dp. Onglets : Température Jour / Heures / Demain · Pluie Cumuls / 60 min / Heures / 7 jours · Limite neige 24 h · Vent Jour / Heures · Humidité Jour / Rosée · Pression 48 h / Tendance · Prévisions Heures / 7 jours. La tuile Demain ouvre Prévisions › 7 jours, la tuile Intérieur ouvre Humidité
- ⚠️ v0.6.3 — Soleil et Air n'ont pas de détail dans le widget (comme dans les maquettes) : un appui ouvre la fiche plein écran ; l'onglet « Modèles » de la limite neige est omis faute de données ; le détail demande un widget d'au moins ~150 dp de haut (4×3) ; le corps du détail garde la palette sombre fixe des maquettes quel que soit le thème
- ✅ **v0.6.4 · Pluie des 60 prochaines minutes** (signalé le 14 nov. : « de la pluie maintenant et plus rien ensuite » alors que les barres heure par heure annonçaient 1,1 mm entre 23 h et minuit). Deux défauts corrigés : (1) une valeur absente (`null`) du nowcast était lue comme « sec » ; elle est maintenant ignorée ; (2) seul AROME comptait : par créneau de 15 min on garde maintenant le plus pluvieux d'AROME et du modèle générique (celui des barres heure par heure), source affichée « AROME + modèle ». Réglages › diagnostic : nouvelle ligne « Pluie 15 min (mm) · AROME … · modèle … » (8 prochains créneaux) pour voir quelle source dit quoi. Cause exacte non vérifiée (API Open-Meteo inaccessible depuis l'environnement de build) : à confirmer avec cette ligne
- ✅ **v0.6.5** : actualisation fiabilisée (voir ci-dessus) ; la dernière rangée incomplète de la grille se répartit sur toute la largeur. (Le retrait des tuiles Humidité et Pression décidé ici était un contresens : annulé en v0.6.6.)
- ✅ **v0.6.6 · Humidité et Pression de retour + pluie colorée selon l'intensité** : (1) tuiles Humidité et Pression de nouveau dans la grille (7 en 4×3, 11 en 4×4, 7 en 4×2) avec leurs fiches et pages dans le widget ; (2) toute la partie « pluie » est colorée par intensité, avec une échelle adaptée à la durée : **5 min et 1 h = mm/h** (très faible < 0,5 · faible 0,5–2,5 · modérée 2,5–7,5 · forte 7,5–20 · très forte ≥ 20) ; **journée = mm sur 24 h** (très faible < 2 · faible 2–8 · modérée 8–20 · forte 20–40 · très forte ≥ 40) ; 5 teintes : bleu clair, bleu, bleu-violet, violet, rose ; la neige garde sa couleur (bleu foncé + flocon) et le mélange son hachuré ; (3) légende en dégradé (bornes + unité) sous chaque graphique de pluie, dans les fiches et dans le widget ; (4) mots « faible / modérée / forte » et teinte de la pastille calculés avec la même échelle ; (5) pas de 5 min : la hauteur des barres ne plafonne plus à 1 mm/h
- ✅ **v0.7.0 · Widget « Bento sombre »** (design choisi le 14 nov. parmi 3 propositions) : en modes Tuiles et Tuiles + détail, la grille est redessinée à l'identique de la maquette — nouvelle `widget/Bento.kt` (mise en page en dp + dessin en bitmap) et `widget/BentoWidget.kt` (zones cliquables posées par-dessus). (1) Extérieur = grande tuile 2×2 (température colorée, max / min / ressenti, flèche ▲▼) avec la pastille d'alerte en bas ; (2) Prévision et Pluie à droite (mini-barres de pluie colorées par intensité) ; (3) Vent (flèche de direction), Humidité (goutte remplie au taux réel), Pression (tendance) ; (4) Lim. neige, Demain, Coucher / Lever ; (5) Air (échelle de l'indice) et Intérieur ; (6) un appui sur une tuile ouvre sa fiche (ou son détail dans le widget), la pastille ouvre la Pluie des 60 min, le rond en haut à droite actualise ; (7) 4×4 = 5 rangées, 4×3 = 4 (Air et Intérieur retirés) ou 3 selon la hauteur réelle, 4×2 = une rangée de petites tuiles teintées (Temp., Pluie, Neige, Vent, Humidité, Pression, Prévision) ; (8) police DM Sans embarquée (licence OFL, `licenses/DMSans-OFL.txt`) ; (9) palette sombre fixe : les thèmes ne s'appliquent plus qu'au mode Complet. Anciennes tuiles natives Glance supprimées.
- ✅ **v0.7.1 · Écritures plus fines** (retour du 16 nov. : « trop épaisses, ça fait pas moderne ») : le widget Bento utilise maintenant la police variable DM Sans (`assets/fonts/DMSans-Variable.ttf`, axes graisse et taille optique) au lieu des 3 graisses fixes : grande température en 360 (au lieu de Bold), valeurs en 500, libellés en 440, sous-lignes en 400, pastille et lieu en 540–560 ; crénage resserré en proportion. Repli automatique sur les graisses fixes si le téléphone ne sait pas charger la police variable.
- ✅ **v0.7.2 · Prévisions refusées (HTTP 403)** (signalé le 16 nov. : « Prévisions Open-Meteo : échec · HTTP 403 » alors que la pluie AROME et l'air passent). La requête `v1/forecast` en modes Tuiles est la plus lourde (8 jours, ~17 variables horaires, ~10 quotidiennes) ; le code de l'appli n'a pas changé de ce côté depuis la v0.6.x, le refus vient donc du serveur. Correctifs : (1) sur un refus 403 / 413 / 414 / 431, les prévisions sont redemandées en plusieurs requêtes de 6 variables (la première porte les variables de base), puis fusionnées ; on reste en mode découpé 1 h avant de retenter la requête complète ; une requête annexe refusée donne des prévisions partielles au lieu de rien ; (2) le diagnostic affiche maintenant la longueur de la requête et un extrait de la réponse du serveur (page de pare-feu, par exemple) ; (3) ligne d'information « requête complète refusée, prévisions demandées en plusieurs fois » quand le mode découpé est actif.
- ✅ **v0.7.3 · Variable refusée isolée** : le diagnostic de la v0.7.2 a montré qu'une seule des 3 requêtes découpées est refusée, par le serveur nginx d'Open-Meteo (« 403 Forbidden nginx », requête de 303 caractères) : ce n'est donc ni la taille ni le débit, mais une variable précise de ce lot. Les prévisions sont maintenant demandées en requêtes de base (indispensables) puis par lots de 6 variables ; quand un lot est refusé (403 / 400), ses variables sont essayées une par une, la ou les coupables sont écartées pendant 1 h et nommées dans le diagnostic (« variables refusées par le serveur, ignorées : … »). Le reste des prévisions s'affiche normalement.
- 🧪 **v0.8.0 · Widget Bento adaptatif** (designs « v2 » validés les 21–22 nov. + scénario orage) : le widget choisit ses tuiles selon la situation, avec 7 scénarios — **Sec** (Vent, UV, Humidité, Pression, Air, Pollens ou Demain, Intérieur, Semaine) · **Pluie annoncée** (barres Pluie 12 h + courbe de probabilité, Vent, Humidité, Pression, Air, Intérieur) · **Pluie imminente / en cours** (Intensité en anneau, Pluie 60 min, Pluie 12 h, Humidité + Pression) · **Hiver** neige (Neige 24 h, Lim. neige en jauge verticale, Gel, Vent, Humidité, Pression) ou gel seul · **Chaleur** (UV large avec échelle, Pollens, Air / ozone, Humidité, Pression, Demain) · **Station hors ligne** (bandeau « Dernière mesure… », Vent, UV, Humidité, Pression) · **Orage** (compte à rebours en anneau, Pluie 60 min, Rafales 12 h, Humidité, Pression). Règles : jamais de lever / coucher de soleil ; aucune tuile « pluie » sans pluie dans les 12 h ; pas de limite pluie / neige en été ; **Humidité et Pression toujours visibles** (en duo compact quand la place manque) ; hystérésis pour éviter de passer d'un scénario à l'autre à chaque actualisation ; 3, 4 ou 5 rangées selon la hauteur, bandeau (héros bas + une rangée) vers 4×2,5, petites tuiles en 4×2. Nouvelles tuiles : héros avec courbe −12 h / +12 h (mesure pleine, prévision pointillée) et Ressenti, Pression avec mini-courbe, UV / Air / Intérieur en anneaux chiffrés, Pollens en colonnes, Semaine, Rafales. Code : `BentoModel` (données des tuiles) · `BentoData` (extraction depuis les mesures et prévisions) · `BentoPlanner` (choix et placement) · `BentoTiles` (dessin) · `BentoGfx` (outils de dessin et palette) · `Bento` (mise en page + rendu) · `BentoWidget` (zones cliquables par tuile). Nouvelles icônes `ic_wifi_off`, `ic_flake`.
- 🧪 **v0.8.1 · Comparaison des modèles aux mesures** (demande du 25 nov. : quel modèle pour quelle prévision ? AROME vs ICON-D2) : Réglages › Diagnostic › « Comparer les modèles aux mesures ». Les prévisions passées de AROME HD, ICON-D2, ICON-CH1, ICON-EU et du modèle automatique (API Open-Meteo « Previous Runs », dernier run et prévu la veille) sont comparées à l'historique Ecowitt 30 min sur ~6 jours : cumul de pluie, détection, fausses alertes, score (heures de pluie ≥ 0,2 mm), biais et écart moyen de température, puis le meilleur modèle pour la pluie et la température. But : décider ensuite, mesures à l'appui, quel modèle alimente quelle prévision (pluie 0–12 h, pluie 12–48 h, limite de neige, température).
- 🧪 **v0.8.2 · Profondeur de la comparaison réglable** : choix 7 / 14 / 30 / 60 jours (puces au-dessus du bouton). L'historique Ecowitt est lu par tranches de 6 jours (plage max de l'API en 30 min), avec 1,1 s de pause entre deux requêtes ; si l'historique s'arrête avant, la période réellement comparée est indiquée. Résultat du 25/11 (6,5 j, 63 h de pluie) : ICON-D2 meilleur en température (1,3°) et en score pluie (65 %), Auto ≈ ICON-D2 à court terme, AROME HD trop chaud (+2,2°) mais peu de fausses alertes (12 %) et pluie sous-estimée (−35 %), ICON-CH1 sans archive « veille ».
- 🧪 **v0.9.0 · Comparaison par échéance** (demande du 26 nov.) : Réglages › Diagnostic › « Comparer par échéance ». Pluie (score à ±1 h et strict, quantité prévue ÷ mesurée) et température (écart moyen, biais) classées par modèle à +1–2 h, +3–4 h, +5–6 h, +11–12 h (API Open-Meteo « Single Runs » : AROME HD, ICON-D2, ICON-CH1, 4 runs/jour sur 30 j max) puis J+1 à J+5 (API « Previous Runs » : AROME, ICON-D2, ICON-EU, ARPEGE Europe, ECMWF IFS, Auto). 3 requêtes en parallèle, ~400 requêtes, plusieurs minutes. Résultat 60 j (26/11) : ICON-D2 meilleur à J+1 en pluie (50 %) et température (1,4°) ; AROME 35 % de fausses alertes, pas « prudent ».
- ⏳ Choix du modèle par type de prévision (après lecture des résultats de la comparaison) ; réglage « modèle de prévision »
- ✅ **Design adaptatif — propositions v2 (21 nov.)** : après retour (« trop de scénarios, le vent fort ne sert à rien, aucun lever/coucher de soleil »), planche réduite à 6 scénarios en 4×4 (sec, pluie annoncée, pluie imminente/en cours, hiver neige + gel, été chaleur, station hors ligne), leur version bandeau 4×2 et un tableau « quoi afficher, quand ». Lever et coucher de soleil supprimés partout ; Humidité et Pression toujours visibles (pression avec sa courbe 24 h, duo en 4×2) ; jauges chiffrées (UV, air, intérieur, intensité de pluie). ⏳ Implémentation (planificateur de tuiles + nouvelles tuiles) après validation des scénarios.
- ⏳ **Étape suivante** : tests sur téléphone de v0.6.2 / v0.6.3, puis intégration PIAF (Météo-France) et choix des tuiles par appui long
- ⏳ Étape 3 : appui long = choix et ordre des tuiles ; comparaison des modèles pour la limite neige (onglet « Modèles ») ; glisser d'une fiche à l'autre dans la fiche plein écran ; fiche Intérieur avec humidité intérieure
- ⚠️ Tailles réelles : une case de lanceur fait ≈ 70 dp, donc hauteur ≈ 70 × n − 30 dp (4×2 ≈ 110 dp, 4×3 ≈ 180 dp, 4×4 ≈ 250 dp). À partir d'environ 160 dp de haut : mise en page des maquettes (4×3 : en-tête + 7 tuiles sur 2 rangées ; 4×4 et plus : 11 tuiles sur 3 rangées). En dessous (4×2) : en-tête + une seule rangée de petites tuiles, car la grille des maquettes (≈ 190 dp) n'y tient pas. Le détail dans le widget (mode 3) demande ≥ 100 dp de haut ; en dessous, un appui ouvre la fiche plein écran
- ⚠️ Mode 3 : pas de lecture au doigt sur les courbes, une page à la fois, taille du widget fixée par le lanceur ; limite de 10 enfants par ligne / colonne dans Glance et poids total des bitmaps à surveiller. Le retour automatique repose sur une alarme : sans la permission « Alarmes et rappels », il peut tarder (le widget revient alors aux tuiles à la prochaine mise à jour)

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
23. [ ] **v0.6.0** — Réglages → « Mode du widget » : passer à « Tuiles + page » ; le widget affiche l'en-tête (météo, température, lieu, heure) et les tuiles, valeurs cohérentes avec l'ancien widget
24. [ ] **v0.6.0** — Tuiles : en 4×2, une rangée de 5 tuiles ; en 4×3 ou plus, 7 tuiles sur deux rangées ; rien de coupé ; un appui sur l'en-tête ou le fond actualise
25. [ ] **v0.6.0** — « Tuiles + page » : un appui sur une tuile ouvre la fiche plein écran sur le bon sujet ; onglets, valeurs et graphique lisibles ; le bandeau d'icônes change de sujet ; ↻ actualise
26. [ ] **v0.6.0** — « Tuiles + détail » (widget ≥ 4×3) : un appui affiche le détail dans le widget ; onglets cliquables, flèche retour, ⤢ ouvre la fiche plein écran sur le même onglet ; retour automatique aux tuiles ≈ 30 s après le dernier appui
27. [ ] **v0.6.0** — Fiche Pluie : onglet « 60 min » cohérent avec le radar ; onglet « Heures » avec barres bleues (pluie), claires (neige), hachurées (mélange) ; « 7 j » plausible
28. [ ] **v0.6.0** — Fiche Limite neige : courbe de la limite sur 24 h avec le trait « chez toi 685 m » ; onglet « Iso 0 °C »
29. [ ] **v0.6.0** — Fiches Vent / Humidité / Pression : courbes de la station sur 24 à 48 h (si absentes : message « Pas encore d'historique », puis Diagnostic pour voir la ligne Historique) ; prévisions en pointillés
30. [ ] **v0.6.0** — Revenir au mode « Complet » : le grand widget d'avant s'affiche, rien n'a changé
31. [ ] **v0.6.1** — Widget en 4×3 : comparer à la maquette « H · Widget 4×3 » : en-tête (lieu · heure, pastille, ↻), tuile Extérieur large, 2 rangées de tuiles avec sous-lignes ; en 4×4 : rangée Demain / Coucher / Air / Intérieur en plus
32. [ ] **v0.6.1** — Pastille d'alerte : « Pluie hh h mm » cohérente avec le radar quand de la pluie est attendue dans la journée, « Sec jusqu'à demain » en vert sinon ; « Il pleut » pendant la pluie
33. [ ] **v0.6.1** — Tuile « Lim. neige » teintée quand la limite atteint ton altitude ; tuiles Demain, Coucher, Air, Intérieur ouvrent leurs fiches (depuis v0.6.2 : Prévisions, Soleil et UV, Air et pollens, Humidité)
34. [ ] **v0.6.1** — Widget en 4×2 : en-tête + une rangée de petites tuiles, rien de coupé ; si la hauteur est entre 4×2 et 4×3 (≈ 160 dp), la grille des maquettes peut apparaître, vérifier qu'elle ne déborde pas
35. [ ] **v0.6.2** — Ouvrir chaque fiche (appui sur une tuile) et la comparer à la maquette correspondante (Fiche-Temperature … Fiche-Air) : poignée, icône ronde, titre + « Station Ecowitt · mesuré à hh:mm », bouton ✕, bandeau de fiches (précédente · ouverte · suivantes), héros, pastille, cartes, légendes ; rien de coupé ni de décalé
36. [ ] **v0.6.2** — Fiche Température : min / max du jour avec l'heure, courbe depuis minuit (mesuré plein, prévu pointillé, ligne 0 °C), « Prévisions corrigées pour 685 m · modèle : N m » (si l'altitude est renseignée), 6 cellules d'heures, ligne « Demain »
37. [ ] **v0.6.2** — Fiches Pluie et Limite neige : cartes de cumuls, barres heure par heure (bleu = pluie, hachuré = pluie ou neige possible, bleu foncé + flocon = neige qui tient), ligne « lim. » sous les barres ; courbe de la limite, trait orange (isotherme 0 °C), trait vert d'eau (ton altitude), bande de ce qui tombe chez toi
38. [ ] **v0.6.2** — Fiches Vent / Humidité / Pression : rafales + flèches de direction + boussole ; courbe d'humidité puis température / rosée avec zone « brouillard » ; courbe −24 h … +24 h avec le minimum prévu et les 5 segments de tendance
39. [ ] **v0.6.2** — Fiches Prévisions / Soleil / Air : phrase « Couvert, pluie ce soir », 8 cellules de 3 h, 7 jours avec barre min–max ; arc du soleil, UV et rayonnement (modèle) ; indice européen, polluants, 24 h, pollens. Si une valeur manque, un « — » apparaît à la place (pas de plantage)
40. [ ] **v0.6.3** — Widget « Tuiles + détail » (≥ 4×3) : appui sur une tuile → en-tête (retour rond, icône du sujet, onglets, ⤢) et carte sombre, comme les maquettes W3 ; chaque onglet (Température Jour / Heures / Demain, Pluie ×4, Neige 24 h, Vent ×2, Humidité ×2, Pression ×2, Prévisions ×2) comparé à la page correspondante
41. [ ] **v0.6.3** — Tuiles Soleil et Air (et widget trop bas) : l'appui ouvre la fiche plein écran ; ⤢ ouvre la fiche du même sujet ; retour automatique aux tuiles après 30 s
42. [ ] **v0.6.4** — Pendant une pluie : la page « 60 min » doit rester cohérente avec les barres « heure par heure » (pluie annoncée dans l'heure suivante = barres bleues, plus de « fin de pluie » immédiate) ; Réglages › diagnostic : ligne « Pluie 15 min (mm) · AROME … · modèle … » (noter les deux séries si un écart persiste)
43. [ ] **v0.6.6** — Grille : tuiles Humidité (« rosée x° ») et Pression (hPa, ▲▼, « ± x / 3 h ») bien présentes (4×3 : 7 tuiles ; 4×4 : 11) ; un appui ouvre leur détail dans le widget (Humidité Jour / Rosée, Pression 48 h / Tendance) ou la fiche plein écran
44. [ ] **v0.6.5** — Réglages › Actualisation : bouton « Ne pas restreindre la batterie » (Android demande confirmation) puis la ligne « Batterie : l'application n'est pas restreinte » ; Xiaomi / HyperOS : Paramètres › Applications › Ecowidget › Économiseur de batterie › « Aucune restriction » et Démarrage automatique activé
45. [ ] **v0.6.5** — Actualisation automatique : écran éteint 30 min puis réveil → les mesures datent de moins de ~15 min ; mode avion 5 min puis retour → mise à jour dans la minute qui suit le retour du réseau
46. [ ] **v0.6.6** — Pluie colorée : fiche Pluie et neige et page Pluie du widget : barres heure par heure et pas de 5 min teintées selon mm/h, barres des 7 jours teintées selon mm/jour (échelles différentes, légende en dégradé sous chaque graphique, unité mm/h ou mm/jour) ; pastille « Pluie faible / modérée / forte dès … » et titre « Pluie … dans … » de la même teinte ; neige = bleu foncé + flocon, mélange = hachuré
47. [ ] **v0.7.0** — Widget 4×4 en modes Tuiles : fond sombre arrondi, grille Bento identique à la maquette (héros 2×2 + Prévision + Pluie / Vent, Humidité, Pression / Lim. neige, Demain, Coucher / Air, Intérieur) ; police DM Sans ; rien de coupé ni de superposé
48. [ ] **v0.7.0** — Un appui sur chaque tuile ouvre bien la fiche associée (ou le détail dans le widget en mode Tuiles + détail) ; la pastille sous la température ouvre la Pluie « 60 min » ; le rond en haut à droite actualise
49. [ ] **v0.7.0** — Widget 4×3 puis 4×2 : 4 ou 3 rangées de tuiles, puis une seule rangée de petites tuiles teintées avec la pastille dans l'en-tête ; texte lisible, aucun appui perdu entre deux tuiles
50. [ ] **v0.7.0** — Pluie : mini-barres de la tuile Pluie et pastille teintées selon l'intensité ; pendant une pluie le fond passe au bleu ; Vent : flèche dans le bon sens ; Humidité : goutte remplie au bon niveau
51. [ ] **v0.7.1** — Widget Bento : écritures fines et modernes (grande température légère, valeurs en semi-gras discret, libellés lisibles) ; vérifier la lisibilité en plein soleil et sur les petites tuiles du 4×2
52. [ ] **v0.7.2** — Réglages › Diagnostic : « Prévisions Open-Meteo : OK » (éventuellement suivi de « requête complète refusée… ») au lieu de « échec · HTTP 403 » ; tuiles Prévision, Lim. neige, Demain, Coucher de nouveau remplies ; en cas d'échec persistant, relever la ligne complète (longueur de requête + extrait)
53. [ ] **v0.7.3** — Diagnostic : « Prévisions Open-Meteo : OK » avec la liste des variables refusées (à me communiquer pour que je les remplace) ; tuiles Prévision, Lim. neige, Demain, Coucher remplies
54. [ ] **v0.8.0** — Widget 4×4, temps sec : héros avec courbe −12 h / +12 h, tuiles Vent, UV, Humidité, Pression, Air, Pollens ou Demain, Intérieur, Semaine ; **aucune** tuile pluie, aucun lever / coucher
55. [ ] **v0.8.0** — Pluie annoncée dans les 12 h : tuile « Pluie · 12 h » (barres colorées + pointillés de probabilité, Début / Cumul / Fin) ; pluie qui commence : tuile Intensité (anneau mm/h), Pluie 60 min, pastille d'en-tête qui ouvre la fiche Pluie
56. [ ] **v0.8.0** — Hiver (neige prévue) : Neige 24 h, jauge de limite de neige avec ton altitude, Gel ; en été (> 25 °C) : UV large, Pollens, pas de limite pluie / neige
57. [ ] **v0.8.0** — **Orage** : tuile Orage (compte à rebours en minutes), Pluie 60 min en violet, Rafales 12 h (4×4 et plus) ; en 4×3 Humidité + Pression en duo compact. Dire si l'orage est bien détecté (code météo orage 95–99 dans les 2 h, ou pluie ≥ 7,5 mm/h avec rafales ≥ 40 km/h) et si le basculement vers ce scénario se fait ni trop tôt ni trop tard
58. [ ] **v0.8.0** — Station hors ligne (≥ 30 min sans mesure) : bandeau « Dernière mesure hh:mm · il y a … » ; retour au scénario normal à la prochaine mesure
59. [ ] **v0.8.0** — **Humidité et Pression toujours visibles** dans tous les scénarios et toutes les tailles (4×3, 4×4, 4×5) ; textes sans coupure ; appui sur chaque tuile → bonne fiche ; ↻ actualise
60. [ ] **v0.8.0** — Widget 4×2 (bandeau) et 4×2 bas (petites tuiles) : lisible, rien de coupé ; le scénario ne bascule pas à chaque actualisation (hystérésis)
61. [ ] **v0.8.1** — Réglages › Diagnostic › « Comparer les modèles aux mesures » : lignes par modèle (dernier run / veille) avec pluie, détection, fausses alertes, score, température ; m'envoyer le résultat complet pour choisir les modèles. Si « pluie indisponible » : l'historique Ecowitt ne renvoie pas la pluie (groupe rainfall / rainfall_piezo)
62. [ ] **v0.8.2** — Comparaison sur 30 j (puis 60 j) : la ligne « Période » indique la durée réellement obtenue ; durée du test < 1 min ; résultats cohérents avec ceux sur 7 j
63. [ ] **v0.9.0** — « Comparer par échéance » : la progression s'affiche (x / N requêtes) ; résultat en quelques minutes avec, par échéance, le classement pluie et température ; m'envoyer le texte complet
