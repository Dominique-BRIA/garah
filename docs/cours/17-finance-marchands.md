# Chapitre 17 — La finance marchands : commissions, écritures, règlements

> Prérequis : chapitres [13](13-paiement-mobile-money.md) et [16](16-sav-reclamations-et-retours.md).
> Durée de lecture : ~30 min.
> **Le chapitre qui ferme les deux `TODO`** laissés aux chapitres 13 et 16.

---

## 1. La correction la plus importante du chapitre 02

La spécification prévoyait :

```text
DETTE_MARCHAND { id, marchand_id, montant, statut }
```

Et le chapitre 02 avait posé la question qui tue :

> « Pourquoi doit-on 1 250 000 FCFA au marchand ABC ? **Détaille.** »

Le modèle ne savait pas répondre. Il n'avait qu'un **nombre**.

```text
❌ dette_marchand.montant       un solde STOCKÉ
✅ ecriture_marchand            un JOURNAL, et le solde est sa SOMME
```

> 🎯 **On ne stocke jamais un solde comme source de vérité.**
>
> Un solde stocké ment dès qu'une écriture est ratée — et plus personne ne
> peut prouver la vérité. C'est le principe de la comptabilité en partie
> double, vieux de 500 ans. Il n'a jamais été battu.

---

## 2. Le solde est une requête, pas une colonne

```java
@Query("""
        SELECT COALESCE(SUM(e.montant), 0) FROM EcritureMarchand e
         WHERE e.marchandId = :marchandId AND e.devise = :devise
        """)
BigDecimal solde(Long marchandId, String devise);
```

Quatre propriétés qu'un solde stocké n'aura jamais :

| | |
|---|---|
| **Prouvable** | On affiche les 400 lignes qui le composent |
| **Recalculable** | Une erreur se corrige par une écriture, jamais par un `UPDATE` |
| **Auditable** | Chaque ligne pointe vers sa pièce justificative |
| **Insynchronisable** | Il n'est stocké nulle part, donc il ne peut pas diverger |

Le cycle complet, testé :

```text
VENTE            +150 000     ligne_commande #1
COMMISSION        −15 000     ligne_commande #1
                 ─────────
                  135 000     ← dû au marchand

RETOUR            −45 000     ligne_retour #7
ANNUL_COMMISSION   +4 500     ligne_retour #7
                 ─────────
                   94 500

REGLEMENT         −94 500     reglement #3
                 ─────────
                        0     ✅
```

---

## 3. Le signe est imposé par le type

```java
public enum TypeEcriture {
    VENTE(1), COMMISSION(-1), RETOUR(-1),
    ANNUL_COMMISSION(1), REGLEMENT(-1), AJUSTEMENT(0);

    public BigDecimal orienter(BigDecimal montantPositif) {
        return signe < 0 ? montantPositif.negate() : montantPositif;
    }
}
```

Le constructeur d'`EcritureMarchand` prend un montant **positif** et l'oriente.
Le code appelant n'a **jamais** à se demander « positif ou négatif ? ».

Et la base vérifie :

```sql
CHECK (
    (type = 'VENTE'      AND montant > 0) OR
    (type = 'COMMISSION' AND montant < 0) OR
    …
    (type = 'AJUSTEMENT')
)
```

> ⚠️ **Pourquoi c'est important.** Le solde est une somme. **Une seule ligne
> mal signée suffit à rendre la dette fausse** — et rien ne le signale : le
> total reste un nombre plausible.
>
> Le test le vérifie en contournant le service : une `VENTE` négative insérée
> en SQL brut est refusée par la base.

`AJUSTEMENT` est le seul type au signe libre. C'est **la soupape** : la seule
façon de corriger, et elle est explicite.

---

## 4. Deux écritures par vente, jamais une

```java
ecritures.save(new EcritureMarchand(marchandId, VENTE,      montantLigne,      …));
ecritures.save(new EcritureMarchand(marchandId, COMMISSION, montantCommission, …));
```

On pourrait écrire directement le net : `150 000 − 15 000 = 135 000`. Une ligne
au lieu de deux.

**Et on perdrait la réponse à la question que le marchand pose vraiment :**

> « Combien de commission avez-vous prélevé ce mois-ci ? »

Le net ne la contient plus. La décomposition, si.

---

## 5. Un retour annule DEUX écritures

C'est le point qu'on oublie une fois sur deux.

```java
ecritures.save(new EcritureMarchand(marchandId, RETOUR,           montantRembourse,  …));
ecritures.save(new EcritureMarchand(marchandId, ANNUL_COMMISSION, commissionAnnulee, …));
```

Si on n'annulait que la vente, **l'entreprise garderait la commission sur une
marchandise qu'elle a rendue**. Indéfendable devant le partenaire — et
parfaitement invisible dans les comptes, puisque le solde resterait un nombre
plausible.

---

## 6. L'idempotence, encore

```java
if (ecritures.existsByOrigineTypeAndOrigineId(LIGNE_COMMANDE, ligneCommandeId)) {
    return;
}
```

Ce n'est pas une précaution de confort. `enregistrerVente` est appelé depuis
`ServicePaiement.confirmer`, c'est-à-dire **depuis un webhook rejoué**
(chapitre 13).

```text
webhook rejoué 3 fois
    sans garde  →  450 000 dus au lieu de 150 000
    avec garde  →  150 000
```

> 📌 **Une règle générale se dégage du projet :**
> tout ce qu'un webhook déclenche doit être idempotent, **de proche en
> proche**. L'idempotence ne s'arrête pas à la porte d'entrée : elle se
> propage à tout ce que la porte ouvre.

Même chose pour la confirmation d'un règlement, testée elle aussi.

---

## 7. Préparer n'est pas payer

```java
public ReglementMarchand preparer(…)   // statut PREVU  → aucune écriture
public ReglementMarchand confirmer(…)  // statut PAYE   → écriture REGLEMENT
```

Un règlement `PREVU` **n'écrit rien** dans le grand livre. La dette ne baisse
qu'au moment où l'argent part réellement.

Le test le montre :

```java
grandLivre.preparer(marchandId, 94_500, "VIREMENT", null);
assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("94500.00");   // inchangé

grandLivre.confirmer(reglement.getId(), "VIR-2026-001");
assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("0.00");
```

Cette séparation permet de **préparer une série de règlements**, de les faire
valider, puis de les exécuter — sans que le solde bouge entre-temps.

### On ne verse pas plus qu'on ne doit

```java
if (montant.compareTo(du) > 0) {
    throw new RegleMetierViolee("REGLEMENT_EXCESSIF", …);
}
```

Un versement excédentaire rendrait le solde **négatif** : l'entreprise
deviendrait créancière du marchand, ce qui n'a aucun sens dans ce modèle.

### Un règlement payé ne s'annule pas

```java
if (reglement.estPaye()) {
    throw new ConflitEtat("REGLEMENT_DEJA_PAYE",
            "Un règlement payé s'annule par une écriture d'ajustement, pas en le modifiant.");
}
```

L'argent est parti. L'écriture existe. **On n'efface pas l'histoire, on
l'allonge** — par un `AJUSTEMENT` justifié.

---

## 8. Ce que la clé étrangère a rattrapé

En branchant le grand livre, **22 tests ont échoué d'un coup** :

```text
ERREUR : DELETE sur « marchand » viole la contrainte
         « ecriture_marchand_marchand_id_fkey »
```

Le nettoyage des tests supprimait le marchand — mais le paiement écrit
désormais des lignes de grand livre qui le référencent.

> 🎯 **Ce n'est pas un problème, c'est le système qui fonctionne.**
> Ajouter un domaine a agrandi le graphe des dépendances, et la clé étrangère
> l'a signalé **immédiatement**, avec un message précis.
>
> Sans elle, les écritures seraient restées orphelines : des dettes envers un
> marchand qui n'existe plus. On l'aurait découvert en produisant un état
> financier, six mois plus tard.

---

## 9. Le cycle complet, vérifié de bout en bout

Le test `grandLivreDeBoutEnBout` part d'une commande payée et va jusqu'au
retour :

```text
paiement confirmé      → écriture VENTE          +150 000
retour de 2 unités     → écriture RETOUR          −30 000
                                                 ─────────
solde                                             120 000
lignes du journal                                        2
```

Aucun appel direct au grand livre : tout passe par le paiement et le retour.
**C'est l'assemblage qu'on teste**, pas les briques.

---

## 10. À retenir

1. **Un solde ne se stocke pas**, il se calcule. Un solde stocké finit toujours par mentir.
2. Le solde est **prouvable, recalculable, auditable, insynchronisable**.
3. Le **signe est imposé par le type**, et vérifié par la base : une ligne mal signée fausserait tout sans erreur.
4. `AJUSTEMENT` est la **seule soupape**, et elle exige un motif.
5. **Deux écritures par vente** : le net perdrait la réponse à « combien de commission ce mois-ci ? ».
6. Un retour annule **la vente ET la commission**.
7. L'**idempotence se propage** : tout ce qu'un webhook déclenche doit l'être aussi.
8. **Préparer n'est pas payer.** La dette baisse quand l'argent part.
9. Un règlement payé se corrige par une **écriture inverse**, jamais par un `UPDATE`.
10. Une clé étrangère qui casse 22 tests fait exactement son travail.

---

## 11. Exercices

**Exercice 1.**
Reprends l'exercice 1 du chapitre 02 : écris la requête qui justifie le montant
dû au marchand ABC. Compare avec ce qu'aurait donné `dette_marchand.montant`.

**Exercice 2.**
Un produit change de marchand après une vente. À qui doit-on l'argent ?
Retrouve le champ qui garantit la bonne réponse, et le chapitre où il a été
décidé.

**Exercice 3.**
On s'aperçoit qu'une commission de 10 % aurait dû être de 8 % sur 40 commandes
passées. Décris la correction. Combien d'écritures ? Peut-on modifier les
lignes existantes ?

**Exercice 4.**
Écris l'état mensuel d'un marchand : ventes, commissions, retours, règlements,
solde de début et de fin. Une seule requête, avec `FILTER`.

**Exercice 5.**
Le webhook de paiement est rejoué **pendant** qu'un règlement est confirmé.
Décris ce qui se passe, et dis si une écriture peut être perdue ou doublée.

---

➡️ **Chapitre suivant :** [18 — Surveillance, score de risque et audit](18-surveillance-et-audit.md)
