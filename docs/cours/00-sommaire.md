# GARAH — Le cours

> Ce dossier n'est pas de la documentation technique.
> C'est un **cours**, écrit au fur et à mesure qu'on construit GARAH.
> Objectif : que tu puisses le relire dans six mois et **refaire le projet seul**,
> en comprenant *pourquoi* chaque décision a été prise, pas seulement *quoi* a été codé.

---

📋 **Voir aussi :** [le journal des décisions](../decisions.md) — le *pourquoi*
de chaque choix structurant, avec ce qu'il coûte et ce qu'il évite.

---

## Comment ce cours est écrit

Chaque chapitre suit toujours la même structure :

| Section | Ce qu'elle contient |
|---|---|
| **Ce qu'on veut faire** | Le besoin, en français, sans jargon |
| **La notion** | La théorie nécessaire (et seulement elle) |
| **Appliqué à GARAH** | Comment la notion se traduit dans notre projet |
| **Les pièges** | Ce qui casse en vrai, et pourquoi |
| **À retenir** | 3 à 5 phrases, le noyau dur |
| **Exercices** | Pour vérifier que tu as compris |

Règle : **une idée par section**. Si un chapitre devient trop gros, il est coupé en deux.

---

## La stack du projet

| Couche | Technologie |
|---|---|
| Frontend | Angular — **trois applications distinctes** |
| Backend | Spring Boot (Java) — une seule API |
| Base de données | PostgreSQL |
| Modélisation | UML (PlantUML) + MCD/MLD |

### Les trois frontends

```text
                    ┌──────────────────────────┐
                    │   API Spring Boot        │
                    │   (une seule)            │
                    └────┬──────┬─────────┬────┘
                         │      │         │
        ┌────────────────┘      │         └────────────────┐
        ▼                       ▼                          ▼
┌───────────────┐      ┌────────────────┐       ┌────────────────────┐
│  garah-web    │      │  garah-client  │       │   garah-admin      │
│  Site vitrine │      │  Espace client │       │   Back-office      │
│               │      │                │       │   entreprise       │
│ Public, SEO   │      │ Authentifié    │       │ SuperAdmin/Admin/  │
│ Pas de login  │      │ Panier, suivi  │       │ Responsable        │
└───────────────┘      └────────────────┘       └────────────────────┘
                         │                          │
                         └──────────┬───────────────┘
                                    ▼
                          ┌────────────────────┐
                          │   garah-ui         │
                          │   Librairie        │
                          │   partagée         │
                          │ (design system,    │
                          │  modèles, HTTP)    │
                          └────────────────────┘
```

**Pourquoi trois applications et pas une seule avec des rôles ?**

| Raison | Détail |
|---|---|
| **Sécurité** | Le code du back-office n'est jamais téléchargé par un client. Ce qui n'est pas livré ne peut pas être analysé. |
| **Performance** | La vitrine doit charger vite (SEO, mobile, réseau camerounais). Elle n'embarque pas 180 écrans d'administration. |
| **Rythme de livraison** | On peut déployer le back-office sans retoucher la vitrine. |
| **Clarté du code** | Pas de `if (isAdmin)` partout dans les composants. |

Le prix à payer : du code commun à factoriser → c'est le rôle de `garah-ui`.
Chapitre 09.

---

## Plan du cours

### Partie I — Comprendre avant de coder

| # | Chapitre | État |
|---|---|---|
| 01 | [Le domaine et les acteurs](01-le-domaine-et-les-acteurs.md) | ✅ écrit |
| 02 | [Revue critique du modèle initial](02-revue-critique-du-modele.md) | ✅ écrit |
| 03 | [Le modèle de données corrigé](03-le-modele-corrige.md) — **chapitre de référence** | ✅ écrit |
| 04 | [Les règles métier et les invariants](04-regles-metier-et-invariants.md) | ✅ écrit |

### Partie II — Les fondations techniques

| # | Chapitre | État |
|---|---|---|
| 05 | [PostgreSQL et le schéma](05-postgresql-et-le-schema.md) — 14 migrations Flyway appliquées | ✅ écrit |
| 06 | [Structurer un projet Spring Boot par domaines](06-structurer-spring-boot.md) | ✅ écrit |
| 07 | [Entités JPA, repositories et transactions](07-entites-jpa-et-transactions.md) — domaine IAM | ✅ écrit |
| 08 | [Authentification, JWT et contrôle des permissions](08-authentification-et-permissions.md) | ✅ écrit |

> ⚠️ **Proposition d'ordre, à valider.** Les chapitres 09 à 14 (Angular) sont
> écrits ici avant les modules métier. Je suggère de **finir le backend
> d'abord** : construire trois frontends sur une API incomplète oblige à tout
> retoucher. Dans ce cas, la Partie III passe avant la fin de la Partie II.
| 09 | Un workspace Angular, trois applications, une librairie partagée | ⏳ |
| 10 | Le design system `garah-ui` (thème, composants, accessibilité) | ⏳ |
| 11 | Faire dialoguer Angular et Spring Boot proprement | ⏳ |
| 12 | Le site vitrine : SEO, performance, rendu serveur | ⏳ |
| 13 | L'espace client : authentification, panier, suivi | ⏳ |
| 14 | Le back-office : permissions côté interface | ⏳ |

### Partie III — Les modules métier

| # | Chapitre | État |
|---|---|---|
| 15 | Catalogue : produits, médias, catégories | ⏳ |
| 16 | Prix par palier de quantité | ⏳ |
| 17 | Stock et mouvements de stock (la concurrence) | ⏳ |
| 18 | Panier et commande | ⏳ |
| 19 | Paiement et mobile money | ⏳ |
| 20 | Conversations et négociation | ⏳ |
| 21 | Logistique : expédition, itinéraire, traçabilité | ⏳ |
| 22 | SAV : réclamations et retours | ⏳ |
| 23 | Finance marchands : commissions, écritures, règlements | ⏳ |
| 24 | Surveillance, score de risque, audit | ⏳ |
| 25 | Statistiques et tableaux de bord | ⏳ |

*(Le plan bougera. C'est normal : on écrit le cours pendant qu'on découvre le projet.)*

---

## La référence de design

Le design s'inspire du dépôt privé `chounfouen/alanyaCenter`, branche `frontend`,
cloné en lecture seule dans `.reference/alanyaCenter` (non versionné).

⚠️ **Ce dépôt est en React + Vite, pas en Angular.** On ne copie donc **pas** le code :
on reprend le **système de design**, qui lui est indépendant du framework
(ce sont des variables CSS).

Ce qu'on reprend :

| Élément | Valeur relevée |
|---|---|
| Couleur primaire | `#6366f1` (indigo), claire `#818cf8`, foncée `#4f46e5` |
| Accent | `#aa3bff` (violet) et `#3b82f6` (bleu) |
| Sémantiques | succès `#10b981`, alerte `#f59e0b`, danger `#ef4444`, info `#0ea5e9` |
| Police | `Outfit`, repli `Inter`, puis police système |
| Rayons | 20 / 12 / 8 px |
| Style | *glassmorphism* : fonds translucides, bordures douces, ombres larges |
| Thème | clair par défaut sur `:root`, sombre via l'attribut `[data-theme='dark']` |

**La leçon d'architecture à retenir dès maintenant :**
le thème tient entièrement dans des **variables CSS** définies à deux endroits
(`:root` et `[data-theme='dark']`). Aucun composant ne connaît une couleur en dur.

```css
:root                { --primary: #6366f1; --bg-color: #f8fafc; --text-main: #0f172a; }
[data-theme='dark']  { --bg-color: #0c0c14; --text-main: #f3f4f6; }
```

C'est **pour ça** que ce design est transposable de React vers Angular sans effort :
il ne dépend d'aucun framework. Un design bâti sur des classes Tailwind ou des
composants React n'aurait pas été transposable.

→ Ces variables vivront dans `garah-ui`, importées par les trois applications.
Chapitre 10.

---

## Conventions du projet

- **Langue du code** : anglais pour le code Java/TypeScript, **français pour le métier**
  (les noms de tables, les codes de cas d'utilisation, les libellés).
  → Raison : le métier est décrit en français par le client ; traduire crée des malentendus.
- **Langue des commits** : français.
- **Codes de cas d'utilisation** : `DOMAINE_ACTION`, en majuscules, **jamais renommés**
  une fois en production (voir chapitre 08).

---

## Vocabulaire GARAH

À connaître avant tout le reste. Ces mots ont un sens **précis** dans le projet :

| Mot | Sens dans GARAH | Ne pas confondre avec |
|---|---|---|
| **Marchand** | Propriétaire économique d'un produit | Un utilisateur : le marchand n'a pas de compte en v1 |
| **Responsable** | Employé de l'entreprise qui opère la plateforme | Le marchand |
| **Cas d'utilisation** | Une permission technique réellement implémentée | Un cas d'utilisation UML (le diagramme) |
| **Catégorie de responsable** | Le profil ET le titre affiché d'un responsable | La catégorie de produit |
| **Expédition** | Un envoi physique rattaché à une commande | La commande (1 commande = N expéditions) |
| **Point de transit** | Une étape intermédiaire du trajet | Le point de récupération (la destination finale) |
| **Événement** | Un fait daté et immuable | Un statut (qui, lui, est écrasé) |

⚠️ Le mot **cas d'utilisation** est surchargé dans ce projet : il désigne à la fois
un concept UML et une table en base. Le cours écrira toujours `CAS_UTILISATION`
en majuscules quand il s'agit de la table.
