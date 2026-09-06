# Chapitre 09 — Le catalogue : produits, variantes et médias

> Prérequis : chapitres [07](07-entites-jpa-et-transactions.md) et [08](08-authentification-et-permissions.md).
> Durée de lecture : ~35 min.
> **Premier module métier.** Tous les autres en dépendent : sans catalogue,
> ni panier, ni commande, ni stock.

---

## 1. Ce qu'on veut faire

Permettre à un responsable de créer une fiche produit, de la décliner, de
l'illustrer, puis de la publier — et permettre à un visiteur de la consulter.

Ça paraît simple. Il y a trois pièges :

1. la **variante par défaut**, qui décide de la complexité de tout le code aval ;
2. les **conditions de publication**, que la base ne peut pas porter ;
3. la **frontière** entre ce que voit le public et ce que voit le back-office.

---

## 2. Produit, variante, attribut

Trois concepts qu'il ne faut jamais confondre (D-01) :

```text
PRODUIT      « Chemise Oxford »
             Ce que le CLIENT voit : nom, description, photos, catégorie.
             ❌ N'a NI prix NI stock.

VARIANTE     « Chemise Oxford — M — Bleu »        SKU : CHO-M-BLE
             Ce qu'on VEND réellement.
             ✅ Porte le prix et le stock. C'est elle qu'on met au panier.

ATTRIBUT     « Taille », « Couleur »              → référentiel partagé
VALEUR       « M », « Bleu »
```

Le référentiel d'attributs est **commun à tout le catalogue** : « Taille » est
défini une fois et réutilisé par tous les vêtements. Sinon chaque produit
inventerait ses propres valeurs (« M », « m », « Medium », « Moyen ») et aucun
filtre transversal ne serait possible.

---

## 3. La variante par défaut : la décision qui simplifie tout

Un sac de ciment n'a aucune déclinaison. La tentation est forte :

```java
// ❌ Le piège
if (produit.aDesVariantes()) {
    prix = variante.getPrix();
} else {
    prix = produit.getPrix();
}
```

Ce `if` a l'air inoffensif. Il va se retrouver dans le panier, la commande, le
colis, le stock, les statistiques — **et il finira par être oublié quelque
part**. Le jour où on l'oublie, le prix est `null` et la commande échoue.

La règle de GARAH :

```java
Produit produit = new Produit(...);
produits.save(produit);
produit.ajouterVariante(reference, nom, true);   // ← TOUJOURS, sans exception
```

**Tout produit a au moins une variante.** Un seul chemin de code, partout.

> ⚠️ **Et `parDefaut` alors, à quoi sert-il ?**
> Uniquement à l'**affichage** : quelle déclinaison montrer en premier sur la
> fiche. Écrire `if (variante.estParDefaut())` pour sauter une étape
> ramènerait exactement le double chemin qu'on cherche à éviter.
>
> Un champ peut exister pour l'interface sans jamais servir à décider.

---

## 4. Le slug

```text
/produits/chemise-oxford        ✅
/produits/42                    ❌
```

Deux raisons, et la seconde surprend souvent :

1. Un slug **se lit, se partage et se référence**. Google l'indexe mieux.
2. `/produits/42` **révèle le volume d'activité**. Un concurrent demande
   `/produits/1`, `/produits/10000`, et déduit en trois requêtes combien de
   produits existent — donc la taille de l'entreprise.

### Le slug est figé

```java
@Column(nullable = false, updatable = false, length = 220)
private String slug;
```

`updatable = false` : renommer le produit ne change **pas** son slug.

C'est délibéré. Un slug qui change casse tous les liens partagés par les
clients, tous les liens externes, et l'indexation Google — pour un bénéfice
esthétique nul.

### Le détail qui fait la différence

```java
Normalizer.normalize(texte, Form.NFD).replaceAll("\\p{M}", "")
```

NFD sépare la lettre de son accent, qu'on supprime ensuite.

```text
✅ « Téléphone »  →  telephone
❌ une suppression naïve des non-ASCII  →  tlphone
```

C'est testé, et c'est exactement le genre de détail qu'on ne remarque qu'en
production, sur un catalogue plein d'accents.

---

## 5. La machine à états écrite comme des données

```java
private static final Map<StatutProduit, Set<StatutProduit>> TRANSITIONS = Map.of(
        BROUILLON, Set.of(PUBLIE, ARCHIVE),
        PUBLIE,    Set.of(MASQUE, ARCHIVE),
        MASQUE,    Set.of(PUBLIE, ARCHIVE),
        ARCHIVE,   Set.of());          // terminal
```

Plutôt qu'une cascade de `if`. Deux avantages :

- la machine se lit **d'un coup d'œil** — elle tient en quatre lignes ;
- on ne peut pas **oublier une branche** : chaque état a son entrée.

### `ARCHIVE` est terminal, et c'est important

Un produit vendu une fois est référencé par des lignes de commande. Il ne doit
**jamais** disparaître, et il ne doit **jamais** revenir à la vente par
accident. On l'archive ; on ne le supprime pas.

```text
un brouillon ne peut pas être MASQUÉ    → il n'a jamais été visible
un archivé ne peut pas être PUBLIÉ      → décision irréversible, assumée
```

Les deux sont testés.

---

## 6. L'invariant I-12 : pourquoi il n'est pas en base

Publier un produit exige trois choses :

```text
≥ 1 variante ACTIVE     sinon il n'y a rien à vendre
≥ 1 prix                sinon il est invendable
≥ 1 photo               sinon il n'y a rien à montrer
```

**Aucune des trois ne peut être une contrainte SQL.** La raison est
structurelle : au moment où la ligne `produit` est insérée, ni la variante, ni
le prix, ni la photo n'existent encore. Un `CHECK` ne voit qu'une ligne, à
l'instant où elle est écrite.

Ces règles vivent donc dans le service — au moment précis de la **publication**,
qui est justement l'instant où elles ont un sens.

> 🎯 **Pourquoi ce contrôle n'est pas cosmétique :**
> un produit publié sans prix s'affiche dans le catalogue, se met au panier,
> et fait échouer la commande **au dernier moment, devant le client**.
> Le contrôle à la publication déplace l'erreur là où elle coûte le moins
> cher : devant le responsable qui publie.

---

## 7. Le média principal et l'index unique partiel

L'index `media_principal_unique` interdit deux photos principales par produit.
Conséquence directe sur le code :

```java
if (devientPrincipal) {
    medias.retirerPrincipal(produitId);   // ← D'ABORD retirer
}
produit.ajouterMedia(type, cleObjet, devientPrincipal);   // ensuite poser
```

Inverser les deux lignes ferait échouer l'insertion. La contrainte ne se
« contourne » pas : elle **dicte l'ordre des opérations**, et c'est très bien
ainsi.

### La règle du premier média

```java
boolean estPremier = medias.countByProduitId(produitId) == 0;
boolean devientPrincipal = principal || estPremier;
```

Le tout premier média devient principal **même sans le demander**. Sinon un
produit se retrouverait sans vignette en liste — et personne ne s'en
apercevrait avant la mise en ligne.

---

## 8. Le bug qu'ArchUnit a attrapé

Ma première version du contrôleur ressemblait à ça :

```java
import com.garah.api.catalogue.domaine.Produit;      // ← une ENTITÉ JPA

@GetMapping("/{slug}")
public DetailProduit parSlug(@PathVariable String slug) {
    Produit produit = produits.findBySlug(slug).orElseThrow(...);
    return DetailProduit.de(produit);
}
```

Ça compile, ça marche, et **ça viole la règle du chapitre 06** :

```java
noClasses().that().resideInAPackage("..web..")
    .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
```

Le test aurait échoué au build.

### La correction, et pourquoi elle est meilleure

Le **service** renvoie désormais des vues (`ResumeProduit`, `DetailProduit`),
et le contrôleur ne voit plus jamais d'entité :

```java
@GetMapping("/{slug}")
public DetailProduit fichePublique(@PathVariable String slug) {
    return catalogue.fichePublique(slug);       // c'est tout
}
```

Le contrôleur ne fait plus que traduire du HTTP en appels de service : pas
d'entité, pas de transaction, pas de règle métier.

> 📌 **La leçon dépasse ce cas précis.**
> J'avais écrit la règle au chapitre 06, je l'ai enfreinte au chapitre 09 —
> sans le remarquer, parce que le code était parfaitement lisible.
>
> **Une règle d'architecture qu'on ne teste pas est une règle qu'on croit
> suivre.** Le test ne sert pas à convaincre les autres : il sert à
> s'attraper soi-même.

---

## 9. La pagination n'est pas optionnelle

```java
private static final int TAILLE_MAX = 100;

PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX), Sort.by("nom"));
```

Deux protections, et les deux sont nécessaires :

| Sans | Ce qui arrive |
|---|---|
| Pagination | `findAll()` charge tout le catalogue en mémoire. Marche à 50 produits, tombe à 50 000. |
| Plafond de taille | `?taille=1000000` fait la même chose, à la demande. C'est la **première** chose qu'un robot essaie. |

Et le tri est **imposé par le serveur** (`Sort.by("nom")`), pas reçu du client :
laisser trier sur un champ arbitraire permet de trier sur une colonne non
indexée, et de faire ramer la base à volonté.

---

## 10. Deux routes pour le même objet

```text
GET /api/produits/{slug}                 public       → uniquement les PUBLIE
GET /api/produits/administration/{id}    PRODUIT_CONSULTER → tout, brouillons compris
```

C'est volontairement **deux routes**, pas une seule avec un `if (estResponsable)`.

> ⚠️ Un endpoint dont le contenu dépend d'un `if` sur le rôle est exactement
> le genre de code où une fuite finit par se glisser : il suffit d'oublier une
> branche, ou d'ajouter un champ dans le mauvais DTO.
>
> Deux routes, deux règles d'accès, deux réponses. Rien à confondre.

Et le détail qui compte :

```java
produits.findBySlug(slug)
        .filter(Produit::estPublie)
        .orElseThrow(() -> RessourceIntrouvable.de("Produit", slug));
```

Un brouillon renvoie **404**, pas **403**. Répondre « interdit » confirmerait
son existence — donc renseignerait un concurrent sur le catalogue à venir.

---

## 11. Deux natures de tests

| | `SlugTest` | `ServiceCatalogueTest` |
|---|---|---|
| Nature | Unitaire pur | Intégration |
| Infrastructure | Aucune | Spring + PostgreSQL |
| Durée | **0,4 s** pour 9 tests | 17 s pour 11 tests |

> 📌 **Ce qui peut être testé sans infrastructure doit l'être sans
> infrastructure.**
> Une suite lente finit par ne plus être lancée — et une suite qu'on ne lance
> pas ne protège de rien.

`Slug` est une fonction pure : elle mérite un test rapide et exhaustif
(`@ParameterizedTest` couvre sept cas en une méthode). Le service, lui, a
besoin d'une vraie base : c'est là que vivent les contraintes.

---

## 12. À retenir

1. **Tout produit a au moins une variante**, même sans déclinaison. Un seul chemin de code.
2. `parDefaut` sert à l'**affichage**, jamais à décider.
3. Le **slug** est figé : le changer casse les liens partagés et l'indexation.
4. Une **machine à états écrite en données** se lit d'un coup d'œil et n'oublie aucune branche.
5. Les règles **« au moins un »** ne peuvent pas être des contraintes SQL : elles vivent dans le service, au moment où elles ont un sens.
6. Une contrainte de base **dicte l'ordre des opérations** — retirer le principal avant d'en poser un autre.
7. **Une règle d'architecture qu'on ne teste pas est une règle qu'on croit suivre.**
8. **Pagination et plafond de taille**, dès la première route de liste.
9. Deux publics ⇒ **deux routes**, jamais un `if` sur le rôle.
10. Un brouillon renvoie **404**, pas 403 : ne rien confirmer.

---

## 13. Exercices

**Exercice 1.**
Un responsable veut dépublier un produit qui n'a jamais été publié. Que se
passe-t-il, et pourquoi est-ce le bon comportement ? Trouve la ligne de la
table `TRANSITIONS` qui décide.

**Exercice 2.**
Ajoute la règle « on ne peut pas archiver un produit qui a du stock
disponible ». Où la places-tu, et pourquoi pas dans l'entité `Produit` ?

**Exercice 3.**
Le catalogue affiche 24 produits par page, chacun avec sa photo principale.
Combien de requêtes SQL sont exécutées ? Vérifie en activant
`logging.level.org.hibernate.SQL=DEBUG`, puis corrige si nécessaire.

**Exercice 4.**
Écris le test qui vérifie qu'un responsable **sans** `PRODUIT_PUBLIER` reçoit
un `403` sur `POST /api/produits/{id}/publication`.

**Exercice 5.**
`ajouterVariante` renvoie encore une entité `Variante`. Est-ce un problème
aujourd'hui ? Le deviendrait-il si on exposait cette méthode dans le
contrôleur ? Que faudrait-il faire alors ?

---

➡️ **Chapitre suivant :** [10 — Le prix par palier de quantité](10-prix-par-palier.md)
