# Ecowidget

Widget Android pour station météo **Ecowitt** (testé en conception pour la HP2564), alimenté par le cloud ecowitt.net.
Mesures au choix (température extérieure, pluie en cours, humidité, vent, pression, UV…), 5 thèmes, actualisation jusqu'à toutes les 2 minutes.

## Obtenir l'APK (sans Android Studio)

1. Crée un dépôt GitHub **privé** et envoie-y le contenu de ce dossier (branche `main`).
2. Onglet **Actions** → workflow **Build APK** → il se lance au push (ou **Run workflow**).
3. Quand il est vert (3-5 min), ouvre l'exécution → **Artifacts** → télécharge `ecowidget-apk` → décompresse.
4. Copie `app-debug.apk` sur le téléphone et installe-le (autorise « Installer des applications inconnues » pour ton navigateur ou gestionnaire de fichiers).

> Chaque build CI utilise une clé de debug différente : pour installer une mise à jour **par-dessus** la version
> précédente, ajoute ta propre clé de signature (voir plus bas), sinon désinstalle d'abord l'ancienne version.

## Premier lancement

1. Sur [ecowitt.net](https://www.ecowitt.net) → profil → **API Keys** : crée une *Application Key* et une *API Key*.
2. Récupère l'**adresse MAC** de la console (WS View Plus → liste des appareils, ou ecowitt.net).
3. Ouvre Ecowidget → saisis les trois valeurs → **Enregistrer et tester**.
4. Bouton **Autoriser les alarmes exactes** (nécessaire pour tenir 2 min).
5. **Ajouter à l'écran d'accueil**, choisis le thème et les mesures. Appui long sur le widget → *Reconfigurer* pour les changer.

## Actualisation

- Intervalle réglable : 2 / 5 / 10 / 15 min. Un appui sur le widget force l'actualisation.
- Android peut espacer les mises à jour quand le téléphone est immobile, écran éteint (mode Doze).
- Inutile de descendre sous l'intervalle d'envoi de la console vers ecowitt.net (réglable dans WS View Plus).

## Signature stable (optionnel)

Génère une clé une fois : `keytool -genkeypair -v -keystore release.jks -alias ecowidget -keyalg RSA -keysize 2048 -validity 10000`
Puis, dans GitHub → Settings → Secrets and variables → Actions, crée :
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Ne commite jamais le fichier `.jks`.

## Sécurité

- Clés chiffrées sur le téléphone (AES-256 via Android Keystore), exclues des sauvegardes.
- HTTPS uniquement. L'API Ecowitt impose les clés dans l'URL : l'app ne les journalise jamais.
- Permissions : Internet, alarmes exactes, démarrage (pour relancer l'actualisation).

## Développement

Projet Gradle standard (Android Studio Ladybug+ ou `./gradlew assembleDebug`). Kotlin, Jetpack Glance, Compose Material 3.
Suivi du projet : voir [ROADMAP.md](ROADMAP.md).

Icônes : jeu « Feather » (licence MIT).
