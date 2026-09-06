# Chapitre 21 — Déploiement : Render, Neon, Backblaze B2

> Prérequis : chapitres [05](05-postgresql-et-le-schema.md), [08](08-authentification-et-permissions.md) et [20](20-tests-et-integration-continue.md).
> Durée de lecture : ~35 min.
> **Dernier chapitre avant Angular.**

---

## 1. Ce que le déploiement a révélé

Ce chapitre devait être une formalité : écrire un `Dockerfile`, un
`render.yaml`, une procédure. Il a trouvé **quatre défauts réels**, dont deux
qui rendaient le site vitrine impossible.

| Défaut | Gravité | Pourquoi les 150 tests ne l'ont pas vu |
|---|---|---|
| Le catalogue public exigeait un jeton | **bloquant** | Aucun test ne passait par HTTP |
| La fiche d'administration plantait en 500 | **bloquant** | Idem |
| Un refus de permission renvoyait 500 au lieu de 403 | grave | `@PreAuthorize` ne s'exécute pas hors HTTP |
| Toutes les URL d'images étaient des chemins relatifs | **bloquant** | Trouvé en lançant le jar, pas en le testant |

Les trois premiers ont été trouvés en écrivant le premier test HTTP. **Le
quatrième a été trouvé en lançant le jar** — aucun test ne l'aurait vu, parce
qu'il venait du fichier de configuration, pas du code.

Une leçon qui vaut pour tout le projet :

> 🎯 **Préparer un déploiement, c'est regarder son application depuis
> l'extérieur pour la première fois.** Tant qu'on l'appelle depuis ses propres
> tests, on ne voit que ce qu'on a pensé à regarder.

Reprenons-les un par un — ils sont plus instructifs que le `Dockerfile`.

---

## 2. La route publique qui ne l'était pas

Le contrôleur du catalogue, écrit au chapitre 09, portait ce commentaire :

```java
/** Le catalogue public. Aucune authentification : la vitrine est ouverte. */
@GetMapping
public Page<ResumeProduit> catalogue(…)
```

Et la configuration de sécurité, écrite au chapitre 08, disait ceci :

```java
.requestMatchers("/api/sante").permitAll()
.requestMatchers(HttpMethod.POST, "/api/auth/connexion").permitAll()
.anyRequest().authenticated()          // ← tout le reste, catalogue compris
```

**Les deux fichiers se contredisaient depuis douze chapitres.** Aucun test
n'était rouge, parce que tous les tests métier appellent
`catalogue.catalogue(…)` directement — la chaîne de filtres de sécurité n'est
alors jamais traversée.

> ⚠️ **Une règle de sécurité vit dans la configuration, pas dans le
> contrôleur.** Un commentaire qui décrit un comportement que rien ne vérifie
> finit toujours par mentir. Ici, il a menti sur la fonctionnalité la plus
> visible du produit : la page d'accueil.

Le défaut se serait manifesté au premier chargement du site vitrine, après le
déploiement, sous la forme d'une page vide.

### La correction, et le piège qu'elle contient

```java
.requestMatchers(HttpMethod.GET, "/api/produits").permitAll()
.requestMatchers(HttpMethod.GET, "/api/produits/{slug}").permitAll()
```

`HttpMethod.GET` n'est pas décoratif. Sans lui, la même règle ouvrirait
**`POST /api/produits`**, c'est-à-dire la création de produit.

C'est exactement l'erreur du chapitre 08 avec `/api/auth/**`, sous une autre
forme :

```text
chapitre 08   un joker de CHEMIN     ouvre plus de chemins que prévu
chapitre 21   un joker de MÉTHODE    ouvre plus de verbes que prévu
```

> 📌 **En sécurité, tout ce qu'on ne précise pas est ouvert.** On énumère
> toujours : la méthode, le chemin, et rien d'autre.

Et le test qui verrouille le piège :

```java
@Test
@DisplayName("ouvrir le GET du catalogue n'ouvre pas son POST")
void ouvrirLeGetNOuvrePasLePost() throws Exception {
    http.perform(post("/api/produits"))
            .andExpect(status().isUnauthorized());
}
```

---

## 3. `MultipleBagFetchException` : la fiche cassée à 100 %

Le premier test HTTP de la route d'administration a répondu `500`. La cause :

```text
MultipleBagFetchException: cannot simultaneously fetch multiple bags:
    [Produit.medias, Produit.variantes]
```

La requête, écrite au chapitre 09 :

```java
SELECT DISTINCT p FROM Produit p
  LEFT JOIN FETCH p.variantes
  LEFT JOIN FETCH p.medias
 WHERE p.id = :id
```

### Ce que je croyais, et ce qui se passe vraiment

Le commentaire que j'avais écrit au-dessus était **faux**, et c'est le plus
intéressant :

> « Deux `JOIN FETCH` provoquent un produit cartésien : 3 variantes × 4 médias
> = 12 lignes. Hibernate les dédoublonne grâce au `DISTINCT`. »

Hibernate ne dédoublonne pas. **Il refuse.**

Un *bag* est une `List` sans colonne d'ordre. Quand deux bags arrivent dans le
même résultat, Hibernate ne peut pas savoir quelle ligne du produit cartésien
appartient à quelle collection :

```text
variantes  S, M, L          médias  photo1, photo2
résultat   S/photo1  S/photo2  M/photo1  M/photo2  L/photo1  L/photo2

Combien de variantes ?  3 ou 6 ?
Combien de médias ?     2 ou 6 ?
```

Il n'y a pas de bonne réponse — alors il lève une exception au lieu de deviner.

> 🎯 **Le refus est le bon comportement.** Un ORM qui aurait « fait de son
> mieux » aurait renvoyé six photos au lieu de deux, et le back-office aurait
> affiché des doublons que personne n'aurait su expliquer.

### La correction : une collection à la fois

```java
Produit produit = produits.chargerAvecVariantes(produitId)
        .orElseThrow(() -> RessourceIntrouvable.de("Produit", produitId));

// Même instance gérée : cette requête ne fait qu'initialiser `medias`.
produits.chargerAvecMedias(produitId);

return DetailProduit.de(produit);
```

Le second appel **ne renvoie pas un autre objet**. Dans la même transaction,
Hibernate reconnaît l'entité déjà présente dans le contexte de persistance et
se contente de remplir sa collection.

```text
1 requête, 2 bags     →  refusée
2 requêtes, 1 bag     →  2 allers-retours, aucun produit cartésien
```

C'est aussi **plus efficace** que ce que je visais : 3 + 2 = 5 lignes
transportées au lieu de 12.

---

## 4. Le 403 devenu 500

Troisième défaut, trouvé par le même test.

```java
@ExceptionHandler(Exception.class)   // le filet de sécurité du chapitre 06
public ResponseEntity<ReponseErreur> inattendue(…) {
    log.error("Erreur inattendue sur {}", …);
    return ResponseEntity.status(500)…;
}
```

Ce filet attrapait aussi `AuthorizationDeniedException`, l'exception que
`@PreAuthorize` lève quand un jeton valide n'a pas la permission demandée.

**C'est la troisième fois que ce filet fait des dégâts** :

```text
chapitre 06   404  →  500     corrigé
chapitre 15   409  →  500     corrigé (via ERRCODE, migration V19)
chapitre 21   403  →  500     corrigé ici
```

> 📌 **Un `@ExceptionHandler(Exception.class)` n'attrape pas seulement ce qu'on
> a oublié : il attrape aussi tout ce que le framework gérait très bien.**
> Chaque fois qu'on ajoute une couche — routage, transactions, sécurité — il
> faut se demander ce qu'elle lève, et le traiter *avant* le filet.

### Deux conséquences, et la seconde est pire

```text
pour le client   « une erreur interne est survenue »
                 au lieu de « vous n'avez pas la permission »
                 → il rappelle le support, qui ne peut rien lui dire

pour l'équipe    CHAQUE refus d'accès journalisé en ERROR, avec sa pile
                 → un back-office normal en produit des dizaines par jour
                 → le journal des VRAIES erreurs devient illisible
```

La correction journalise en `INFO`, délibérément :

```java
log.info("Acces refuse sur {}", requete.getRequestURI());
```

> 💡 **Un refus d'accès est le fonctionnement normal d'un système de
> permissions, pas une panne.** Le journaliser comme une erreur, c'est
> apprendre à l'équipe à ignorer ses propres alertes.

### 401 et 403 ne disent pas la même chose

```text
401   je ne sais pas qui tu es          →  reconnecte-toi
403   je sais qui tu es, mais tu n'as   →  demande le droit
      pas le droit                          à un administrateur
```

Confondre les deux se paie en support : « je suis pourtant bien connecté ».
Le frontend ne peut pas réagir correctement si l'API ne fait pas la différence.

---

## 5. Le premier test HTTP, et pourquoi il arrive si tard

```java
@SpringBootTest
@AutoConfigureMockMvc
class SecuriteHttpTest {
```

Huit tests, qui portent tous sur **la frontière**, jamais sur le métier :

| Test | Ce qu'il prouve |
|---|---|
| `GET /api/produits` → 200 sans jeton | La vitrine fonctionne |
| `GET /api/produits/inexistant` → **404**, pas 401 | La route est ouverte, le produit absent |
| `POST /api/produits` → 401 | Ouvrir le GET n'a pas ouvert le POST |
| jeton sans permission → 403 | Le refus est lisible |
| jeton avec permission → 404 | La requête atteint bien le service |

Le deuxième mérite un mot. Attendre `404` plutôt que `200` est **volontaire** :

> 🎯 Un `401` dirait « la route est fermée ». Un `404` dit « la route est
> ouverte, mais ce produit n'existe pas ». C'est le second qu'on veut prouver,
> et il ne demande aucune donnée de test.

Et le dernier applique la même idée : on demande le produit `999999`, qui
n'existe pas. Un `404` prouve que la requête a traversé la sécurité **et**
atteint le service. Ni `401`, ni `403`, ni `500`.

> 📌 **Un test de sécurité se lit dans les codes de statut, pas dans les
> données.** C'est ce qui le rend rapide, stable, et indépendant du contenu de
> la base.

---

## 6. L'avertissement qu'on lisait depuis douze chapitres

À chaque démarrage, Spring Data affichait ceci :

```text
Serializing PageImpl instances as-is is not supported, meaning that there is
no guarantee about the stability of the resulting JSON structure!
```

Traduction : **le JSON de `GET /api/produits` est le reflet des champs internes
d'une classe de Spring Data.** Une montée de version peut les renommer — et les
trois applications Angular cessent d'afficher le catalogue, sans qu'aucun test
backend ne devienne rouge.

```java
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class ConfigurationWeb { }
```

La forme devient contractuelle :

```json
{
  "content": [ … ],
  "page": { "size": 24, "number": 0, "totalElements": 137, "totalPages": 6 }
}
```

> 💡 **Le faire maintenant ne coûte rien ; le faire après Angular obligerait à
> reprendre chaque écran de liste des trois applications.** Un avertissement de
> démarrage est une dette qui n'a pas encore de facture.

---

## 7. La clé d'objet, et pourquoi ce n'est pas une URL

Rappel de D-14 : la table `media` ne stocke pas d'URL.

```text
en base      produits/42/photo-1.jpg
affiché      https://f003.backblazeb2.com/file/garah-medias/produits/42/photo-1.jpg
```

```java
public String urlPublique(String cleObjet) {
    if (cleObjet == null || cleObjet.isBlank()) return null;
    String cle = cleObjet.strip();
    if (estAbsolue(cle)) return cle;
    return baseUrl + "/" + cle.replaceFirst("^/+", "");
}
```

Trois comportements, chacun corrigeant une erreur réelle :

| Cas | Comportement | Pourquoi |
|---|---|---|
| Clé `null` ou vide | renvoie `null` | Un produit sans photo est **normal**, pas une exception |
| Clé commençant par `/` | nettoyée | `…/garah-medias//produits/42.jpg` est un **autre objet** pour S3 |
| Clé déjà absolue | renvoyée telle quelle | Les lignes importées d'un ancien système seraient cassées sinon |

### Ce que ça achète

```text
avec une URL stockée   changer d'hébergeur = réécrire TOUTES les lignes
                       et, pendant la migration, la moitié du catalogue
                       pointe vers un hébergeur qu'on ne paie plus

avec une clé           changer d'hébergeur = UNE variable d'environnement
```

C'est la **règle de la photographie du chapitre 03, prise à l'envers** : on
copie ce qui a une valeur juridique (un prix, un nom de marchand), on référence
ce qui n'est qu'une adresse technique.

### 7.1 Le quatrième défaut : vide n'est pas absent

Avant d'écrire ce chapitre, j'ai construit le jar et je l'ai lancé — c'est ce
que fera Render. Tout répondait correctement :

```json
GET /api/sante           {"tables":58,"versionSchema":19,"permissionsActives":188,"etat":"OK"}
GET /api/produits        200
GET /api/auth/moi        401
```

Sauf ceci :

```json
GET /api/configuration   {"baseUrlMedias": "", …}
```

**Vide.** Alors que `GARAH_MEDIA_BASE_URL` figure bien dans le `.env`. La ligne
existait — mais sans valeur :

```properties
GARAH_MEDIA_BASE_URL=
```

Et voici la règle qu'il faut retenir :

> ⚠️ **Spring n'applique la valeur par défaut de `@Value("${CLE:defaut}")` que
> si la clé est ABSENTE, jamais si elle est VIDE.** Une ligne présente et vide
> gagne contre le défaut.

Ce que ça produisait :

```text
attendu   https://f003.backblazeb2.com/file/garah-medias/produits/42.jpg
obtenu    /produits/42.jpg
```

Un chemin relatif, résolu contre le domaine de l'API — qui ne sert aucun
fichier. **Toutes les images du site auraient été cassées, sans la moindre
erreur nulle part** : pas d'exception, pas de log, pas de 500. Juste des
vignettes vides sur toutes les pages.

> 🎯 **Un formulaire de configuration à moitié rempli suffisait à provoquer la
> panne la plus visible du produit.** C'est la forme la plus vicieuse de
> défaut : celle qui ne casse rien, et qui casse tout.

La correction ne se contente pas de replier — elle **le dit** :

```java
if (valeur.isBlank()) {
    log.warn("GARAH_MEDIA_BASE_URL n'est pas renseignee : repli sur {}. "
            + "En production, TOUTES les images seront introuvables.", DEFAUT);
    valeur = DEFAUT;
}
```

> 📌 **Un repli silencieux est un piège ; un repli bruyant est un filet.**
> La valeur par défaut est parfaite pour développer et catastrophique en
> production : elle doit se voir au démarrage.

Et cinq tests unitaires verrouillent le comportement — sans base, sans contexte
Spring, en quelques millisecondes. C'est la deuxième fonction vraiment pure du
projet après `Slug`, et elle a droit au même traitement.

---

## 8. `/api/configuration` : l'API annonce son propre déploiement

```json
GET /api/configuration        (publique)

{
  "version": "0.1.0",
  "baseUrlMedias": "https://f003.backblazeb2.com/file/garah-medias",
  "devise": "XAF",
  "langues": ["fr", "en", "sag"]
}
```

Pourquoi une route plutôt qu'un fichier de configuration Angular :

```text
dans les 3 frontends   un changement d'hébergeur = 3 modifications,
                       3 compilations, 3 déploiements
                       → le jour où l'un des trois est oublié, ses images
                         disparaissent et RIEN ne le signale

dans l'API             l'API connaît son propre déploiement. Elle l'annonce.
```

> ⚠️ Cette route est **publique** : le site vitrine l'appelle avant qu'un
> visiteur ait un compte. Elle ne doit donc contenir que ce qu'on accepterait
> d'imprimer sur une affiche. **Jamais une clé d'API, jamais un identifiant de
> bucket privé.**

---

## 9. Le `Dockerfile` : deux étapes, et pourquoi

```dockerfile
FROM eclipse-temurin:24-jdk AS construction
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline      # ← couche mise en cache
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:24-jre
RUN useradd --create-home --shell /bin/false garah
USER garah
WORKDIR /app
COPY --from=construction /build/target/*.jar application.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
```

### 9.1 Deux étapes, ce n'est pas de la coquetterie

```text
en une étape    JDK + Maven + sources + dépôt de dépendances    ~800 Mo
en deux étapes  JRE + le jar                                    ~250 Mo
```

Sur l'offre gratuite de Render, **l'instance s'endort après quinze minutes
d'inactivité** (D-14). Chaque réveil paie le démarrage — et une image trois
fois plus petite se charge trois fois plus vite.

### 9.2 L'ordre des `COPY` détermine le cache

C'est le détail qui change tout, et il paraît arbitraire :

```dockerfile
COPY mvnw pom.xml ./           # ← d'abord ceci
RUN ./mvnw dependency:go-offline
COPY src/ src/                 # ← ensuite seulement les sources
```

Docker met chaque couche en cache et **réutilise tout ce qui précède la
première ligne modifiée**. Tant que le `pom.xml` ne bouge pas, le
téléchargement de Spring Boot n'est pas refait.

```text
COPY src/ AVANT le pom     chaque commit retélécharge tout    ~3 min
COPY src/ APRÈS le pom     seul le code est recompilé         ~40 s
```

> 📌 **Dans un `Dockerfile`, on copie du plus stable au plus changeant.**
> C'est la seule règle à retenir sur le cache.

### 9.3 `MaxRAMPercentage` plutôt que `-Xmx`

```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
```

`-Xmx512m` écrirait une valeur **absolue** dans l'image. Le jour où l'on passe
à un plan Render plus grand, ou sur le VPS, la JVM continuerait d'utiliser
512 Mo sur une machine qui en offre quatre fois plus. `MaxRAMPercentage` lit la
limite **du conteneur**, quelle qu'elle soit.

`UseSerialGC` est le bon choix sur une seule petite instance : les ramasse-
miettes parallèles coûtent plus en threads qu'ils ne rapportent en dessous de
deux cœurs.

### 9.4 Un utilisateur non privilégié

```dockerfile
RUN useradd --create-home --shell /bin/false garah
USER garah
```

Sans ces deux lignes, l'application tourne en `root` **dans le conteneur**. Ça
ne donne pas la machine hôte à un attaquant, mais ça lui donne tout le reste :
écrire n'importe où, installer des outils, modifier le jar. Deux lignes.

### 9.5 Le `.dockerignore` protège un secret

```text
.env
.env.*
target/
.mvn/maven.config
```

`.env` contient le mot de passe de la base. **Le copier dans une image revient
à le publier** : une image se pousse dans un registre, se partage, se
télécharge, et ses couches se lisent avec `docker history`.

`.mvn/maven.config` est exclu pour une autre raison : il contient
`-Dmaven.repo.local=D:/Dev/.m2`, un chemin Windows qui n'existe pas dans le
conteneur.

---

## 10. `render.yaml` : la configuration se versionne

```yaml
services:
  - type: web
    name: garah-api
    runtime: docker
    plan: free
    region: frankfurt
    dockerfilePath: ./backend/Dockerfile
    dockerContext: ./backend
    healthCheckPath: /api/sante
    envVars:
      - key: GARAH_DB_PASSWORD
        sync: false
      - key: GARAH_JWT_SECRET
        generateValue: true
```

### Pourquoi un fichier plutôt que des clics

Un service configuré à la main **n'existe que dans la tête de celui qui l'a
créé**. Ici, la configuration se relit, se commente, et se restaure à
l'identique le jour où l'instance est perdue.

### `sync: false` et `generateValue: true`

```text
sync: false          Render DEMANDE la valeur au premier déploiement,
                     la chiffre, et ne l'écrit jamais dans le fichier
                     → donc jamais dans git

generateValue: true  Render TIRE une valeur aléatoire et la garde
                     → personne ne la connaît, donc personne ne peut
                       la divulguer
```

`GARAH_JWT_SECRET` est en `generateValue`. C'est mieux qu'un secret choisi par
un humain, pour une raison qui n'a rien de technique : un humain choisit un
secret qu'il peut retenir, donc qu'un autre peut deviner.

> ⚠️ Changer ce secret **invalide tous les jetons en circulation** : tous les
> clients connectés sont déconnectés. C'est le comportement voulu — c'est même
> la procédure d'urgence si un jeton fuit.

### `healthCheckPath` n'est pas décoratif

```yaml
healthCheckPath: /api/sante
```

Render ne bascule le trafic vers la nouvelle version que si cette adresse
répond `200`. Sans elle, une instance **qui démarre mais dont Flyway a échoué**
serait annoncée « live » — et servirait des `500` à tout le monde.

Et `/api/sante` interroge réellement la base (chapitre 06) : elle ne répond pas
`200` si la connexion à Neon est cassée.

---

## 11. Les migrations en production : ce qui se passe au premier démarrage

C'est le moment le plus délicat du déploiement, et il est **automatique** :

```text
1. Render démarre le conteneur
2. Spring Boot ouvre la connexion à Neon
3. Flyway lit flyway_schema_history        → table absente, base vierge
4. Flyway applique V1 … V19                → 58 tables, 107 CHECK, 105 FK
5. AmorcageSuperAdmin crée le premier compte si les variables existent
6. /api/sante répond 200
7. Render bascule le trafic
```

**L'étape 4 est exactement ce que la CI vérifie à chaque commit** (chapitre 20
§6). C'est pour cela qu'on peut la laisser s'exécuter sans surveillance : elle
a déjà tourné des dizaines de fois sur une base vierge.

### Les deux points de vigilance

**`btree_gist`.** La migration `V1` crée cette extension, nécessaire à la
contrainte `EXCLUDE` des paliers de prix (chapitre 10). Sur Neon, elle fait
partie des extensions autorisées — mais tous les hébergeurs ne l'autorisent
pas, et l'échec serait immédiat et total.

**Le rôle de connexion.** L'utilisateur applicatif doit pouvoir créer des
tables au premier démarrage. Le principe du moindre privilège voudrait qu'il ne
le puisse plus ensuite ; en pratique, il faudra le conserver pour les
migrations suivantes. C'est un compromis assumé, à revoir sur le VPS où l'on
pourra séparer le rôle « migration » du rôle « application ».

---

## 12. La procédure, dans l'ordre

L'ordre n'est pas indifférent : chaque étape a besoin de la précédente.

```text
1. NEON        créer la base, récupérer l'URL (avec ?sslmode=require)
2. BACKBLAZE   créer le bucket PUBLIC garah-medias, une clé applicative
               → noter le préfixe public : c'est GARAH_MEDIA_BASE_URL
3. RENDER      connecter le dépôt, Render lit render.yaml
               → renseigner les variables sync: false
               → renseigner GARAH_SUPERADMIN_* pour ce premier déploiement
4. VÉRIFIER    GET /api/sante        → tables: 58, versionSchema: 19
               GET /api/configuration → la devise et le préfixe des médias
               GET /api/produits      → une page vide, mais 200 et non 401
5. SE CONNECTER avec le SuperAdmin, CHANGER le mot de passe
6. SUPPRIMER   GARAH_SUPERADMIN_MOT_DE_PASSE de l'environnement Render
7. VERCEL      déployer les trois frontends (chapitres 22 à 27)
8. RETOUR      mettre GARAH_CORS_ORIGINS à jour avec les 3 domaines réels
```

L'étape 6 n'est pas facultative :

> ⚠️ **Un mot de passe d'amorçage qui reste dans l'environnement est une clé
> sous le paillasson.** Il n'est utile qu'une fois ; il reste dangereux
> indéfiniment.

L'étape 8 est celle qu'on oublie, et son symptôme est déroutant : l'API répond
correctement, et **le navigateur refuse de livrer la réponse au JavaScript**.
La page reste vide sans aucune erreur serveur. Quand un frontend affiche du
vide alors que l'API répond `200`, il faut regarder CORS avant tout le reste.

---

## 13. Une réserve honnête, la seconde

Il faut séparer ce qui a tourné de ce qui n'a jamais tourné.

```text
✅ exécuté   les 163 tests, sur base vierge
✅ exécuté   ./mvnw -DskipTests package        → jar de 60 Mo
✅ exécuté   java -jar sur ce jar              → l'API répond réellement
             /api/sante        58 tables, versionSchema 19, 188 permissions
             /api/produits     200 sans jeton
             /api/auth/moi     401
             /api/configuration  ← c'est CE lancement qui a trouvé le 4e défaut

❌ jamais    le Dockerfile — Docker n'est pas installable ici
             (chapitre 05 : disque saturé, virtualisation désactivée)
❌ jamais    render.yaml, le déploiement, les migrations sur Neon
❌ jamais    le workflow de CI (réserve du chapitre 20 §8)
```

> 🎯 **Lancer le jar est ce qui distingue « les tests passent » de
> « l'application marche ».** Les deux lignes de commande ont coûté deux
> minutes et trouvé un défaut bloquant que 158 tests verts ne voyaient pas.
> Le jar est exactement l'artefact que Render exécutera ; le tester coûte
> moins cher que de le découvrir en production.

Ce qui reste le plus susceptible de demander un ajustement :

- la disponibilité du tag `eclipse-temurin:24-jre` ;
- le premier `dependency:go-offline`, qui peut dépasser le temps de
  construction alloué sur l'offre gratuite ;
- la création de `btree_gist` sur Neon.

> 💡 **Le dire est le sujet du chapitre.** Un `Dockerfile` qu'on n'a pas
> construit et une procédure qu'on n'a pas suivie sont des **hypothèses**, pas
> des livrables. Les présenter comme acquis serait la seule vraie faute — ce
> chapitre vient précisément de montrer ce que coûtent quatre hypothèses qu'on
> n'avait pas vérifiées.

---

## 14. Le backend est prêt à partir

```text
11 domaines     IAM, marchand, catalogue, stock, commerce, service client,
                logistique, SAV, finance, surveillance, mesure
19 migrations   58 tables, 107 CHECK, 105 clés étrangères, 2 triggers
163 tests       dont 4 d'architecture, 2 de concurrence, 8 de sécurité HTTP
3 routes        publiques et testées comme telles
1 jar           construit et lancé, qui répond réellement
```

---

## 15. À retenir

1. **Préparer un déploiement, c'est regarder son application depuis l'extérieur** — et ça trouve ce que les tests de service ne peuvent pas voir.
2. Une règle de sécurité vit dans **la configuration**, pas dans un commentaire de contrôleur.
3. En sécurité, **tout ce qu'on ne précise pas est ouvert** : le chemin *et* la méthode.
4. Hibernate **refuse** deux `JOIN FETCH` de `List` — et le refus vaut mieux qu'un résultat faux.
5. Deux requêtes dans la même transaction remplissent **la même instance** : c'est la correction, et elle transporte moins de lignes.
6. Un `@ExceptionHandler(Exception.class)` attrape aussi **ce que le framework gérait bien**. Troisième récidive dans ce projet.
7. **401 ≠ 403**, et un refus d'accès se journalise en `INFO` : c'est un fonctionnement normal.
8. Un test de sécurité se lit dans les **codes de statut**, pas dans les données. `404` prouve mieux qu'`200`.
9. Un **avertissement de démarrage est une dette sans facture** — on la paie avant que trois frontends en dépendent.
10. On stocke une **clé d'objet**, jamais une URL : changer d'hébergeur devient une variable d'environnement.
11. **Une variable vide n'est pas une variable absente** : `@Value("${CLE:defaut}")` n'applique son défaut que si la clé manque. Et un **repli silencieux est un piège** — il doit prévenir au démarrage.
12. **Lancer le jar n'est pas la même chose que lancer les tests.** Deux minutes, un défaut bloquant trouvé.
13. Dans un `Dockerfile`, on copie **du plus stable au plus changeant**.
14. La configuration d'un hébergeur **se versionne** ; les secrets, jamais.
15. Le mot de passe d'amorçage **se supprime après usage**.
16. **Ce qu'on n'a pas exécuté n'est pas fait** — et il faut le dire.

---

## 16. Exercices

**Exercice 1.**
Retire `HttpMethod.GET` des deux règles publiques du catalogue et lance
`SecuriteHttpTest`. Quel test tombe, et qu'aurait-il permis de faire à un
visiteur non authentifié ?

**Exercice 2.**
Remets les deux `JOIN FETCH` dans une seule requête et lance la suite. Puis
transforme `List<Media>` en `Set<Media>` : l'exception disparaît. Explique
pourquoi, et dis quel piège du chapitre 07 ce changement réintroduit.

**Exercice 3.**
Ajoute un test qui vérifie qu'un jeton **expiré** produit un `401` et non un
`500`. Indice : la validité se règle dans le jeton, pas dans la configuration.

**Exercice 4.**
Écris le `Dockerfile` en une seule étape et compare la taille de l'image.
Explique ensuite pourquoi cette différence compte davantage sur une offre
gratuite que sur un VPS.

**Exercice 5.**
Le déploiement échoue à l'étape 4 : `/api/sante` répond `500`. Liste les cinq
causes les plus probables, dans l'ordre où tu les vérifierais, et dis quelle
information de la réponse d'erreur oriente le diagnostic.

**Exercice 6.**
Ajoute au `render.yaml` un second service qui lance l'agrégation nocturne du
chapitre 19. Quelles précautions, sachant que l'instance gratuite s'endort et
que l'agrégation doit être idempotente ?

---

➡️ **Chapitre suivant :** 22 — L'espace de travail Angular et les trois applications
