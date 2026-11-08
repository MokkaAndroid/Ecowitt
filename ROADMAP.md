# Ecowidget — Roadmap

Légende : ✅ terminé · 🧪 écrit, **à tester** · ⏳ à faire · ⛔ hors périmètre

> État au 08/11/2026 : **v0.1** installée et validée sur téléphone (température, pluie, humidité OK).
> **v0.2** (Open-Meteo + courbe 24 h + couleurs dynamiques + tendances) : code écrit, compilation GitHub Actions en cours de vérification,
> **pas encore testée sur téléphone**. Voir la checklist en bas de page.

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
- ⏳ Qualité de l'air / pollens (API air-quality, Europe)
- ⏳ Alertes météo (vigilance) — non couvertes par Open-Meteo, source à étudier (Météo-France)

## Phase 4 — Sécurité
- ✅ Clés stockées chiffrées (AES-256, Android Keystore) ; position également chiffrée
- ✅ HTTPS uniquement, pas de trafic en clair, redirections désactivées
- ✅ Aucune clé dans les journaux ni les messages d'erreur
- ✅ Sauvegarde Android désactivée (les clés ne partent pas dans un backup)
- ✅ Récepteur d'alarme non exporté
- ⏳ Build « release » avec R8 (réduction/obfuscation) — après validation fonctionnelle

## Phase 5 — Widget
- ✅ 17 mesures Ecowitt (dont Mini/Maxi du jour) et 14 informations Open-Meteo au choix
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

## Phase 6 — Configuration
- ✅ Écran de connexion (clés + MAC, validation du format, test en direct)
- ✅ Écran de personnalisation par widget (thème, mesures station, infos Open-Meteo, ordre, options)
- ✅ « Reconfigurer » via appui long sur le widget
- ✅ Bouton « Ajouter à l'écran d'accueil »
- 🧪 Écran « Position » : position de la station, recherche de ville, altitude
- 🧪 Écran « Diagnostic » : état de chaque source (direct / historique 24 h / Open-Meteo) + bouton « Tout actualiser »
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
- ✅ README (installation pas à pas)
- ⏳ Image d'aperçu dans le sélecteur de widgets

## Phase 9 — Idées pour la suite
- ⏳ Flèche de direction du vent
- ⏳ Notification « il commence à pleuvoir »
- ⏳ Données du réseau local de la console (mode sans cloud)
- ⛔ Version iOS (WidgetKit, nécessite un Mac et Xcode) : projet séparé à lancer si besoin

## Checklist de test sur téléphone (v0.2)
1. [ ] Installer l'APK **par-dessus** la v0.1 (même signature ? sinon désinstaller d'abord) puis ouvrir l'app
2. [ ] Réglages → « Enregistrer et tester » : message « position de la station détectée » (sinon, rechercher ta ville)
3. [ ] Réglages → Diagnostic → « Tout actualiser » : les 3 lignes passent à OK (noter précisément toute ligne en échec)
4. [ ] Ajouter / reconfigurer le widget : bloc Open-Meteo en haut, Ecowitt en bas
5. [ ] Courbe 24 h visible à côté de la température (widget assez large, température en 1re position)
6. [ ] Comparer min/max du jour et prévisions avec l'app Ecowitt et une autre source
7. [ ] Régler l'altitude de la station et vérifier que la température prévue est cohérente
8. [ ] Redimensionner le widget (petit → grand) : la mise en page s'adapte, rien n'est coupé
9. [ ] Couper le Wi-Fi/données : le widget garde les dernières valeurs + « hors ligne »
10. [ ] Pluie : fond bleuté + « En cours » en bleu ; température proche de 0 °C : badge de gel
11. [ ] Vérifier la régularité de l'actualisation (écran allumé, puis éteint 10 min) et la batterie sur 24 h
