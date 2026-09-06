# Chapitre 20 — Tests automatisés et intégration continue

> Prérequis : tout le backend (chapitres 05 à 19).
> Durée de lecture : ~25 min.

---

## 1. Ce qu'on veut faire

Une seule promesse :

> **Si la pastille est verte, la branche compile et ses 158 tests passent sur
> une base PostgreSQL vierge.**

Chaque mot compte, et le dernier plus que les autres.

---

## 2. La pyramide, telle qu'elle existe vraiment

On dessine souvent la pyramide des tests avant d'écrire la première ligne.
Voici celle que GARAH a produite **en pratique** :

| Nature | Classes | Tests | Durée | Ce qu'elle protège |
|---|---|---|---|---|
| **Unitaire pur** | `SlugTest` | 9 | 0,1 s | Une fonction, exhaustivement |
| **Architecture** | `ArchitectureTest` | 4 | 3 s | Les règles du chapitre 06 |
| **Intégration** | 9 classes | 124 | ~60 s | Le métier, contre une vraie base |
| **Concurrence** | 2 classes | 6 | 1 s | Ce qui ne casse que sous charge |
| **Bout en bout** | `ParcoursLogistiqueTest` | 14 | 23 s | Les **frontières** entre domaines |
| **Sécurité HTTP** | `SecuriteHttpTest` | 8 | 4 s | Les codes 200 / 401 / 403 (ch. 21) |

Deux observations honnêtes :

**La pyramide est déséquilibrée**, et c'est assumé. Presque tout est en
intégration, parce que **presque toute la logique de GARAH dépend de la
base** : contraintes, verrous, triggers, séquences. Un test unitaire de
`ServiceStock` avec une base simulée ne prouverait rien — c'est le verrou
qu'on veut vérifier, pas le code Java autour.

**`SlugTest` est la seule fonction vraiment pure du projet**, et elle a droit
à un test unitaire. La règle reste : *ce qui peut être testé sans
infrastructure doit l'être sans infrastructure*. Il se trouve qu'ici, ça ne
concerne qu'une classe.

---

## 3. Ce que les tests ont réellement attrapé

C'est la meilleure façon de juger une suite de tests : **qu'a-t-elle trouvé ?**

| Chapitre | Défaut | Visible autrement ? |
|---|---|---|
| 07 | `equals` sur clé composite : le `Set` avalait un élément | ❌ silencieux |
| 08 | En-tête JWT sans HS256 | ❌ au démarrage seulement |
| 08 | `/api/auth/**` ouvrait une route protégée | ❌ 500 au lieu de 401 |
| 09 | Un contrôleur importait une entité JPA | ❌ compile et marche |
| 11 | **Survente** : 15 clients servis sur 5 articles | ❌ seulement sous charge |
| 12 | `CHECK` violé à l'insertion des frais | ✅ à l'exécution |
| 15 | Triggers en `P0001` → 500 au lieu de 409 | ❌ assemblage uniquement |
| 17 | 22 nettoyages cassés par une nouvelle clé étrangère | ✅ immédiat |
| 19 | `catch` inopérant sur transaction condamnée | ❌ silencieux |
| 21 | Le catalogue public exigeait un jeton | ❌ **bloquant**, invisible hors HTTP |
| 21 | `MultipleBagFetchException` : fiche cassée à 100 % | ❌ **bloquant**, invisible hors HTTP |
| 21 | Refus de permission → 500 au lieu de 403 | ❌ silencieux |

**Neuf défauts sur douze étaient invisibles autrement.** Aucune relecture ne les
aurait trouvés : le code était lisible, il compilait, et il avait l'air juste.

> ⚠️ **Les trois derniers ont été ajoutés après coup.** Ils n'ont été trouvés
> qu'au chapitre 21, en écrivant le **premier test qui passe par HTTP** — après
> que ce chapitre-ci eut annoncé 150 tests verts. Deux d'entre eux étaient
> bloquants.
>
> C'est la limite honnête de cette page : une suite de tests ne protège que ce
> qu'elle traverse. Ici, aucun test ne traversait la chaîne de filtres de
> sécurité, donc **rien de ce qui s'y trouvait n'était vérifié** — et la
> pastille restait verte.

---

## 4. Pourquoi une vraie base, et ce que ça coûte

Rappel du chapitre 07 : rien de ce qui protège GARAH n'existe en H2.

```text
❌ index uniques partiels        ❌ contraintes d'exclusion GiST
❌ triggers PL/pgSQL             ❌ jsonb, inet
❌ SELECT … FOR UPDATE fidèle    ❌ séquences
```

> ⚠️ **Un test qui passe sur H2 et échoue en production est pire qu'un test
> absent : il donne confiance à tort.**

Le prix : la suite met **~90 secondes** au lieu de quelques secondes. C'est
acceptable pour ce qu'on achète.

### Et Testcontainers ?

C'est la réponse habituelle : démarrer un PostgreSQL jetable par exécution.

**On ne l'utilise pas**, pour une raison concrète : Docker n'est pas
installable sur le poste de développement (chapitre 05 — disque saturé,
virtualisation désactivée). Une suite qui ne tourne pas en local est une suite
qu'on cesse de lancer.

Le compromis retenu :

```text
en local    une base PostgreSQL native, garah_test, nettoyée par les tests
en CI       un conteneur PostgreSQL éphémère, vierge à chaque exécution
```

> 📌 **Le jour où Docker sera installé** (avec le VPS), Testcontainers
> deviendra le bon choix : il supprimerait le besoin d'une base préexistante
> et le nettoyage manuel. C'est une amélioration identifiée, pas un oubli.

---

## 5. Le workflow, et les trois détails qui comptent

### 5.1 Le *healthcheck* n'est pas optionnel

```yaml
options: >-
  --health-cmd pg_isready
  --health-interval 5s
  --health-retries 10
```

Sans lui, les tests démarrent **avant** que PostgreSQL n'accepte les
connexions. L'échec est **intermittent** — il apparaît une fois sur cinq,
selon la charge du runner.

> 🎯 **Un test intermittent est le pire type d'échec :** on relance, ça passe,
> on finit par ne plus regarder la pastille. Une CI en laquelle on n'a pas
> confiance ne sert à rien.

### 5.2 PostgreSQL 17 en CI, 18 en local

C'est **délibéré**.

```text
poste de développement   PostgreSQL 18
intégration continue     PostgreSQL 17
production (Neon)        PostgreSQL 16 ou 17
```

On préfère découvrir une incompatibilité de version **ici** plutôt qu'en
production. Et le chapitre 05 avait déjà noté que Flyway 11 ne certifie pas
PostgreSQL 18 : la CI teste donc une combinaison plus proche du réel.

### 5.3 Le cache Maven

```yaml
cache: maven
```

Sans lui, chaque exécution retélécharge Spring Boot en entier — trois minutes
gagnées à chaque commit.

### 5.4 Les rapports, surtout quand ça échoue

```yaml
if: always()
uses: actions/upload-artifact@v4
```

`always()` est le point clé : un rapport de test n'intéresse **que** quand la
suite est rouge. Sans cette ligne, l'étape serait sautée précisément quand on
en a besoin.

---

## 6. La base vierge : ce que la CI voit et pas le local

C'est la vraie valeur ajoutée du pipeline.

```text
en local   la base de test ACCUMULE l'historique des exécutions
           une migration cassée peut passer inaperçue :
           les tables existent déjà

en CI      Flyway rejoue les 19 migrations DEPUIS ZÉRO
           à chaque exécution
```

> 📌 **C'est le seul endroit où on vérifie que les migrations tiennent debout
> ensemble**, dans l'ordre, sur une base neuve — c'est-à-dire exactement ce
> qui se passera au premier déploiement.

Avant d'écrire ce chapitre, j'ai simulé ce scénario en local :

```bash
DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION garah_app;
mvn test
→ 158 tests, BUILD SUCCESS
```

Les 19 migrations se rejouent proprement.

---

## 7. Ce qu'on n'a pas mis, et pourquoi

| Outil | Verdict |
|---|---|
| **Couverture de code** (JaCoCo) | Utile en indicateur, **désastreux en objectif**. Viser 80 % produit des tests qui appellent du code sans rien vérifier. |
| **Tests de mutation** (PIT) | Excellent — il mesure si les tests **détectent** vraiment. Trop lent pour chaque commit ; une exécution hebdomadaire aurait du sens. |
| **Analyse statique** (SonarQube) | À ajouter quand le projet grossira. ArchUnit couvre déjà ce qui compte le plus ici. |
| **Tests de charge** | Prématuré : sans trafic réel, on optimiserait au hasard. |

> 🎯 **La règle :** on ajoute un outil quand on peut nommer le problème qu'il
> résout. « Tout le monde le fait » n'est pas un problème.

---

## 8. Une réserve honnête

**Ce workflow n'a pas encore été exécuté.** Il est syntaxiquement valide,
la commande Maven qu'il lance a été vérifiée en local sur une base vierge, mais
la première exécution réelle sur GitHub reste à observer.

Les points les plus susceptibles de demander un ajustement :

- la disponibilité de **Java 24** chez `actions/setup-java` (temurin) ;
- le comportement du conteneur PostgreSQL sur le **premier démarrage à froid** ;
- la création de l'extension `btree_gist`, qui exige des droits suffisants.

> 💡 **Un fichier de CI est du code comme un autre :** il se teste en
> l'exécutant, pas en le relisant. Le premier `git push` est son premier test.

---

## 9. À retenir

1. La CI fait **une promesse précise** : compile + tests verts sur base **vierge**.
2. La pyramide réelle est **déséquilibrée**, parce que la logique vit dans la base. C'est assumé, pas subi.
3. **Six défauts sur neuf** trouvés dans ce projet étaient invisibles à la relecture.
4. **Pas de H2.** Un test qui passe à tort est pire qu'un test absent.
5. Testcontainers est le bon outil — **le jour où Docker est installable**. Une suite qu'on ne lance pas ne protège de rien.
6. **Le healthcheck évite l'échec intermittent**, qui détruit la confiance dans la CI.
7. Tester sur une version **proche de la production**, pas de son poste.
8. `if: always()` sur les rapports : ils n'intéressent que quand c'est rouge.
9. La **base vierge** est ce que la CI voit et que le local ne verra jamais.
10. On ajoute un outil quand on peut **nommer le problème** qu'il résout.

---

## 10. Exercices

**Exercice 1.**
Retire le `healthcheck` du service PostgreSQL et relance la CI plusieurs fois.
Combien d'exécutions avant un échec ? Explique pourquoi c'est le pire type de
défaut.

**Exercice 2.**
Ajoute une étape qui échoue si une migration Flyway est **modifiée** au lieu
d'être ajoutée. Indice : `mvn flyway:validate` et l'historique git.

**Exercice 3.**
La suite met 90 secondes, dont 60 en démarrage de contextes Spring. Propose
deux façons de la réduire sans perdre de couverture.

**Exercice 4.**
Ajoute JaCoCo et regarde la couverture de `ServiceStock`. Est-elle un bon
indicateur de la qualité de ses tests ? Argumente avec le test de concurrence.

**Exercice 5.**
Écris le workflow qui déploie automatiquement sur Render quand `main` est
vert. Quelles précautions avant d'automatiser un déploiement ?

---

➡️ **Chapitre suivant :** [21 — Déploiement : Render, Neon, Backblaze B2](21-deploiement.md)
