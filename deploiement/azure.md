# Déploiement sur Azure App Service

Ce qui a été provisionné, lu dans le modèle ARM exporté du portail :

| | |
|---|---|
| Abonnement | `99ce50e7-7428-43ea-940f-84dfdd9ebab9` (Azure for Students) |
| Groupe de ressources | `garah-project` |
| Application | `garah-api` — **France Central** |
| Plan | `ASP-garahproject-a0c3`, **B1** : 1 cœur, 1,75 Go |
| Pile | conteneur Linux |

---

## Pourquoi un conteneur et pas la pile « Java SE »

Ce n'est pas un choix de confort : **App Service ne propose pas Java 24**, que
ce projet utilise. La pile Java SE s'arrête aux versions LTS supportées.

Le conteneur contourne la question entièrement — c'est le même `Dockerfile`
qui tournait sur Render, sans une ligne de différence. C'est exactement la
réversibilité que D-14 exigeait, et elle vient de servir pour de bon.

---

## Ce qui change par rapport à Render

| | Render (gratuit) | Azure B1 |
|---|---|---|
| Mémoire | 512 Mo | **1,75 Go** |
| Mise en veille | après 15 min | **aucune**, si Always On est activé |
| Démarrage à froid | ~120 s, à chaque réveil | au déploiement seulement |
| Coût | 0 € | ~13 $/mois **prélevés sur le crédit** |

### ⚠️ Le crédit est une échéance, pas une gratuité

100 $ sur 12 mois. À ~13 $/mois pour le seul plan B1, le crédit s'épuise en
**7 à 8 mois**. Ensuite l'application s'arrête, sauf à payer ou à revenir sur
une offre gratuite.

Deux conséquences concrètes :

- **Garder Neon pour la base.** Azure PostgreSQL est gratuit 12 mois puis
  facturé ; Neon est gratuit sans échéance. Y basculer consommerait le crédit
  deux fois plus vite pour remplacer quelque chose qui fonctionne.
- **Noter la date.** Le jour où le crédit tombe à zéro, l'application s'arrête
  sans préavis autre qu'un e-mail. Ce n'est pas une panne à diagnostiquer.

---

## 1. Réglages à faire dans le portail

### Always On — le plus important

**Paramètres → Configuration → Paramètres généraux → Always On : Activé**

`alwaysOn` est à `false` dans le modèle exporté. Laissé ainsi, App Service
décharge l'application après 20 minutes d'inactivité et le visiteur suivant
paie le démarrage complet — c'est-à-dire **exactement le défaut de Render
qu'on cherche à fuir**. Sur B1, l'option est incluse : il n'y a aucune raison
de s'en priver.

### Port du conteneur

**Paramètres → Variables d'environnement** → `WEBSITES_PORT` = `8080`

Le `Dockerfile` expose 8080 et `application.yml` lit `${PORT:8080}`.
App Service injecte `PORT` de son côté, donc les deux se rejoignent — mais
sans `WEBSITES_PORT`, App Service devine, et une devinette ratée donne un
« Application Error » sans rien dans les journaux applicatifs.

### ⚠️ Délai de démarrage du conteneur

**Paramètres → Variables d'environnement** → `WEBSITES_CONTAINER_START_TIME_LIMIT` = `600`

Le défaut d'Azure est de **230 secondes**. Passé ce délai, si le conteneur n'a
pas ouvert son port, App Service le **tue et le relance** — indéfiniment. Le
symptôme est un `503` permanent servi par Azure, pas par l'application.

Or ce démarrage a été mesuré à **119,8 s sur Render**, et App Service B1 n'a
qu'un cœur. Ajoutez le premier tirage de l'image (250 Mo), et les 230 s se
franchissent sans difficulté.

C'est le piège le plus vicieux de la liste, parce qu'il **imite une panne
applicative** : le journal montre Spring qui démarre normalement, puis
s'interrompt sans erreur — Azure a coupé le processus au milieu. On cherche
alors un bogue dans un code qui n'en a pas.

600 s laisse de la marge sans masquer un vrai blocage.

### ⚠️ Après CHAQUE mise en ligne, l'API est muette quelques minutes

`Always On` empêche l'application de s'endormir **à l'inactivité**. Il
n'empêche pas le redémarrage qui suit un déploiement : le conteneur est
remplacé, et il faut le retélécharger, le lancer, laisser Spring démarrer et
Flyway vérifier les migrations.

Mesuré le 9 septembre 2026, sur le déploiement de `6023ebb` :

| Requête après le déploiement | Temps    |
|------------------------------|----------|
| la première                  | **38 s** |
| la deuxième                  | 0,7 s    |
| la troisième                 | 0,8 s    |

Et pendant la fenêtre qui précède, l'API ne répond pas du tout.

**Le symptôme trompe.** Dans le back-office, *tous* les écrans affichent en
même temps « Le service ne répond pas ». On croit à une régression de la
dernière fonctionnalité livrée — alors que c'est justement sa mise en ligne
qui coupe l'API, et que la fonctionnalité, elle, n'a rien.

Le test : ouvrir **un autre** écran. S'il échoue aussi, ce n'est pas l'écran,
c'est la liaison. Et si un déploiement vient de passer, il n'y a rien à
corriger — il faut attendre et recharger.

### ⚠️ WebSockets — à activer explicitement

Le temps réel des conversations passe par un WebSocket (`/ws`). **App Service
le refuse tant qu'on ne l'a pas activé**, et le réglage n'est pas dans les
variables d'environnement : il est dans la configuration du site.

> Portail Azure → l'App Service → **Configuration** → **Paramètres généraux**
> → **Web sockets** → **Activé** → Enregistrer.

Ou en ligne de commande :

```bash
az webapp config set --name <app> --resource-group <groupe> --web-sockets-enabled true
```

**⚠️ Le symptôme à reconnaître.** Rien ne dit que c'est éteint. Le navigateur
signale seulement une connexion fermée ; le client retente indéfiniment,
comme il doit le faire quand le réseau tombe ; et l'écran a simplement l'air
de ne pas se mettre à jour. On cherche alors le défaut dans le code du temps
réel, qui est juste.

**Deux autres causes du même symptôme**, à écarter dans cet ordre :

1. `/ws` doit être ouvert dans `ConfigurationSecurite`. Sans la ligne
   `.requestMatchers("/ws").permitAll()`, la poignée de main répond **401**
   avant que STOMP ne voie la trame `CONNECT` qui porte le jeton. Le test
   `PoigneeDeMainWebSocketTest` garde ce point.

2. `GARAH_CORS_ORIGINS` doit contenir l'origine EXACTE du front, `www` compris.
   Le WebSocket lit la **même** variable que CORS — volontairement : deux
   listes finiraient par diverger, et la prise resterait ouverte là où HTTP a
   été fermé.

**Pour vérifier depuis n'importe où**, sans navigateur :

```bash
curl -s -i -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: https://www.garah.me" \
  https://<api>/ws | head -3
```

`101 Switching Protocols` : tout va bien. `401` : la règle de sécurité manque.
`403` : l'origine n'est pas dans `GARAH_CORS_ORIGINS`. `404` ou une coupure
sèche : les WebSockets ne sont pas activés sur l'App Service.

### Sonde de santé

**Surveillance → Health check** → `/api/sante`

Sans elle, une instance qui répond 500 reste dans la rotation.

### Registre d'images

L'image vit sur GHCR. Si le paquet est **privé**, ajouter trois variables :

```
DOCKER_REGISTRY_SERVER_URL       https://ghcr.io
DOCKER_REGISTRY_SERVER_USERNAME  <votre compte GitHub>
DOCKER_REGISTRY_SERVER_PASSWORD  <jeton GitHub avec read:packages>
```

Si le paquet est **public**, aucune de ces trois n'est nécessaire. L'image ne
contient que le JAR compilé — aucun secret n'y est cuit, ils arrivent tous par
variables d'environnement.

---

## 2. Variables d'environnement de l'application

Toutes celles que `render.yaml` déclarait. À recopier dans
**Paramètres → Variables d'environnement**.

⚠️ **`GARAH_JWT_SECRET` : en engendrer un NOUVEAU.** Render le fabriquait
(`generateValue: true`). Reprendre l'ancien ferait accepter par Azure des
jetons émis par Render — ce qui est précisément ce qu'on veut éviter en
changeant d'hébergeur. Le changer déconnecte tout le monde une fois : c'est
le comportement voulu.

```
# Base — on GARDE Neon
GARAH_DB_URL, GARAH_DB_USER, GARAH_DB_PASSWORD
GARAH_DB_POOL_MAX=5

# Sécurité
GARAH_JWT_SECRET               (nouveau, 32 octets minimum)
GARAH_JWT_EXPIRATION_MINUTES=15
GARAH_REFRESH_EXPIRATION_JOURS=14
GARAH_COOKIE_SECURE=true
GARAH_COOKIE_SAMESITE=None
GARAH_COOKIE_DOMAIN            (vide)

# Frontends autorisés — À METTRE À JOUR
GARAH_CORS_ORIGINS=https://<votre-front>.vercel.app,http://localhost:4200

# Fichiers
GARAH_S3_ENDPOINT, GARAH_S3_REGION, GARAH_S3_BUCKET
GARAH_S3_ACCESS_KEY, GARAH_S3_SECRET_KEY
GARAH_S3_URLS_SIGNEES=true
GARAH_S3_SIGNATURE_JOURS=7
GARAH_MEDIA_BASE_URL

# Paiement
GARAH_CAMPAY_BASE_URL, GARAH_CAMPAY_APP_USERNAME, GARAH_CAMPAY_APP_PASSWORD
GARAH_CAMPAY_WEBHOOK_KEY, GARAH_CAMPAY_WEBHOOK_STRICT=false

# E-mail
GARAH_MAIL_HOST, GARAH_MAIL_PORT, GARAH_MAIL_UTILISATEUR
GARAH_MAIL_MOT_DE_PASSE, GARAH_MAIL_EXPEDITEUR, GARAH_MAIL_NOM_EXPEDITEUR

# Confirmation d'adresse — À METTRE À JOUR avec l'URL Azure
GARAH_URL_VERIFICATION=https://<hôte-azure>/api/auth/verification
GARAH_VERIFICATION_VALIDITE_HEURES=48

# Premier démarrage — À RETIRER une fois le compte créé
GARAH_SUPERADMIN_EMAIL, GARAH_SUPERADMIN_MOT_DE_PASSE

GARAH_VERSION=0.1.0
```

---

## 3. Secrets GitHub

**Paramètres du dépôt → Secrets and variables → Actions**

| Secret | D'où il vient |
|---|---|
| `AZURE_PUBLISH_PROFILE` | Portail → l'application → **Télécharger le profil de publication**, coller le fichier entier |
| `AZURE_URL_SANTE` | `https://<hôte-azure>/api/sante` |

Le workflow ne demande **aucun** secret de registre : GHCR accepte le
`GITHUB_TOKEN` intégré, grâce à `permissions: packages: write`.

---

## 4. Ce qu'on peut enfin remettre en place

Le passage à 1,75 Go rend inutile une partie du bricolage imposé par les
512 Mo de Render. Un réglage en particulier mérite d'être annulé :

```yaml
spring.data.jpa.repositories.bootstrap-mode: lazy
```

Il a sauvé le déploiement du 07/09/2026, mais son coût est écrit dans
`application.yml` : **une requête `@Query` invalide ne fait plus échouer le
démarrage, elle échoue en production devant un utilisateur**. Le filet a été
retiré parce que la mémoire manquait. Elle ne manque plus.

⚠️ À faire **après** un premier déploiement réussi, pas en même temps : si le
démarrage échouait, on ne saurait pas lequel des deux changements en est la
cause.

Les autres réglages (`plan_cache_max_size`, threads Tomcat à 20,
`MaxRAMPercentage=45`) restent bons : ils ne coûtent rien et protègent d'un
retour en arrière.

---

## 5. Vérifier que ça marche

```bash
# 1. L'application répond
curl -s -o /dev/null -w "%{http_code}\n" https://<hôte-azure>/api/sante     # 200

# 2. C'est bien LA bonne version
curl -s https://<hôte-azure>/api/configuration

# 3. Le test qui décide du sort du Worker Cloudflare :
#    DEPUIS UNE CONNEXION ORANGE CAMEROUN, pas depuis un autre réseau.
curl -sv https://<hôte-azure>/api/sante 2>&1 | grep -E "SSL|Connected|refused"
```

Le point 3 est le seul qui tranche, et **il doit être fait depuis une
connexion Orange Cameroun** — pas depuis un autre réseau, pas depuis un
navigateur qui passerait par un VPN.

Le filtrage décrit par D-22 portait sur le **nom d'hôte** lu dans la poignée
de main TLS (`garah-api.onrender.com`) : avoir ouvert `portal.azure.com` ne
dit rien de `*.azurewebsites.net`, ce sont deux domaines différents.

---

## 6. L'ordre, et pourquoi il n'est pas négociable

**Le Worker Cloudflare reste devant pendant tout ce qui précède.** Le frontend
continue de l'appeler, les visiteurs ne voient rien changer, et Render reste
debout.

On ne bascule le frontend **qu'après** avoir vu l'API Azure répondre `200`
depuis une connexion Orange. Faire les deux d'un coup — changer d'hébergeur
*et* changer le chemin réseau — donnerait, en cas de panne, deux causes
possibles et aucun moyen de les départager.

Une fois le test passé, il reste trois gestes, dans cet ordre :

1. `urlApi` dans les deux fichiers `environments/*.ts` → l'hôte Azure ;
2. `GARAH_CORS_ORIGINS` côté Azure → l'origine du frontend ;
3. supprimer `proxy-cloudflare.js` et `wrangler.toml`, et marquer D-22
   « remplacée par D-27 ».

Et seulement ensuite, éteindre le service Render.
