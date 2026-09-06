# Chapitre 13 — Le paiement mobile money

> Prérequis : chapitre [12](12-panier-et-commande.md).
> Durée de lecture : ~30 min.
> **Un seul sujet compte vraiment ici : l'idempotence.**

---

## 1. Ce qui rend le paiement mobile différent

Un paiement par carte est **synchrone** : on appelle, on attend, on sait.

Le mobile money ne fonctionne pas ainsi.

```text
1. le client demande à payer            → INITIE
2. l'opérateur envoie un code au client → EN_ATTENTE
3. le client valide sur son téléphone      (30 s ? 5 min ? jamais ?)
4. l'opérateur appelle NOTRE webhook    → CONFIRME
```

Entre l'étape 1 et l'étape 4, notre serveur **ne sait rien**. C'est toute la
raison d'être de la réservation de stock construite au chapitre 11 : pendant
cette attente, la marchandise ne doit être ni vendue à quelqu'un d'autre, ni
considérée comme vendue.

---

## 2. Le montant vient de la commande, jamais du client

```java
BigDecimal reste = commande.getMontantTotal().subtract(dejaPaye);
return paiements.save(Paiement.encaissement(commandeId, reste, moyen));
```

Le montant n'est **pas** un paramètre de la requête.

> ⚠️ **C'est la faille la plus classique d'un tunnel de paiement.** Accepter un
> montant venu de l'extérieur permet de payer 100 FCFA une commande de
> 32 000 : il suffit de modifier la requête avant de l'envoyer. Aucune
> validation de format ne protège de ça — seule la règle « le serveur décide
> du montant » protège.

---

## 3. L'idempotence : le cœur du chapitre

### Le webhook n'est pas appelé une fois

MTN et Orange **rejouent** leur notification tant qu'ils n'ont pas reçu un
accusé de réception :

- délai réseau dépassé ;
- notre serveur redémarre au mauvais moment ;
- l'instance Render sortait de veille et a mis 20 secondes à répondre (D-14).

**Deux appels pour le même paiement sont la norme, pas l'exception.**

### Ce que produirait une confirmation traitée deux fois

```text
✗ deux sorties de stock pour une seule commande
✗ deux écritures de vente au marchand
✗ un solde faux

et surtout : AUCUNE erreur visible
```

### La garde

```java
if (paiement.estConfirme()) {
    return paiement;        // ← on sort SANS erreur
}
```

Le détail qui compte : on renvoie une réponse **normale**, pas une exception.
Répondre en erreur ferait croire à l'opérateur que la notification a échoué —
et il rejouerait **indéfiniment**.

### Le test

```java
paiements.confirmer(paiement.getId(), "OM-REF-0001");
paiements.confirmer(paiement.getId(), "OM-REF-0001");
paiements.confirmer(paiement.getId(), "OM-REF-0001");

assertThat(stock.etat(varianteId).total()).isEqualTo(7);    // une seule sortie
assertThat(confirmes).isEqualTo(1);
```

---

## 4. La seconde ligne de défense

Le service est idempotent. Ça ne suffit pas.

```sql
-- V18
CREATE UNIQUE INDEX paiement_reference_unique
    ON paiement (reference_transaction)
    WHERE reference_transaction IS NOT NULL;
```

> 📌 **Doctrine du chapitre 04, appliquée :** la base est la dernière ligne de
> défense. Même un import manuel, un script de reprise ou un second service ne
> pourra pas enregistrer deux fois la même transaction opérateur.

L'index est **partiel**, et c'est nécessaire : plusieurs paiements peuvent
légitimement n'avoir aucune référence (un paiement encore `INITIE`, un
remboursement interne). Un `UNIQUE` classique les interdirait à partir du
second — `NULL` n'est pas égal à `NULL` en SQL, mais mieux vaut être explicite.

Le test le vérifie **en contournant le service** :

```java
assertThatThrownBy(() -> jdbc.update("INSERT INTO paiement … 'MOMO-DOUBLON' …"))
        .isInstanceOf(DataIntegrityViolationException.class);
```

---

## 5. Le sens est porté par le type, jamais par le signe

```java
public enum TypePaiement { ENCAISSEMENT, REMBOURSEMENT }
```

```sql
CHECK (montant > 0)
```

Un remboursement est un montant **positif** de type `REMBOURSEMENT`.

> 🎯 **Pourquoi pas un montant négatif ?**
> Parce qu'il suffirait d'oublier un `WHERE type = 'ENCAISSEMENT'` dans une
> requête de chiffre d'affaires pour que les remboursements se soustraient
> silencieusement — ou s'additionnent, selon l'oubli. Avec des montants
> toujours positifs, un oubli produit un chiffre visiblement faux, pas
> subtilement faux.

C'est le même raisonnement qu'`ecriture_marchand`, mais la conclusion est
**inverse** : là-bas le signe porte le sens, ici c'est le type.

La différence : le grand livre marchand **doit** se sommer d'un coup
(`SUM(montant)` = solde). Les paiements, eux, sont toujours interrogés par
type. Chaque modèle a sa logique — l'important est qu'elle soit explicite.

---

## 6. Les échecs se conservent

```java
paiement.echouer();
tentatives.save(new TentativePaiement(id, "ECHOUE", codeErreur, message));
securite.enregistrer(clientId, ECHEC_PAIEMENT, FAIBLE, null, …);
```

Trois écritures, deux raisons :

**La §18 de la spécification** veut surveiller les échecs de paiement — c'est
un signal du score de risque (§19). Si on n'enregistrait que les paiements
réussis, ce signal n'existerait tout simplement pas.

**Un échec n'est pas une fraude.** Solde insuffisant, code erroné, opérateur
indisponible : c'est banal. C'est la **répétition** qui fait le signal, comme
pour les échecs de connexion (chapitre 08 §9).

Et la commande ne bouge pas : le stock reste réservé le temps du délai, pour
que le client puisse réessayer.

---

## 7. On ne « déconfirme » pas un paiement

```java
if (paiement.estConfirme()) {
    throw new ConflitEtat("PAIEMENT_DEJA_CONFIRME", …);
}
```

Une notification d'échec arrivant **après** une confirmation est suspecte :
notification en retard, rejeu désordonné, ou tentative de manipulation.

On refuse, et **la trace reste**. Accepter reviendrait à défaire un paiement
sur la foi d'un message reçu hors séquence.

---

## 8. Le plafond de remboursement

```java
if (dejaRembourse.add(montant).compareTo(encaisse) > 0) {
    throw new RegleMetierViolee("REMBOURSEMENT_EXCESSIF", …);
}
```

C'est l'invariant I-27. Ni le total encaissé ni le total remboursé ne sont
stockés : ce sont des **sommes**, calculées à la demande.

> 💡 Exactement comme le solde marchand du chapitre 03. **Un total stocké
> finit toujours par mentir** — il suffit d'une écriture ratée.

Et un remboursement doit dire ce qui le justifie, sinon la contrainte
`paiement_remboursement_justifie` le refuse :

```sql
CHECK (type <> 'REMBOURSEMENT' OR origine_type IS NOT NULL)
```

Un remboursement sans cause est, comptablement, indiscernable d'un
détournement.

---

## 9. Ce que le paiement déclenche

```text
paiement CONFIRMÉ
    ├── commande        EN_ATTENTE_PAIEMENT → PAYEE
    ├── stock           RESERVEE −n   (la marchandise quitte l'entrepôt)
    └── marchand        écritures VENTE et COMMISSION      ← chapitre 17
```

Les deux premiers sont faits. Le troisième est marqué par un `TODO` explicite
dans le code :

```java
// TODO chapitre 17 : écrire ici les écritures VENTE et COMMISSION du
// grand livre marchand. C'est le bon moment — le paiement est acquis.
```

> 📌 **Un `TODO` daté et situé vaut mieux qu'un module à moitié écrit.**
> Le chapitre 17 saura exactement où brancher, et le lecteur sait que ce
> n'est pas un oubli.

---

## 10. À retenir

1. Le mobile money est **asynchrone** : entre la demande et la confirmation, le serveur ne sait rien. D'où la réservation de stock.
2. **Le montant vient de la commande, jamais du client.** C'est la faille classique du tunnel de paiement.
3. **Un webhook est rejoué.** Deux appels sont la norme.
4. L'idempotence renvoie une réponse **normale**, pas une erreur — sinon l'opérateur rejoue indéfiniment.
5. **Deux lignes de défense** : la garde dans le service, l'index unique dans la base.
6. Les montants sont **toujours positifs** ; le sens est porté par le type.
7. **Les échecs se conservent** : sans eux, le signal du score de risque n'existe pas.
8. On ne **déconfirme** jamais un paiement.
9. Les totaux encaissé et remboursé sont des **sommes**, jamais des colonnes.
10. Un `TODO` **daté et situé** vaut mieux qu'un module à moitié écrit.

---

## 11. Exercices

**Exercice 1.**
Le webhook arrive avec une référence opérateur **déjà utilisée par un autre
paiement**. Que se passe-t-il aujourd'hui ? Est-ce le bon comportement, et
quel code d'erreur le client devrait-il recevoir ?

**Exercice 2.**
Un client paie 20 000 sur une commande de 32 000. Décris l'état de la
commande, du stock, et ce que renvoie `resteAPayer`. Puis écris le test.

**Exercice 3.**
Le travail périodique annule une commande impayée **pendant** que le webhook
de confirmation arrive. Décris les deux ordres possibles et dis lequel pose
problème. Que faudrait-il ajouter ?

**Exercice 4.**
Écris le contrôleur du webhook. Attention : il est appelé par l'opérateur, pas
par un utilisateur connecté. Comment l'authentifier sans jeton JWT ?

**Exercice 5.**
On veut permettre le paiement en plusieurs fois (deux versements). Qu'est-ce
qui fonctionne déjà sans rien changer ? Qu'est-ce qui casse ?

---

➡️ **Chapitre suivant :** 14 — Les conversations et la négociation
