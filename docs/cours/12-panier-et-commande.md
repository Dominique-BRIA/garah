# Chapitre 12 — Le panier et la commande

> Prérequis : chapitres [10](10-prix-par-palier.md) et [11](11-stock-et-concurrence.md).
> Durée de lecture : ~35 min.
> **L'opération la plus lourde du backend** : elle touche cinq domaines en une
> seule transaction.

---

## 1. Panier et commande ne sont pas la même chose

C'est la distinction qui structure tout le chapitre.

| | Panier | Commande |
|---|---|---|
| Nature | Une **intention** | Un **engagement** |
| Réserve du stock | ❌ non | ✅ oui |
| Fige les prix | ❌ non | ✅ oui |
| Peut contenir un article en rupture | ✅ oui, on le signale | ❌ non |
| Durée de vie | Indéfinie | Définitive |

### Pourquoi le panier ne réserve rien

Réserver au panier paraît protecteur pour le client. C'est en fait la porte
ouverte à un blocage total : n'importe qui pourrait **immobiliser tout le
catalogue** en remplissant un panier qu'il n'a aucune intention de payer.

```java
panier.ajouter(clientId, varianteId, 3);
assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);   // inchangé
```

### Pourquoi le panier n'a pas de prix

`ligne_panier` ne contient **ni prix ni désignation** — seulement la variante
et la quantité. Le prix est recalculé à chaque affichage.

```java
panier.ajouter(clientId, varianteId, 2);     // → 30 000 (palier 1-4)
panier.definirQuantite(clientId, varianteId, 6);  // → 78 000 (palier 5+)
```

> 🎯 **La photographie commence à la commande, pas au panier.**
> Un article mis au panier il y a trois semaines s'affiche au tarif du jour.
> C'est ce que le client attend, et c'est ce qui évite qu'un panier oublié
> devienne une promesse de prix qu'on n'a jamais faite.

### Mais on signale les ruptures

```java
public record ContenuPanier(…, List<String> indisponibles) { }
```

> ⚠️ Découvrir « il n'en reste que 2 » **au moment de payer** est la pire
> expérience possible : le client a déjà sorti son téléphone. On le dit
> avant.

---

## 2. Le passage de commande : cinq domaines, une transaction

```text
commerce      construit la commande et ses lignes
catalogue     fournit la désignation et le marchand
tarification  fournit le prix du jour
marchand      fournit le taux de commission
logistique    fournit les frais du point de retrait
stock         RÉSERVE la marchandise
```

Tout cela dans **une seule transaction**. Le chapitre 04 le disait :

> Ce qui doit être vrai ensemble s'écrit ensemble.

Une réservation de stock validée sans sa commande immobiliserait de la
marchandise pour un client qui n'existe pas — et personne ne s'en apercevrait
avant l'inventaire.

### Le contrat entre domaines

Le commerce ne charge **pas** l'entité `Variante`. Il demande un record :

```java
public record InfoVenteVariante(
        Long varianteId, Long produitId, String nomProduit, String libelleVariante,
        Long marchandId, Long categorieProduitId, BigDecimal tauxTva,
        String statutVariante, StatutProduit statutProduit) { }
```

Deux bénéfices :

1. la requête lit **six colonnes** au lieu d'une trentaine ;
2. le commerce ne dépend pas de la **structure interne** du catalogue —
   qui peut réorganiser ses entités tant qu'il remplit ce contrat.

---

## 3. La règle de la photographie, prouvée

Cinq champs sont **copiés** dans `ligne_commande` :

```text
designation      le produit peut être renommé
attributs        « M / Bleu » au moment de l'achat
marchandId       le produit peut CHANGER de marchand
prixUnitaire     le tarif évoluera
tauxCommission   la règle de commission évoluera
```

Le test qui le démontre :

```java
DetailCommande commande = commandes.passer(clientId, pointRetraitId, "fr");
// total : 38 000

tarification.changerPrix(palierId, new BigDecimal("25000.00"));

assertThat(commandes.detail(commande.id()).montantTotal())
        .isEqualByComparingTo("38000.00");     // ← inchangé
```

> 📌 **C'est toute la raison d'être de la règle** : une facture de mars reste
> juste en septembre. Sans les copies, elle afficherait le nom actuel, le prix
> actuel et le marchand actuel — et on devrait de l'argent au mauvais
> partenaire.

---

## 4. Le bug que la contrainte a attrapé

Ma première version faisait ceci :

```java
Commande commande = new Commande(…);
commande.setMontantFrais(pointRetrait.getFraisAcheminement());   // 8 000
commandes.save(commande);          // ← ÉCHEC
```

```text
ERREUR : la nouvelle ligne viole la contrainte « commande_total_coherent »
```

**Pourquoi.** La contrainte impose :

```sql
montant_total = montant_articles + montant_frais − montant_remise
```

Au moment de l'insertion, aucune ligne n'existe encore : `articles = 0`,
`total = 0`, mais `frais = 8 000`. Soit `0 ≠ 8 000`.

### La correction, et ce qu'elle enseigne

```java
Commande commande = new Commande(…);
commandes.save(commande);              // tous les montants à zéro : cohérent

… construire les lignes …
reserverLeStock(commande);

commande.setMontantFrais(frais);       // frais et totaux ENSEMBLE
commande.recalculer();
```

> 🎯 **Une contrainte `CHECK` est vérifiée à CHAQUE écriture, pas seulement à
> la fin du traitement.**
> Elle impose donc que la ligne soit **cohérente à tout instant** — pas
> seulement quand le développeur estime avoir fini.
>
> C'est contraignant, et c'est exactement ce qu'on veut : ça élimine la
> catégorie entière des bugs « j'ai oublié de recalculer le total ».

---

## 5. Le numéro de commande : le piège du `count(*)`

```java
// ❌ La tentation
SELECT count(*) + 1 FROM commande;
```

Deux clients qui commandent à la même seconde lisent le même compte et
obtiennent le **même numéro**. La contrainte `UNIQUE` fait alors échouer une
commande **déjà payée**.

C'est le même bug que la survente du chapitre 11, déguisé en numérotation.

```java
// ✅ Une séquence PostgreSQL (V17)
@Query(value = "SELECT nextval('commande_numero_seq')", nativeQuery = true)
long prochainNumero();
```

Une séquence est **atomique** et **ne bloque personne**.

> ⚠️ **Elle peut sauter des numéros.** Une transaction annulée consomme quand
> même sa valeur. C'est acceptable ici. Si un jour la réglementation impose
> une numérotation de factures **sans trou**, il faudra une table de compteur
> verrouillée — beaucoup plus lente, et réservée aux documents qui l'exigent
> vraiment (voir D-11, la TVA).

---

## 6. Le verrouillage ordonné

```java
commande.getLignes().stream()
        .sorted(Comparator.comparing(LigneCommande::getVarianteId))   // ← essentiel
        .forEach(l -> stock.reserver(l.getVarianteId(), l.getQuantite(), commande.getId()));
```

Sans ce tri, deux commandes contenant les mêmes articles dans un ordre
différent peuvent s'attendre mutuellement (chapitre 11 §5).

C'est une ligne de code, elle a l'air décorative, et elle évite une classe
entière d'incidents en production.

---

## 7. L'atomicité, prouvée

Le test le plus important du chapitre :

```java
panier.ajouter(clientId, varianteId, 25);   // il n'y en a que 20

assertThatThrownBy(() -> commandes.passer(clientId, pointRetraitId, "fr"))
        .isInstanceOf(ConflitEtat.class);

assertThat(commandesCreees).isZero();                       // aucune commande
assertThat(stock.etat(varianteId).reserve()).isZero();      // aucun stock engagé
assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);
```

La commande était **déjà enregistrée** quand la réservation a échoué. La
transaction a tout annulé — y compris le numéro consommé dans la séquence.

> 💡 **Pourquoi ce test n'est pas `@Transactional`.**
> Dans une transaction de test unique, l'exception marquerait simplement la
> transaction « à annuler » et on ne pourrait plus rien lire ensuite. Pour
> vérifier un rollback, il faut committer pour de vrai — et nettoyer après.

---

## 8. L'isolation des clients : 404, jamais 403

```java
commandes.chargerAvecLignes(commandeId)
        .filter(c -> c.getClientId().equals(clientId))
        .orElseThrow(() -> RessourceIntrouvable.de("Commande", commandeId));
```

Deux points importants :

**Un client n'a aucune permission** (chapitre 08 §7.2). Son accès repose sur
la **propriété** de ses données, pas sur un droit. C'est un mécanisme
différent, et il doit être vérifié **explicitement à chaque lecture** — aucune
annotation ne le fera à ta place.

**Le message est celui d'une commande introuvable.** Répondre « interdit »
confirmerait qu'elle existe. Un client curieux pourrait alors énumérer les
identifiants et compter les commandes de la plateforme.

---

## 9. La libération des impayées

```java
@Transactional
public int libererLesImpayees() {
    Instant limite = Instant.now().minus(Duration.ofMinutes(30));
    …
}
```

**Ce n'est pas une optimisation. C'est la contrepartie obligatoire** du choix
de réserver plutôt que décrémenter (chapitre 11 §7).

```text
Sans ce travail périodique :
    chaque client qui abandonne son paiement immobilise sa marchandise
    → DÉFINITIVEMENT
    → le stock disponible fond
    → personne ne comprend pourquoi
```

La seule trace serait dans `mouvement_stock` — encore faut-il penser à
regarder.

---

## 10. À retenir

1. **Un panier est une intention, une commande est un engagement.** Le panier ne réserve rien et ne fige rien.
2. Mais il **signale les ruptures** : découvrir une rupture au paiement est la pire expérience possible.
3. Le passage de commande touche **cinq domaines en une transaction**. Tout ou rien.
4. Les domaines communiquent par **records de contrat**, pas par entités.
5. Un `CHECK` est vérifié à **chaque écriture** : la ligne doit être cohérente à tout instant.
6. **Une séquence, jamais `count(*) + 1`** — c'est la survente déguisée en numérotation.
7. **Verrouiller dans un ordre trié**, toujours.
8. Pour tester un rollback, le test ne doit **pas** être `@Transactional`.
9. Un client accède à ses données par **propriété**, pas par permission — vérification explicite, et **404 plutôt que 403**.
10. **Libérer les réservations impayées** n'est pas optionnel.

---

## 11. Exercices

**Exercice 1.**
Un client met 3 articles au panier, puis le responsable dépublie le produit.
Que voit le client dans son panier ? Que se passe-t-il s'il tente de commander ?
Retrouve les deux endroits du code qui traitent ce cas.

**Exercice 2.**
Écris le test qui vérifie qu'une commande contenant **deux** variantes
différentes réserve bien les deux stocks, et qu'un échec sur la seconde annule
la réservation de la première.

**Exercice 3.**
Le travail périodique `libererLesImpayees` s'exécute pendant qu'un paiement
arrive. Décris ce qui se passe, et dis si le verrou du chapitre 11 suffit à
éviter un double effet.

**Exercice 4.**
`recalculer()` est appelé une seule fois, à la fin. Que se passerait-il si on
l'appelait après chaque ligne ajoutée ? Teste, et explique avec la contrainte
`commande_total_coherent`.

**Exercice 5.**
On veut permettre une remise commerciale sur une commande. Où l'écrire pour
que `montant_total = articles + frais − remise` reste vrai, et quelle
permission faut-il exiger ?

---

➡️ **Chapitre suivant :** [13 — Le paiement mobile money](13-paiement-mobile-money.md)
