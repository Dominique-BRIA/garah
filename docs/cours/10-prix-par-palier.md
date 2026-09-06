# Chapitre 10 — Le prix par palier de quantité

> Prérequis : chapitre [09](09-catalogue.md).
> Durée de lecture : ~25 min. Chapitre court, mais dense en pièges.

---

## 1. Ce qu'on veut faire

Répondre à une question qui paraît triviale :

> **Combien coûte l'unité si j'en prends 7 ?**

La grille de la spécification (§12) :

```text
1 à 4      15 000 FCFA
5 à 9      13 000 FCFA
10 et +    11 500 FCFA
```

Trois difficultés se cachent derrière :

1. la réponse doit être **unique** — jamais deux prix possibles ;
2. la remise s'applique-t-elle à **toutes** les unités ou seulement aux
   suivantes ? ;
3. que devient l'**historique** quand un prix change ?

---

## 2. Une seule réponse possible, garantie par la base

```java
Optional<Tarification> palierApplicable(Long varianteId, int quantite, LocalDate jour);
```

Remarque le type : `Optional`, pas `List`. Ce n'est pas une simplification —
c'est une **garantie**.

La contrainte d'exclusion posée au chapitre 05 rend impossible l'écriture de
deux paliers qui se recouvrent :

```sql
EXCLUDE USING gist (
    variante_id WITH =,
    int4range(quantite_min, COALESCE(quantite_max + 1, 1000000)) WITH &&,
    daterange(date_debut, date_fin) WITH &&
)
```

> 🎯 **Ce que ça change concrètement.**
> Sans cette contrainte, la requête renverrait parfois deux lignes, et il
> faudrait trancher avec un `ORDER BY` arbitraire — donc **le prix dépendrait
> de l'ordre d'insertion des paliers**. Un prix qui dépend de l'ordre
> d'insertion n'est pas un prix, c'est un hasard.
>
> La base ne se contente pas d'empêcher une erreur : elle **simplifie le
> code** qui la lit.

### La vérification en double, et pourquoi ce n'est pas redondant

```java
if (tarifications.existeChevauchement(varianteId, min, max, aujourdhui)) {
    throw new RegleMetierViolee("PALIER_CHEVAUCHANT",
        "Ce palier chevauche un palier existant : la question "
        + "« quel prix pour cette quantité ? » aurait deux réponses.");
}
```

La base refuserait de toute façon. Mais elle refuserait avec :

```text
ERREUR: la valeur d'une clé en conflit rompt la contrainte d'exclusion
        « tarification_sans_chevauchement »
```

C'est exactement la doctrine du chapitre 04 : **la base est la dernière ligne
de défense, jamais la seule ligne visible**. Le service produit le message que
le responsable peut comprendre ; la base garantit qu'aucun chemin ne
contourne la règle.

---

## 3. La dégressivité s'applique à TOUTES les unités

C'est la question qu'on se pose une fois et qu'on oublie d'écrire. Tranchons-la.

```text
10 unités au palier « 10 et + » à 11 500

✅ retenu    10 × 11 500                     = 115 000
❌ écarté    4 × 15 000 + 6 × 11 500         =  129 000
```

La première forme est le fonctionnement attendu d'une **remise sur quantité** :
« à partir de 10, c'est 11 500 pièce ». La seconde est un **barème progressif**,
comme l'impôt sur le revenu — utile ailleurs, déroutant dans un catalogue.

C'est écrit dans le code, commenté, **et testé** :

```java
assertThat(tarification.montantPour(varianteId, 10)).isEqualByComparingTo("115000.00");
```

> 📌 **Une décision qui n'est pas testée est une décision qui sera reprise.**
> Le test est le seul endroit où une règle métier ne peut pas être « oubliée »
> lors d'une refonte.

---

## 4. Le palier manquant n'est pas un bug technique

```java
.orElseThrow(() -> new RegleMetierViolee("AUCUN_PALIER",
        "Aucun prix n'est défini pour une quantité de " + quantite + "."));
```

Si un catalogue ne définit un prix que de 10 à 20, une demande de 3 unités
n'a **pas** de réponse.

Ce n'est pas une panne : c'est une **grille tarifaire incomplète**. Le message
doit envoyer le responsable au bon endroit — dans le catalogue, pas dans les
logs du serveur.

> ⚠️ Un `null` renvoyé ici serait la pire réponse : il produirait plus tard un
> `NullPointerException` à trois couches de distance, dans du code qui n'a
> rien à voir avec la tarification.

---

## 5. Changer un prix : fermer, puis ouvrir

C'est le point le plus important du chapitre.

```java
ancien.setDateFin(aujourdhui);
tarifications.saveAndFlush(ancien);          // 1. on FERME

Tarification nouveau = new Tarification(...); // 2. on OUVRE
```

**On ne fait jamais un `UPDATE` du prix.**

### Deux raisons, et la seconde est technique

**1. L'historique reste calculable.** On peut encore répondre à « quel était
le prix le 12 mars ? ». Cette question se pose lors d'un litige sur une
commande ancienne, et il vaut mieux pouvoir y répondre.

> 💡 Le prix est déjà **figé** dans `ligne_commande` (règle de la
> photographie, chapitre 03). Les commandes passées sont donc correctes de
> toute façon. Ce qu'on gagne ici en plus, c'est la reconstitution de la
> **règle** : non pas « ce client a payé 15 000 », mais « le tarif public
> était de 15 000 ce jour-là ». Les deux ne servent pas au même contrôle.

**2. La contrainte d'exclusion l'impose.** Elle porte aussi sur la période de
validité. Insérer un nouveau palier `1-4` sans fermer l'ancien produirait deux
plages qui se recouvrent dans le temps — refusé.

L'ordre compte donc : `saveAndFlush` avant l'insertion, sinon Hibernate peut
réordonner les écritures et déclencher le conflit.

### Ce que voit le test

```text
grille en vigueur   →  3 paliers        (inchangée pour le client)
table tarification  →  4 lignes         (l'ancien prix est conservé)
```

---

## 6. Les bornes : Java et PostgreSQL doivent raisonner pareil

```java
AND (t.dateFin IS NULL OR t.dateFin > :jour)     // borne haute EXCLUSIVE
```

```sql
daterange(date_debut, date_fin)                  -- [debut, fin)  exclusive
```

Les deux disent la même chose, et **c'est indispensable**.

> ⚠️ Si le code Java écrivait `dateFin >= jour` (borne inclusive), un palier
> fermé aujourd'hui serait :
>
> - **clos** pour PostgreSQL, qui refuserait donc un chevauchement ;
> - **encore valide** pour Java, qui l'appliquerait.
>
> Résultat : deux paliers actifs le jour de la bascule, et un prix qui dépend
> de l'ordre des lignes. Un bug d'un seul caractère, visible un seul jour par
> changement de prix — donc quasiment introuvable.

Même remarque sur les quantités : `COALESCE(quantite_max + 1, ...)` avec borne
haute exclusive, des deux côtés.

---

## 7. Les paliers adjacents ne se chevauchent pas

```text
1 à 4    puis    5 à 9        ✅ adjacents, aucun recouvrement
1 à 4    puis    3 à 10       ❌ 3 et 4 auraient deux prix
```

Testé dans les deux sens. C'est le genre de détail où une erreur de `<` contre
`<=` passe inaperçue jusqu'à la première commande de 4 unités.

---

## 8. À retenir

1. La **contrainte d'exclusion** ne fait pas qu'empêcher une erreur : elle rend la requête de lecture **non ambiguë**, donc plus simple.
2. `Optional`, pas `List` : le type exprime la garantie donnée par la base.
3. La **dégressivité s'applique à toutes les unités** — décidé, commenté, testé.
4. Un palier manquant est une **erreur de catalogue**, pas une panne. Jamais de `null`.
5. **On ne modifie pas un prix : on ferme et on ouvre.** L'historique reste calculable.
6. **Java et PostgreSQL doivent raisonner sur les mêmes bornes.** Un `>=` contre `>` crée un bug d'un jour, quasiment introuvable.
7. Vérifier en Java **et** contraindre en base : message clair d'un côté, garantie de l'autre.

---

## 9. Exercices

**Exercice 1.**
La grille est `1-4 : 15 000` et `10 et + : 11 500`. Que se passe-t-il pour une
commande de 7 unités ? Est-ce le comportement souhaitable ? Comment le
détecterais-tu **avant** qu'un client ne le rencontre ?

**Exercice 2.**
Écris la requête qui liste toutes les variantes publiées dont la grille a un
« trou » — une quantité entre 1 et 100 sans prix applicable.

**Exercice 3.**
Un responsable veut programmer une promotion : 12 000 FCFA du 1er au 31
décembre, puis retour à 15 000. Décris les lignes à écrire, et vérifie qu'aucune
ne viole la contrainte d'exclusion.

**Exercice 4.**
`changerPrix` appelle `saveAndFlush` avant d'insérer le nouveau palier.
Retire le `flush`, lance le test, et explique ce qui se passe.

**Exercice 5.**
On veut afficher « à partir de 11 500 FCFA » sur la vignette du catalogue.
Écris la méthode, et dis pourquoi elle ne doit **pas** appeler `prixUnitaire`
en boucle sur chaque palier.

---

➡️ **Chapitre suivant :** [11 — Le stock et la concurrence](11-stock-et-concurrence.md)
