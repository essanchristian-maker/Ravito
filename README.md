# Ravito

Rapport journalier des transactions de ravitaillement mobile money.

Application Android native (Kotlin, Jetpack Compose, Room chiffrée par SQLCipher) qui lit les SMS de confirmation d'envoi Orange Money, MTN MoMo, Moov Money et Wave, les range dans un historique et prépare le rapport du soir à coller dans WhatsApp. Tout fonctionne sur le téléphone, sans serveur ni Internet.

## Fonctions

| Écran | Contenu |
| --- | --- |
| Démarrage | Explication, autorisations (SMS, notifications, contacts), choix des opérateurs, création du code PIN, économie de batterie, première lecture des SMS (30 jours) |
| Historique | Envois groupés par jour, total et nombre d'envois du jour par opérateur, filtres date / opérateur, recherche (numéro, nom, référence), détail avec SMS d'origine, « Exclure du rapport », correction, saisie manuelle, liste « À vérifier », bouton Synchroniser |
| Rapport | Choix de la date, texte généré selon le modèle, Modifier, Copier, Partager sur WhatsApp, alerte si des SMS n'ont pas été reconnus |
| Paramètres | Opérateurs actifs et SIM, expéditeurs supplémentaires, modèle de rapport avec aperçu, période du rapport, frais dans le total, soldes, carnet des marchands (depuis les contacts), rappel du soir, PIN, empreinte, export CSV (Excel), état de l'arrière-plan |

Lecture des SMS :
- **en direct** grâce à un récepteur de SMS entrants ;
- **en rattrapage** en relisant la boîte de réception à chaque ouverture, au bouton Synchroniser et toutes les heures (WorkManager) ;
- **pour Wave**, par lecture de la notification de l'application Wave (accès aux notifications à accorder).

Pas de doublon : la référence de transaction sert d'identifiant ; sans référence, c'est une empreinte du texte qui sert.

Sécurité :
- code PIN haché avec PBKDF2, et attente imposée après 5 erreurs ;
- empreinte digitale en option ;
- base chiffrée par SQLCipher, avec une clé protégée par Android Keystore ;
- captures d'écran bloquées et aucune sauvegarde cloud ;
- lecture seule : l'application n'envoie aucun SMS et ne fait aucune transaction.

## Structure

```
app/src/main/java/com/ravito/app/
  domain/    SmsParser (règles de lecture), ReportGenerator, Operator  ← Kotlin pur, testé
  data/      Room (entités, DAO, base chiffrée), SettingsStore, RavitoRepository (traitement, synchro, export)
  sms/       SmsReceiver (SMS entrants)
  notif/     WaveNotificationListener, rappel du soir
  work/      Synchro horaire, rappel, redémarrage
  security/  PinManager
  ui/        Écrans Compose (démarrage, verrouillage, historique, rapport, paramètres)
app/src/test/  Tests du parseur et du rapport
```

## Obtenir l'APK

### Avec GitHub Actions (recommandé)

Chaque `git push` sur `main` lance [.github/workflows/android.yml](.github/workflows/android.yml). Le workflow exécute les tests, construit l'APK, puis le publie dans **Actions → le dernier run → Artifacts → ravito-apk-N**.

**Important : signer avec une clé fixe.** Sans clé, chaque build reçoit une clé de debug différente. Android refuse alors la mise à jour, et il faut désinstaller l'application, donc perdre l'historique. Créez la clé une seule fois et gardez-la précieusement :

```bash
keytool -genkeypair -v -keystore ravito.jks -alias ravito -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 ravito.jks > ravito.jks.b64
```

Dans GitHub → Settings → Secrets and variables → Actions, ajoutez :
`RAVITO_KEYSTORE_BASE64` (contenu de `ravito.jks.b64`), `RAVITO_KEYSTORE_PASSWORD`, `RAVITO_KEY_ALIAS` (`ravito`), `RAVITO_KEY_PASSWORD`.

### En local (Android Studio ou ligne de commande)

Prérequis : JDK 17 et Android SDK 35. Android Studio les installe.

```bash
./gradlew testDebugUnitTest      # tests de lecture des SMS et du rapport
./gradlew assembleRelease        # APK : app/build/outputs/apk/release/
```

Pour signer en local, créez un fichier `keystore.properties` à la racine. Il est ignoré par git.

```
storeFile=/chemin/vers/ravito.jks
storePassword=...
keyAlias=ravito
keyPassword=...
```

## Installer sur le téléphone

1. Copier l'APK sur le téléphone (WhatsApp, câble ou Drive), puis l'ouvrir et autoriser « Installer des applications inconnues ».
2. Au premier lancement, suivre l'écran de démarrage.
3. **Android 13 et plus :** si les autorisations SMS ou notifications sont grisées (« paramètre restreint »), ouvrir Paramètres → Applications → Ravito → menu ⋮ → **Autoriser les paramètres restreints**, puis revenir dans Ravito.
4. Tecno, Infinix, itel et Xiaomi : retirer Ravito de l'économie de batterie et activer le démarrage automatique. Les deux sont accessibles depuis Paramètres → Fonctionnement en arrière-plan.

## Adapter la lecture aux vrais SMS

Les formats de SMS intégrés reprennent l'exemple du cahier des charges et les tournures courantes. Pour chaque vrai SMS reçu, numéros masqués :

1. Ajouter un test dans [SmsParserTest.kt](app/src/test/java/com/ravito/app/domain/SmsParserTest.kt).
2. Si le test échoue, ajuster les mots-clés ou motifs dans [SmsParser.kt](app/src/main/java/com/ravito/app/domain/SmsParser.kt). Ils sont classés par catégorie : argent reçu, envoi, dépôt, échec, code, promo.
3. Si l'opérateur utilise un autre nom d'expéditeur, l'ajouter dans Paramètres → Opérateurs → Expéditeurs, sans recompiler. On peut aussi l'ajouter dans [Operator.kt](app/src/main/java/com/ravito/app/domain/Operator.kt).

Un SMS d'opérateur au format inconnu n'est jamais perdu. Il est rangé dans « À vérifier », d'où le marchand l'ajoute en un geste avec les champs déjà remplis.

## Modèle de rapport

Le modèle se modifie dans Paramètres → Modèle de rapport. Les zones disponibles sont :
`{date}`, `{jour}`, `{periode}`, `{operateurs}`, `{liste}`, `{total}`, `{nb}`, `{nb_envois}`, `{frais}`, `{soldes}`, `{nb_echecs}`.
Il existe aussi des zones par opérateur : `{orange_liste}`, `{orange_total}`, `{orange_nb}`, `{orange_solde}` (de même avec `mtn`, `moov` et `wave`).

Modèle par défaut :

```
RAPPORT DU {date}

{operateurs}

TOTAL : {nb_envois} - {total}
{soldes}
```
