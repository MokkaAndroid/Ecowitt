# Ecowidget — Roadmap

Légende : ✅ terminé · 🧪 écrit, **à tester** · ⏳ à faire · ⛔ hors périmètre

> État au 07/11/2026 : le code **compile** (build GitHub Actions réussi) mais **n'a pas encore été testé
> sur un téléphone**. Prochaine étape : installer l'APK et dérouler la checklist en bas de page.

## Phase 0 — Cadrage
- ✅ Station : Ecowitt HP2564 (console Wi-Fi → cloud ecowitt.net)
- ✅ Source de données : API cloud Ecowitt v3 (`/device/real_time`), HTTPS
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
- 🧪 Valider les noms de champs JSON avec **ta** HP2564 (bouton « Enregistrer et tester »)
- ✅ Cache de la dernière réponse (le widget reste affiché hors ligne)
- ✅ Messages d'erreur sans fuite des clés

## Phase 3 — Sécurité
- ✅ Clés stockées chiffrées (AES-256, Android Keystore)
- ✅ HTTPS uniquement, pas de trafic en clair, redirections désactivées
- ✅ Aucune clé dans les journaux ni les messages d'erreur
- ✅ Sauvegarde Android désactivée (les clés ne partent pas dans un backup)
- ✅ Récepteur d'alarme non exporté
- ⏳ Build « release » avec R8 (réduction/obfuscation) — après validation fonctionnelle

## Phase 4 — Widget
- ✅ 15 mesures disponibles (température, pluie en cours, humidité, vent, pression, UV…)
- ✅ Première mesure affichée en grand, les autres selon la place
- ✅ Mise en page adaptative (format court / format large, 1 ou 2 colonnes)
- ✅ 5 thèmes : Nuit, Océan, Aurore, Clair, Verre
- ✅ Heure de la mesure + indicateur « hors ligne » / « ancien »
- ✅ Appui sur le widget = actualisation immédiate
- 🧪 Rendu réel sur ton lanceur (Samsung One UI, Pixel…) et à plusieurs tailles

## Phase 5 — Configuration
- ✅ Écran de connexion (clés + MAC, validation du format, test en direct)
- ✅ Écran de personnalisation par widget (thème, mesures, ordre, options)
- ✅ « Reconfigurer » via appui long sur le widget
- ✅ Bouton « Ajouter à l'écran d'accueil »
- ⏳ Aperçu en direct du widget dans l'écran de personnalisation

## Phase 6 — Actualisation toutes les 2 min
- ✅ Chaîne d'alarmes exactes (2 / 5 / 10 / 15 min au choix)
- ✅ Demande de la permission « Alarmes et rappels » (Android 12+)
- ✅ Relance après redémarrage et mise à jour de l'app
- ✅ Filet de sécurité système toutes les 30 min
- 🧪 Mesurer la régularité réelle (écran allumé / éteint / téléphone immobile)
- 🧪 Mesurer l'impact batterie sur 24 h
- ⏳ Plage de repos configurable (ex. pas d'actualisation 23h–6h)

## Phase 7 — Livraison
- ✅ Workflow GitHub Actions → APK téléchargeable
- ✅ Signature stable optionnelle via secrets GitHub (mises à jour sans désinstaller)
- ✅ README (installation pas à pas)
- ⏳ Image d'aperçu dans le sélecteur de widgets

## Phase 8 — Idées pour la suite
- ⏳ Min/max du jour, tendance de pression, flèche de direction du vent
- ⏳ Notification « il commence à pleuvoir »
- ⏳ Données du réseau local de la console (mode sans cloud)
- ⛔ Version iOS (WidgetKit, nécessite un Mac et Xcode) : projet séparé à lancer si besoin

## Checklist de test sur téléphone
1. [ ] Installer l'APK, ouvrir l'app, saisir les clés → « Connexion réussie » avec la bonne température
2. [ ] Ajouter le widget, choisir les mesures → affichage correct
3. [ ] Comparer température / pluie / vent avec l'app Ecowitt officielle
4. [ ] Vérifier la mise à jour automatique (écran allumé, puis éteint 10 min)
5. [ ] Autoriser « Alarmes et rappels » et revérifier la régularité
6. [ ] Redimensionner le widget (petit → grand) : la mise en page s'adapte
7. [ ] Couper le Wi-Fi/données : le widget garde la dernière valeur + « hors ligne »
8. [ ] Redémarrer le téléphone : le widget reprend tout seul
9. [ ] Provoquer de la pluie (ou simuler) : « En cours » apparaît en bleu
