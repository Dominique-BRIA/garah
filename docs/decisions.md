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

**Dépôt distant :** `https://github.com/Dominique-BRIA/garah`

---

## D-05 — Retrait en point de récupération uniquement

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** GARAH ne livre **pas** à domicile. Le client vient retirer sa
marchandise dans un **point de récupération**, qu'il **choisit au moment de
la commande** parmi les points actifs créés par les Admins.

**Conséquences sur le modèle.**

```text
commande.point_recuperation_id     OBLIGATOIRE, choisi au checkout
                                   → c'est une PHOTO : il ne bouge plus après

table adresse                      SUPPRIMÉE (elle n'a plus d'objet)
client.point_recuperation_prefere  NON RETENU
```

**Pourquoi pas de point de retrait « préféré » sur le profil du client ?**
Parce qu'un même client peut commander pour lui à Douala en mars, puis se
faire livrer à Bangui en avril. Une préférence stockée sur le profil serait
fausse une fois sur deux — et surtout ce serait une **référence** là où il
faut un **fait**.

**Ce que ça coûte.** Le client doit choisir un point à chaque commande.
L'interface doit donc bien présenter la liste (par ville, avec les horaires).

**Ce que ça évite.** Toute la gestion d'adresses, de zones de livraison, de
frais au kilomètre et de livreurs. C'est un pan entier du métier qui disparaît.

---

## D-06 — Moyens de paiement : sans espèces

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Trois moyens en v1 : **MTN Mobile Money**, **Orange Money**,
**virement bancaire**. **Pas d'espèces**, ni à la commande ni au retrait.

**La conséquence la plus importante :** la marchandise n'est **jamais**
acheminée avant d'être payée.

```text
EN_ATTENTE_PAIEMENT ──▶ PAYEE ──▶ EN_PREPARATION ──▶ PRETE
                                                        │
              RETIREE ◀── DISPONIBLE ◀── EXPEDIEE ◀─────┘
```

**Ce que ça évite.**

- Une marchandise acheminée jusqu'à Bangui, puis jamais retirée ni payée.
- Un encaissement en espèces dans chaque point de retrait, avec la
  réconciliation de caisse et les risques que ça implique.

**Ce que ça coûte — et c'est le vrai sujet technique.**
Le paiement mobile money est **asynchrone** : le client valide sur son
téléphone, et l'opérateur confirme par un *webhook*, quelques secondes ou
quelques minutes plus tard.

Il faut donc :

- réserver le stock (`quantite_reservee`) dès la commande, sans le décrémenter ;
- un travail périodique qui libère les réservations non confirmées et annule
  la commande ;
- stocker la `reference_transaction` de l'opérateur, sans laquelle aucun
  litige n'est arbitrable ;
- conserver les **échecs** (`tentative_paiement`), qui alimentent le score de
  risque.

> Ce point justifie à lui seul la distinction
> `quantite_disponible` / `quantite_reservee` du domaine Stock.

---

## D-07 — Compte obligatoire, pas d'achat invité

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Impossible de commander sans créer un compte.

**Ce que ça coûte.** Un frein à la conversion : une partie des visiteurs
abandonne devant le formulaire d'inscription. C'est mesurable et réel.

**Ce que ça apporte.**

- Tout le module de **surveillance** fonctionne (activité, score de risque,
  appareils connus) — il n'aurait aucun sens sur des acheteurs anonymes.
- Le **suivi de commande** et le **code de retrait** ont un destinataire fiable.
- Les **statistiques clients** (fidélisation, panier moyen, clients actifs)
  de la §20 de la spec deviennent calculables.
- La **négociation** suppose une relation identifiée dans la durée.

**Cohérence.** C'est le bon choix ici : GARAH n'est pas une boutique d'achat
impulsif, c'est une plateforme avec négociation, acheminement long et
récupération en point. La relation client est le cœur du métier.

---

## D-08 — Trois langues : français, anglais, sango

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Les interfaces sont disponibles en **français**, **anglais** et
**sango** (langue nationale de la République centrafricaine — cohérent avec
l'axe logistique Douala → Bangui).

**Le point qu'il ne faut pas rater :** il y a **deux** multilingues.

| | Texte d'**interface** | Texte de **contenu** |
|---|---|---|
| Exemple | « Ajouter au panier » | « Chemise Oxford », sa description |
| Écrit par | Le développeur | Le Responsable, dans le back-office |
| Vit dans | `fr.json`, `en.json`, `sg.json` | **La base de données** |
| Impact modèle | aucun | des tables de traduction |

**Conséquences sur le modèle** (domaine 12, chapitre 03) :

```text
langue                          référentiel des 3 langues, fr = par défaut
produit_traduction              (produit_id, langue) → nom, description
categorie_produit_traduction
attribut_traduction
valeur_attribut_traduction

client.langue                   préférence d'affichage
commande.langue                 PHOTO : la langue d'émission du document
```

**Règles retenues.**

- **Une table de traduction par entité**, jamais une table générique
  `(entite_type, entite_id, champ, langue, valeur)` — elle interdit toute clé
  étrangère et devient la table la plus lente de la base.
- Le **français est obligatoire**, les autres langues facultatives, avec un
  **repli** systématique vers le français (`COALESCE`).
- `commande.langue` est **figée** : une facture émise en sango reste en sango,
  même si le client change de langue plus tard.

**Ce que ça coûte.** Chaque écran de création de produit a trois onglets de
saisie. Et il faudra des traducteurs — le sango est une langue peu outillée,
sans traduction automatique fiable.

**Questions restées ouvertes** (voir chapitre 03, §19) : le catalogue est-il
réellement saisi dans les trois langues, ou seulement l'interface ?
Et le back-office est-il multilingue, ou français seulement ?
