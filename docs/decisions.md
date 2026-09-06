# Journal des décisions — GARAH

> Une décision par entrée. On note **le choix**, **la raison**, et **ce qu'on perd**.
> On ne réécrit jamais une entrée : si on change d'avis, on ajoute une entrée
> qui remplace la précédente (et on marque l'ancienne « remplacée par D-xx »).
>
> Pourquoi ce fichier : dans six mois, la question « pourquoi c'est fait comme ça ? »
> se posera. Le code répond au « comment », jamais au « pourquoi ».

---

## D-01 — Produits avec variantes

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Les produits sont déclinés en **variantes** (taille, couleur, capacité…).

**Conséquences sur le modèle.**

```text
PRODUIT            le modèle commercial : nom, description, photos, marchand, catégorie
   │
   └──*── VARIANTE  la référence vendable : SKU, attributs, prix, stock
              │
              ├── STOCK          (1-1 avec la VARIANTE, plus avec le produit)
              ├── TARIFICATION   (paliers de quantité, sur la variante)
              └── MEDIA          (optionnel : photos propres à la variante)

LIGNE_PANIER    → variante_id
LIGNE_COMMANDE  → variante_id  (+ copie figée : nom produit, attributs, prix, marchand)
LIGNE_COLIS     → ligne_commande_id
```

**Ce que ça coûte.** Tout le code aval (panier, commande, colis, statistiques,
mouvements de stock) manipule une variante et non un produit. Les écrans
d'administration ont un niveau de plus.

**Ce que ça évite.** Le catalogue reste propre (une chemise = une fiche, pas trois),
et le refactoring « produit → variante », qui est le plus douloureux d'une
plateforme e-commerce, n'aura jamais à être fait.

> ⚠️ **Piège à traiter** : un produit sans déclinaison réelle (un sac de ciment)
> aura quand même **une** variante, dite « par défaut ». Il ne faut surtout pas
> deux chemins de code (« avec » et « sans » variante) : **toujours** passer par
> la variante, même quand il n'y en a qu'une. Un seul chemin, toujours.

---

## D-02 — Un Responsable peut avoir plusieurs catégories

**Date :** 06/09/2026
**Statut :** ✅ actée — modifie la spécification initiale (§6)

**Choix.** Un Responsable peut cumuler plusieurs catégories (ex. commercial **et**
logistique). Une catégorie est marquée **principale** : c'est elle qui donne le
titre affiché.

**Conséquences sur le modèle.**

```text
RESPONSABLE  *──────*  CATEGORIE_RESPONSABLE
        table RESPONSABLE_CATEGORIE (responsable_id, categorie_id, principale)
```

**Calcul des permissions effectives :**

```text
    UNION des cas d'utilisation de TOUTES ses catégories
  + exceptions individuelles ADD
  − exceptions individuelles REMOVE
  = permissions effectives
```

**Contraintes à faire porter par la base :**

- exactement **une** catégorie principale par responsable
  → index unique partiel sur `(responsable_id) WHERE principale = true` ;
- pas de doublon `(responsable_id, categorie_id)` → clé primaire composite.

**Ce que ça coûte.** L'affichage du titre demande une règle explicite (la principale),
et le calcul des permissions fait une jointure de plus.

**Ce que ça évite.** Une liste d'exceptions `ADD` interminable pour toute personne
polyvalente — ce qui est la norme dans une petite structure.

> ⚠️ **Le `REMOVE` devient plus subtil** : si `PRIX_MODIFIER` vient de deux
> catégories, un seul `REMOVE` doit le retirer **entièrement**. La soustraction
> s'applique **après** l'union, jamais catégorie par catégorie.

---

## D-03 — Un workspace Angular, trois applications, designs indépendants

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Un **seul workspace Angular** contenant les trois applications et une
librairie partagée — **mais chaque application garde son propre design**.

Ces deux choses ne s'opposent pas, et c'est le point important :

```text
garah-ui  fournit          │  chaque app décide
───────────────────────────┼──────────────────────────────
les jetons (variables CSS) │  leurs VALEURS
la structure des composants│  leur apparence
les modèles TypeScript     │  —
le client HTTP, les erreurs│  —
```

Concrètement :

```text
garah-ui/theme/_tokens.scss      déclare les NOMS   --primary, --bg-color, --radius-lg
                                 et des valeurs par défaut

garah-web/styles.scss            :root { --primary: #aa3bff; --radius-lg: 24px; }
                                 vitrine : chaleureuse, éditoriale, grands visuels

garah-client/styles.scss         :root { --primary: #6366f1; }
                                 espace client : rassurant, lisible, mobile d'abord

garah-admin/styles.scss          :root { --primary: #4f46e5; --radius-lg: 12px; }
                                 back-office : dense, tableaux, glassmorphism
```

Un bouton de `garah-ui` écrit `background: var(--primary)`.
Il est **indigo** dans le back-office et **violet** sur la vitrine, sans
une ligne de code dupliquée.

> 🎯 **La notion à retenir : l'inversion par les jetons.**
> Une librairie de composants ne doit **jamais** contenir de couleur en dur.
> Elle déclare des *intentions* (`--primary`, `--danger`, `--surface`).
> L'application qui la consomme fournit les *valeurs*.
> C'est ce qui permet à trois interfaces très différentes de partager
> le même code de composants.

**Ce que ça coûte.** Un peu de discipline : interdiction absolue d'écrire
un `#6366f1` dans `garah-ui`.

**Ce que ça évite.** Trois `theme.css` copiés-collés qui divergent, trois
définitions du modèle `Commande`, et une correction de bug à faire trois fois.

**Ce qu'on se réserve.** Si une application doit vraiment diverger sur la
structure d'un composant (et pas seulement son apparence), elle écrit son
propre composant en local. La librairie n'est pas une prison.

---

## D-04 — Un dépôt git unique

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Un seul dépôt `garah/` contenant backend, frontend et documentation.

```text
garah/
├── backend/          Spring Boot
├── frontend/         workspace Angular (3 apps + garah-ui)
├── docs/
│   ├── cours/        le cours
│   └── decisions.md  ce fichier
└── .gitignore
```

**Pourquoi.** Une évolution d'API touche le backend **et** les frontends.
Dans un dépôt unique, ça tient dans **un seul commit** : on voit d'un coup
la modification et son impact. Dans deux dépôts, il faut deux commits
qu'on doit garder synchrones — et qui finissent par diverger.

**Ce que ça coûte.** Le dépôt est plus gros, et un `git clone` télécharge tout.
Négligeable à cette échelle.

> ⚠️ **Attention sur ce poste** : le dossier `C:\Users\Administrator` est
> lui-même un dépôt git (branche `test-clean`). C'est un accident.
> Toujours travailler depuis `Documents\Dev\garah`, et ne **jamais** committer
> depuis la racine du profil.
