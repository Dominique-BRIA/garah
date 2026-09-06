# Chapitre 14 — Les conversations et la négociation

> Prérequis : chapitres [11](11-stock-et-concurrence.md) et [12](12-panier-et-commande.md).
> Durée de lecture : ~30 min.

---

## 1. Ce qu'on veut faire

Deux mécanismes qui se répondent :

```text
CONVERSATION    un client pose une question, un responsable la prend
NÉGOCIATION     le prix se discute, dans cette conversation
```

C'est ce qui distingue GARAH d'une boutique en ligne classique. Le prix n'est
pas toujours celui affiché — mais chaque écart doit être **justifiable**.

---

## 2. La file d'attente partagée

Une conversation naît **sans responsable**, en `WAITING`. Plusieurs
responsables voient la même liste. Le premier qui clique gagne.

C'est le même problème que le stock du chapitre 11 — **avec une solution
différente**, et c'est instructif.

### Deux techniques, deux situations

| | Stock | Conversation |
|---|---|---|
| Technique | Verrou pessimiste `FOR UPDATE` | `UPDATE` conditionnel |
| Pourquoi | On a besoin de la valeur **avant** (journal) | On n'a besoin de **rien** |
| Coût | Une requête + attente éventuelle | **Une seule requête**, aucune attente |

```java
@Modifying
@Query("""
        UPDATE Conversation c
           SET c.responsableId = :responsableId, c.statut = ASSIGNED, …
         WHERE c.id = :conversationId
           AND c.statut = WAITING
        """)
int prendre(Long conversationId, Long responsableId);
```

```text
1 ligne modifiée  →  j'ai eu la conversation
0 ligne modifiée  →  quelqu'un a été plus rapide
```

> 🎯 **La leçon générale :** le verrou n'est pas la seule réponse à la
> concurrence. Quand l'opération n'a besoin d'aucune valeur préalable, un
> `UPDATE … WHERE <condition attendue>` est plus simple, plus rapide, et sans
> risque d'interblocage.
>
> C'est la parade n°2 du chapitre 11 — celle qu'on avait écartée pour le
> stock, faute de pouvoir journaliser `quantite_avant`. Ici, elle gagne.

### Le test

```text
5 responsables cliquent en même temps
→ 1 gagnant, 4 refus
→ 1 seule affectation enregistrée
```

Et le message compte autant que le mécanisme :

```java
throw new ConflitEtat("CONVERSATION_DEJA_PRISE",
        "Un autre responsable a déjà pris cette conversation.");
```

> ⚠️ « Erreur de mise à jour » ferait cliquer trois fois de plus. Un message
> qui **explique** évite un support inutile.

---

## 3. L'historique des affectations

La conversation ne porte que son responsable **actuel**. Une table séparée
garde le reste :

```text
affectation_conversation
    qui l'a eue, à partir de quand, jusqu'à quand, retirée par qui, pourquoi
```

Un Admin peut retirer une conversation à un responsable (§4 de la spec). Le
**motif est obligatoire** :

```java
if (motif == null || motif.isBlank()) {
    throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
            "Un retrait de conversation doit être justifié.");
}
```

Retirer un dossier à quelqu'un est une décision qui sera relue — notamment
lors de l'évaluation du responsable (§20). Sans motif, elle est indéfendable
dans les deux sens.

### L'ordre imposé par la contrainte

```java
affectations.findByConversationIdAndDateFinIsNull(conversationId)
        .ifPresent(a -> { a.cloturer(motif); affectations.saveAndFlush(a); });   // 1. FERMER
…
affectations.save(new AffectationConversation(…));                              // 2. OUVRIR
```

L'index unique partiel `affectation_conversation_ouverte_unique` (I-30)
n'accepte **qu'une** affectation ouverte. Inverser les deux lignes échoue.

C'est le même motif que la photo principale du chapitre 09 : **une contrainte
dicte l'ordre des opérations**.

---

## 4. Ce que le modèle initial ne disait pas

La spécification prévoyait :

```text
PROPOSITION_PRIX { id, conversation_id, montant, statut, date_creation }
```

Quatre informations manquaient, et chacune rend la table inutilisable :

| Manquant | Sans lui |
|---|---|
| **le produit** | Une conversation peut parler de trois articles. Lequel ? |
| **la quantité** | 12 000 pour 1 pièce ou pour 50 ? Ce n'est pas la même remise. |
| **l'auteur et le sens** | Qui propose ? Qui accorde ? Aucun contrôle possible. |
| **l'expiration** | Une remise valable pour toujours n'est pas une négociation. |

C'était la correction A6 du chapitre 02. Elle prend tout son sens ici.

---

## 5. Le fil se reconstitue

```java
@Column(name = "proposition_parente_id")
private Long propositionParenteId;
```

Chaque contre-proposition pointe vers celle à laquelle elle répond.

```text
Client        « 12 000 pour 20 pièces »        PROPOSEE
                        ↓
Responsable   « 13 500 »                       parente = #1
                        ↓
Client        accepte                          ACCEPTEE
                        ↓
Commande      ligne_commande.proposition_prix_id = #2    CONSOMMEE
```

> 📌 **Ce qu'on peut alors répondre :** « qui a accordé cette remise, quand, à
> partir de quel prix, et pour quelle quantité ? »
>
> C'est une question de **contrôle interne**, pas de curiosité. Une remise de
> 20 % non justifiée est indiscernable d'un arrangement personnel.

### Une contre-offre est un refus

```java
precedente.refuser();
PropositionPrix contre = proposer(…);
contre.rattacherA(precedente.getId());
```

Laisser les deux propositions ouvertes permettrait au client d'accepter
l'**ancienne** après avoir vu la nouvelle — donc de choisir le meilleur des
deux prix, ce qui n'est pas ce qu'on lui a offert.

---

## 6. Deux règles qui protègent l'entreprise

### On ne négocie pas à la hausse

```java
if (prixPropose.compareTo(tarifPublic) > 0) {
    throw new RegleMetierViolee("PROPOSITION_SUPERIEURE_AU_TARIF", …);
}
```

Un responsable qui propose **plus cher** que le tarif affiché est au mieux une
erreur de saisie, au pire un abus. Le tarif public reste la référence.

*(La règle ne s'applique qu'aux propositions du responsable : un client peut
évidemment proposer ce qu'il veut.)*

### Une proposition ne sert qu'une fois

```java
proposition.consommer();   // CONSOMMEE, définitif
```

Sans ça, une remise accordée pour 20 pièces s'appliquerait à **toutes** les
commandes suivantes. Le test le vérifie : consommer deux fois est refusé.

---

## 7. L'expiration : une règle que SQL ne peut pas porter

```java
if (proposition.estExpiree()) {
    throw new ConflitEtat("PROPOSITION_EXPIREE", …);
}
```

C'est l'invariant I-33. Et il **ne peut pas** être une contrainte `CHECK` :
elle devrait lire l'heure courante, ce que PostgreSQL interdit dans un
`CHECK` (chapitre 05 §7).

Il vit donc dans le service — plus un travail périodique qui marque les
propositions dépassées :

```java
public int expirerLesDepassees() { … }
```

> 💡 **Le contrôle dans `accepter` suffirait à protéger.** Le travail
> périodique sert à autre chose : sans lui, l'écran afficherait des dizaines
> de propositions « en cours » qui ne le sont plus. **Marquer l'état rend
> l'interface honnête.**

---

## 8. Ce que les contraintes m'ont appris pendant les tests

Trois échecs de test, tous provoqués par la base — et tous instructifs.

### 8.1 On ne fabrique pas un passé incohérent

Ma première version créait une proposition déjà expirée :

```java
negociation.proposer(…, Duration.ofSeconds(-1));   // ❌
```

```text
ERREUR : viole « proposition_prix_expiration_posterieure »
         (date_expiration > date_creation)
```

**La contrainte a raison** : une telle ligne n'a aucun sens métier.

Deuxième tentative — antidater seulement l'expiration :

```sql
UPDATE proposition_prix SET date_expiration = now() - interval '1 day'   -- ❌ encore
```

Refusé aussi : **un `CHECK` est vérifié à l'`UPDATE` comme à l'`INSERT`**.

La bonne façon : reculer **les deux** dates, en gardant leur ordre.

```sql
SET date_creation   = now() - interval '10 days',
    date_expiration = now() - interval '1 day'
```

> 🎯 **La base refuse de fabriquer un passé incohérent, jusque dans les
> tests.** C'est exactement ce qu'on lui demande — même quand ça oblige à
> écrire un test plus soigneux.

### 8.2 L'invariant que SQL ne porte pas, on l'oublie

```text
ERREUR : conversation.responsable_id viole une contrainte de clé étrangère
```

J'avais créé un `utilisateur` de type `RESPONSABLE`… **sans sa ligne
`responsable`**. Or `conversation.responsable_id` pointe vers `responsable`,
pas vers `utilisateur`.

C'est l'invariant **I-06** du chapitre 04 : « un utilisateur de type
RESPONSABLE a sa ligne fille ». SQL ne sait pas exprimer un « au moins un » —
c'est précisément l'invariant qu'on oublie.

> 📌 **Les invariants que la base ne porte pas sont ceux qui cassent.**
> Le chapitre 04 le disait en théorie ; je viens de le vivre deux fois.

### 8.3 Une garde manquante dans le service

`prendre(conversationId, null)` produisait :

```text
ERREUR : viole « conversation_responsable_coherent »
```

Message correct pour la base, incompréhensible pour l'appelant. J'ai ajouté la
garde :

```java
if (responsableId == null) {
    throw new RegleMetierViolee("RESPONSABLE_OBLIGATOIRE", …);
}
```

Encore la doctrine du chapitre 04 : **la base est la dernière ligne de
défense, jamais la seule ligne visible.**

---

## 9. À retenir

1. Le **verrou n'est pas la seule réponse** à la concurrence : sans besoin de valeur préalable, un `UPDATE … WHERE` suffit et va plus vite.
2. Le **message d'erreur** fait partie de la solution : « déjà pris » évite trois clics de plus.
3. Une contrainte **dicte l'ordre des opérations** : fermer avant d'ouvrir.
4. Un retrait de dossier **doit se justifier** — il sera relu.
5. Une proposition dit **sur quoi, combien, par qui, jusqu'à quand**. Les quatre.
6. Le fil se **reconstitue** : c'est du contrôle interne, pas de la curiosité.
7. Une **contre-offre est un refus**, sinon le client choisit le meilleur des deux prix.
8. Une proposition **ne sert qu'une fois**.
9. Les règles **temporelles** ne peuvent pas être des `CHECK` : elles vivent dans le service, avec un travail périodique pour rendre l'affichage honnête.
10. **La base refuse de fabriquer un passé incohérent, même dans les tests.**

---

## 10. Exercices

**Exercice 1.**
Un client accepte une proposition puis passe commande. Écris le code qui relie
`ligne_commande.proposition_prix_id`, et dis à quel moment appeler
`consommer()`.

**Exercice 2.**
Deux responsables cliquent sur « Prendre » à 3 ms d'intervalle. Décris les
deux requêtes SQL exécutées et le contenu de `affectation_conversation` à la
fin.

**Exercice 3.**
On veut qu'un responsable ne puisse pas avoir plus de 10 conversations
`ASSIGNED` en même temps. Où poses-tu la règle ? Peut-elle être une contrainte
SQL ?

**Exercice 4.**
Le travail périodique `expirerLesDepassees` tourne toutes les heures. Un
client accepte une proposition expirée depuis 20 minutes. Que se passe-t-il,
et pourquoi le contrôle dans `accepter` est-il indispensable ?

**Exercice 5.**
Reproduis l'erreur du §8.1 : passe une `Duration` négative et lance le test.
Puis explique pourquoi une contrainte `CHECK` est vérifiée aussi à l'`UPDATE`.

---

➡️ **Chapitre suivant :** 15 — La logistique : expédition, itinéraire, traçabilité
