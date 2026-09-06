# Chapitre 15 — La logistique : expédition, itinéraire, traçabilité

> Prérequis : chapitres [12](12-panier-et-commande.md) et [13](13-paiement-mobile-money.md).
> Durée de lecture : ~35 min.

---

## 1. Ce qu'on veut faire

Acheminer la marchandise de Douala à Bangui, et pouvoir répondre à tout
moment :

> **Où est mon colis, et par où est-il passé ?**

C'est le module où la **règle fondatrice n°1** du chapitre 01 prend tout son
sens :

```text
La VÉRITÉ est la suite des ÉVÉNEMENTS.
Le STATUT n'en est qu'une PROJECTION.
```

---

## 2. Un lieu, trois natures

Le modèle initial avait deux tables : `point_transit` et `point_recuperation`.

**Il ne pouvait donc enregistrer ni le départ de l'entrepôt, ni la remise au
client** — les deux événements les plus importants du parcours (correction A10).

```java
public enum TypeLieu { ENTREPOT, POINT_TRANSIT, POINT_RECUPERATION }
```

Une seule table `lieu`, et un événement peut se produire **n'importe où**.

Et le type reste contraint là où il compte, sans trigger :

```sql
-- sur lieu
CONSTRAINT lieu_id_type_unique UNIQUE (id, type)

-- sur expedition
point_recuperation_type varchar(20) NOT NULL DEFAULT 'POINT_RECUPERATION',
CHECK (point_recuperation_type = 'POINT_RECUPERATION'),
FOREIGN KEY (point_recuperation_id, point_recuperation_type) REFERENCES lieu (id, type)
```

C'est la technique du chapitre 05 §4.3 : **une clé étrangère composite vaut
mieux qu'un trigger**, parce qu'elle est déclarative et visible dans le schéma.

---

## 3. Le modèle prévu et le trajet réel

```java
@Column(name = "itineraire_id")
private Long itineraireId;        // ← nullable
```

**L'itinéraire est facultatif** (correction A9) : une livraison Douala → Douala
n'en a pas.

Et quand il existe, ce n'est qu'un **modèle prévu**. Le trajet réel vit dans
les événements — et peut s'en écarter.

```text
ITINÉRAIRE « Douala → Bangui »        ÉVÉNEMENTS du colis n°42
   Douala                                12/03  DEPART    Douala
   Yaoundé                                13/03  ARRIVEE   Bertoua
   Bertoua                                14/03  ANOMALIE  Bertoua « route coupée »
   Garoua-Boulaï                          15/03  ARRIVEE   Batouri  ← HORS itinéraire
   Bangui                                 …
```

### Le déroutement est autorisé, et c'est volontaire

```java
// Aucune vérification que le lieu appartient à l'itinéraire.
EvenementExpedition evenement = evenements.save(new EvenementExpedition(…));
```

> 🎯 **Une contrainte qui empêche d'enregistrer la réalité est une mauvaise
> contrainte.**
>
> Si la base refusait Batouri, l'opérateur saisirait **Bertoua** — parce qu'il
> faut bien que le camion avance. On aurait alors une base cohérente et
> fausse : le pire des deux mondes.
>
> On contraint l'**intégrité** (le lieu existe), jamais la **conformité au
> plan** (l'écart au plan est une information, pas une erreur).

Le test le vérifie explicitement.

---

## 4. La projection tient dans une seule méthode

```java
static StatutColis projeter(TypeEvenement type, boolean auPointDeRecuperation) {
    return switch (type) {
        case DEPART            -> EN_TRANSIT;
        case ARRIVEE, RECEPTION -> auPointDeRecuperation ? DISPONIBLE : EN_TRANSIT;
        case ANOMALIE          -> BLOQUE;
        case REMISE            -> REMIS;
        case CONTROLE          -> null;      // n'affecte pas le statut
    };
}
```

**Un seul endroit à lire** pour savoir comment un événement se traduit en
statut, et un seul endroit à corriger.

Remarque la subtilité : `ARRIVEE` ne signifie pas la même chose partout.

```text
ARRIVEE à Bertoua (transit)            → EN_TRANSIT
ARRIVEE à Bangui  (point de retrait)   → DISPONIBLE
```

C'est le **lieu** qui décide, pas le type d'événement seul.

### La projection se vérifie

```java
public boolean projectionCoherente(Long colisId) { … }
```

Même principe que la réconciliation du stock (chapitre 11 §10) : le statut est
recalculé depuis le dernier événement et comparé à la valeur stockée.

> 💡 Ce test ne sert à rien le jour où on l'écrit. Il attrapera un bug dans
> deux ans, quand quelqu'un ajoutera un type d'événement en oubliant de
> compléter le `switch`.

Et le tri du « dernier événement » compte :

```java
findFirstByColisIdOrderByDateHeureDescIdDesc(colisId)
```

Deux événements enregistrés dans la même milliseconde auraient sinon un ordre
**indéterminé**, et la projection cesserait d'être déterministe.

---

## 5. Le bug du code d'erreur

Celui-ci n'apparaît qu'au test de bout en bout, et il vaut le détour.

Le trigger `I-35` fonctionne : mettre 12 unités en colis pour 10 commandées est
bien refusé.

```text
ERREUR : I-35 : quantite en colis (12) superieure a la quantite commandee (10)
```

Mais le test échouait quand même :

```text
Expecting  DataIntegrityViolationException
but was    JpaSystemException
```

### La cause, en cascade

```text
PL/pgSQL   RAISE EXCEPTION          → SQLSTATE P0001 (raise_exception)
     ↓
Spring     P0001 n'est pas classe 23 → JpaSystemException
     ↓
API        gestionnaire global       → 500 ERREUR_INTERNE
```

**Nos deux invariants les plus coûteux — ceux qui protègent la marchandise et
l'argent — produisaient une erreur serveur illisible au lieu d'un conflit
métier compréhensible.**

### La correction

```sql
RAISE EXCEPTION '…' USING ERRCODE = '23514';   -- check_violation
```

Le message ne change pas. Sa **classification**, oui. Spring le traduit alors
en `DataIntegrityViolationException`, et le gestionnaire du chapitre 06 répond
`409`.

> 📌 **La leçon dépasse ce cas.**
> Un trigger ne fait pas que vérifier une règle : il doit aussi se
> **présenter correctement** à la couche au-dessus. Une règle juste mais mal
> classée casse l'interface.
>
> Et c'est un défaut qu'aucune relecture n'aurait trouvé : le SQL est correct,
> le Java est correct. Seul l'assemblage était faux.

---

## 6. Le code de retrait

```java
private static final String ALPHABET = "ACDEFGHJKLMNPQRSTUVWXYZ2345679";
```

Regarde ce qui **manque** : `B`, `I`, `O`, `0`, `1`, `8`.

> 🎯 Ce code sera **lu à voix haute, recopié à la main, épelé au téléphone**.
> Un « 0 » confondu avec un « O » fait revenir le client le lendemain — pour
> un aller-retour Bangui-PK5 qui lui coûte une demi-journée.
>
> Trente caractères au lieu de trente-six : le genre de détail qui ne se voit
> pas en développement et qui se paie sur le terrain.

### Le code est cherché par lui-même

```java
retraits.findByCodeRetrait(codeRetrait)
        .orElseThrow(() -> new RegleMetierViolee("CODE_INVALIDE", …));
```

**Pas** « charger l'expédition puis comparer le code ». C'est ce qui en fait
une **preuve** : un opérateur pressé ne peut pas sauter la vérification, parce
qu'il n'a aucun autre moyen de trouver le retrait.

### La remise est un événement, pas un statut

```java
expedition.getColis().forEach(paquet ->
        enregistrer(paquet.getId(), …, TypeEvenement.REMISE, null));
retrait.confirmer(responsableId);
```

On écrit un événement `REMISE` **par colis**. La remise fait partie du
parcours ; elle n'est pas seulement un champ qui change.

---

## 7. Une expédition va au rythme de son colis le moins avancé

```java
if (colis.stream().anyMatch(c -> c.getStatut() == BLOQUE))            → BLOQUEE
else if (colis.stream().allMatch(c -> c.getStatut() == REMIS))        → REMISE
else if (colis.stream().allMatch(c -> DISPONIBLE || REMIS))           → DISPONIBLE
else if (colis.stream().anyMatch(c -> EN_TRANSIT))                    → EN_TRANSIT
```

L'ordre des tests compte : **un seul colis bloqué bloque l'expédition**, et
`DISPONIBLE` exige que **tout** soit arrivé.

> ⚠️ Annoncer au client que sa commande est prête alors qu'un colis est encore
> en route est la meilleure façon de le faire venir pour rien. Sur un trajet
> Douala-Bangui, « venir pour rien » coûte une journée.

---

## 8. À retenir

1. **Un lieu, trois natures.** Deux tables séparées rendaient le départ et la remise inenregistrables.
2. **Clé étrangère composite plutôt que trigger** pour contraindre le type d'un lieu.
3. L'itinéraire est un **modèle prévu**, pas le trajet réel.
4. **Le déroutement est autorisé** : une contrainte qui empêche d'enregistrer la réalité produit une base cohérente et fausse.
5. La **projection tient dans une seule méthode**, et elle se vérifie.
6. Le **dernier événement** se trie par date **et** par identifiant, sinon la projection n'est pas déterministe.
7. Un trigger doit **se présenter correctement** : `USING ERRCODE = '23514'`, sinon 500 au lieu de 409.
8. Un **code lu à voix haute** exclut les caractères ambigus.
9. Le code se cherche **par lui-même** : c'est ce qui en fait une preuve.
10. Une expédition va au rythme de son colis **le moins avancé**.

---

## 9. Exercices

**Exercice 1.**
Un colis est marqué `ANOMALIE` à Bertoua, puis repart. Quel événement
enregistrer pour le débloquer ? Que dit la projection ? Faut-il un nouveau
type d'événement ?

**Exercice 2.**
Une commande contient des articles de deux marchands, expédiés séparément.
Écris le code qui crée deux expéditions et répartit les lignes. Que doit
afficher le client tant qu'un seul colis est arrivé ?

**Exercice 3.**
Retire `USING ERRCODE = '23514'` d'un trigger, lance `ParcoursLogistiqueTest`,
et observe le code HTTP obtenu. Explique la chaîne complète.

**Exercice 4.**
Un client prétend n'avoir jamais reçu sa marchandise, alors que le retrait est
confirmé. Quelles données peux-tu produire ? Que manque-t-il pour être
totalement convaincant ?

**Exercice 5.**
On veut prévenir le client par SMS à chaque étape. Où brancher l'envoi, et
pourquoi surtout pas dans la transaction qui enregistre l'événement ?

---

➡️ **Chapitre suivant :** [16 — Le SAV : réclamations et retours](16-sav-reclamations-et-retours.md)
