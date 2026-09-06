# Chapitre 04 — Les règles métier et les invariants

> Prérequis : [chapitre 03](03-le-modele-corrige.md).
> Durée de lecture : ~35 min.
> C'est le chapitre qui transforme le modèle en **cahier des charges du schéma SQL**.

---

## 1. Ce qu'on veut faire

Le chapitre 03 a défini **ce qu'on stocke**. Il ne dit rien de **ce qui a le
droit d'exister**.

Rien, dans le modèle actuel, n'empêche :

- un stock à −40 unités ;
- une commande dont le total ne correspond pas à ses lignes ;
- un produit publié sans aucun prix ;
- une conversation `ASSIGNED` sans responsable ;
- un colis contenant 12 articles alors que la commande en compte 3 ;
- un remboursement supérieur à ce que le client a payé.

Ce chapitre liste ces règles, et surtout décide **où chacune est appliquée**.

---

## 2. La notion : trois natures de règles

C'est la distinction fondatrice du chapitre. On les confond tout le temps, et
c'est pour ça qu'on les place au mauvais endroit.

| Nature | Définition | Exemple GARAH | Où l'appliquer |
|---|---|---|---|
| **Invariant** | Vrai **à tout instant**, quel que soit le chemin par lequel on est arrivé là | `quantite_disponible >= 0` | **La base de données** |
| **Transition** | Ce qui a le droit de passer de l'état A à l'état B | `PAYEE → EN_PREPARATION` oui, `PAYEE → RETIREE` non | **Le service métier** |
| **Validation** | La forme d'une donnée entrante | un e-mail contient un `@` | **Frontend + API** |

### Comment reconnaître un invariant

Pose-toi cette question :

> « Si je regarde la base **à n'importe quel moment**, sans savoir ce qui s'est
> passé avant, cette affirmation est-elle **forcément** vraie ? »

- « Le stock est positif » → oui, toujours. **Invariant.**
- « Une commande payée a été confirmée avant » → ça parle du **passé**, pas de
  l'instant. **Transition**, pas invariant.

> 🎯 **Pourquoi cette distinction change tout :**
> un invariant peut être confié à la base, qui le fera respecter **sans
> exception**, même par une requête écrite à la main un dimanche soir.
> Une transition ne le peut pas : la base ne connaît pas l'histoire.

---

## 3. Où placer une règle : la défense en profondeur

Une règle importante est vérifiée **plusieurs fois**, à des endroits différents,
pour des raisons différentes. Ce n'est pas de la redondance inutile.

```text
┌──────────────────────────────────────────────────────────────────┐
│  1. FRONTEND (Angular)                                           │
│     Bouton grisé, message immédiat.                              │
│     ➜ Pour le CONFORT. Aucune sécurité. Contournable en 10 s.    │
├──────────────────────────────────────────────────────────────────┤
│  2. API — validation du DTO (Spring)                             │
│     Format, obligatoire, longueur, plage.                        │
│     ➜ Pour un REFUS PROPRE, avec un message exploitable.         │
├──────────────────────────────────────────────────────────────────┤
│  3. SERVICE MÉTIER (Spring, transactionnel)                      │
│     Transitions d'état, permissions, cohérence multi-tables.     │
│     ➜ Pour la RÈGLE MÉTIER. C'est ici que vit l'intelligence.    │
├──────────────────────────────────────────────────────────────────┤
│  4. BASE DE DONNÉES (PostgreSQL)                                 │
│     CHECK, UNIQUE, FK, EXCLUDE, NOT NULL.                        │
│     ➜ Pour la VÉRITÉ. Dernière ligne, infranchissable.           │
└──────────────────────────────────────────────────────────────────┘
```

> 📌 **Les deux règles d'or de ce schéma :**
>
> 1. **La base est la dernière ligne, jamais la seule ligne visible.**
>    Si l'utilisateur découvre une règle par une erreur SQL brute
>    (`violates check constraint "stock_positif"`), c'est un bug d'interface.
>    Le service aurait dû la vérifier avant et produire un message clair.
>
> 2. **Mais la base doit quand même l'avoir.**
>    Parce qu'un jour il y aura un script d'import, une correction manuelle en
>    production, un second service, ou un développeur pressé qui oublie
>    d'appeler la méthode qui vérifie.

### Le contre-exemple à ne pas suivre

```java
// ❌ La règle n'existe QUE dans ce service
public void reserverStock(Long varianteId, int qte) {
    Stock s = repo.findByVarianteId(varianteId);
    if (s.getQuantiteDisponible() < qte) {
        throw new StockInsuffisantException();
    }
    s.setQuantiteDisponible(s.getQuantiteDisponible() - qte);
}
```

Trois failles, dont deux mortelles :

1. **Concurrence** — deux appels simultanés lisent tous les deux « 1 disponible ».
2. **Contournement** — un `UPDATE` manuel en base ignore complètement ce code.
3. **Duplication** — le jour où une deuxième méthode décrémente le stock,
   la règle est réécrite… ou oubliée.

La version correcte confie l'invariant à la base :

```sql
ALTER TABLE stock ADD CONSTRAINT stock_positif
  CHECK (quantite_disponible >= 0 AND quantite_reservee >= 0);
```

```java
// ✅ On demande a la base de faire l operation, on regarde si elle a reussi
int lignes = repo.reserver(varianteId, qte);   // UPDATE ... WHERE dispo >= :qte
if (lignes == 0) throw new StockInsuffisantException();
```

---

## 4. Les machines à états de GARAH

Une **machine à états** répond à une seule question : depuis cet état, où ai-je
le droit d'aller ?

C'est le meilleur outil contre le bug le plus banal d'une application de
gestion : un objet qui passe dans un état incohérent parce qu'un écran a été
rafraîchi deux fois.

### 4.1 La commande

```text
                    ┌──────────────────────┐
                    │ EN_ATTENTE_PAIEMENT  │◀── création
                    └───┬──────────────┬───┘
        paiement confirmé│              │ délai dépassé / annulation client
                         ▼              ▼
                    ┌─────────┐    ┌──────────┐
                    │  PAYEE  │───▶│ ANNULEE  │ (remboursement si payée)
                    └────┬────┘    └──────────┘
                         │ préparation lancée
                         ▼
                  ┌────────────────┐
                  │ EN_PREPARATION │
                  └────────┬───────┘
                           │ articles rassemblés
                           ▼
                      ┌─────────┐
                      │  PRETE  │
                      └────┬────┘
                           │ expédition créée et partie
                           ▼
                     ┌───────────┐
                     │ EXPEDIEE  │
                     └─────┬─────┘
                           │ arrivée au point de récupération
                           ▼
                    ┌─────────────┐
                    │ DISPONIBLE  │
                    └──────┬──────┘
                           │ retrait confirmé
                           ▼
                     ┌──────────┐
                     │ RETIREE  │ ← état final
                     └──────────┘
```

Le tableau des transitions autorisées — c'est **ça** qu'on code, pas le dessin :

| Depuis | Vers | Déclencheur | Permission requise |
|---|---|---|---|
| `EN_ATTENTE_PAIEMENT` | `PAYEE` | Webhook opérateur confirmé | *(système)* |
| `EN_ATTENTE_PAIEMENT` | `ANNULEE` | Délai dépassé, ou client | `COMMANDE_ANNULER` |
| `PAYEE` | `EN_PREPARATION` | Responsable | `PREPARATION_COMMENCER` |
| `PAYEE` | `ANNULEE` | Décision + remboursement | `COMMANDE_ANNULER` |
| `EN_PREPARATION` | `PRETE` | Responsable | `PREPARATION_TERMINER` |
| `PRETE` | `EXPEDIEE` | Départ enregistré | `EXPEDITION_EXPEDIER` |
| `EXPEDIEE` | `DISPONIBLE` | Arrivée au point de retrait | `MARCHANDISE_RECEPTIONNER` |
| `DISPONIBLE` | `RETIREE` | Code de retrait validé | `RETRAIT_CONFIRMER` |

**Toute autre transition est refusée.** Y compris — et surtout — les
« retours en arrière » : une commande `EXPEDIEE` ne redevient jamais `PRETE`.
Si un colis revient, ce n'est pas une régression d'état, c'est un **incident**
ou un **retour** : une autre entité, avec sa propre histoire.

> ⚠️ **Le piège du statut qui recule.**
> C'est tentant : « le responsable s'est trompé, remets-le en PRETE ».
> Mais alors l'historique ment : la commande a bien été expédiée.
>
> La bonne réponse est toujours **une écriture de plus**, jamais une écriture
> annulée. C'est la règle fondatrice n°1 du chapitre 01 : les faits ne
> s'effacent pas.

### 4.2 La conversation

```text
   WAITING ──prise par un responsable──▶ ASSIGNED ──clôture──▶ CLOSED
      ▲                                     │                     │
      └────── retrait par un Admin ─────────┘                     │
                                                                  ▼
                                                       évaluation (une seule)
```

| Règle | Détail |
|---|---|
| `WAITING` | `responsable_id` est **null** |
| `ASSIGNED` | `responsable_id` est **non null** |
| Prise | `UPDATE ... WHERE statut = 'WAITING'` — 0 ligne = quelqu'un a été plus rapide |
| Retrait | Réservé à l'Admin, **motif obligatoire**, tracé dans `affectation_conversation` et `audit_log` |
| Évaluation | Possible **uniquement** si `CLOSED`, et une seule fois |

### 4.3 La proposition de prix

```text
   PROPOSEE ──┬── acceptée ──▶ ACCEPTEE ── utilisée dans une commande ──▶ CONSOMMEE
              │
              ├── refusée ──▶ REFUSEE
              │
              ├── contre-proposée ──▶ REFUSEE (+ une nouvelle PROPOSEE enfant)
              │
              └── date_expiration dépassée ──▶ EXPIREE  (travail périodique)
```

**Invariant clé :** une proposition ne peut passer à `ACCEPTEE` que si
`date_expiration > maintenant`. Sinon un client accepte en octobre un prix
proposé en mars.

### 4.4 Le retour

```text
   DEMANDE ──▶ ACCEPTE ──▶ RECEPTIONNE ──▶ VALIDE ──▶ CLOTURE
      │                         │
      └──▶ REFUSE               └── contrôle physique des articles
```

Le remboursement n'est déclenché qu'à `VALIDE` — c'est-à-dire **après** que
quelqu'un a vu et contrôlé la marchandise. Jamais à `DEMANDE`.

### 4.5 Le colis : l'exception

Le colis n'a **pas** de machine à états au sens strict.

Son `statut` est une **projection du dernier événement** ([A11](02-revue-critique-du-modele.md)) :

```text
dernier événement           →   statut projeté
────────────────────────────────────────────────
DEPART      (entrepôt)          EN_TRANSIT
ARRIVEE     (transit)           EN_TRANSIT
ANOMALIE                        BLOQUE
ARRIVEE     (pt récupération)   DISPONIBLE
REMISE                          REMIS
```

> 💡 **Comment tester une projection.**
> Écris un test qui, pour chaque colis, **recalcule** le statut depuis ses
> événements et le compare au statut stocké. Fais-le tourner sur toute la base.
>
> Ce test ne sert à rien le jour où tu l'écris. Il attrapera un bug dans deux
> ans, quand quelqu'un ajoutera un événement en oubliant de mettre à jour la
> projection.

---

## 5. Le catalogue des invariants

La colonne **« Porté par »** est ce qu'on écrira dans le schéma au chapitre 05.

### 5.1 IAM et permissions

| # | Invariant | Porté par |
|---|---|---|
| I-01 | Un e-mail identifie un seul utilisateur | `UNIQUE (email)` |
| I-02 | Un responsable a **exactement une** catégorie principale | Index unique partiel `WHERE principale = true` |
| I-03 | Un responsable a **au moins une** catégorie | Service *(la base ne sait pas exiger « au moins un »)* |
| I-04 | Une exception est soit `ADD`, soit `REMOVE`, jamais les deux | Clé primaire `(responsable_id, cas_utilisation_id)` |
| I-05 | Le `code` d'un cas d'utilisation est unique et immuable | `UNIQUE (code)` + révocation du droit `UPDATE` sur la colonne |
| I-06 | Un `CLIENT` a une ligne `client`, un `RESPONSABLE` une ligne `responsable` | Service + test d'intégrité nocturne |

### 5.2 Catalogue

| # | Invariant | Porté par |
|---|---|---|
| I-07 | Tout produit a **au moins une** variante | Service + contrôle à la publication |
| I-08 | Un SKU identifie une seule variante | `UNIQUE (sku)` |
| I-09 | `quantite_min <= quantite_max` | `CHECK` |
| I-10 | Les paliers de prix d'une variante **ne se chevauchent pas** | `EXCLUDE USING gist` |
| I-11 | Un prix est strictement positif | `CHECK (prix_unitaire > 0)` |
| I-12 | Un produit `PUBLIE` a ≥ 1 variante active, ≥ 1 prix, ≥ 1 photo | Service, au moment de `PRODUIT_PUBLIER` |
| I-13 | Une seule photo `principal = true` par produit | Index unique partiel |

### 5.3 Stock

| # | Invariant | Porté par |
|---|---|---|
| I-14 | `quantite_disponible >= 0` et `quantite_reservee >= 0` | `CHECK` |
| I-15 | Une variante a **exactement un** stock | `UNIQUE (variante_id)` + création automatique |
| I-16 | `quantite_apres = quantite_avant + quantite` | `CHECK` sur `mouvement_stock` |
| I-17 | Tout mouvement a une origine renseignée | `NOT NULL` sur `origine_type` |
| I-18 | Un mouvement est **immuable** | Aucun `UPDATE`/`DELETE` accordé sur la table |

### 5.4 Commerce

| # | Invariant | Porté par |
|---|---|---|
| I-19 | Un seul panier `ACTIF` par client | Index unique partiel |
| I-20 | Une variante n'apparaît qu'une fois par panier | `UNIQUE (panier_id, variante_id)` |
| I-21 | `montant_total = articles + frais − remise` | `CHECK` |
| I-22 | `montant_ligne = quantite × prix_unitaire` | `CHECK` |
| I-23 | `montant_commission = montant_ligne × taux / 100` | `CHECK` |
| I-24 | Une commande a **au moins une** ligne | Service *(vérifié à la création)* |
| I-25 | `point_recuperation_id` pointe un lieu de type `POINT_RECUPERATION` | Trigger, ou colonne `type` dénormalisée + FK composite |
| I-26 | On ne passe à `PAYEE` que si les encaissements confirmés ≥ `montant_total` | Service, dans la transaction du webhook |
| I-27 | La somme des remboursements ≤ la somme des encaissements | Service + contrôle nocturne |
| I-28 | Quantité commandée strictement positive | `CHECK (quantite > 0)` |

### 5.5 Service client

| # | Invariant | Porté par |
|---|---|---|
| I-29 | `statut = 'ASSIGNED'` ⇔ `responsable_id IS NOT NULL` | `CHECK` sur les deux colonnes |
| I-30 | Une seule affectation ouverte (`date_fin IS NULL`) par conversation | Index unique partiel |
| I-31 | Une évaluation par conversation, et seulement si `CLOSED` | `UNIQUE (conversation_id)` + service |
| I-32 | Une note est entre 1 et 5 | `CHECK` |
| I-33 | Une proposition n'est acceptable que non expirée | Service |

### 5.6 Logistique

| # | Invariant | Porté par |
|---|---|---|
| I-34 | L'ordre des étapes d'un itinéraire est unique | `UNIQUE (itineraire_id, ordre)` |
| I-35 | La somme des `ligne_colis.quantite` ≤ la quantité de la ligne de commande | Trigger *(agrégat multi-lignes)* |
| I-36 | Un événement n'est pas daté dans le futur | `CHECK (date_heure <= now())` |
| I-37 | Un événement est **immuable** | Aucun `UPDATE`/`DELETE` accordé |
| I-38 | Un code de retrait est unique | `UNIQUE (code_retrait)` |
| I-39 | Un retrait n'est confirmé que si l'expédition est `DISPONIBLE` | Service |

### 5.7 SAV et finance

| # | Invariant | Porté par |
|---|---|---|
| I-40 | Le cumul retourné d'une ligne ≤ la quantité commandée | Trigger |
| I-41 | Une écriture `VENTE` est positive, une `COMMISSION` négative | `CHECK` par type |
| I-42 | Une écriture est **immuable** — on corrige par une écriture inverse | Aucun `UPDATE`/`DELETE` accordé |
| I-43 | Un règlement `PAYE` a une écriture `REGLEMENT` correspondante | Service, même transaction |
| I-44 | `audit_log` est **immuable** | Droits révoqués + `ON DELETE SET NULL` sur l'acteur |
| I-45 | Un score de risque est entre 0 et 100, et son niveau est cohérent | `CHECK` |

---

## 6. Les trois familles d'invariants, et leur outil

Tu as dû remarquer que « Porté par » n'est pas toujours une contrainte SQL.
Il y a une logique derrière :

| Famille | Exemple | Outil |
|---|---|---|
| **Sur une seule ligne** | `montant_total = articles + frais − remise` | `CHECK` — simple, rapide, infaillible |
| **Sur une colonne, entre lignes** | Un seul panier `ACTIF` par client | `UNIQUE`, index partiel, `EXCLUDE` |
| **Sur un agrégat de plusieurs lignes** | Somme des quantités de colis ≤ quantité commandée | **Trigger**, ou vérification dans le service |

> ⚠️ **Pourquoi le troisième cas est difficile — et pourquoi il faut le savoir.**
> SQL ne sait pas exprimer « la somme des lignes filles ne dépasse pas une
> valeur de la ligne mère ». Un `CHECK` ne voit qu'une ligne à la fois.
>
> Deux options, et aucune n'est parfaite :
>
> - **Trigger** : fiable même face à un `INSERT` manuel, mais invisible depuis
>   le code Java. Un développeur qui lit le service ne le voit pas. Il faut
>   donc le **documenter dans le schéma**.
> - **Service transactionnel** : lisible, testable, mais contournable.
>
> **Choix GARAH :** trigger pour les deux invariants où l'argent ou le stock
> est en jeu (I-35, I-40), service ailleurs. Le trigger est réservé aux cas
> où une erreur coûte de l'argent réel.

> 📌 **Et le « au moins un » ?**
> « Un produit a au moins une variante », « une commande a au moins une ligne » :
> SQL ne sait pas l'exprimer, parce qu'au moment où on insère le produit,
> la variante n'existe pas encore.
>
> C'est structurel, pas un manque de PostgreSQL. Ces règles vivent dans le
> service — plus un **contrôle d'intégrité nocturne** qui alerte si une
> anomalie s'est glissée. Détecter vaut mieux que ne rien faire.

---

## 7. Les frontières transactionnelles

Une **transaction** est un groupe d'écritures indivisible : tout passe, ou rien.
Mal découper les transactions est la source de bugs la plus coûteuse d'une
application de gestion, parce que les dégâts sont **silencieux**.

### Les opérations atomiques de GARAH

| Opération | Ce qui doit être atomique |
|---|---|
| **Créer une commande** | commande + lignes + réservation de stock + mouvements + panier → `CONVERTI` |
| **Confirmer un paiement** | paiement → `CONFIRME` + commande → `PAYEE` + écritures marchand + notification |
| **Valider un retour** | lignes de retour + mouvements de stock + remboursement + écritures marchand + audit |
| **Enregistrer un événement colis** | événement + projection du statut du colis + éventuellement de l'expédition |
| **Prendre une conversation** | conversation → `ASSIGNED` + ligne d'affectation |

### Le contre-exemple qui fait mal

```text
❌ Deux transactions séparées

  Transaction 1 : stock réservé          ✅ validée
  ──────────────────────────────────────────────────
  Transaction 2 : commande créée         ❌ échoue

  Résultat : 12 articles réservés pour une commande qui n'existe pas.
             Personne ne le voit. Le stock disponible baisse tout seul.
             On le découvre trois mois plus tard, en inventaire.
```

> 🎯 **La règle : ce qui doit être vrai ensemble doit être écrit ensemble.**
>
> Et le corollaire, moins connu :
> **ce qui n'a pas besoin d'être vrai ensemble ne doit PAS être dans la même
> transaction.** L'envoi d'un e-mail de confirmation ne doit jamais faire
> échouer une commande. Il part **après** la validation, jamais dedans.

```text
┌─────────── TRANSACTION ────────────┐
│  commande + lignes + stock + audit │   ← doit être vrai ensemble
└──────────────┬─────────────────────┘
               │ validée
               ▼
      e-mail, SMS, notification          ← en dehors. Peut échouer sans
                                            annuler la commande.
```

---

## 8. Les pièges de ce chapitre

### Piège 1 — Écrire la règle uniquement dans le frontend

Angular grise le bouton « Commander » quand le stock est à zéro.
C'est bien. Ça ne protège de **rien** : n'importe qui peut envoyer la requête
directement. Le frontend, c'est du confort, jamais de la sécurité.

### Piège 2 — Le trigger invisible

Un trigger applique une règle qu'aucun développeur ne voit en lisant le code
Java. Six mois plus tard, quelqu'un passe une heure à comprendre pourquoi son
`INSERT` est refusé.

→ Tout trigger doit être **commenté dans le schéma SQL** et **listé dans ce
chapitre**. Un trigger non documenté est un piège qu'on se tend à soi-même.

### Piège 3 — Croire qu'une contrainte ralentit

« On mettra les contraintes plus tard, pour les performances. »

C'est faux deux fois :

1. Un `CHECK` sur une ligne coûte quelques microsecondes — indétectable.
2. Ajouter une contrainte **après coup** est bien plus coûteux : il faut
   d'abord **nettoyer** les données déjà corrompues, ligne par ligne, en
   production, sans savoir laquelle était juste.

> **Une contrainte se pose le premier jour, ou elle ne se pose jamais.**

### Piège 4 — Confondre validation et invariant

« L'e-mail doit contenir un @ » n'est pas un invariant, c'est une validation
de format. La mettre en `CHECK` avec une expression régulière semble rigoureux,
et pose des problèmes réels : les adresses valides sont bien plus variées que
ce qu'on croit, et une contrainte trop stricte bloquera un vrai client sans
qu'on comprenne pourquoi.

→ Format côté API. Unicité côté base.

---

## 9. À retenir

1. **Invariant** (toujours vrai) → la base. **Transition** (d'un état à l'autre) → le service. **Validation** (le format) → l'API.
2. Un invariant se reconnaît à ce qu'il est vrai **sans connaître le passé**.
3. La base est la **dernière** ligne de défense, jamais la **seule** ligne visible.
4. **Une contrainte se pose le premier jour**, ou elle ne se pose jamais.
5. Un statut ne **recule** jamais. Un problème produit une écriture de plus, pas une écriture annulée.
6. Le statut d'un colis est une **projection** de ses événements — écris le test qui le vérifie.
7. **Ce qui doit être vrai ensemble s'écrit ensemble.** Ce qui peut échouer seul sort de la transaction.
8. SQL ne sait pas dire « au moins un » : ces règles vivent dans le service, avec un contrôle nocturne.

---

## 10. Exercices

**Exercice 1.**
Classe ces règles en invariant / transition / validation, et dis où tu les places :

1. Un numéro de téléphone camerounais commence par 6.
2. Le montant remboursé ne dépasse pas le montant payé.
3. Une commande annulée ne peut plus être préparée.
4. Deux marchands ne peuvent pas avoir le même code.
5. Un responsable ne peut prendre une conversation que si elle est en attente.

**Exercice 2.**
Écris la contrainte SQL qui garantit I-16
(`quantite_apres = quantite_avant + quantite`).
Puis explique pourquoi elle **ne suffit pas** à garantir que l'historique des
mouvements est cohérent d'un mouvement au suivant.

**Exercice 3.**
Un responsable enregistre par erreur un colis comme « REMIS » alors qu'il est
encore en transit. Décris la marche à suivre — sans jamais supprimer ni
modifier l'événement fautif.

**Exercice 4.**
Le webhook de MTN Mobile Money confirme un paiement. Liste **toutes** les
écritures de la transaction, dans l'ordre, et dis ce qui doit rester **en
dehors** de cette transaction.

**Exercice 5.**
Trouve, dans le catalogue du §5, un invariant qui pourrait passer d'un trigger
à une contrainte SQL si on acceptait de **dénormaliser** une colonne.
Explique le compromis.

---

➡️ **Chapitre suivant :** 05 — Mettre en place PostgreSQL et le schéma
