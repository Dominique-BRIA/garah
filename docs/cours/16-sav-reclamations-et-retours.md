# Chapitre 16 — Le SAV : réclamations et retours

> Prérequis : chapitres [11](11-stock-et-concurrence.md), [13](13-paiement-mobile-money.md) et [15](15-logistique.md).
> Durée de lecture : ~25 min.

---

## 1. Ce qu'on veut faire

Le chapitre 02 avait posé le diagnostic (correction A4) :

```text
RETOUR { id, commande_id, motif, statut, date_creation }
```

> « Le client a commandé 10 chemises et 2 pantalons. Il retourne 3 chemises. »
>
> **Le modèle ne savait pas répondre.**

Et la conclusion était : **un retour est une opération, pas un post-it.**

Ce chapitre en est la démonstration.

---

## 2. Le retour partiel

```java
retour.ajouterLigne(ligneCommandeId, quantite, etat);
```

Trois informations par ligne, et chacune est indispensable :

| Champ | Sans lui |
|---|---|
| **la ligne de commande** | On ne sait pas quel article revient |
| **la quantité** | Le retour porte sur la commande entière |
| **l'état** | On revend une marchandise abîmée |

```java
public enum EtatArticle {
    NEUF,          // redevient vendable
    ABIME,         // invendable
    INUTILISABLE   // invendable
}
```

> 📌 La §13 de la spécification demandait un compteur « endommagé ». Sans lui,
> **on aurait revendu la marchandise abîmée** — et le client suivant aurait
> ouvert un retour à son tour.

---

## 3. Rien ne bouge avant le contrôle physique

```text
DEMANDE  ──▶ ACCEPTE ──▶ RECEPTIONNE ──▶ VALIDE ──▶ CLOTURE
   │                          │
   └──▶ REFUSE                └── quelqu'un a VU la marchandise
```

À la **demande**, aucune écriture ailleurs : ni stock, ni remboursement. Le
test le vérifie :

```java
Retour retour = retours.demander(…);
assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);   // inchangé
```

> ⚠️ **Rembourser à la demande serait une invitation à la fraude** : il
> suffirait de déclarer un retour sans jamais renvoyer la marchandise.
>
> Le remboursement part à `VALIDE`, c'est-à-dire **après** que quelqu'un a vu
> et contrôlé les articles.

Et le test vérifie qu'on ne peut pas sauter l'étape :

```java
assertThatThrownBy(() -> retours.valider(retour.getId(), MTN_MOMO))
        .isInstanceOf(ConflitEtat.class);      // RECEPTIONNE manquant
```

---

## 4. L'opération à quatre domaines

La validation, c'est l'exemple du chapitre 04 §11 devenu du code :

```text
1. le retour passe à VALIDE
2. pour chaque ligne, un MOUVEMENT DE STOCK
      NEUF  → compteur DISPONIBLE
      ABIMÉ → compteur ENDOMMAGEE
3. le montant remboursé est figé sur chaque ligne
4. un PAIEMENT de type REMBOURSEMENT
5. les écritures marchand                       ← chapitre 17
```

**Tout dans une seule transaction.**

> 🎯 **Un retour à moitié enregistré est pire que pas de retour du tout.**
> Stock remis mais client non remboursé : personne ne s'en apercevra, parce
> qu'aucune erreur n'aura été levée. Le client rappellera dans trois semaines,
> et il faudra reconstituer à la main.

Le test le montre en chiffres :

```text
retour de 2 NEUF + 1 ABIMÉ sur 10 achetées

disponible   20 → 22      (les 2 neuves reviennent)
endommagé     0 →  1      (l'abîmée ne repartira jamais)
remboursé    45 000       (3 × 15 000)
paiements     1           un seul virement, pas trois
```

---

## 5. On rembourse au prix payé, pas au prix du jour

```java
BigDecimal montant = ligneCommande.getPrixUnitaire()
        .multiply(BigDecimal.valueOf(ligneRetour.getQuantite()));
```

Le prix vient de la **ligne de commande**, pas du catalogue.

C'est possible **uniquement** grâce à la règle de la photographie (chapitre 12).
Sans elle :

```text
achat en mars     15 000 l'unité
prix en septembre 25 000 l'unité

remboursement au prix du jour → 75 000 pour 3 articles payés 45 000
```

> 💡 Une décision de modélisation prise au chapitre 03 rend correcte une
> opération écrite au chapitre 16. **C'est ça, un bon modèle** : il rend les
> choses justes par construction, longtemps après.

### Un seul remboursement pour tout le retour

```java
if (totalARembourser.signum() > 0) {
    paiements.rembourser(commandeId, totalARembourser, moyen, "RETOUR", retour.getId());
}
```

Le client reçoit **un** virement, pas un par ligne. Trois notifications MTN
pour un seul retour donnent l'impression d'un problème.

---

## 6. Le trigger I-40 et les retours successifs

```sql
SELECT COALESCE(SUM(lr.quantite), 0) INTO qte_deja
  FROM ligne_retour lr JOIN retour r ON r.id = lr.retour_id
 WHERE lr.ligne_commande_id = NEW.ligne_commande_id
   AND lr.id IS DISTINCT FROM NEW.id
   AND r.statut <> 'REFUSE';
```

Trois subtilités dans cette requête :

1. **Le cumul porte sur TOUS les retours** de la ligne, pas seulement celui en
   cours. Un client peut renvoyer 2 articles en mars et 2 en avril sur 3
   achetés — le second doit échouer.
2. **`IS DISTINCT FROM NEW.id`** exclut la ligne en cours de modification,
   sinon un `UPDATE` se compterait lui-même.
3. **Les retours `REFUSE` ne comptent pas** : un retour refusé n'a rien
   immobilisé.

> ⚠️ Comme au chapitre 15, ce trigger lève désormais son erreur avec
> `USING ERRCODE = '23514'` (migration V19). Sans ça, dépasser la quantité
> produisait un **500** au lieu d'un **409**.

---

## 7. La réclamation : la voie de recours

Le client ne peut pas :

- annuler une commande payée (D-12) ;
- décider seul d'un retour.

Il **réclame**, et un humain tranche.

```java
public Reclamation ouvrir(Long clientId, Long commandeId, String motif, String description)
public Reclamation prendreEnCharge(Long reclamationId, Long responsableId)
public Reclamation resoudre(Long reclamationId, boolean favorable)
```

### La prise en charge explicite

Même logique que les conversations (chapitre 14) : sans attribution explicite,
une réclamation reste **sans propriétaire**.

> 📌 C'est exactement comme ça qu'un dossier reste sans réponse pendant trois
> semaines : tout le monde le voit, personne ne l'a.

### La date de résolution n'est pas décorative

```sql
CHECK (statut NOT IN ('RESOLUE', 'FERMEE') OR date_resolution IS NOT NULL)
```

Sans elle, impossible de mesurer les **délais de traitement** demandés par la
§20. La contrainte force à poser les deux ensemble.

---

## 8. Ce qui reste au chapitre 17

```java
// TODO chapitre 17 : écritures RETOUR et ANNUL_COMMISSION du grand
// livre marchand. Le retour annule la vente ET la commission.
```

Un retour annule **deux** écritures, pas une : on ne doit plus la vente au
marchand, mais on ne garde pas non plus la commission qu'on avait prélevée
dessus.

C'est le dernier maillon du cycle financier, et il sera écrit au chapitre 17.

---

## 9. À retenir

1. **Un retour est une opération, pas un post-it** — quatre domaines en une transaction.
2. Trois informations par ligne : **quoi, combien, dans quel état**.
3. **Un article abîmé ne redevient jamais vendable** : compteur dédié.
4. **Rien ne bouge avant le contrôle physique.** Rembourser à la demande invite à la fraude.
5. **Un retour à moitié enregistré est pire que pas de retour** : l'erreur est silencieuse.
6. On rembourse **au prix payé** — rendu possible par la photographie du chapitre 12.
7. **Un seul virement** pour tout le retour.
8. Le cumul retourné porte sur **tous** les retours, et exclut les refusés.
9. La réclamation est la **voie de recours** : le client demande, un humain décide.
10. Une **prise en charge explicite** évite le dossier que tout le monde voit et que personne n'a.

---

## 10. Exercices

**Exercice 1.**
Reprends l'exercice 4 du chapitre 04 : 3 chemises retournées dont 1 abîmée,
prix 15 000, commission 10 %. Écris les **neuf** écritures avec des valeurs
concrètes, y compris celles du chapitre 17.

**Exercice 2.**
Un client retourne 2 articles en mars, puis 2 en avril, sur 3 achetés.
Que se passe-t-il exactement, et à quel moment ? Écris le test.

**Exercice 3.**
Le remboursement échoue (opérateur indisponible) après que le stock a été
remis. Que se passe-t-il ? Est-ce le bon comportement ?

**Exercice 4.**
Ajoute une règle : « un retour n'est acceptable que dans les 14 jours suivant
le retrait ». Où la places-tu, et pourquoi pas en base ?

**Exercice 5.**
Une réclamation aboutit à un geste commercial de 5 000 FCFA sans retour de
marchandise. Quelles écritures faut-il ? Le modèle actuel les permet-il ?

---

➡️ **Chapitre suivant :** [17 — La finance marchands](17-finance-marchands.md)
