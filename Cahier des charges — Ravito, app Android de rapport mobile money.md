# Cahier des charges — Ravito, app Android de rapport mobile money

Nov 24, 2026 · @Christian

## 1. Contexte et objectif

On construit Ravito, une application Android installée sur le téléphone du marchand. Elle lit ses SMS mobile money et prépare tout seule son rapport du soir.

**Le problème.** Le client est un marchand qui ravitaille d'autres marchands en crédit mobile money. Chaque soir, il doit envoyer à son groupe WhatsApp le rapport des envois qu'il a faits dans la journée. Aujourd'hui, il retrouve chaque transaction à la main dans ses SMS. C'est long et il peut faire des erreurs.

**La solution.** L'application repère les SMS de confirmation d'envoi (Orange Money, MTN MoMo, Moov Money, Wave), en extrait les informations utiles, les range dans un **Historique** et prépare le texte dans l'onglet **Rapport**. Le marchand relit, corrige si besoin, copie et colle dans WhatsApp.

**Résultat attendu.** Un rapport prêt en moins d'une minute au lieu d'une recherche manuelle.

**Phase actuelle.** Prototype testé d'abord sur le téléphone de Christian (Orange Money, 07 48 86 96 03), puis montré au client.

## 2. Principe de fonctionnement

L'application ne fait qu'une chose : transformer les SMS d'envoi en lignes d'historique, puis en rapport.

&#91;embedded content: parcours d'un SMS · 5 étapes, 1 décision, 1 rattrapage\]

Si l'application était fermée au moment d'un envoi, le SMS reste dans la boîte de réception : la synchronisation le récupère plus tard et le fait passer par le même chemin.

## 3. Périmètre

L'application ne garde que les **envois** faits par le marchand. Elle ignore tout le reste (argent reçu, promos, codes, messages perso).

| Élément | Ce qui est prévu |
| --- | --- |
| Téléphone | Android uniquement (tous les modèles récents, à partir d'Android 8) |
| Puces (SIM) | Jusqu'à 4 comptes mobile money sur le même téléphone (SIM 1, SIM 2, application Wave) |
| Opérateurs | Orange Money, MTN MoMo, Moov Money, Wave |
| Source des infos | SMS de confirmation reçus après chaque envoi ; notification de l'app pour Wave si Wave n'envoie pas de SMS |
| Utilisateur | Un seul : le marchand lui-même |
| Internet | Pas nécessaire pour lire les SMS et faire le rapport |
| Diffusion du rapport | Copier-coller (ou bouton Partager) vers son groupe WhatsApp |

**Bon à savoir sur les SIM.** La plupart des téléphones Android n'acceptent que **2 SIM** à la fois. Pour 4 opérateurs, le cas courant est : 2 SIM (ex. Orange + MTN) + Moov sur une autre SIM ou un autre téléphone + Wave en application. La V1 couvre tout ce qui arrive sur **un seul téléphone**.

**Pourquoi Internet n'est pas nécessaire.** Les SMS arrivent par le réseau téléphonique, pas par Internet. Tant que le téléphone a du réseau, les SMS sont dans la boîte de réception, et l'application peut les relire à tout moment.

## 4. Écrans de l'application

L'application a **3 onglets** en bas de l'écran, plus un écran de démarrage.

**Écran de démarrage (première ouverture)**

1. Explication courte : « L'application lit uniquement les SMS de vos opérateurs mobile money. »
2. Demande de l'autorisation **SMS** (obligatoire) et **Notifications** (pour Wave).
3. Choix des opérateurs utilisés et création d'un **code PIN** à 4 chiffres.
4. Première lecture des SMS du jour.

**Onglet Historique**

- Liste de toutes les transactions d'envoi, la plus récente en haut, groupées par jour.
- Chaque ligne : heure, logo opérateur, bénéficiaire (nom ou numéro), montant.
- En haut : total du jour et nombre d'envois par opérateur.
- Filtres : date, opérateur ; recherche par numéro ou nom.
- Appui sur une ligne : détail complet + SMS d'origine + bouton « Exclure du rapport ».
- Bouton **Synchroniser** : relit les SMS pour récupérer ceux qui manquent.

**Onglet Rapport**

- Choix de la date (aujourd'hui par défaut).
- Texte du rapport généré automatiquement selon le modèle.
- Bouton **Modifier** : le texte devient modifiable à la main.
- Bouton **Copier** : copie le texte dans le presse-papiers.
- Bouton **Partager** : ouvre directement WhatsApp avec le texte (raccourci du copier-coller).
- Bandeau d'alerte si des SMS n'ont pas été reconnus ce jour-là.

**Onglet Paramètres**

- Opérateurs actifs et SIM associée.
- Modèle de rapport (texte avec des zones comme {date}, {total}, {liste}).
- Carnet des marchands : numéro → nom (ajout à la main ou depuis les contacts).
- Heure de rappel du soir (ex. 19 h 30 : « Votre rapport est prêt »).
- Code PIN, export des données (fichier Excel/CSV).

## 5. Exemple Orange Money : du SMS au rapport

Le format exact des SMS sera recopié sur de **vrais SMS** du téléphone de test. L'exemple ci-dessous est illustratif.

**Étape 1 — Le SMS reçu** (expéditeur : `OrangeMoney`)

```
Transfert de 50000 FCFA vers le 0102030405 reussi. Frais: 0 FCFA. Nouveau solde: 325000 FCFA. Ref: CI241124.0915.A12345
```

**Étape 2 — Ce que l'application en extrait**

| Champ | Valeur extraite |
| --- | --- |
| Opérateur | Orange Money |
| Type | Envoi |
| Montant | 50 000 FCFA |
| Bénéficiaire | 01 02 03 04 05 (nom si enregistré : « Boutique Awa ») |
| Frais | 0 FCFA |
| Solde après | 325 000 FCFA |
| Référence | CI241124.0915.A12345 |
| Date et heure | 24/11/2026 à 09:15 (heure de réception du SMS) |

**Étape 3 — Le rapport généré** (modèle par défaut, à remplacer par celui du client)

```
RAPPORT DU 24/11/2026

ORANGE MONEY : 3 envois - 120 000 F
1. 09:15 - Boutique Awa (0102030405) - 50 000 F
2. 11:40 - 0707080910 - 40 000 F
3. 16:05 - Kiosque Yao (0505060708) - 30 000 F

MTN : 0 envoi
MOOV : 0 envoi
WAVE : 0 envoi

TOTAL : 3 envois - 120 000 F
Solde Orange fin de journée : 275 000 F
```

Le marchand appuie sur **Copier**, ouvre son groupe WhatsApp et colle.

## 6. Règles de fonctionnement et cas particuliers

| Situation | Ce que fait l'application |
| --- | --- |
| Un SMS d'envoi arrive | Il est lu tout de suite, enregistré dans l'Historique, le rapport du jour se met à jour |
| L'application était fermée ou le téléphone éteint | À la prochaine ouverture (ou bouton Synchroniser), elle relit la boîte SMS depuis la dernière lecture |
| Le même SMS est lu deux fois | Pas de doublon : la référence de transaction sert d'identifiant unique |
| SMS d'argent reçu, promo, code secret | Ignoré, jamais enregistré |
| SMS de l'opérateur au format inconnu | Rangé dans « À vérifier » ; le marchand peut l'ajouter à la main |
| Transaction échouée ou annulée | Marquée « échouée », non comptée dans le total |
| Le marchand supprime un SMS | La transaction reste dans l'Historique de l'application |
| Période du rapport | Par défaut de 00:00 à 23:59 ; modifiable (ex. 07:00 → 22:00) |

**Comment l'application reconnaît un SMS.** Deux contrôles : l'**expéditeur** (ex. OrangeMoney, MobileMoney, MoovMoney) puis des **mots-clés** du texte (ex. « Transfert de … vers … reussi »). Une règle par opérateur et par type de message, faciles à mettre à jour si l'opérateur change son texte.

## 7. Exigences techniques et compatibilité

Une application Android **native**, qui fonctionne sans serveur et sans Internet en V1.

| Sujet | Choix |
| --- | --- |
| Langage | Kotlin (langage officiel d'Android, meilleur accès aux SMS) |
| Interface | Jetpack Compose |
| Base de données | Room (SQLite), stockée sur le téléphone |
| Version minimale | Android 8.0 (couvre la quasi-totalité des téléphones en circulation) |
| Marques testées | Tecno, Infinix, itel, Samsung, Xiaomi (les plus courantes en Côte d'Ivoire) |
| Installation | Fichier APK installé directement, **pas de Play Store** |
| Lecture en direct | Récepteur de SMS entrants |
| Rattrapage | Relecture de la boîte SMS (à l'ouverture, au bouton Synchroniser, et automatiquement toutes les heures) |
| Wave | Lecture des notifications de l'application Wave |

**Autorisations Android demandées**

- `RECEIVE_SMS` et `READ_SMS` : recevoir et relire les SMS (obligatoire).
- Accès aux notifications : uniquement pour Wave.
- `POST_NOTIFICATIONS` : le rappel du soir (Android 13 et plus).
- `READ_CONTACTS` : facultatif, pour afficher le nom des marchands.
- `READ_PHONE_STATE` : savoir sur quelle SIM le SMS est arrivé.

**Pourquoi pas le Play Store.** Google interdit presque toujours la lecture des SMS aux applications publiées sur le Play Store. L'installation par APK est normale pour un outil privé comme celui-ci.

**Piège des marques chinoises.** Tecno, Infinix et Xiaomi ferment les applications en arrière-plan pour économiser la batterie. Au démarrage, l'application guide le marchand pour la retirer de l'économie de batterie. Le rattrapage par relecture de la boîte SMS garantit qu'aucun envoi n'est perdu.

**Place de l'IA.** En V1, la lecture des SMS se fait par **règles** (plus fiables et gratuites). L'IA peut venir en V2 : lire automatiquement un SMS au format inconnu, ou répondre à des questions (« Combien j'ai envoyé à Awa cette semaine ? »).

## 8. Sécurité et données

Les données restent **sur le téléphone du marchand** : rien n'est envoyé sur Internet en V1.

- L'application ne lit que les SMS des expéditeurs mobile money choisis ; les autres SMS ne sont ni lus ni stockés.
- Elle n'envoie **aucun** SMS et ne fait **aucune** transaction : lecture seule.
- Ouverture protégée par code PIN (ou empreinte digitale).
- Base de données chiffrée sur le téléphone.
- Les codes secrets mobile money ne sont jamais demandés ni enregistrés.
- Sauvegarde : export manuel en fichier ; sauvegarde en ligne prévue en V2.
- Accord écrit du client avant installation (il autorise la lecture de ses SMS mobile money).

## 9. Plan de test sur le téléphone de Christian

Le test se fait sur un Android avec la SIM Orange Money de Christian (07 48 86 96 03). Chaque étape suit le schéma objectif → manipulation → résultat attendu.

| # | Objectif | Manipulation | Résultat attendu |
| --- | --- | --- | --- |
| 1 | Installer | Installer l'APK, accepter les autorisations, créer le PIN | L'application s'ouvre sur l'Historique |
| 2 | Lire l'existant | Lancer la première synchronisation | Les envois Orange Money passés apparaissent, sans les SMS reçus ni les promos |
| 3 | Envoi en direct | Faire un petit transfert (ex. 100 F) vers un autre numéro | La transaction apparaît en moins de 10 secondes, montant et numéro corrects |
| 4 | App fermée | Forcer l'arrêt de l'application, faire un 2e transfert, rouvrir | Le 2e transfert est récupéré, sans doublon |
| 5 | Journée réelle | Utiliser le téléphone normalement toute une journée, sans ouvrir l'application | Le soir, tous les envois du jour sont dans l'Historique |
| 6 | Rapport | Ouvrir l'onglet Rapport | Le texte reprend tous les envois du jour, total juste |
| 7 | Modifier et copier | Corriger une ligne, Copier, coller dans un groupe WhatsApp de test | Le texte collé est identique à celui de l'écran |
| 8 | Contrôle | Comparer le total avec l'historique Orange Money (#144#) | Aucun écart |

**Critère de réussite.** 100 % des envois du jour retrouvés, aucun doublon, aucun SMS hors sujet dans le rapport. Ensuite, on ajoute MTN, Moov et Wave avec leurs vrais SMS.

## 10. Ce qui reste à fournir ou décider

Trois éléments bloquent le développement des règles de lecture ; les autres peuvent attendre.

**À fournir (bloquant)**

- [ ] Le **modèle de rapport** du client (le texte exact qu'il colle dans WhatsApp).
- [ ] **3 à 5 vrais SMS d'envoi Orange Money** du téléphone de test (numéros masqués), plus un SMS d'échec si possible.
- [ ] Un téléphone Android de test (marque et version d'Android).

**À fournir (pour la suite)**

- [ ] Des SMS d'envoi MTN MoMo et Moov Money.
- [ ] Une capture de la notification Wave après un envoi (pour savoir si Wave envoie un SMS ou seulement une notification).
- [ ] La liste des marchands ravitaillés (numéro + nom), si le client veut les noms dans le rapport.

**À décider**

- [ ] Période du rapport : minuit à minuit, ou heures d'ouverture ?
- [ ] Afficher le solde de fin de journée dans le rapport ?
- [ ] Compter les frais dans le total ou à part ?
- [ ] Heure du rappel du soir.
- [ ] Nom de l'application : Ravito (choisi le 24/11/2026).
