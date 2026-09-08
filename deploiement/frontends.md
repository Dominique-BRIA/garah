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

Les deux dépôts portent déjà un `vercel.json` : **rien à régler dans
l'interface**. Un réglage posé à la souris ne se relit pas, ne se versionne
pas, et personne ne sait plus pourquoi il est là six mois plus tard.

### La boutique

1. vercel.com → **Add New** → **Project** → importer `Dominique-BRIA/garah-client`
2. Ne toucher à **rien** dans l'écran de configuration — `vercel.json` fixe
   déjà la commande d'installation, celle de construction et le dossier de
   sortie.
3. **Deploy**

### Le back-office

Même chose avec le dépôt `garah`. Le `vercel.json` de ce dépôt construit
`garah-ui` **avant** `garah-admin` : sans cet ordre, la compilation échoue sur
un module introuvable.

### ⚠️ Personne ne redéploie personne

Chaque dépôt porte deux choses : `garah` a le backend **et** le back-office,
`garah-client` a le web **et** le mobile. Sans précaution, un commit sur l'un
reconstruit l'autre.

C'est réglé **dans les fichiers**, pas par des branches séparées :

| Ce qui déclenche | Ce qui le limite | Où |
|---|---|---|
| Déploiement Azure du backend | `paths: backend/**` | `deploiement-azure.yml` |
| Tests du backend | `paths: backend/**` | `ci.yml` |
| Déploiement Azure du back-office | `paths: frontend/**` | `admin-azure.yml` |
| Déploiement Vercel | `ignoreCommand` | `vercel.json` |

```
git diff --quiet HEAD^ HEAD -- frontend/ vercel.json
```

⚠️ **`vercel.json` est dans la liste, et ce n'est pas un détail.** Le fichier
qui décrit la construction doit pouvoir la **déclencher** : sans lui, corriger
la configuration de déploiement ne déploie rien — et on ne peut plus jamais
réparer le déploiement par un commit. C'est arrivé : le premier correctif du
`vercel.json` a été annulé par le `vercel.json` qu'il corrigeait.

⚠️ **Le sens de `ignoreCommand` est contre-intuitif.** La commande rend `0`
quand il **n'y a pas** de différence — et Vercel **annule** la construction sur
un `0`. S'y tromper désactive tous les déploiements sans qu'on comprenne
pourquoi.

> **Premier déploiement d'un projet neuf.** Tant qu'aucun commit n'a touché le
> dossier surveillé, Vercel annule — et le projet n'a donc jamais rien servi.
> Le débloquer se fait d'un commit sur ce dossier, ou depuis le tableau de bord
> Vercel : *Deployments → … → Redeploy*, en décochant **Use existing Build
> Cache**, ce qui force le passage.

⚠️ Le filtre du backend n'est pas une économie de minutes, c'est une
**protection** : le conteneur qui part rejoue les migrations Flyway sur la base
de **production** au démarrage. Redéployer pour rien, c'est s'exposer pour rien.

> **Pourquoi pas deux branches `frontend` et `backend` ?**
>
> Parce que ce qui doit décider du déclenchement est **ce qui a changé**, pas
> l'endroit où l'on a poussé — et que les filtres ci-dessus le font déjà.
>
> Deux branches longues coûteraient cher : elles divergent, un changement qui
> touche les deux côtés (une route d'API et l'écran qui l'appelle) ne peut plus
> être atomique, et `main` cesse d'être la vérité. Le jour d'une panne, il
> faudrait chercher dans laquelle des trois branches est le code réellement
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
