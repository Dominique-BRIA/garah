# Chapitre 01 — Le domaine et les acteurs

> Prérequis : aucun.
> Durée de lecture : ~20 min.

---

## 1. Ce qu'on veut faire

GARAH est une plateforme qui fait **cinq métiers différents** dans une seule application :

1. **Du commerce** — vendre des produits (les siens et ceux de partenaires).
2. **Du service client** — discuter, négocier un prix, traiter des réclamations.
3. **De la logistique** — expédier, tracer, faire transiter, livrer.
4. **De la finance** — encaisser les clients, reverser aux partenaires.
5. **De la sécurité** — surveiller, auditer, détecter les comportements anormaux.

C'est **beaucoup**. Et c'est le premier piège du projet.

> 🎯 **La question à se poser avant d'écrire une ligne de code :**
> est-ce que ces cinq métiers doivent vivre dans la même base, le même code,
> le même déploiement ?

Pour GARAH v1 : **oui**, une seule application (un « monolithe modulaire »).
Mais on va la découper **à l'intérieur** en domaines étanches, pour pouvoir
en extraire un morceau plus tard sans tout casser. On verra comment au chapitre 06.

---

## 2. La notion : qu'est-ce qu'un « système d'information » ?

Un débutant pense : *« un SI, c'est un logiciel avec une base de données »*.
C'est faux, et cette erreur coûte cher.

Un **système d'information** est l'ensemble de :

```text
      LES DONNÉES          ce que l'entreprise sait
            +
      LES PROCESSUS        ce que l'entreprise fait, dans quel ordre
            +
      LES ACTEURS          qui a le droit de faire quoi
            +
      LES TRACES           ce qui s'est passé, et qui en est responsable
```

Un logiciel classique ne modélise souvent que **les données**.
Un SI doit modéliser les quatre.

### Pourquoi c'est important ici

Regarde la différence entre ces deux phrases :

| Phrase | Ce que ça implique en base |
|---|---|
| « Le colis est arrivé à Bertoua » | Une colonne `statut` qu'on écrase |
| « Le colis **a été réceptionné** à Bertoua le 12/03 à 14h par David » | Une **ligne** dans une table d'événements |

La première phrase perd l'information dès que le colis repart.
La seconde la garde pour toujours.

GARAH est un SI **parce qu'il choisit systématiquement la deuxième forme**
sur tout ce qui engage une responsabilité : logistique, paiement, audit, sécurité.

> 📌 **Règle fondatrice n°1 du projet :**
> un statut est une **photo de l'instant**.
> Un événement est un **fait daté et immuable**.
> Quand un fait engage quelqu'un, on stocke l'événement — le statut n'en est que le résumé.

---

## 3. Les acteurs

### 3.1 Les quatre acteurs

```text
   ┌──────────────┐
   │  SUPER ADMIN │   Le gardien du système
   └──────┬───────┘
          │ crée / désactive
          ▼
   ┌──────────────┐
   │    ADMIN     │   Le gestionnaire de l'exploitation
   └──────┬───────┘
          │ crée / habilite
          ▼
   ┌──────────────┐
   │ RESPONSABLE  │   L'opérateur du quotidien
   └──────────────┘

   ┌──────────────┐
   │    CLIENT    │   Extérieur à l'entreprise
   └──────────────┘
```

### 3.2 Ce qui les distingue vraiment

Ne retiens pas « admin = plus de droits ». C'est plus subtil que ça :

| Acteur | Ce qu'il gère | Sa nature |
|---|---|---|
| **SuperAdmin** | Le **référentiel** : qui sont les admins, quelles fonctionnalités existent, la sécurité globale | Il configure le système lui-même |
| **Admin** | L'**exploitation** : qui fait quoi, et tout le métier | Il configure l'organisation du travail |
| **Responsable** | Les **opérations** : publier, répondre, expédier | Il exécute le travail |
| **Client** | Ses **propres** données : panier, commandes, réclamations | Il consomme le service |

La ligne de partage SuperAdmin / Admin est celle-ci :

```text
SuperAdmin  →  agit sur le SI       (les fonctionnalités qui existent)
Admin       →  agit dans le SI      (qui les utilise, et sur quoi)
```

C'est pour ça que le SuperAdmin gère `CAS_UTILISATION` (le catalogue des
fonctionnalités) mais **pas** les produits.

### 3.3 Le Marchand : l'acteur qui n'en est pas un

C'est la décision de modélisation la plus intéressante de la spec.

Un **Marchand** est le **propriétaire économique** d'un produit.
En v1, il **n'a pas de compte** et ne se connecte pas.

```text
  Marchand ABC  ──possède──►  Produit X
       │                          │
       │                          ├── créé par     Responsable Paul
       │                          ├── publié par   Responsable Jean
       │                          └── expédié par  Responsable David
       │
       └──géré par──►  Responsable Paul (affectation commerciale)
```

Quatre notions différentes cohabitent sur un même produit :

1. **Qui le possède** → le Marchand
2. **Qui gère la relation commerciale** → un Responsable affecté au Marchand
3. **Qui a effectué telle action** → un Responsable, tracé dans l'audit
4. **Qui l'achète** → le Client

> 🎯 **La leçon de modélisation :**
> ne donne jamais un compte utilisateur à une entité juste pour pouvoir
> la rattacher à des données. « Être propriétaire de » et « pouvoir se connecter »
> sont deux choses indépendantes.

Le jour où les marchands auront un espace en ligne (v2), on ajoutera un
`UTILISATEUR` relié au `MARCHAND`. Le modèle actuel ne s'y oppose pas :
c'est le signe qu'il est correct.

---

## 4. Les permissions : le cœur architectural du projet

### 4.1 Le problème

L'approche naïve : donner des rôles.

```text
ROLE_COMMERCIAL, ROLE_LOGISTIQUE, ROLE_SAV...
```

Ça marche… jusqu'au jour où l'Admin dit :
*« Paul est commercial, mais lui seul peut valider les remboursements. »*

Alors on crée `ROLE_COMMERCIAL_AVEC_REMBOURSEMENT`.
Puis `ROLE_COMMERCIAL_SANS_PUBLICATION`.
Six mois plus tard il y a 40 rôles et plus personne ne sait ce qu'ils font.

### 4.2 La solution retenue

Trois niveaux, et **un seul endroit** où les fonctionnalités sont définies :

```text
┌────────────────────────────────────────────────────┐
│  CAS_UTILISATION                                   │
│  Le catalogue des fonctionnalités qui EXISTENT     │
│  réellement dans le code.                          │
│  Créé par le SuperAdmin. Jamais inventé par un     │
│  Admin.                                            │
│      ex : PRODUIT_PUBLIER, EXPEDITION_CREER        │
└────────────────────┬───────────────────────────────┘
                     │ on en SÉLECTIONNE un sous-ensemble
                     ▼
┌────────────────────────────────────────────────────┐
│  CATÉGORIE DE RESPONSABLE                          │
│  Un profil métier = un paquet de cas d'utilisation │
│      ex : « Responsable Commercial »               │
└────────────────────┬───────────────────────────────┘
                     │ appliqué à
                     ▼
┌────────────────────────────────────────────────────┐
│  RESPONSABLE                                       │
│  + exceptions individuelles (ajouts / retraits)    │
└────────────────────────────────────────────────────┘
```

Le calcul final :

```text
   permissions de la catégorie
 + permissions ajoutées individuellement      (type = ADD)
 − permissions retirées individuellement      (type = REMOVE)
 ─────────────────────────────────────────
 = permissions effectives du Responsable
```

### 4.3 Pourquoi c'est une bonne architecture

| Propriété | Conséquence concrète |
|---|---|
| Un Admin ne peut pas **inventer** une permission | Pas de droit qui ne correspond à aucun code |
| Le code technique (`PRODUIT_PUBLIER`) est **stable** | On peut renommer le libellé sans casser le code |
| L'exception est **explicite** (ADD/REMOVE) | On voit d'un coup d'œil pourquoi Paul est différent |
| La catégorie sert aussi de **titre** | Pas de champ `titre` qui diverge du profil réel |

### 4.4 Le piège à connaître dès maintenant

> ⚠️ **Piège : la permission retirée devenue orpheline.**
>
> Paul a `REMOVE` sur `PRIX_MODIFIER`.
> Puis l'Admin retire `PRIX_MODIFIER` de la catégorie « Commercial ».
> La ligne `REMOVE` de Paul ne sert plus à rien — mais elle reste.
> Si un jour la permission est **remise** dans la catégorie, Paul ne l'aura
> toujours pas, et personne ne comprendra pourquoi.
>
> → Il faudra soit nettoyer les exceptions devenues sans objet,
> soit afficher clairement les exceptions inactives dans l'interface.
> On tranchera au chapitre 08.

---

## 5. Les grands flux du métier

### 5.1 Le flux commercial

```text
Marchand ──► Produit ──► Prix + Stock ──► Client ──► Panier ──► Commande ──► Paiement
```

### 5.2 Le flux logistique

```text
Commande ──► Préparation ──► Expédition ──► Colis
                                              │
                                              ▼
                                 Itinéraire (suite d'étapes)
                                              │
                    ┌─────────────────────────┼──────────────────────┐
                    ▼                         ▼                      ▼
              Point transit             Point transit          Point transit
                  Douala                    Bertoua                Bangui
                    │                         │                      │
                    └─────────── événements datés ───────────────────┘
                                              │
                                              ▼
                                    Point de récupération
                                              │
                                              ▼
                                           Client
```

### 5.3 Le flux de service

```text
Client ──► Conversation ──► Messages ──► Proposition de prix ──► Commande
                  │
                  └──► Évaluation (après clôture)
```

### 5.4 Le flux de surveillance (en parallèle, jamais bloquant)

```text
Client ──► Activités ──► Événements de sécurité ──► Score de risque ──► Alerte ──► Admin
```

> 📌 **Règle fondatrice n°2 :**
> la surveillance **observe**, elle ne **décide pas**.
> Un score `HIGH` ne bloque personne automatiquement.
> Il alerte un humain, qui tranche. Le score doit toujours être **explicable**
> (« pourquoi HIGH ? » → la liste des signaux).

---

## 6. Les pièges de ce chapitre

### Piège 1 — Croire que le nombre de fonctionnalités mesure la difficulté

La spec liste ~180 codes de cas d'utilisation.
Ça impressionne, mais ce n'est **pas** là qu'est la difficulté.

Les vrais points durs de GARAH sont au nombre de cinq :

1. Le **stock** (concurrence : deux clients, un seul article restant).
2. Le **prix** (négocié, par palier, et devant rester exact dans l'historique).
3. La **traçabilité** (des événements, pas des statuts).
4. La **finance marchand** (ce qu'on doit à qui, et quand).
5. Les **permissions** (calcul correct et rapide, à chaque requête).

Le reste, ce sont des écrans et des formulaires.

### Piège 2 — Modéliser les statistiques après coup

Le chapitre 20 de la spec demande : *vues, favoris, ajouts au panier, produits tendance*.

Ces chiffres ne peuvent pas être calculés rétroactivement.
**Si on n'enregistre pas les vues aujourd'hui, elles sont perdues pour toujours.**

→ Les tables de mesure doivent exister **dès la v1**, même si les écrans
de statistiques viennent plus tard.

### Piège 3 — Confondre les trois journaux

GARAH a **trois** mécanismes de traçabilité qui se ressemblent :

| Table | Qui l'alimente | Pourquoi elle existe |
|---|---|---|
| `ACTIVITE_CLIENT` | Le client | Comprendre son comportement (métier + marketing) |
| `EVENEMENT_SECURITE` | Le système | Détecter une anomalie (sécurité) |
| `AUDIT_LOG` | Les admins/responsables | Savoir qui a modifié quoi (responsabilité interne) |

Sans règle claire, tout finit dans la même table et plus rien n'est exploitable.

> **Règle :** `AUDIT_LOG` = actions **internes** sur des données.
> `ACTIVITE_CLIENT` = actions **du client** sur son parcours.
> `EVENEMENT_SECURITE` = faits **d'authentification et d'anomalie**.
> Un même geste peut légitimement écrire dans deux d'entre elles.

---

## 7. À retenir

1. Un SI, c'est **données + processus + acteurs + traces**. GARAH modélise les quatre.
2. **Statut ≠ événement.** Dès qu'un fait engage une responsabilité, il devient une ligne immuable.
3. Le **Marchand n'est pas un utilisateur** : posséder et se connecter sont indépendants.
4. Les permissions viennent d'un **catalogue unique** (`CAS_UTILISATION`), sélectionné par catégorie, ajusté par exception.
5. La **surveillance observe, l'humain décide**.
6. Les données de mesure (vues, favoris) doivent être **collectées dès le début**.

---

## 8. Exercices

**Exercice 1.**
Un Responsable de catégorie « Logistique » doit exceptionnellement pouvoir
modifier un prix pendant une semaine. Décris **précisément** les lignes créées
en base, et le problème que ça pose une semaine plus tard.

**Exercice 2.**
Le colis n°42 est à Bertoua. Le client demande où il est passé depuis Douala.
Explique pourquoi une colonne `colis.statut` ne suffit pas, et liste les
informations minimales à stocker pour chaque étape.

**Exercice 3.**
Un Admin veut créer une catégorie « Responsable Import » avec la permission
« valider les documents douaniers ». Que doit-il se passer dans le système,
et pourquoi ne peut-il pas le faire seul ?

**Exercice 4.**
Cite trois raisons pour lesquelles on stocke le prix unitaire dans
`LIGNE_COMMANDE` alors qu'il est déjà dans la tarification du produit.

---

➡️ **Chapitre suivant :** [02 — Revue critique du modèle initial](02-revue-critique-du-modele.md)
