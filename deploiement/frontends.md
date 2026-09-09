# Déployer les frontends — Vercel et Azure, gratuitement

> Le backend est déjà sur **Azure App Service** (voir `azure.md`). Ce document
> ne concerne que les deux applications Angular : la **boutique**
> (`garah-client`) et le **back-office** (`garah`).

## 0. Ce qu'on cherche à savoir

Pas « quelle plateforme est la meilleure », qui ne veut rien dire, mais :
**laquelle sert GARAH le plus vite depuis Douala et Bangui.**

Ça ne se lit pas sur une carte de points de présence. Ce qui compte est le
chemin réel emprunté par la connexion d'un client d'Orange Cameroun ou de
Moov Centrafrique, et il n'a aucune raison de ressembler à ce que le marketing
annonce. On déploie donc les deux, on mesure, on garde un seul.

⚠️ **On supprime le perdant.** Deux déploiements d'une même application qui
vivent côte à côte finissent par diverger, et un jour quelqu'un teste sur celui
qui n'est plus servi.

---

## 1. Ce que coûte « gratuit », vraiment

| | Vercel Hobby | Azure Static Web Apps Free |
|---|---|---|
| Prix | 0 € | 0 € |
| Bande passante | 100 Go/mois | 100 Go/mois |
| Domaine personnalisé | oui | oui |
| HTTPS | automatique | automatique |
| Échéance | **aucune** | **aucune** |
| Usage commercial | ⚠️ **interdit** sur Hobby | autorisé |

⚠️ **La ligne qui compte est la dernière.** Le plan Hobby de Vercel interdit
l'usage commercial. GARAH vend. Tant qu'on teste, personne ne viendra rien
dire ; le jour où la boutique encaisse pour de bon, c'est soit le plan Pro
(20 $/mois), soit Azure.

Ce n'est pas une raison de ne pas mesurer Vercel — c'en est une de savoir ce
qu'on choisit.

---

## 2. Vercel

Les commandes, les réécritures et les en-têtes sont dans `vercel.json`. **Un
seul réglage se fait à la souris**, et il n'y a pas moyen de faire autrement :
la *Root Directory*.

### ⚠️ La Root Directory — le réglage qui décide de tout le reste

| Dépôt | Root Directory |
|---|---|
| `garah-client` | `web` |
| `garah` | `frontend` |

*Settings → General → Root Directory.* Vercel la propose souvent tout seul à
l'import, en reconnaissant l'application Angular.

**Ce réglage change le répertoire depuis lequel les commandes s'exécutent**, et
c'est ce qui rend les chemins de `vercel.json` relatifs à lui — `npm ci` et non
`cd web && npm ci`, `dist/garah-boutique/browser` et non
`web/dist/garah-boutique/browser`.

> Le `cd web && npm ci` a échoué avec « No such file or directory » précisément
> parce que la Root Directory valait déjà `web` : la commande refaisait le
> chemin une fois de trop.

`vercel.json` est posé **à deux endroits**, identiques : à la racine du dépôt
et dans le dossier applicatif. Ce n'est pas un doublon par négligence — selon
la version, Vercel cherche le fichier dans l'un ou dans l'autre, et la copie
rend la configuration insensible à ce détail.

⚠️ **Les deux doivent rester identiques.** Modifier l'un sans l'autre donne un
déploiement qui dépend de la version de Vercel — c'est-à-dire un déploiement
qu'on ne peut plus expliquer.

### La boutique

1. vercel.com → **Add New** → **Project** → importer `Dominique-BRIA/garah-client`
2. Vérifier que **Root Directory** vaut `web`
3. Ne toucher à rien d'autre
4. **Deploy**

### Le back-office

Même chose avec le dépôt `garah`, **Root Directory** à `frontend`.

⚠️ Le `vercel.json` de ce dépôt construit `garah-ui` **avant** `garah-admin` :
sans cet ordre, la compilation échoue sur un module introuvable.

### ⚠️ Personne ne redéploie personne — là où ça compte

Chaque dépôt porte deux choses : `garah` a le backend **et** le back-office,
`garah-client` a le web **et** le mobile.

| Ce qui déclenche | Ce qui le limite | Où |
|---|---|---|
| Déploiement Azure du backend | `paths: backend/**` | `deploiement-azure.yml` |
| Tests du backend | `paths: backend/**` | `ci.yml` |
| Déploiement Azure du back-office | `paths: frontend/**` | `admin-azure.yml` |
| Déploiement Azure de la boutique | `paths: web/**` | `web-azure.yml` |
| Déploiement Vercel | **rien** — voir ci-dessous | |

⚠️ **Le filtre du backend est ce qui compte vraiment.** Ce n'est pas une
économie de minutes : le conteneur qui part rejoue les migrations Flyway sur la
base de **production** au démarrage. Redéployer pour rien, c'est s'exposer pour
rien — et il n'existe pas de bouton « annuler » sur une migration.

#### Pourquoi Vercel construit sur tous les commits

Vercel propose un `ignoreCommand` pour sauter les constructions inutiles. Il a
été essayé, et **retiré après deux déploiements annulés à tort**.

```
git diff --quiet HEAD^ HEAD -- web/ vercel.json
```

Trois pièges s'y accumulent :

1. **Le sens est inversé.** La commande rend `0` quand il n'y a **pas** de
   différence — et Vercel **annule** sur un `0`.
2. **Le fichier qui décrit la construction doit pouvoir la déclencher.** Sans
   `vercel.json` dans la liste, corriger la configuration de déploiement ne
   déploie rien : le correctif est annulé par ce qu'il corrige.
3. **`HEAD^` n'est pas fiable dans le clone de Vercel.** La même commande
   rendait `1` en local et `0` sur leurs machines, **sur le même commit**. Un
   clone superficiel ne porte pas forcément le parent.

Ce que ça coûte de l'avoir retiré : une construction de deux minutes quand on
touche au backend ou au mobile. Ce que ça coûtait de le garder : ne plus
pouvoir déployer du tout, sans comprendre pourquoi.

À reprendre **une fois qu'un premier déploiement existe**, avec
`VERCEL_GIT_PREVIOUS_SHA` — que Vercel garantit, contrairement à `HEAD^` — et
seulement si les minutes de construction deviennent un vrai sujet.

> **Pourquoi pas deux branches `frontend` et `backend` ?**
>
> Parce que ce qui doit décider du déclenchement est **ce qui a changé**, pas
> l'endroit où l'on a poussé — et que les filtres GitHub Actions le font déjà,
> eux, de façon fiable.
>
> Deux branches longues coûteraient cher : elles divergent, un changement qui
> touche les deux côtés (une route d'API et l'écran qui l'appelle) ne peut plus
> être atomique, et `main` cesse d'être la vérité. Le jour d'une panne, il
> faudrait chercher dans laquelle des trois branches vit le code réellement
> déployé.

---

## 3. Azure Static Web Apps

Pour chacune des deux applications :

1. Portail Azure → **Static Web Apps** → **Créer**
   - Groupe de ressources : `garah-project` (le même que l'API)
   - Offre : **Gratuit**
   - Région : **West Europe** — la plus proche de l'API, qui est en France Central
   - Source : ⚠️ **Autre**, surtout pas « GitHub »

   > « GitHub » ferait écrire au portail son **propre** workflow, à côté de
   > celui du dépôt. Les deux se déclencheraient sur chaque commit et
   > déploieraient des choses différentes.

2. Une fois créée : **Aperçu → Gérer le jeton de déploiement**, copier.

3. Poser le jeton dans les secrets du dépôt
   (*Settings → Secrets and variables → Actions*) :

   | Dépôt | Nom du secret |
   |---|---|
   | `garah-client` | `AZURE_STATIC_WEB_APPS_API_TOKEN` |
   | `garah` | `AZURE_STATIC_WEB_APPS_API_TOKEN_ADMIN` |

4. Poser aussi la **variable** qui allume le workflow, sinon il se saute :

   | Dépôt | Variable | Valeur |
   |---|---|---|
   | `garah-client` | `AZURE_SWA_ACTIVE` | `true` |
   | `garah` | `AZURE_SWA_ADMIN_ACTIVE` | `true` |

---

## 4. ⚠️ SANS CETTE ÉTAPE, RIEN NE MARCHERA

### Où en est-on vraiment, aujourd'hui

| Application | Adresse | État |
|---|---|---|
| Boutique | `https://garah.vercel.app` | ✅ déployée, origine acceptée |
| Back-office | *(aucune)* | ❌ **jamais déployé** |

> ⚠️ **L'adresse de la boutique n'était écrite nulle part.** Elle a été
> retrouvée en essayant des origines contre l'API déployée jusqu'à ce qu'un
> préflight passe. Un projet dont personne ne sait où il est déployé est un
> projet qu'on ne sait pas dépanner.

> ⚠️ **Le back-office n'a pas d'adresse.** Son workflow Azure est désactivé
> (`vars.AZURE_SWA_ADMIN_ACTIVE` n'est pas posée, donc le job est *skipped* à
> chaque exécution), et aucun projet Vercel ne répond sous un nom plausible.
> Le `vercel.json` de ce dépôt est prêt ; il manque le projet.

### Vérifier, plutôt que d'espérer

L'oubli de cette étape échoue **en silence** : l'API répond correctement et le
navigateur jette la réponse. Pas d'erreur serveur, pas de trace, une page vide.

```bash
node outils/verifier-origines.mjs
node outils/verifier-origines.mjs https://mon-nouveau-domaine.vercel.app
```

Le script envoie le préflight qu'enverrait le navigateur et vérifie la présence
de `Access-Control-Allow-Origin` — un `200` sans cet en-tête ne sert à rien.


Chaque nouveau domaine doit être ajouté à **`GARAH_CORS_ORIGINS`**, dans les
réglages de l'App Service `garah-api` :

```
Azure → App Service garah-api → Configuration → Application settings
  GARAH_CORS_ORIGINS = https://garah-web.vercel.app,https://garah-admin.vercel.app,https://xxx.azurestaticapps.net,http://localhost:4200
```

Séparés par des virgules, **sans espace**, **sans barre oblique finale**.

**Le symptôme quand on l'oublie est trompeur** : la page s'affiche
parfaitement, et tous les appels échouent. À l'écran, la boutique dit « Pas de
connexion » — ce qui fait chercher du côté du réseau alors que le serveur
répond très bien. Dans la console du navigateur, c'est un refus de *preflight*
CORS.

⚠️ Le **WebSocket lit la même variable**, exprès. Deux listes d'origines
finiraient par diverger, et le temps réel resterait ouvert là où HTTP a été
fermé.

---

## 5. Mesurer, pour de vrai

### Ce qui ne sert à rien

- **PageSpeed / Lighthouse depuis un poste européen.** Ils mesurent la
  distance entre Google et la plateforme, pas entre vos clients et elle.
- **Le ressenti.** « Ça a l'air plus rapide » après trois chargements est du
  cache, pas de la vitesse.

### Ce qui sert

Depuis **Douala ou Bangui**, sur une connexion mobile réelle, onglet privé,
outils de développement ouverts sur l'onglet Réseau, case **Disable cache**
cochée :

| À relever | Où |
|---|---|
| **TTFB** du document | Réseau → la première ligne → Timing → *Waiting (TTFB)* |
| Temps de chargement complet | bas de l'onglet Réseau |
| Poids transféré | bas de l'onglet Réseau |

Trois mesures par plateforme, à des moments différents de la journée. Une
seule mesure ne dit rien : une connexion mobile varie du simple au triple
entre midi et vingt heures.

Le chiffre qui décide est le **TTFB** : le reste dépend surtout du poids de
l'application, qui est identique des deux côtés.

### En ligne de commande

```bash
curl -o /dev/null -s -w "TTFB %{time_starttransfer}s  total %{time_total}s\n" \
  https://garah-web.vercel.app/

curl -o /dev/null -s -w "TTFB %{time_starttransfer}s  total %{time_total}s\n" \
  https://xxx.azurestaticapps.net/
```

À lancer **depuis le terrain**, pas depuis un serveur.

---

## 6. Ce qui est déjà réglé dans les fichiers

Pour que la relecture n'ait pas à les redécouvrir :

- **La réécriture SPA.** Sans elle, ouvrir directement `/produit/chaussure` —
  un lien partagé, un favori, une notification touchée — rend 404. Le défaut
  ne se voit *jamais* en navigant depuis l'accueil : il ne concerne que les
  visiteurs qui viennent d'ailleurs, c'est-à-dire presque tous.

- **⚠️ `firebase-messaging-sw.js` est EXCLU de cette réécriture.** S'il
  recevait `index.html`, l'agent de service ne s'enregistrerait pas —
  l'abonnement aux notifications réussirait quand même, et rien n'arriverait
  jamais, sans le moindre message d'erreur. Vercel sert les fichiers réels
  avant d'appliquer ses réécritures ; Azure, lui, a besoin qu'on le dise
  explicitement, d'où la liste `exclude` de `staticwebapp.config.json`.

- **L'agent de service n'est pas mis en cache.** Un navigateur qui garde
  l'ancien continue de router les notifications vers des chemins disparus, et
  le corriger demanderait de vider le cache de chaque visiteur.

- **`index.html` n'est pas mis en cache non plus** : c'est lui qui nomme les
  fichiers empreintés. Gardé en cache, il réclamerait les anciens, et un
  déploiement ne changerait rien à l'écran.

- **Le reste est gardé un an.** Les fichiers construits portent une empreinte
  dans leur nom : une nouvelle version a un nouveau nom. C'est ce qui rend le
  second chargement instantané sur une connexion lente.

- **Le back-office porte `X-Robots-Tag: noindex`.** Il n'a rien à faire dans
  un moteur de recherche.
