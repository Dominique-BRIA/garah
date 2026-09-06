# Chapitre 11 — Le stock et la concurrence

> Prérequis : chapitres [05](05-postgresql-et-le-schema.md) et [10](10-prix-par-palier.md).
> Durée de lecture : ~40 min.
> **Le chapitre le plus important du backend.** Le métier est simple — compter
> des unités. La difficulté est ailleurs.

---

## 1. Le bug fantôme

Deux clients cliquent sur « Commander » à la même milliseconde. Il reste un
article.

```text
        Client A                      Client B
           │                             │
      lit « 1 disponible »          lit « 1 disponible »
           │                             │
      « OK, je peux »               « OK, je peux »
           │                             │
      écrit « 0 »                   écrit « 0 »
           │                             │
           ▼                             ▼
                 L'article est vendu DEUX fois
```

Ce bug a trois propriétés qui le rendent redoutable :

| Propriété | Conséquence |
|---|---|
| Invisible en développement | On est seul, les requêtes ne se croisent jamais |
| Invisible en test unitaire | Un test séquentiel ne le reproduit pas |
| Apparaît sous charge | Le jour d'une promotion, quand ça coûte le plus cher |

Et il ne lève **aucune erreur**. On le découvre à la préparation de commande,
quand il manque de la marchandise.

---

## 2. La démonstration, chiffres à l'appui

J'ai écrit le test **avant** de vérifier la protection, puis j'ai retiré le
verrou pour voir ce qui se passe. Voici le résultat réel :

```text
SANS VERROU

  2 clients, 1 article    →  2 réussites      ❌ vendu deux fois
 20 clients, 5 articles   →  15 réussites     ❌ 10 unités vendues en trop
 réconciliation           →  échec            ❌ l'état ne correspond plus au journal

AVEC VERROU

  2 clients, 1 article    →  1 réussite, 1 refus      ✅
 20 clients, 5 articles   →  5 réussites, 15 refus    ✅
 réconciliation           →  cohérente                ✅
```

> 🎯 **La règle la plus utile de ce chapitre :**
> **un test de concurrence qui passe aussi sans la protection ne prouve rien.**
>
> Écris le test, retire la protection, vérifie qu'il **échoue**, remets-la.
> Sans cette étape, tu as un test qui rassure sans protéger — pire que pas de
> test du tout.

### Le détail qui rend le test valide

```java
CyclicBarrier depart = new CyclicBarrier(clients);
…
depart.await(10, TimeUnit.SECONDS);      // tout le monde part au même instant
stock.reserver(varianteId, quantite, commandeId);
```

Sans la barrière, les threads démarrent les uns après les autres — et le test
**passerait même sans verrou**. C'est le point le plus facile à rater en
écrivant un test de concurrence.

---

## 3. Pourquoi le `CHECK` ne suffit pas

On a pourtant une contrainte, posée au chapitre 05 :

```sql
CHECK (quantite_disponible >= 0)
```

Elle n'a **pas** empêché la survente. Il faut comprendre pourquoi.

```text
stock disponible = 5

15 transactions lisent toutes « 5 »
15 transactions écrivent toutes « 4 »

valeur finale : 4        ← jamais négative, donc le CHECK ne se déclenche jamais
unités réellement engagées : 15
```

C'est ce qu'on appelle une **mise à jour perdue** (*lost update*) : chacun
écrase le travail des autres. Le résultat est parfaitement valide **en tant que
valeur** — il est simplement faux.

> 📌 **Un `CHECK` valide une LIGNE, il n'ordonne pas des TRANSACTIONS.**
> Ce sont deux problèmes différents, et il faut deux outils différents.
> La contrainte reste indispensable — elle attrape les scripts d'import et les
> corrections manuelles — mais elle ne remplace pas un verrou.

---

## 4. Les trois parades

| Parade | Verdict |
|---|---|
| `synchronized` en Java | ❌ Ne marche que sur **un** serveur. Cassé dès qu'il y en a deux — et Render peut en démarrer plusieurs. |
| `UPDATE … WHERE disponible >= :n`, puis lire le nombre de lignes | ✅ Correct, très rapide, sans verrou explicite |
| Verrou pessimiste `SELECT … FOR UPDATE` | ✅ Correct, et donne l'état avant/après |

### Ce que GARAH retient, et pourquoi

**Le verrou pessimiste.** Le `UPDATE` conditionnel est plus rapide, mais il ne
renvoie **pas** les valeurs. Or chaque opération doit journaliser
`quantite_avant` et `quantite_apres` — sans quoi le journal ne serait pas
vérifiable.

Le verrou permet le schéma en quatre temps :

```text
1. verrouiller la ligne          SELECT … FOR UPDATE
2. vérifier                      « en reste-t-il assez ? »
3. appliquer                     modifier les compteurs
4. journaliser                   écrire le ou les mouvements
```

Les quatre dans **une seule transaction**. Sans ça, on pourrait réserver du
stock pour une commande qui n'existera jamais.

---

## 5. Le verrou : portée, coût, et le piège de l'interblocage

### Il ne bloque qu'une ligne

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Stock s WHERE s.varianteId = :varianteId")
Optional<Stock> verrouiller(Long varianteId);
```

Deux commandes de **produits différents** ne s'attendent pas. On ne sérialise
que ce qui est réellement en concurrence — c'est ce qui rend le coût
acceptable.

### Le piège : l'ordre de verrouillage

Une commande contient souvent plusieurs articles. Là, un vrai danger apparaît :

```text
Transaction A   verrouille la variante 7, puis demande la 3
Transaction B   verrouille la variante 3, puis demande la 7

     A attend B      et      B attend A      →  interblocage
```

PostgreSQL le détecte et tue l'une des deux — mais le client, lui, voit une
erreur incompréhensible.

**La parade est simple et absolue : toujours verrouiller dans le même ordre.**

```java
@Query("SELECT s FROM Stock s WHERE s.varianteId IN :varianteIds ORDER BY s.varianteId")
List<Stock> verrouillerPlusieurs(List<Long> varianteIds);
```

Le `ORDER BY` n'est pas cosmétique : en verrouillant toujours par identifiant
croissant, le cycle ne peut pas se former. C'est une des rares situations où
une clause de tri est une **règle de correction**, pas de présentation.

---

## 6. Une lacune du modèle, découverte en codant

C'est arrivé pendant l'écriture de ce chapitre, et ça mérite d'être raconté.

La table `mouvement_stock` avait `quantite_avant` et `quantite_apres`, avec la
contrainte :

```sql
CHECK (quantite_apres = quantite_avant + quantite)
```

Impeccable — **jusqu'à ce qu'on essaie de journaliser une réservation.**

```text
Réserver 3 unités :
    quantite_disponible  10 → 7      (−3)
    quantite_reservee     0 → 3      (+3)
```

**Deux compteurs bougent.** Une seule ligne ne peut pas décrire ça : elle ne
dit même pas de quel compteur elle parle.

### La correction : un journal à double entrée

La migration `V16` ajoute une colonne `compteur`, et chaque mouvement décrit
**un seul** compteur. Une réservation s'écrit donc en **deux lignes**, dans la
même transaction :

```text
type=RESERVATION  compteur=DISPONIBLE  quantite=−3   avant=10  après=7
type=RESERVATION  compteur=RESERVEE    quantite=+3   avant=0   après=3
```

> 🎯 **C'est exactement le principe du grand livre marchand** (chapitre 03,
> domaine 9) : on n'écrit pas un état, on écrit des mouvements, et **l'état
> est leur somme**.
>
> Le même motif est apparu deux fois, dans deux domaines sans rapport — la
> finance et le stock. Quand un motif revient comme ça, c'est en général qu'il
> est juste.

Et la leçon de méthode : **un modèle se révèle incomplet au moment de
l'implémenter**, pas au moment de le dessiner. La migration `V16` n'est pas un
échec de conception, c'est le fonctionnement normal.

---

## 7. Réserver plutôt que décrémenter

Pourquoi ne pas simplement enlever la marchandise du stock à la commande ?

**Parce que le mobile money est asynchrone** (D-06). Entre le moment où le
client valide sur son téléphone et la confirmation de l'opérateur, il peut
s'écouler plusieurs minutes.

```text
commande créée      → RÉSERVÉ      la marchandise est engagée, pas vendue
paiement confirmé   → SORTIE       elle quitte l'entrepôt
paiement jamais confirmé → LIBÉRÉ  elle redevient vendable
```

Pendant l'attente, la marchandise ne doit être **ni vendue à quelqu'un
d'autre**, **ni considérée comme vendue**.

> ⚠️ **La libération n'est pas optionnelle.**
> Sans un travail périodique qui libère les réservations dont le paiement
> n'arrive jamais, chaque client qui abandonne son paiement **immobilise de la
> marchandise pour toujours**. Le stock disponible fond, personne ne comprend
> pourquoi, et la seule trace est dans `mouvement_stock`.

---

## 8. Les six opérations

| Opération | Compteurs touchés | Lignes de journal |
|---|---|---|
| **Entrée** (réception) | `DISPONIBLE +n` | 1 |
| **Réservation** (commande) | `DISPONIBLE −n`, `RESERVEE +n` | 2 |
| **Libération** (annulation) | `RESERVEE −n`, `DISPONIBLE +n` | 2 |
| **Sortie** (paiement confirmé) | `RESERVEE −n` | 1 |
| **Retour** en bon état | `DISPONIBLE +n` | 1 |
| **Retour** abîmé | `ENDOMMAGEE +n` | 1 |
| **Ajustement** (inventaire) | `DISPONIBLE ±écart` | 1 |

Deux détails qui comptent :

**La sortie ne touche que `RESERVEE`.** Le disponible a déjà été décrémenté à
la réservation. C'est à ce moment seulement que le stock physique diminue
réellement.

**Un retour abîmé ne redevient pas vendable.** Il rejoint `ENDOMMAGEE` : il
existe physiquement, il compte dans l'inventaire, mais il ne doit jamais
repartir chez un client. La §13 de la spécification le demandait ; sans un
compteur dédié, on l'aurait revendu.

---

## 9. L'ajustement, et pourquoi le motif est obligatoire

```java
if (motif == null || motif.isBlank()) {
    throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
            "Un ajustement de stock doit être justifié.");
}
```

L'ajustement est la **seule** opération qui fait varier le stock sans cause
métier. C'est donc la seule qui doit s'expliquer.

> ⚠️ Un ajustement sans justification est **indiscernable d'un vol**. Le champ
> `commentaire` n'est pas de la documentation : c'est un élément de contrôle
> interne.

Et un ajustement sans écart n'écrit **rien** :

```java
if (ecart == 0) return EtatStock.de(stock);
```

Un mouvement de zéro est interdit par la contrainte
`mouvement_stock_quantite_non_nulle` — et il n'apprendrait rien à personne.

---

## 10. La réconciliation

```java
public boolean estReconcilie(Long varianteId) {
    return stock.getQuantiteDisponible() == mouvements.recalculer(id, DISPONIBLE)
        && …
}
```

L'état stocké dans `stock` doit **toujours** égaler la somme de son journal.

Si les deux divergent, une écriture a eu lieu **hors du service** : script
d'import, correction manuelle en base, ou bug.

> 📌 **À faire tourner chaque nuit.** Mieux vaut apprendre un écart de stock
> par une alerte que par un inventaire six mois plus tard, quand plus personne
> ne peut dire d'où il vient.
>
> C'est le même contrôle que pour les statuts de colis (chapitre 04 §4.5) :
> **une projection se vérifie**.

Le test le fait après la bousculade à 20 threads — et c'est ce test qui échoue
en premier quand on retire le verrou.

---

## 11. À retenir

1. Le bug de concurrence est **silencieux, invisible en dev, et se déclenche sous charge**.
2. **Un test de concurrence qui passe sans la protection ne prouve rien.** Retire-la, vérifie qu'il échoue, remets-la.
3. Sans **barrière de départ**, les threads ne se croisent pas et le test est faux.
4. Un `CHECK` valide une **ligne** ; il n'ordonne pas des **transactions**. La mise à jour perdue produit une valeur valide et fausse.
5. `synchronized` ne protège rien dès qu'il y a **deux serveurs**.
6. Le **verrou pessimiste** ne bloque qu'une ligne — on ne sérialise que ce qui est en concurrence.
7. **Toujours verrouiller dans le même ordre**, sinon interblocage. Le `ORDER BY` est une règle de correction.
8. Un mouvement décrit **un seul compteur** : le stock est un journal à double entrée, comme le grand livre marchand.
9. On **réserve** avant de sortir, parce que le paiement mobile est asynchrone — et on **libère** ce qui n'est jamais payé.
10. **Une projection se vérifie** : réconciliation nocturne entre l'état et le journal.

---

## 12. Exercices

**Exercice 1.**
Retire `@Lock(LockModeType.PESSIMISTIC_WRITE)` de `verrouiller`, lance
`ConcurrenceStockTest`, et note les trois échecs. Explique, pour chacun,
quelle propriété est violée.

**Exercice 2.**
Une commande contient trois variantes. Écris la réservation en utilisant
`verrouillerPlusieurs`, et explique ce qui se passerait avec trois appels
successifs à `verrouiller` dans un ordre différent d'une commande à l'autre.

**Exercice 3.**
Écris le travail périodique qui libère les réservations des commandes restées
en `EN_ATTENTE_PAIEMENT` depuis plus de 30 minutes. Quels mouvements
écrit-il ? Que se passe-t-il si le paiement arrive **pendant** son exécution ?

**Exercice 4.**
Un inventaire trouve 8 unités alors que la base en annonce 10. Décris
l'ajustement, les lignes écrites, et ce que la réconciliation dira ensuite.

**Exercice 5.**
On veut afficher « plus que 2 en stock ! » sur la fiche produit. Faut-il
compter `disponible` seul, ou `disponible + reservee` ? Argumente en pensant
à ce que le client comprend.

---

➡️ **Chapitre suivant :** 12 — Le panier et la commande
