# Chapitre 06 — Structurer un projet Spring Boot par domaines

> Prérequis : chapitre [05](05-postgresql-et-le-schema.md).
> Durée de lecture : ~30 min.

---

## 1. Ce qu'on veut faire

Le schéma existe. Il faut maintenant décider **où le code va vivre**.

Ça paraît secondaire. Ça ne l'est pas : la structure des packages décide de
ce qui sera facile et de ce qui sera pénible pendant deux ans. Et contrairement
à une classe qu'on renomme, une structure se change difficilement — parce que
tout le monde s'y est habitué.

---

## 2. La notion : découper par couche ou par domaine ?

Il y a exactement deux façons de ranger un projet Spring Boot. On voit la
première partout dans les tutoriels, et c'est la mauvaise.

### 2.1 Le découpage par couche (à éviter)

```text
com.garah.api
├── controller
│   ├── ProduitController
│   ├── CommandeController
│   ├── ExpeditionController
│   └── … 40 autres
├── service
│   ├── ProduitService
│   ├── CommandeService
│   └── … 40 autres
├── repository
│   └── … 58 repositories
└── entity
    └── … 58 entités
```

**Le problème n'est pas l'esthétique, c'est le mouvement.**

Ajouter « publier un produit » oblige à toucher `controller/`, `service/`,
`repository/`, `entity/`, `dto/` — cinq dossiers éloignés les uns des autres.
Et surtout : **rien n'empêche `ProduitService` d'appeler `ExpeditionRepository`**.
Tout est au même niveau, donc tout peut parler à tout.

Au bout d'un an, plus personne ne sait ce qui dépend de quoi. On appelle ça
une « boule de boue » — et le pire, c'est qu'elle ressemblait à un projet
bien rangé au premier jour.

### 2.2 Le découpage par domaine (retenu)

```text
com.garah.api
├── catalogue
│   ├── domaine        Produit, Variante, règles métier
│   ├── infra          ProduitRepository
│   └── web            ProduitController, DTO
├── commerce
│   ├── domaine
│   ├── infra
│   └── web
├── stock
└── …
```

**Ce qui change vraiment :**

| | Par couche | Par domaine |
|---|---|---|
| Ajouter une fonctionnalité | Toucher 5 dossiers éloignés | Rester dans **un** dossier |
| Comprendre un module | Lire des fichiers dispersés | Ouvrir un dossier |
| Voir les dépendances | Impossible | Les `import` les montrent |
| Extraire un module plus tard | Un chantier | Déplacer un dossier |

> 🎯 **La règle qui décide :**
> range ensemble ce qui **change ensemble**.
>
> Quand tu modifies le catalogue, tu ne touches jamais la logistique.
> Quand tu modifies le contrôleur produit, tu touches presque toujours le
> service produit. La proximité doit refléter ça.
>
> C'est exactement le même critère qu'au chapitre 03 pour découper le modèle
> en domaines. **Le code reprend le découpage des données** — et c'est
> volontaire : les deux répondent au même métier.

---

## 3. La structure de GARAH

```text
backend/src/main/java/com/garah/api/
│
├── GarahApplication.java
│
├── commun/                    ← le noyau partagé, sans métier
│   ├── erreur/                    exceptions + réponse d'erreur normalisée
│   └── web/                       gestionnaire d'erreurs global
│
├── config/                    ← configuration technique
│   └── ConfigurationCors.java
│
├── iam/                       ← domaine 1
├── marchand/                  ← domaine 2
├── catalogue/                 ← domaines 3 et 12
├── stock/                     ← domaine 4
├── commerce/                  ← domaine 5
├── serviceclient/             ← domaine 6
├── logistique/                ← domaines 7 et lieux
├── sav/                       ← domaine 8
├── finance/                   ← domaine 9
├── surveillance/              ← domaine 10
└── mesure/                    ← domaine 11
```

Et dans chaque domaine, **toujours les trois mêmes sous-packages** :

```text
catalogue/
├── domaine/      Les entités JPA et la logique métier.
│                 Ne connaît NI le web NI la base.
├── infra/        Les repositories Spring Data, l'accès au stockage objet.
│                 Sait parler à la base.
└── web/          Les contrôleurs REST et les DTO.
                  Sait parler HTTP.
```

> 💡 **Pourquoi `domaine` ne connaît ni le web ni la base.**
> Parce que la règle « un produit publié a au moins une variante avec un prix »
> est vraie qu'on l'appelle depuis une API REST, depuis un import de fichier
> ou depuis un test. Elle ne doit dépendre d'aucun des trois.
>
> Le jour où tu ajoutes un import CSV, tu réutilises `domaine` tel quel.
> Si la règle vivait dans le contrôleur, tu la réécrirais — donc tu la
> réécrirais **différemment**.

---

## 4. La règle des dépendances

C'est le cœur du chapitre. Une structure sans règle de dépendance ne sert à rien.

### 4.1 À l'intérieur d'un domaine

```text
web  ──────▶  domaine  ◀──────  infra

     Le web appelle le domaine.
     L'infra sert le domaine.
     Le domaine n'appelle NI l'un NI l'autre.
```

### 4.2 Entre domaines

```text
✅ commerce  ──▶  stock       « réserver 3 unités »
✅ commerce  ──▶  catalogue   « quel est le prix de cette variante ? »
❌ stock     ──▶  commerce    interdit : cela créerait un CYCLE
```

**Deux domaines ne doivent jamais dépendre l'un de l'autre.**

Un cycle, c'est deux modules qui n'en sont plus qu'un : on ne peut plus les
tester, les comprendre ni les déplacer séparément.

### 4.3 Que faire quand on a besoin d'un cycle

Ça arrive, et c'est un **signal**, pas une fatalité.

```text
Le problème :
    commerce a besoin de stock            (réserver à la commande)
    stock a besoin de commerce            (savoir quelle commande a réservé)

La mauvaise solution :
    s'appeler mutuellement.

La bonne :
    stock ne connaît PAS commerce.
    mouvement_stock porte origine_type = 'COMMANDE' + origine_id.
    Un identifiant opaque, pas une dépendance.
```

Et c'est exactement ce que fait le schéma du chapitre 05 : `origine_type` et
`origine_id` sont des colonnes **sans clé étrangère**. On l'avait fait pour
des raisons de modélisation ; on découvre ici que ça sert aussi à casser un
cycle de code.

> 📌 **Quand le modèle de données et le découpage du code tombent d'accord
> tout seuls, c'est en général que les deux sont justes.**

### 4.4 Comment empêcher les cycles pour de vrai

Une règle qu'on ne vérifie pas est une règle qu'on viole. Le jour où on est
pressé, on ajoute l'`import` interdit, et personne ne le voit en relecture.

**ArchUnit** est une bibliothèque de test qui vérifie l'architecture comme on
vérifie un calcul :

```java
@Test
void aucun_cycle_entre_domaines() {
    slices().matching("com.garah.api.(*)..")
            .should().beFreeOfCycles()
            .check(classes);
}

@Test
void le_domaine_ne_connait_pas_le_web() {
    noClasses().that().resideInAPackage("..domaine..")
        .should().dependOnClassesThat().resideInAPackage("..web..")
        .check(classes);
}
```

Ces deux tests échouent **au build**, avant la relecture. On les écrira au
chapitre 07, en même temps que les premières entités.

---

## 5. Le noyau commun

`commun/` contient ce que tous les domaines utilisent et qui ne contient
**aucun métier**.

> ⚠️ **Le piège du package `commun`** : il devient le dépotoir du projet.
> Tout ce qui ne trouve pas sa place y atterrit, et au bout d'un an il pèse
> plus lourd que les domaines.
>
> **La règle : on ne met dans `commun` que ce qui est utilisé par au moins
> trois domaines ET qui ne contient aucune règle métier.**
> Sinon, ça appartient à un domaine.

### 5.1 Les erreurs métier

Trois exceptions couvrent tous les cas de GARAH :

| Exception | Quand | Code HTTP |
|---|---|---|
| `RessourceIntrouvable` | L'objet demandé n'existe pas | 404 |
| `RegleMetierViolee` | La demande est invalide métier | 422 |
| `ConflitEtat` | L'objet n'est pas dans le bon état, ou quelqu'un a été plus rapide | 409 |

`ConflitEtat` mérite une explication : c'est l'exception des **machines à
états** du chapitre 04 et de la **concurrence** du chapitre 05.

```text
« Cette conversation a déjà été prise par un autre responsable. »   409
« Cette commande est déjà expédiée, elle ne peut plus être annulée. » 409
« Il ne reste que 2 unités en stock. »                              409
```

Ce n'est pas une erreur du client : c'est le monde qui a changé entre le
moment où il a affiché la page et celui où il a cliqué. Le message doit le
dire ainsi, pas accuser l'utilisateur.

### 5.2 La réponse d'erreur normalisée

Toutes les erreurs de l'API ont **la même forme**. Les trois frontends peuvent
alors écrire **un seul** gestionnaire d'erreurs.

```json
{
  "code": "STOCK_INSUFFISANT",
  "message": "Il ne reste que 2 unités disponibles.",
  "champs": { "quantite": "maximum 2" },
  "horodatage": "2026-09-06T02:41:30Z",
  "chemin": "/api/commandes"
}
```

Le `code` est **stable et technique** — c'est lui que le frontend teste,
et c'est lui qui sert de clé de traduction (`erreur.STOCK_INSUFFISANT`).
Le `message` est humain, et il peut changer sans rien casser.

> 🎯 **Exactement le même principe que `cas_utilisation.code` au chapitre 03 :**
> un code stable pour la machine, un libellé traduisible pour l'humain.
> Ce motif reviendra partout dans le projet.

### 5.3 Le cas particulier des violations de contraintes

Le chapitre 04 posait une règle :

> *La base est la dernière ligne de défense, jamais la seule ligne visible.*

Que se passe-t-il si une contrainte SQL est quand même violée ? Le client
recevrait :

```text
500 — ERREUR: la nouvelle ligne de la relation « stock » viole
      la contrainte de vérification « stock_quantites_positives »
```

Illisible, et ça expose la structure interne de la base.

Le gestionnaire global fait donc deux choses :

1. **Il traduit** le nom de la contrainte en message compréhensible.
2. **Il journalise un avertissement**, parce qu'arriver là signifie que le
   service **aurait dû** vérifier avant. Une contrainte qui se déclenche est
   un **bug de service**, pas un fonctionnement normal.

C'est ce second point qui compte. Sans ce log, on ne saurait jamais qu'un
contrôle manque quelque part.

---

## 6. Les pièges de ce chapitre

### Piège 1 — Créer les 12 domaines vides tout de suite

Douze dossiers vides donnent l'impression d'avancer et ne servent à rien.
Un package se crée quand on écrit sa première classe. Le plan est dans ce
chapitre ; le disque n'a pas besoin de le refléter à l'avance.

### Piège 2 — Faire porter la transaction au contrôleur

`@Transactional` sur un contrôleur, c'est ouvrir une transaction pendant la
sérialisation JSON, la validation, et parfois un appel réseau. La transaction
appartient au **service** — c'est lui qui sait ce qui doit être vrai ensemble
(chapitre 04, §7).

### Piège 3 — `open-in-view` laissé à `true`

Spring Boot l'active par défaut. La session Hibernate reste ouverte pendant
la génération de la réponse, ce qui fait « marcher » du code qui charge des
données paresseusement depuis un contrôleur — et déclenche des dizaines de
requêtes invisibles.

Déjà désactivé dans `application.yml` :

```yaml
spring.jpa.open-in-view: false
```

Ça rend certaines erreurs visibles **tout de suite** plutôt qu'en production
sous charge. C'est inconfortable, et c'est le but.

### Piège 4 — Le filet de sécurité trop large

Celui-ci, je l'ai fait en écrivant ce chapitre. Il mérite d'être raconté.

Le gestionnaire global se termine par un attrape-tout :

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ReponseErreur> inattendue(Exception e, …) {
    return 500 ERREUR_INTERNE;
}
```

L'intention est bonne : qu'aucune trace technique ne fuie vers le client.

Le résultat au premier essai :

```text
GET /api/inexistant
→ 500 ERREUR_INTERNE     ❌ ça devrait être un 404
```

**Pourquoi ?** Spring signale une route inconnue par une exception
(`NoResourceFoundException`) qu'il sait très bien traduire en `404` tout seul.
Mais mon attrape-tout la capturait **avant** lui.

> 🎯 **La leçon :**
> un filet trop large n'attrape pas seulement ce qu'on a oublié —
> il attrape aussi **ce que le framework gérait très bien**.
>
> Il faut donc lister explicitement les exceptions de routage et de format
> (route inconnue, méthode non autorisée, JSON illisible, paramètre manquant)
> **avant** l'attrape-tout. Spring les résout dans l'ordre du plus spécifique
> au plus général.

Et la leçon secondaire, plus importante encore : **je ne l'ai vu qu'en
appelant l'API pour de vrai**. Ce genre de défaut ne se voit ni à la
compilation, ni à la relecture.

### Piège 5 — Confondre DTO et entité

Une entité JPA est le reflet d'une table. Un DTO est le contrat de l'API.
Les exposer directement paraît économique, et ça revient à publier ton schéma
de base : le moindre renommage de colonne casse les trois frontends, et un
`mot_de_passe` finit un jour dans une réponse JSON.

**Une entité ne sort jamais du package `domaine`.** Chapitre 07.

---

## 7. À retenir

1. **Range ensemble ce qui change ensemble.** Par domaine, jamais par couche.
2. Trois sous-packages par domaine : `domaine`, `infra`, `web`.
3. **`domaine` ne connaît ni le web ni la base.** C'est ce qui le rend réutilisable et testable.
4. **Aucun cycle entre domaines.** Un besoin de cycle se casse avec un identifiant opaque (`origine_type` + `origine_id`).
5. Une règle d'architecture non vérifiée est violée : **ArchUnit** la teste au build.
6. `commun` ne contient que ce qui sert à ≥ 3 domaines **et** n'a aucun métier.
7. Toutes les erreurs ont **la même forme**, avec un `code` stable et un `message` traduisible.
8. Une contrainte SQL qui se déclenche est un **bug de service** : on la traduit *et* on la journalise.

---

## 8. Exercices

**Exercice 1.**
Dans quel domaine ranges-tu la table `piece_jointe` ? Elle sert aux messages,
aux réclamations, aux incidents et aux événements d'expédition. Justifie —
et vérifie ta réponse avec la règle du §5.

**Exercice 2.**
Le domaine `commerce` doit connaître le prix d'une variante. Écris les deux
façons de le faire (appel au service catalogue, ou lecture directe de la table
`tarification`), et dis laquelle respecte la règle des dépendances.

**Exercice 3.**
Un développeur ajoute `import com.garah.api.commerce.domaine.Commande;` dans
le domaine `stock`. Quel test doit échouer, et que faut-il faire à la place ?

**Exercice 4.**
Le client reçoit `409 CONFLIT_ETAT — cette conversation a déjà été prise`.
Décris ce que le frontend devrait faire, et pourquoi un `400` serait un
mauvais choix ici.

---

➡️ **Chapitre suivant :** 07 — Entités JPA, repositories et transactions
