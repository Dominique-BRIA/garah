# Chapitre 19 — Les statistiques et les tableaux de bord

> Prérequis : chapitres [12](12-panier-et-commande.md) et [18](18-surveillance-et-audit.md).
> Durée de lecture : ~30 min.
> **Dernier chapitre du backend.**

---

## 1. Le domaine que la spécification avait oublié

La §20 réclamait « vues, favoris, paniers, produits tendance ».

Et le modèle initial ne contenait **aucune** table pour les stocker. C'était la
correction **A2**, classée bloquante — et voici pourquoi ce n'était pas
« à faire plus tard » :

> 🎯 **Une vue non enregistrée est définitivement perdue.**
>
> Un écran se code après coup. Une donnée non collectée ne se rattrape
> **jamais**. Si on avait repoussé ce domaine de six mois, on aurait six mois
> de trou dans toutes les statistiques — pour toujours.

C'est la raison pour laquelle les trois tables existent depuis la migration
`V13`, bien avant le premier tableau de bord.

---

## 2. Écriture détaillée, lecture agrégée

Deux régimes qui n'ont rien à voir cohabitent dans ce module.

```text
ÉCRITURE                              LECTURE
vue_produit                           statistique_produit_jour
1 ligne par consultation              1 ligne par produit et par jour
des millions                          quelques milliers
en continu, très légère               une fois par nuit, lourde
```

> ⚠️ **Les confondre est l'erreur classique.** Afficher un tableau de bord en
> scannant la table de détail fonctionne parfaitement avec 50 produits, et
> fait tomber le serveur avec 50 000.

Compter les vues d'un produit sur 30 jours :

```text
sur vue_produit                 scan de millions de lignes
sur statistique_produit_jour    lecture de 30 lignes
```

---

## 3. Vues et vues uniques

```sql
count(*)                    AS vues,
count(DISTINCT session_id)  AS vues_uniques
```

Le même visiteur qui rafraîchit trois fois compte pour **trois vues** et
**un visiteur**.

> 📌 Sans cette distinction, un client qui recharge dix fois une page
> compterait pour dix visiteurs — et le classement des produits tendance
> serait faux au profit des pages... lentes à charger.

C'est `session_id` qui rend la mesure possible. Sans lui, la colonne
`vues_uniques` de la spécification serait incalculable.

---

## 4. L'agrégation : en SQL, et idempotente

```sql
INSERT INTO statistique_produit_jour (…)
SELECT … FROM produit p
  LEFT JOIN ( … ) v ON …
  LEFT JOIN ( … ) c ON …
ON CONFLICT (produit_id, jour) DO UPDATE SET …
```

Deux décisions ici.

### Tout le calcul est fait par la base

Charger des millions de lignes en mémoire pour les compter en Java serait
absurde : **agréger est le métier de la base**, et elle le fait sans transférer
les données.

### `ON CONFLICT … DO UPDATE` rend l'opération rejouable

Un travail nocturne échoue parfois à mi-parcours — coupure réseau, redémarrage,
instance Render endormie. **On le relance**, et il ne doit rien dupliquer.

Le test lance l'agrégation **trois fois** et vérifie qu'une seule ligne existe.

> 💡 **L'idempotence revient pour la quatrième fois dans le projet** : webhook
> de paiement (ch. 13), écriture au grand livre (ch. 17), alerte de risque
> (ch. 18), agrégation nocturne (ici).
>
> Quand un motif revient quatre fois, ce n'est plus une astuce : c'est une
> **propriété qu'on cherche par défaut**.

---

## 5. `FILTER` : deux agrégats, une seule lecture

```sql
SUM(quantite_vendue) FILTER (WHERE jour >= CURRENT_DATE - 7)  AS recentes,
SUM(quantite_vendue) FILTER (WHERE jour <  CURRENT_DATE - 7)  AS precedentes
```

C'est du **SQL standard**, peu connu, et bien plus lisible que trois
sous-requêtes corrélées.

Surtout : la table n'est lue **qu'une fois**. Avec des sous-requêtes, elle le
serait deux ou trois fois.

---

## 6. Les tendances : progression, pas volume

C'est la « dynamique récente » de la §20, et le choix n'est pas neutre.

```text
Produit A    2 ventes la semaine passée  →  30 cette semaine    croissance ×15
Produit B   40 ventes la semaine passée  →  40 cette semaine    croissance ×1
```

**B vend plus. A est la tendance.**

> ⚠️ Classer par volume absolu laisserait le même best-seller en première
> place pendant trois ans. Un classement qui ne bouge jamais n'apprend rien —
> et personne ne le regarde plus.

### Le piège de la division par zéro

```java
double croissance = precedentes == 0
        ? (recentes > 0 ? 2.0 : 0.0)
        : (double) recentes / precedentes;
```

Une progression depuis zéro est mathématiquement **infinie**. Sans cette borne,
un produit vendu **une seule fois** écraserait tout le classement.

---

## 7. La purge, et son ordre

```java
public int purgerLeDetail() {
    return vues.purger(Instant.now().minus(Duration.ofDays(90)));
}
```

D-15 : le détail vit 90 jours, l'agrégat pour toujours.

```text
On garde     toutes les statistiques, indéfiniment
On perd      « qui a vu quoi, à quelle heure » au-delà de 3 mois
```

Trois raisons, et la troisième compte autant que les deux autres :

| | |
|---|---|
| **Volume** | Le détail grossit sans limite ; l'agrégat est minuscule |
| **Utilité** | Personne n'analyse le parcours détaillé d'un client sur un an |
| **Données personnelles** | Garder des adresses IP nominatives sans usage est une mauvaise pratique |

### L'ordre est vital

> ⚠️ **Purger AVANT d'agréger détruirait la journée qu'on s'apprêtait à
> résumer.** Le travail nocturne fait toujours : agréger, **puis** purger.

Et l'index `vue_produit_purge_idx` sur `date_heure` existe uniquement pour que
cette suppression soit rapide — sans lui, elle scannerait toute la table
chaque nuit.

---

## 8. Le bug en deux temps

Le test « une erreur de mesure ne doit pas faire échouer l'affichage » a échoué
**deux fois**, pour deux raisons différentes. C'est le plus instructif du
chapitre.

### Tentative 1 : `try/catch`

```java
@Transactional
public void enregistrerVue(…) {
    try {
        vues.save(new VueProduit(…));
    } catch (RuntimeException e) { }
}
```

```text
UnexpectedRollbackException: Transaction silently rolled back
                             because it has been marked as rollback-only
```

**Attraper une exception n'annule pas le marquage « rollback only ».** Dès que
la base refuse une écriture, la transaction est **condamnée** : tout ce qu'on
écrira ensuite sera perdu, et l'appelant recevra une erreur incompréhensible
au moment du commit.

> 📌 **Un `catch` ne répare pas une transaction.** C'est un des malentendus les
> plus répandus sur les transactions.

### Tentative 2 : `@Transactional(REQUIRES_NEW)`

Même erreur. Pourquoi ?

```text
1. entrée dans la méthode          → transaction ouverte
2. save()                          → la base refuse
3. catch                           → on attrape ✅
4. SORTIE de la méthode            → COMMIT
5. le commit échoue                → exception ❌   ← après le catch
```

**Le commit a lieu après le `catch`**, puisqu'il est déclenché par la sortie de
la méthode proxifiée. L'exception surgit hors du bloc protégé.

### La solution

```java
try {
    transactionIsolee.executeWithoutResult(statut -> vues.save(…));
} catch (RuntimeException e) { }
```

Un `TransactionTemplate` explicite : **le commit se produit à l'intérieur du
`try`**, donc l'exception est enfin attrapable.

> 🎯 **La leçon, valable partout :** avec `@Transactional`, la validation a
> lieu à la **sortie** de la méthode. Tout ce qui doit réagir au résultat du
> commit doit donc se trouver **en dehors** de cette méthode — ou piloter la
> transaction explicitement.

Et pourquoi tout ça pour une simple statistique :

> **Une statistique perdue est regrettable. Une fiche produit en erreur est un
> client perdu.** C'est le seul endroit du projet où on ignore délibérément une
> exception — et il fallait le faire correctement.

---

## 9. Le backend est terminé

```text
11 domaines      IAM, marchand, catalogue, stock, commerce, service client,
                 logistique, SAV, finance, surveillance, mesure
19 migrations    58 tables, 107 CHECK, 105 clés étrangères, 2 triggers
150 tests        dont 4 d'architecture et 2 de concurrence réelle
```

Le parcours complet fonctionne de bout en bout :

```text
catalogue → panier → commande → paiement → stock → expédition
         → retrait → retour → remboursement → grand livre marchand
```

---

## 10. À retenir

1. **Une donnée non collectée ne se rattrape jamais.** Les tables de mesure existent avant les écrans.
2. **Écriture détaillée, lecture agrégée.** Les confondre marche à 50 produits et tombe à 50 000.
3. `session_id` rend les **vues uniques** calculables. Sans lui, la colonne est un vœu.
4. **La base agrège**, pas Java. C'est son métier, et elle ne transfère rien.
5. `ON CONFLICT … DO UPDATE` rend le travail nocturne **rejouable**.
6. `FILTER` calcule deux agrégats en **une seule lecture**.
7. Les tendances classent par **progression**, avec une borne pour la division par zéro.
8. **Agréger, puis purger.** Jamais l'inverse.
9. **Un `catch` ne répare pas une transaction** marquée « rollback only ».
10. Avec `@Transactional`, le **commit a lieu après le `catch`**. Pour réagir au commit, il faut piloter la transaction soi-même.

---

## 11. Exercices

**Exercice 1.**
La colonne `ajouts_panier` reste à zéro : l'agrégation ne la remplit pas.
Écris la sous-requête manquante. Quelle difficulté pose le fait qu'un panier
soit converti en commande ?

**Exercice 2.**
Écris le tableau de bord d'un marchand : chiffre d'affaires, commandes,
retours, panier moyen sur 30 jours. Une seule requête, avec `FILTER`.

**Exercice 3.**
Le travail nocturne tombe en panne trois jours. Que faut-il faire au
redémarrage ? Écris la boucle de rattrapage, et dis pourquoi elle est sûre.

**Exercice 4.**
Reproduis le bug du §8 : remplace le `TransactionTemplate` par
`@Transactional(REQUIRES_NEW)` et lance `ServiceStatistiquesTest`. Explique la
séquence exacte.

**Exercice 5.**
Un responsable veut « les produits les plus vus mais jamais achetés ».
Écris la requête, et dis ce qu'elle révèle sur le catalogue.

---

➡️ **Chapitre suivant :** [20 — Tests automatisés et intégration continue](20-tests-et-integration-continue.md)
