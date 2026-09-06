# Chapitre 07 — Entités JPA, repositories et transactions

> Prérequis : chapitres [05](05-postgresql-et-le-schema.md) et [06](06-structurer-spring-boot.md).
> Durée de lecture : ~40 min.
> Domaine traité : **IAM**, parce que tout le reste en dépend.

---

## 1. Ce qu'on veut faire

Le schéma existe (chapitre 05). La structure du code existe (chapitre 06).
Il faut maintenant **relier les deux** : des classes Java qui reflètent les
tables, et un service qui calcule les permissions effectives d'un responsable.

C'est le premier code métier de GARAH. Et le domaine IAM n'est pas choisi au
hasard : **rien d'autre ne peut être sécurisé tant qu'il n'existe pas**.

---

## 2. La notion : ce qu'un ORM fait, et ce qu'il ne fait pas

Un **ORM** (Hibernate, ici) traduit des objets Java en SQL et inversement.

| Il fait très bien | Il fait mal, ou pas du tout |
|---|---|
| Charger et enregistrer un objet et ses relations | Les requêtes analytiques et les agrégats |
| Suivre les modifications et écrire les `UPDATE` | Les CTE, les fonctions de fenêtrage, `FILTER` |
| Gérer la transaction et le cache de premier niveau | Les opérations de masse |
| Éviter d'écrire du SQL répétitif | Rendre le SQL inutile |

> 🎯 **La règle du projet :**
> l'ORM pour **manipuler des objets métier**, le SQL pour **répondre à des
> questions**.
>
> On le verra concrètement au §7 : le calcul des permissions est écrit en SQL
> natif, parce que c'est une **question** (« quels codes cet homme a-t-il ? »),
> pas une manipulation d'objets.

---

## 3. Pas d'héritage JPA : la composition

Le schéma décrit un héritage :

```text
utilisateur (id)
    ├── client      (id → utilisateur.id)
    └── responsable (id → utilisateur.id)
```

JPA sait exprimer ça avec `@Inheritance(strategy = JOINED)`. **On ne l'utilise
pas.** Deux raisons, et la première est décisive.

### 3.1 Un `ADMIN` n'a pas de table fille

L'héritage JPA a besoin d'un **discriminateur** : une valeur par classe.
Or notre colonne `type` prend **quatre** valeurs, dont deux
(`ADMIN`, `SUPER_ADMIN`) correspondent à la même classe racine.
`@DiscriminatorValue` n'accepte qu'une valeur. Impasse.

### 3.2 L'héritage rend toute requête polymorphe

Avec `JOINED`, charger un `Utilisateur` fait joindre **toutes** les tables
filles — même quand on veut juste afficher un nom.

### 3.3 La solution : `@MapsId`

```java
@Entity
public class Client {

    @Id
    private Long id;

    @MapsId                                    // « ma clé primaire EST cette FK »
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id")
    private Utilisateur utilisateur;
}
```

C'est **exactement** ce que fait le schéma, dit en une annotation.
Pas de magie polymorphe, pas de discriminateur, et un `Client` reste un objet
qu'on peut charger seul.

> 📌 **Leçon générale :** l'héritage JPA est presque toujours plus coûteux que
> la composition. Ne l'utilise que si tu as réellement besoin de requêter la
> hiérarchie de façon polymorphe (« tous les utilisateurs, quel que soit leur
> type, avec leurs champs spécifiques »). Ce n'est pas le cas ici.

---

## 4. Les énumérations : `STRING`, jamais `ORDINAL`

```java
@Enumerated(EnumType.STRING)          // ✅
@Column(nullable = false, length = 20)
private TypeUtilisateur type;
```

`EnumType.ORDINAL` (le **défaut** de JPA !) stocke la **position** : 0, 1, 2…

```text
Aujourd'hui        Après insertion de MODERATEUR en 2e position
0 SUPER_ADMIN      0 SUPER_ADMIN
1 ADMIN            1 MODERATEUR      ← toutes les lignes "1" en base
2 RESPONSABLE      2 ADMIN              étaient des ADMIN, elles
3 CLIENT           3 RESPONSABLE        deviennent des MODERATEUR
                   4 CLIENT
```

**Toutes les données existantes changent de sens, sans une seule erreur.**

Ici la contrainte `CHECK` de la base l'aurait de toute façon empêché — mais
compter sur ça est une mauvaise habitude. `STRING` toujours, sans exception.

---

## 5. Les clés composites

`responsable_categorie` a une clé primaire à deux colonnes. Le motif JPA :

```java
@Embeddable
public static class Cle implements Serializable {
    @Column(name = "responsable_id") private Long responsableId;
    @Column(name = "categorie_id")   private Long categorieId;
}

@EmbeddedId
private Cle cle = new Cle();

@MapsId("responsableId")              // « cette association remplit ce morceau de clé »
@ManyToOne @JoinColumn(name = "responsable_id")
private Responsable responsable;

@MapsId("categorieId")
@ManyToOne @JoinColumn(name = "categorie_id")
private CategorieResponsable categorie;
```

Sans les `@MapsId`, JPA verrait les colonnes **deux fois** (dans la clé et dans
l'association) et refuserait de démarrer.

### Quand une table de liaison devient-elle une entité ?

| Situation | Mapping |
|---|---|
| Liaison **sans** attribut (`categorie_cas_utilisation`) | `@ManyToMany` suffit |
| Liaison **avec** attribut (`responsable_categorie` et son `principale`) | Une vraie entité |

Dès qu'il y a une colonne en plus, `@ManyToMany` ne suffit plus.

---

## 6. Le piège qui m'a coûté deux tests

Celui-ci mérite d'être raconté en détail, parce qu'il est **silencieux** — et
que je l'ai commis en écrivant ce chapitre.

### Le symptôme

Un responsable rattaché à **deux** catégories. Le test :

```text
Attendu : PRIX_MODIFIER, PRODUIT_PUBLIER, EXPEDITION_CREER
Obtenu  : PRIX_MODIFIER, PRODUIT_PUBLIER
```

La seconde catégorie a disparu. **Aucune erreur, aucune exception.**

### La cause

`ResponsableCategorie` est rangé dans un `Set`. Et son `equals` était fondé
sur la clé composite :

```java
// ❌ Le code fautif
public boolean equals(Object autre) {
    return autre instanceof ResponsableCategorie rc && Objects.equals(cle, rc.cle);
}
```

Or **au moment du `new`, la clé est encore vide**. C'est `@MapsId` qui la
remplit, et seulement au `flush`.

```text
new ResponsableCategorie(paul, commercial)   →  cle = (null, null)
new ResponsableCategorie(paul, logistique)   →  cle = (null, null)

                    (null,null).equals((null,null))  →  TRUE

Set.add(second)  →  « j'ai déjà cet élément »  →  ignoré en silence
```

### La correction

Comparer les **associations**, qui sont renseignées dès la construction :

```java
public boolean equals(Object autre) {
    return autre instanceof ResponsableCategorie rc
        && Objects.equals(responsable, rc.responsable)
        && Objects.equals(categorie, rc.categorie);
}
```

> ⚠️ **La règle à retenir :**
> ne fonde jamais `equals`/`hashCode` sur une valeur **que la base attribuera
> plus tard**. Identifiant auto-généré, clé composite remplie par `@MapsId`,
> date posée par un `DEFAULT` : tant que l'objet n'est pas enregistré, ces
> champs sont `null` — et **tous les objets neufs se ressemblent**.
>
> Et le pire : ça ne lève aucune erreur. Ça perd des données, poliment.

> 💡 **Pourquoi le test l'a attrapé et pas la relecture.**
> Le code fautif est parfaitement lisible. Il « dit » la bonne chose. Seule
> l'exécution montre que le second élément n'arrive jamais en base.
> C'est exactement le genre de défaut pour lequel les tests existent.

---

## 7. Charger les données : le problème N+1

### Le piège

```java
List<Responsable> tous = responsables.findAll();      // 1 requête
for (Responsable r : tous) {
    System.out.println(r.titre());                    // + 1 requête CHACUN
}
```

Avec 200 responsables : **201 requêtes**. Le code Java est identique, seul le
nombre d'allers-retours change — et il ne se voit **que** dans les logs SQL.

### Pourquoi `LAZY` malgré tout

On garde `fetch = FetchType.LAZY` partout. `EAGER` réglerait le N+1… en
chargeant **toujours** tout, même quand on n'en a pas besoin. On remplacerait
un problème visible par un ralentissement diffus, bien plus difficile à
diagnostiquer.

### La solution : charger explicitement

```java
@Query("""
        SELECT DISTINCT r FROM Responsable r
          LEFT JOIN FETCH r.categories rc
          LEFT JOIN FETCH rc.categorie
         WHERE r.id = :id
        """)
Optional<Responsable> chargerAvecCategories(Long id);
```

Une requête, tout est là.

> 📌 **La règle : `LAZY` par défaut, `JOIN FETCH` quand on sait qu'on en aura
> besoin.**
> Le chargement est une décision **de l'appelant**, pas une propriété de
> l'entité. Deux écrans différents n'ont pas les mêmes besoins.

---

## 8. Les transactions

### Où la poser

```java
@Service
public class ServicePermissions {

    @Transactional(readOnly = true)
    public Set<String> permissionsEffectives(Long responsableId) { … }

    @Transactional
    public void poserException(…) { … }
}
```

**Sur le service, jamais sur le contrôleur.** Le contrôleur sérialise du JSON
et valide des entrées ; garder une transaction ouverte pendant ce temps, c'est
tenir une connexion de base pour rien — et Neon en compte peu (D-14).

### `readOnly = true` n'est pas décoratif

Sur une lecture, il fait trois choses :

1. Hibernate saute la **détection des modifications** (le *dirty checking*).
2. Le pilote peut router vers un **réplica en lecture**, le jour où il y en a un.
3. Ça **documente l'intention** : cette méthode ne modifie rien.

Sur la requête la plus appelée de l'application, ça compte.

### Le rappel du chapitre 04

> **Ce qui doit être vrai ensemble s'écrit ensemble. Ce qui peut échouer seul
> sort de la transaction.**

Un e-mail de confirmation ne doit jamais faire échouer une commande.

---

## 9. Le calcul des permissions

C'est la requête la plus exécutée de GARAH : elle tourne à **chaque appel
authentifié**.

```sql
WITH depuis_categories AS (
    SELECT DISTINCT ccu.cas_utilisation_id
      FROM responsable_categorie rc
      JOIN categorie_cas_utilisation ccu ON ccu.categorie_id = rc.categorie_id
     WHERE rc.responsable_id = :responsableId
),
ajouts   AS (SELECT cas_utilisation_id FROM responsable_cas_utilisation
              WHERE responsable_id = :responsableId AND type = 'ADD'),
retraits AS (SELECT cas_utilisation_id FROM responsable_cas_utilisation
              WHERE responsable_id = :responsableId AND type = 'REMOVE')
SELECT cu.code
  FROM cas_utilisation cu
 WHERE cu.statut = 'ACTIF'
   AND cu.id IN     (SELECT cas_utilisation_id FROM depuis_categories
                     UNION
                     SELECT cas_utilisation_id FROM ajouts)
   AND cu.id NOT IN (SELECT cas_utilisation_id FROM retraits)
 ORDER BY cu.code
```

**Pourquoi du SQL natif et pas du JPQL :**

- JPQL ne connaît pas les CTE (`WITH`) ;
- on ne charge **aucune entité**, juste des chaînes ;
- la requête doit rester lisible à côté du chapitre 03, qui l'explique.

### Le point subtil, testé

`PRIX_MODIFIER` est donné par **deux** catégories. Un seul `REMOVE` doit le
retirer **entièrement**.

```text
union des deux catégories   →  PRIX_MODIFIER, PRODUIT_PUBLIER, EXPEDITION_CREER
− REMOVE PRIX_MODIFIER      →  PRODUIT_PUBLIER, EXPEDITION_CREER          ✅
```

Le `NOT IN (retraits)` s'applique **à la fin**, sur l'union complète.
Si la soustraction se faisait catégorie par catégorie, la seconde redonnerait
le droit — et personne ne s'en apercevrait avant un contrôle sur une remise
non autorisée.

C'est l'exercice 5 du chapitre 04, devenu un test.

---

## 10. Tester contre une vraie base

### Pourquoi pas H2

H2 est une base en mémoire, rapide, sans installation. Et **inutilisable ici**.

Rien de ce qui protège GARAH n'y existe :

```text
❌ index uniques partiels      ❌ contraintes d'exclusion GiST
❌ CTE dans certains modes      ❌ jsonb, inet
❌ nos triggers PL/pgSQL
```

> ⚠️ **Un test qui passe sur H2 et échoue en production est pire qu'un test
> absent** : il donne confiance à tort.

Les tests tournent donc sur `garah_test`, une vraie base PostgreSQL, où Flyway
rejoue les **mêmes** migrations que la production.

### Comment les tests se nettoient

```java
@SpringBootTest
@Transactional            // ← chaque test est annulé à la fin
class ServicePermissionsTest { … }
```

Chaque test s'exécute dans une transaction annulée à la sortie. Aucun code de
nettoyage, aucun ordre d'exécution à respecter, aucun test qui pollue le
suivant.

### Ce que couvrent les 7 tests

| Test | Ce qu'il protège |
|---|---|
| Les catégories multiples s'additionnent | D-02 |
| Le titre vient de la catégorie principale | Spec §6 |
| Un `ADD` ajoute une permission | Modèle d'exceptions |
| **Un `REMOVE` retire même si deux catégories la donnent** | **Le piège de D-02** |
| Une exception sans motif est refusée | Auditabilité |
| On ne peut pas inventer une permission | Chapitre 01 §4 |
| Un responsable inconnu lève une erreur explicite | Pas de `null` silencieux |

---

## 11. Tester l'architecture

Les règles du chapitre 06 sont désormais **vérifiées au build** :

```java
@Test
void aucunCycleEntreDomaines() {
    slices().matching("com.garah.api.(*)..").should().beFreeOfCycles().check(classes);
}

@Test
void lesEntitesRestentDansLeDomaine() {
    noClasses().that().resideInAPackage("..web..")
        .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
        .check(classes);
}
```

Ce dernier test est le plus utile du lot : il rend **impossible** d'exposer une
entité JPA dans une réponse JSON. Donc impossible de publier accidentellement
un `mot_de_passe`.

> 💡 **Une précision honnête sur le chapitre 06.**
> Il disait « `domaine` ne connaît ni le web ni la base ». En pratique,
> `ServicePermissions` (dans `domaine`) utilise les repositories (dans `infra`).
>
> L'orthodoxie hexagonale voudrait l'interface dans `domaine` et
> l'implémentation dans `infra`. Sur un projet à une personne, c'est de la
> cérémonie sans bénéfice : une interface de plus par repository, pour un
> découplage dont on ne se sert jamais.
>
> **La règle est donc précisée :** `domaine` ne connaît **pas le web**, et ne
> connaît de l'infrastructure que des **interfaces de repository**. Aucune
> classe Hibernate, aucun `EntityManager`, aucun objet Spring Web n'y entre.
> C'est ce que le test vérifie.

---

## 12. À retenir

1. **L'ORM pour manipuler des objets, le SQL pour répondre à des questions.**
2. **Composition (`@MapsId`) plutôt qu'héritage JPA** : plus explicite, plus prévisible.
3. `@Enumerated(EnumType.STRING)` **toujours**. `ORDINAL` est le défaut, et c'est un piège.
4. **Ne fonde jamais `equals`/`hashCode` sur une valeur que la base attribuera plus tard.** Ça perd des données en silence.
5. `LAZY` par défaut, `JOIN FETCH` quand on sait. Le chargement est la décision de l'appelant.
6. `@Transactional` sur le **service**, jamais sur le contrôleur. `readOnly = true` sur les lectures.
7. **Tester sur une vraie base PostgreSQL.** Un test qui passe sur H2 donne confiance à tort.
8. **ArchUnit** transforme une règle d'architecture en test qui échoue au build.

---

## 13. Exercices

**Exercice 1.**
`CasUtilisation.hashCode()` renvoie une constante et `equals()` compare l'`id`.
Explique pourquoi c'est correct ici, et pourquoi ça ne le serait pas pour
`ResponsableCategorie`.

**Exercice 2.**
Écris la requête qui liste les responsables ayant la permission
`PAIEMENT_REMBOURSER`. Attention : il faut tenir compte des `ADD` **et** des
`REMOVE`.

**Exercice 3.**
Un développeur ajoute `@Transactional` sur `ControleurSante`. Décris
précisément ce qui se dégrade, et à partir de combien d'utilisateurs ça devient
visible.

**Exercice 4.**
Reproduis le bug du §6 : remets `equals` sur la clé composite, lance les tests,
et observe **lesquels** échouent. Puis explique pourquoi le test `unAdd…`
passait quand même.

**Exercice 5.**
Le calcul des permissions tourne à chaque appel authentifié. Propose deux
façons de l'accélérer, et dis ce que chacune coûte en fraîcheur des droits
(que se passe-t-il quand un Admin retire un droit à quelqu'un de connecté ?).

---

➡️ **Chapitre suivant :** 08 — Authentification, JWT et contrôle des permissions
