# Chapitre 05 — PostgreSQL et le schéma

> Prérequis : chapitres [03](03-le-modele-corrige.md) et [04](04-regles-metier-et-invariants.md).
> Durée de lecture : ~30 min.
> **C'est le premier chapitre où quelque chose tourne vraiment.**

---

## 1. Ce qu'on veut faire

Transformer le modèle du chapitre 03 et les invariants du chapitre 04 en un
schéma PostgreSQL **réel**, **reproductible** et **versionné**.

Trois mots à peser :

| Mot | Ce qu'il exige |
|---|---|
| **Réel** | Le schéma existe en base, pas seulement dans un diagramme |
| **Reproductible** | N'importe qui, sur n'importe quelle machine, obtient exactement le même |
| **Versionné** | L'évolution du schéma vit dans git, à côté du code qui en dépend |

---

## 2. La notion : pourquoi des migrations

### 2.1 Les trois façons de créer un schéma, et pourquoi deux sont mauvaises

| Approche | Ce qui se passe en vrai |
|---|---|
| **À la main dans pgAdmin** | Ça marche sur ta machine. Personne d'autre n'a le même schéma. Six mois plus tard, plus personne ne sait ce qui a été fait. ❌ |
| **Hibernate `ddl-auto: update`** | Hibernate crée les tables depuis les entités Java. Confortable — et **catastrophique** ici. ❌ |
| **Des migrations SQL versionnées** | Une suite de fichiers `V1`, `V2`, `V3`… rejouée dans l'ordre. ✅ |

### 2.2 Pourquoi `ddl-auto` est disqualifié pour GARAH

C'est la question qu'on se pose toujours, alors répondons-y une fois pour toutes.

Hibernate génère le schéma à partir des annotations Java. Or les annotations
JPA **ne savent pas exprimer** :

```text
❌ les CHECK arithmétiques      montant_ligne = quantite × prix_unitaire
❌ les index uniques PARTIELS   un seul panier ACTIF par client
❌ les contraintes d'EXCLUSION  paliers de prix sans chevauchement
❌ les clés étrangères COMPOSITES  (id, type) → un point de récupération
❌ les TRIGGERS                 pas plus de colis que de commande
❌ les COMMENTAIRES             pourquoi cette colonne existe
```

Autrement dit : **`ddl-auto` perdrait tout le chapitre 04**.

Il resterait des tables et des clés étrangères — la partie facile — et aucune
des règles qui protègent réellement l'argent et le stock.

> 📌 **La règle du projet :**
> ```yaml
> spring.jpa.hibernate.ddl-auto: none
> ```
> **Le schéma appartient à Flyway. Hibernate le lit, il ne l'écrit jamais.**
>
> Et le corollaire, tout aussi important :
> quand on ajoute un champ à une entité Java, il faut **aussi** écrire la
> migration. Ce n'est pas une lourdeur, c'est le moment où on se demande
> quelle contrainte accompagne ce champ.

### 2.3 Comment Flyway fonctionne

C'est simple, et il faut le comprendre exactement.

```text
src/main/resources/db/migration/
    V1__referentiels.sql
    V2__iam.sql
    V3__marchands.sql
    ...

                    │
                    ▼
        Flyway lit la table flyway_schema_history
                    │
                    ├── V1 déjà appliquée ?  → il la SAUTE
                    │                          mais il VÉRIFIE son empreinte
                    │
                    └── V15 inconnue ?       → il l'APPLIQUE et l'enregistre
```

Le nom du fichier obéit à une convention stricte :

```text
V 14 __ seed_cas_utilisation .sql
│  │      │                    │
│  │      │                    └── extension
│  │      └── description (les _ deviennent des espaces à l'affichage)
│  └── version, qui donne l'ORDRE
└── V pour « versioned »
```

### 2.4 La règle d'or de Flyway

> 🎯 **Une migration appliquée ne se modifie JAMAIS.**

Flyway calcule une **empreinte** (checksum) de chaque fichier et la stocke.
Si tu modifies `V4__catalogue.sql` après l'avoir appliquée, la commande suivante
échoue :

```text
Migration checksum mismatch for migration version 4
```

Ce n'est pas une contrariété, c'est une **protection**. Réfléchis :

```text
Ta machine        : V4 modifiée, appliquée depuis le début → colonne présente
La production     : V4 ancienne, appliquée il y a 3 mois   → colonne ABSENTE

Les deux bases ont "V4 appliquée" dans leur historique.
Elles n'ont pas le même schéma. Et rien ne le signale.
```

**Un besoin de changement = un nouveau fichier.** Toujours.

```sql
-- V15__ajout_champ_produit.sql
ALTER TABLE produit ADD COLUMN garantie_mois int;
```

---

## 3. Le schéma de GARAH

### 3.1 Ce qui a été écrit

14 migrations, une par domaine :

| Fichier | Domaine | Points remarquables |
|---|---|---|
| `V1__referentiels.sql` | Extensions, langues | `btree_gist`, index unique partiel |
| `V2__iam.sql` | Utilisateurs, permissions | Héritage JOINED, unicité sur `lower(email)` |
| `V3__marchands.sql` | Marchands | Une seule gestion active par marchand |
| `V4__catalogue.sql` | Produits et variantes | **Contrainte d'exclusion** sur les paliers de prix |
| `V5__stock.sql` | Stock | `CHECK` de positivité, cohérence arithmétique des mouvements |
| `V6__lieux.sql` | Lieux, itinéraires | Contrainte `UNIQUE (id, type)` préparant les FK composites |
| `V7__commerce.sql` | Panier, commande, paiement | Cohérence des montants, extraction de TVA |
| `V8__service_client.sql` | Conversations, négociation | Fermeture des **dépendances circulaires** |
| `V9__logistique.sql` | Expéditions, colis | **Trigger** I-35 |
| `V10__sav.sql` | Réclamations, retours | **Trigger** I-40 |
| `V11__finance.sql` | Grand livre marchand | `CHECK` de signe par type d'écriture |
| `V12__systeme.sql` | Surveillance, audit | `jsonb`, `inet`, `ON DELETE SET NULL` |
| `V13__mesure.sql` | Vues, favoris, agrégats | Le domaine absent de la spec |
| `V14__seed_cas_utilisation.sql` | 188 permissions | Un **référentiel**, pas des données de test |

### 3.2 Le résultat, mesuré

```text
58   tables
107  contraintes CHECK
105  clés étrangères
  7  index uniques PARTIELS
  2  triggers
188  cas d'utilisation
```

> 💡 **107 contraintes `CHECK` pour 58 tables.**
> Ce ratio n'est pas de la coquetterie : c'est le chapitre 04 qui a été
> transféré dans la base. Chacune est une règle métier qui ne peut plus être
> contournée — ni par un bug Java, ni par un script d'import, ni par une
> correction manuelle en production.

---

## 4. Les techniques PostgreSQL employées

Cinq techniques reviennent partout dans le schéma. Les comprendre, c'est
comprendre pourquoi GARAH reste sur PostgreSQL.

### 4.1 L'index unique partiel

```sql
CREATE UNIQUE INDEX panier_actif_unique
    ON panier (client_id) WHERE statut = 'ACTIF';
```

**Ce qu'il fait :** un client peut avoir 50 paniers `ABANDONNE`, mais **jamais
deux** `ACTIF`.

**Pourquoi c'est remarquable :** un `UNIQUE (client_id)` classique interdirait
aussi les paniers abandonnés — donc la statistique « paniers abandonnés »
demandée par la §20. Le `WHERE` fait toute la différence.

Utilisé 7 fois dans GARAH : panier actif, catégorie principale, adresse
principale, photo principale, variante par défaut, gestion de marchand active,
affectation de conversation ouverte. **C'est toujours le même motif** :
« un seul X actif parmi plusieurs ».

### 4.2 La contrainte d'exclusion

```sql
ALTER TABLE tarification ADD CONSTRAINT tarification_sans_chevauchement
    EXCLUDE USING gist (
        variante_id WITH =,
        int4range(quantite_min, COALESCE(quantite_max + 1, 1000000)) WITH &&,
        daterange(date_debut, date_fin) WITH &&
    );
```

**Ce qu'elle dit :** deux lignes sont interdites si elles ont le **même**
`variante_id` **et** des plages de quantité qui **se recouvrent** **et** des
périodes qui **se recouvrent**.

Vérifié en vrai :

```text
1–4  → 15 000     ✅ accepté
5–∞  → 13 000     ✅ accepté
3–10 → 14 000     ❌ REFUSÉ : chevauche 1–4
```

**Sans elle**, la question « quel prix pour 3 unités ? » aurait deux réponses.
Aucun code Java ne peut garantir ça de façon fiable en environnement concurrent.

> ⚠️ **Deux détails qui font échouer si on ne les connaît pas :**
>
> 1. `btree_gist` est **obligatoire** : sans cette extension, GiST ne sait pas
>    utiliser l'opérateur `=` sur un `bigint`.
> 2. On écrit `COALESCE(quantite_max + 1, ...)` avec une borne **exclusive**.
>    Écrire `int4range(min, 2147483647, '[]')` déborde la plage `int4`, parce
>    que la forme canonique ajoute 1 à la borne haute.

### 4.3 La clé étrangère composite : un trigger transformé en contrainte

L'invariant I-25 dit : *le lieu choisi par le client est forcément un point de
récupération, pas un entrepôt.*

Une clé étrangère normale ne sait pas l'exprimer : `lieu` contient les trois
types. Le réflexe serait d'écrire un trigger. Il y a mieux :

```sql
-- Dans lieu : on rend le couple (id, type) unique — techniquement redondant,
-- puisque id est déjà la clé primaire.
CONSTRAINT lieu_id_type_unique UNIQUE (id, type)

-- Dans commande : on transporte le type, et on le fige.
point_recuperation_type varchar(20) NOT NULL DEFAULT 'POINT_RECUPERATION',
CHECK (point_recuperation_type = 'POINT_RECUPERATION'),
FOREIGN KEY (point_recuperation_id, point_recuperation_type)
    REFERENCES lieu (id, type)
```

Vérifié en vrai : insérer une commande pointant vers un entrepôt est **refusé
par la clé étrangère**.

> 🎯 **Le compromis, et il faut le dire :**
> on a **dénormalisé** — le type du lieu est stocké deux fois. En échange, on
> gagne une règle portée par le moteur plutôt que par du code procédural.
>
> **Une clé étrangère est toujours préférable à un trigger** : elle est
> déclarative, visible dans le schéma, et elle ne s'oublie pas.
> C'est exactement l'exercice 5 du chapitre 04.

### 4.4 Le `CHECK` arithmétique

```sql
CHECK (montant_ligne = quantite * prix_unitaire)
CHECK (montant_commission = round(montant_ligne * taux_commission / 100, 2))
CHECK (montant_tva = round(montant_ligne * taux_tva / (100 + taux_tva), 2))
CHECK (montant_total = montant_articles + montant_frais - montant_remise)
CHECK (quantite_apres = quantite_avant + quantite)
```

Cinq lignes de SQL qui rendent **impossible** un total faux en base.

Vérifié en vrai : une TVA *ajoutée* à un prix TTC (1 925 sur 10 000 à 19,25 %)
est refusée ; seule la TVA *extraite* passe.

```text
15 000 TTC à 19,25 %  →  montant_tva = 2 421,38   ✅
```

> 💡 **Pourquoi `round(..., 2)` et pas une égalité brute.**
> `numeric` en PostgreSQL est **exact** — pas de `0.1 + 0.2 ≠ 0.3` comme avec
> un `float`. Mais une division produit des décimales infinies. On arrondit
> donc explicitement à 2 décimales, **exactement comme le fera le code Java**.
> Les deux calculs doivent être identiques au centime près, sinon la
> contrainte rejettera des écritures légitimes.

### 4.5 Le trigger — et pourquoi il reste l'exception

Deux invariants seulement ont un trigger, parce que SQL ne sait pas exprimer
« la somme des lignes filles ne dépasse pas une valeur de la ligne mère » :

```text
I-35   pas plus d'articles en colis que d'articles commandés
I-40   pas plus d'articles retournés que d'articles achetés
```

Vérifié en vrai :

```text
4 unités sur 10 en colis    ✅
puis 10 (la limite exacte)  ✅
12 unités                   ❌ « I-35 : quantite en colis (12) superieure... »
```

> ⚠️ **Le trigger est invisible depuis le code Java.**
> Un développeur qui lit le service ne le voit pas, et passera une heure à
> comprendre pourquoi son `INSERT` est refusé.
>
> D'où la règle : **tout trigger est commenté dans le fichier SQL et listé
> dans le chapitre 04**. Deux triggers, c'est peu — c'est volontaire. Ils sont
> réservés aux cas où l'erreur coûte de la marchandise ou de l'argent réel.

---

## 5. L'ordre des migrations et les dépendances circulaires

Un problème qu'on rencontre dans tout schéma un peu riche :

```text
commande         a besoin de   conversation   (d'où vient cette commande ?)
conversation     a besoin de   client
ligne_commande   a besoin de   proposition_prix
proposition_prix a besoin de   conversation
```

Le domaine commerce et le domaine service client se référencent **mutuellement**.
Aucun ordre de création ne satisfait tout le monde.

**La solution, en deux temps :**

```sql
-- V7 : on crée la colonne, SANS clé étrangère
conversation_id  bigint,   -- FK ajoutée en V8

-- V8 : la table cible existe enfin, on ferme la boucle
ALTER TABLE commande
    ADD CONSTRAINT commande_conversation_fk
    FOREIGN KEY (conversation_id) REFERENCES conversation (id);
```

> 📌 **La règle : la colonne d'abord, la contrainte ensuite — jamais l'inverse.**
> Et on **commente** la colonne orpheline dans le fichier. Une clé étrangère
> manquante ne se voit pas ; six mois plus tard, personne ne saura si c'était
> un oubli ou une intention.

Cette gêne est une **information** : elle indique où sont les vraies frontières
entre domaines. On la retrouvera au chapitre 06, quand il faudra découper les
packages Java.

---

## 6. La configuration, et les secrets

### 6.1 Aucun identifiant dans le code

```yaml
spring:
  datasource:
    url:      ${GARAH_DB_URL:jdbc:postgresql://localhost:5432/garah}
    username: ${GARAH_DB_USER:garah_app}
    password: ${GARAH_DB_PASSWORD}      # ← aucune valeur par défaut, volontairement
```

Trois principes :

1. **Le mot de passe n'a pas de valeur par défaut.** L'application refuse de
   démarrer si la variable est absente — bien mieux qu'un démarrage avec un
   mot de passe de développement en production.
2. `.env` est **dans le `.gitignore`**. `.env.example` est versionné, avec la
   liste des variables et aucune valeur.
3. Le jour de la bascule vers Neon ou vers le VPS (D-14), **seule la variable
   change**. Pas une ligne de code.

### 6.2 Le pool de connexions

```yaml
hikari:
  maximum-pool-size: ${GARAH_DB_POOL_MAX:8}
```

> ⚠️ **Piège Neon** : l'offre gratuite limite fortement le nombre de connexions
> simultanées. Le défaut de HikariCP (10 **par instance**) les épuise vite.
> On plafonne dès maintenant, et on le rend configurable — parce que la bonne
> valeur en local n'est pas la bonne valeur sur Render.

---

## 7. Les pièges rencontrés

### Piège 1 — `now()` est interdit dans un `CHECK`

L'invariant I-36 disait : *un événement n'est pas daté dans le futur.*

```sql
CHECK (date_heure <= now())   -- ❌ PostgreSQL refuse
```

Un `CHECK` doit être **immuable** : il est réévalué à chaque écriture, mais
aussi lors d'un `ALTER TABLE ... VALIDATE`. `now()` change à chaque appel.
Une ligne valide aujourd'hui le serait toujours demain — mais PostgreSQL
refuse par principe, et il a raison.

→ I-36 est vérifié dans le **service**. Toutes les règles ne descendent pas
en base, et il faut savoir lesquelles ne le peuvent pas.

### Piège 2 — `GENERATED ALWAYS` contre `GENERATED BY DEFAULT`

```sql
id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY
```

`GENERATED ALWAYS` interdit toute insertion d'un `id` explicite. C'est plus
rigoureux — et ça complique les jeux de test, les imports et les seeds.
`BY DEFAULT` génère la valeur quand on n'en fournit pas, et accepte qu'on
en fournisse une.

*(Et on préfère `bigint … IDENTITY` à `bigserial` : c'est le standard SQL,
et la séquence appartient vraiment à la colonne.)*

### Piège 3 — Flyway et PostgreSQL 18

```text
WARNING: PostgreSQL 18.4 is newer than this version of Flyway and support
         has not been tested. The latest supported version is 17.
```

Les 14 migrations passent sans problème. Mais **c'est un avertissement à
prendre au sérieux** : garde-le en tête si un comportement étrange apparaît.

Note aussi que Neon ne sera probablement pas en 18 — donc **développement et
production ne tourneront pas sur la même version mineure**. Une bonne raison
de plus pour que les tests automatisés tournent sur la version de production
(chapitre 07).

### Piège 4 — La migration de seed n'est pas un jeu de test

`V14__seed_cas_utilisation.sql` insère 188 lignes. Ce ne sont **pas** des
données de démonstration : c'est un **référentiel** dont le code Java dépend.

Conséquence : ajouter une permission demandera un **nouveau fichier**
(`V15__…`), jamais une modification de `V14`. Et une permission qui disparaît
passe à `INACTIF` — on ne la supprime pas, parce que des exceptions
individuelles y font référence.

---

## 8. Les commandes utiles

```bash
cd backend

mvn flyway:info        # où en est la base ?
mvn flyway:migrate     # appliquer les migrations manquantes
mvn flyway:validate    # les fichiers correspondent-ils à ce qui a été appliqué ?
```

Au démarrage de l'application, Spring Boot lance `migrate` tout seul. Le plugin
Maven sert quand on veut travailler la base **sans** démarrer l'API — c'est
plus rapide, et c'est ce qu'on a fait ici.

> ⚠️ `mvn flyway:clean` **efface toute la base**. Ne l'utilise jamais ailleurs
> qu'en développement. Sur un projet réel, on la désactive explicitement dans
> la configuration.

---

## 9. À retenir

1. **Le schéma appartient à Flyway, pas à Hibernate.** `ddl-auto: none`, toujours.
2. `ddl-auto` perdrait les `CHECK`, les index partiels, les exclusions, les FK composites et les triggers — c'est-à-dire tout le chapitre 04.
3. **Une migration appliquée ne se modifie jamais.** Un changement = un nouveau fichier.
4. L'**index unique partiel** répond au motif « un seul X actif parmi plusieurs ». Sept fois dans GARAH.
5. La **contrainte d'exclusion** rend un chevauchement littéralement impossible.
6. Une **clé étrangère composite** vaut mieux qu'un trigger : déclarative, visible, indépliable.
7. Toutes les règles ne descendent pas en base : `now()` est interdit dans un `CHECK`.
8. **Aucun secret dans le code.** Le mot de passe n'a pas de valeur par défaut : mieux vaut ne pas démarrer que démarrer mal.

---

## 10. Exercices

**Exercice 1.**
Tu ajoutes un champ `garantie_mois` sur `produit`. Écris la migration.
Puis explique pourquoi tu ne peux pas simplement l'ajouter à `V4__catalogue.sql`.

**Exercice 2.**
Écris l'index qui garantit qu'un marchand n'a qu'**une seule** règle de
commission active à la fois pour une catégorie donnée. Compare ta solution
avec la contrainte d'exclusion de `tarification` : laquelle convient, et pourquoi ?

**Exercice 3.**
La contrainte `ligne_commande_tva_coherente` utilise `round(..., 2)`.
Que se passerait-il si le code Java arrondissait à 3 décimales ? Écris le cas
qui casse.

**Exercice 4.**
Applique la technique de la clé étrangère composite (§4.3) à un autre invariant
du chapitre 04. Indice : regarde `expedition.lieu_depart_id`.

**Exercice 5.**
Un collègue propose de passer `ddl-auto` à `validate` en production « pour
vérifier que les entités correspondent au schéma ». Bonne ou mauvaise idée ?
Argumente dans les deux sens avant de trancher.

---

➡️ **Chapitre suivant :** 06 — Structurer un projet Spring Boot par domaines
