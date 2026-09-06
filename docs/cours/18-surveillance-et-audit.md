# Chapitre 18 — Surveillance, score de risque et audit

> Prérequis : chapitres [08](08-authentification-et-permissions.md) et [13](13-paiement-mobile-money.md).
> Durée de lecture : ~30 min.

---

## 1. Trois journaux qui se ressemblent

Le chapitre 01 avait posé le piège. Voici la règle, une bonne fois :

| Table | Qui l'alimente | Pourquoi elle existe |
|---|---|---|
| `activite_client` | Le client | Comprendre son **parcours** |
| `evenement_securite` | Le système | Détecter une **anomalie** |
| `audit_log` | Les employés | **Responsabilité** interne |

Un même geste peut légitimement écrire dans deux d'entre eux. Ce qui n'est pas
légitime, c'est de tout mettre dans un seul : **plus rien n'est exploitable**.

```text
Un client change son mot de passe
    → evenement_securite   CHANGEMENT_MOT_DE_PASSE   (signal de risque)

Un Admin change le mot de passe d'un client
    → evenement_securite   CHANGEMENT_MOT_DE_PASSE   (signal de risque)
    → audit_log            qui l'a fait, et quand    (responsabilité)
```

---

## 2. Le score explicable

La spécification (§19) exigeait :

> « Le score doit être explicable. »

Mais le modèle ne stockait qu'un nombre. C'était la correction **A14**.

```java
public record SignalRisque(String code, String libelle, double valeur, double poids) { }
```

> 🎯 **L'écran d'administration n'affiche pas « 78 ». Il affiche les phrases :**
>
> ```text
> Client #125 — risque HIGH (55)
>   • 10 échecs de connexion en 24 h          25 pts
>   • 5 paiements refusés en 24 h             30 pts
> ```
>
> Un nombre ne permet aucune décision. Une liste de faits, si.

Et un signal **absent** ne figure pas dans l'explication : afficher
« 0 nouvel appareil » noierait les vrais signaux.

---

## 3. Le plafonnement : ce qui distingue un distrait d'un fraudeur

```java
double poids = Math.min(plafond, valeur * parUnite);
```

Chaque signal est plafonné **individuellement**.

```text
20 échecs de connexion  →  20 × 4 = 80, plafonné à 25  →  MEDIUM
```

> ⚠️ **Sans ce plafond**, un client qui se trompe vingt fois de mot de passe —
> ce qui arrive, surtout sur un clavier de téléphone — serait classé
> `CRITICAL` et traité comme un fraudeur.

**C'est l'accumulation de signaux différents qui fait le risque, pas
l'intensité d'un seul.** Testé dans les deux sens :

```text
10 échecs connexion seuls           →  25   MEDIUM
10 échecs connexion + 5 paiements   →  55   HIGH
```

---

## 4. La version de l'algorithme

```java
private static final String VERSION = "v1.0";
```

À incrémenter **à chaque modification** des signaux ou des poids.

```text
Client A   78   calculé en v1.0
Client A   78   calculé en v1.2

Sont-ils comparables ?  NON.
```

Sans cette colonne, un tableau d'évolution du risque **ment**, et une décision
de blocage prise sur cette comparaison est arbitraire.

### Et chaque calcul ajoute une ligne

On n'écrase jamais le score précédent. Savoir qu'un client était à 78 le
6 septembre et à 12 le 20 raconte quelque chose que la valeur courante ne dit
pas — typiquement : « le problème s'est résolu tout seul ».

---

## 5. `jsonb` : quand oui, quand non

```java
@Column(columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private String details;
```

**Pourquoi du JSON ici**, alors que le chapitre 03 mettait en garde contre son
usage abusif :

| | |
|---|---|
| La structure **varie selon la version** de l'algorithme | Une table figée exigerait une migration par nouveau signal |
| On l'**affiche**, on ne le **joint** jamais | Aucun besoin d'index sur ses champs |
| Les anciennes lignes gardent leur forme d'origine | Une colonne ajoutée serait `NULL` pour tout l'historique |

> 📌 **La règle du chapitre 03, appliquée :** `jsonb` pour ce qu'on affiche,
> vraies colonnes pour ce qu'on filtre. Ici `client_id`, `score` et `niveau`
> sont des colonnes — ce sont eux qu'on interroge.

Et l'audit s'en sert autrement, pour une raison différente :

```sql
SELECT count(*) FROM audit_log
 WHERE ancienne_valeur ->> 'prix' = '15000'
```

Là, on **interroge** le JSON — mais sur une table dont on ne connaît pas les
champs à l'avance, puisqu'elle audite **toutes** les entités. Un schéma figé
serait impossible.

---

## 6. La surveillance observe, elle ne décide pas

C'est la **règle fondatrice n°2**, et elle est testée :

```java
@Test
@DisplayName("la surveillance n'a AUCUNE méthode qui bloque un compte")
void surveillanceObserveMaisNeDecidePas() {
    // 20 échecs de connexion + 10 paiements refusés → CRITICAL
    risque.calculerEtEnregistrer(clientId);

    assertThat(statutDuCompte).isEqualTo("ACTIF");   // ← inchangé
}
```

`ServiceScoreRisque` n'a **aucune** méthode qui bloque un compte, annule une
commande ou refuse un paiement. Il constate, il explique, il alerte.

> 🎯 Un score `HIGH` signifie **« risque élevé »**, pas « client frauduleux ».
> La distinction protège autant l'entreprise que le client : bloquer
> automatiquement, c'est perdre des clients honnêtes sans jamais le savoir.

---

## 7. Une alerte, pas vingt

```java
boolean dejaOuverte = alertes.existsByClientIdAndTypeAndStatutIn(
        clientId, "SCORE_RISQUE", List.of("OUVERTE", "EN_COURS"));
```

Le score se recalcule périodiquement. Sans cette garde, un client à risque
génèrerait **une alerte par calcul** — et l'Admin cesserait de les regarder.

> ⚠️ **Une file d'alertes qu'on ne regarde plus est pire qu'aucune alerte :**
> elle donne le sentiment d'être protégé.

Et la file est triée **par gravité**, pas par date :

```sql
ORDER BY CASE gravite WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 … END, date_creation
```

Un tri chronologique noierait une alerte critique sous trente alertes faibles.

---

## 8. La décision est obligatoire

```java
if (decision == null || decision.isBlank()) {
    throw new RegleMetierViolee("DECISION_OBLIGATOIRE",
            "Une alerte se clôt avec une décision écrite.");
}
```

Une alerte traitée sans décision écrite ne sert à rien : **personne ne saura
si le compte a été vérifié ou simplement classé pour vider la file**.

Et la contrainte `alerte_securite_traitement_coherent` exige que `traite_par`
et `date_traitement` accompagnent le statut final — les trois sont posés
ensemble.

---

## 9. L'audit : trois décisions qui font la différence

### 9.1 Le nom de l'acteur est copié

```java
@Column(name = "utilisateur_id")     private Long utilisateurId;     // ON DELETE SET NULL
@Column(name = "acteur_nom")         private String acteurNom;       // COPIE
```

Le test le vérifie : après suppression du compte, `utilisateur_id` passe à
`null` — mais **le nom reste**.

> 📌 **Un audit qu'on efface en supprimant un utilisateur n'est pas un audit.**
> `ON DELETE CASCADE` sur un journal est toujours une erreur.

### 9.2 L'audit survit à ce qu'il audite

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public AuditLog enregistrer(…)
```

Le test le montre : on journalise, puis l'opération échoue et la transaction
est annulée. **La trace reste.**

> 💡 C'est souvent la trace la plus intéressante : savoir que quelqu'un a
> *tenté* de supprimer un produit vendu vaut au moins autant que de savoir
> qu'il y a réussi.

Même mécanisme que le journal de sécurité (chapitre 08 §9). Le motif revient
une troisième fois dans le projet.

### 9.3 Les valeurs sont en `jsonb`

```sql
WHERE ancienne_valeur ->> 'prix' = '15000'
```

On veut pouvoir demander « qui a changé le prix ? », pas relire des chaînes de
caractères.

---

## 10. Le bug du repository sans entité

Ma première version des mesures de risque :

```java
public interface SignauxRisqueRepository extends Repository<Object, Long> { … }
```

L'application refusait de démarrer :

```text
Not a managed type: class java.lang.Object
```

**Le message est sec, et il a raison.** Un repository Spring Data JPA
**appartient** à une entité — c'est tout son contrat. Mes requêtes ne faisaient
que compter des lignes dans quatre tables de trois domaines : elles n'ont
aucune entité.

### La bonne réponse

```java
@Component
public class SignauxRisqueRepository {
    private final JdbcTemplate jdbc;
    …
}
```

> 🎯 **La leçon :** quand un outil résiste, c'est souvent qu'on lui demande
> quelque chose qui n'est pas son travail. Le réflexe n'est pas de contourner,
> c'est de se demander si le besoin est bien formulé.
>
> Ici, lire directement les tables est **le bon choix** : on ne modifie rien,
> et on évite que la surveillance devienne un nœud dont trois domaines
> dépendent (chapitre 06 §4).

---

## 11. À retenir

1. **Trois journaux, trois raisons d'être.** Tout mettre dans un seul rend l'ensemble inexploitable.
2. Un score **sans explication ne permet aucune décision**. L'écran affiche les phrases, pas le nombre.
3. **Plafonner chaque signal** : c'est l'accumulation qui fait le risque, pas l'intensité d'un seul.
4. **Versionner l'algorithme**, sinon comparer deux scores dans le temps n'a aucun sens.
5. Chaque calcul **ajoute une ligne** ; on n'écrase pas l'historique.
6. `jsonb` pour ce qu'on **affiche** ou dont la forme varie ; vraies colonnes pour ce qu'on **filtre**.
7. **La surveillance observe, l'humain décide.** Aucune méthode ne bloque un compte.
8. **Une alerte à la fois** : une file qu'on ne regarde plus est pire qu'aucune alerte.
9. Un audit **survit à son acteur** (nom copié) et **à ce qu'il audite** (`REQUIRES_NEW`).
10. Quand un outil résiste, **le besoin est peut-être mal formulé**.

---

## 12. Exercices

**Exercice 1.**
Ajoute un signal « commande depuis un pays inhabituel ». Quelles données
manquent aujourd'hui ? Faut-il une migration ? Quel poids lui donnerais-tu ?

**Exercice 2.**
Tu passes le poids des échecs de paiement de 8 à 12 points. Que dois-tu faire
d'autre, et pourquoi ? Que deviennent les scores déjà enregistrés ?

**Exercice 3.**
Écris la requête qui liste les clients dont le score a **augmenté** de plus de
30 points en une semaine. Attention à la version de l'algorithme.

**Exercice 4.**
Un Admin veut savoir qui a modifié le prix du produit 42 le mois dernier.
Écris la requête, et dis ce qu'il faudrait ajouter pour qu'elle soit rapide sur
dix millions de lignes.

**Exercice 5.**
On veut bloquer automatiquement un compte à `CRITICAL`. Argumente **contre**,
puis **pour**, puis propose un compromis qui préserve la règle fondatrice n°2.

---

➡️ **Chapitre suivant :** [19 — Les statistiques et les tableaux de bord](19-statistiques.md)
