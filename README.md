# Ecowidget

Widget Android pour station météo **Ecowitt** (conçu pour la HP2564), alimenté par le cloud ecowitt.net, avec les prévisions **Open-Meteo**.

- **En bas** : les mesures de ta station (température, pluie en cours, humidité, vent, pression, UV… au choix), courbe des 24 h, min/max du jour, tendances ▲▼.
- **En haut** : la météo du moment et les prévisions Open-Meteo, au choix parmi ≈ 85 infos classées par catégorie (température, vent, précipitations, ciel, pression, soleil & UV, orage, sol, astro, qualité de l'air, pollens) + n'importe quelle variable Open-Meteo saisie à la main ; message « pluie imminente » (modèle AROME France).
- Couleurs dynamiques (température, fond bleuté sous la pluie, badge de gel), 5 thèmes, actualisation jusqu'à toutes les 2 minutes.

## Obtenir l'APK (sans Android Studio)

1. Crée un dépôt GitHub **privé** et envoie-y le contenu de ce dossier (branche `main`).
2. Onglet **Actions** → workflow **Build APK** → il se lance au push (ou **Run workflow**).
3. Quand il est vert (3-5 min), ouvre **Releases** → **Dernière version** → télécharge `Ecowidget.apk` (lien direct, sans zip : `https://github.com/MokkaAndroid/Ecowitt/releases/download/latest/Ecowidget.apk`). L'artefact `ecowidget-apk` (zip) reste disponible dans l'exécution du workflow.
4. Ouvre l'APK sur le téléphone et installe-le (autorise « Installer des applications inconnues » pour ton navigateur ou gestionnaire de fichiers).

> Chaque build CI utilise une clé de debug différente : pour installer une mise à jour **par-dessus** la version
> précédente, ajoute ta propre clé de signature (voir plus bas), sinon désinstalle d'abord l'ancienne version.

## Premier lancement

1. Sur [ecowitt.net](https://www.ecowitt.net) → profil → **API Keys** : crée une *Application Key* et une *API Key*.
2. Récupère l'**adresse MAC** de la console (WS View Plus → liste des appareils, ou ecowitt.net).
3. Ouvre Ecowidget → saisis les trois valeurs → **Enregistrer et tester**.
4. La position de la station est récupérée automatiquement pour Open-Meteo (sinon : *Position pour les prévisions* → recherche d'une ville). Renseigne aussi l'**altitude** de chez toi : la température prévue est corrigée de −0,65 °C par 100 m par rapport au point de prévision, et la flèche ⇄ de la carte prévisions bascule entre valeur corrigée et valeur du modèle (un interrupteur équivalent existe dans les réglages).
   Les tuiles « Pluie aujourd'hui » et « Limite neige » indiquent la pluie attendue d'ici minuit et la limite pluie / neige : cette dernière est une **estimation** (isotherme 0 °C − 300 m, arrondie à 100 m) ; Open-Meteo ne fournit pas de limite pluie / neige horaire fiable sur les Alpes.
5. Bouton **Autoriser les alarmes exactes** (nécessaire pour tenir 2 min).
6. **Ajouter à l'écran d'accueil**, choisis le thème, les mesures de la station et les informations Open-Meteo. Appui long sur le widget → *Reconfigurer* pour les changer.
7. En cas de doute sur une donnée : section **Diagnostic** de l'app (état de chaque source) + bouton *Tout actualiser maintenant*.

## Modes du widget
Dans l'application, **Mode du widget** choisit la présentation (réglage commun à tous les widgets) :
- **Complet** : le grand widget (mesures, prévisions, pluie, heures), un appui actualise ;
- **Tuiles + page** : en-tête et 7 tuiles (température, pluie, limite neige, vent, humidité, pression, prévisions) ; un appui ouvre une fiche détaillée en plein écran ;
- **Tuiles + détail** : mêmes tuiles ; un appui affiche le détail directement dans le widget (onglets, retour, ⤢ pour la fiche plein écran), retour automatique aux tuiles après 30 s. Demande un widget d'au moins 4×3 cases ; en dessous, un appui ouvre la fiche plein écran.

## Actualisation

- Intervalle réglable : 2 / 5 / 10 / 15 min. Un appui sur le widget force l'actualisation.
- Android peut espacer les mises à jour quand le téléphone est immobile, écran éteint (mode Doze).
- Mesures en direct : à chaque cycle · historique 24 h (courbe, min/max, tendances) : toutes les 15 min · prévisions et pluie à court terme Open-Meteo : toutes les 10 min · air et pollens : toutes les 60 min.
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
- Open-Meteo ne reçoit que des coordonnées arrondies à ~1 km (et l'altitude si tu la renseignes) : jamais tes clés Ecowitt.

## Sources de données

- Mesures : cloud Ecowitt (tes propres clés API).
- Prévisions : [Open-Meteo.com](https://open-meteo.com/) — *Weather data by Open-Meteo.com*, licence [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
  gratuit pour un usage non commercial (< 10 000 appels/jour ; l'app en fait environ 150 par jour et par téléphone).

## Développement

Projet Gradle standard (Android Studio Ladybug+ ou `./gradlew assembleDebug`). Kotlin, Jetpack Glance, Compose Material 3.
Suivi du projet : voir [ROADMAP.md](ROADMAP.md).

Icônes : jeu « Feather » (licence MIT).
