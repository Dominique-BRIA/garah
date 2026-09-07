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

**Précisé par [D-09](#d-09--multilingue--linterface-seulement)** : seule
l'interface est traduite.

---

## D-09 — Multilingue : l'interface seulement

**Date :** 06/09/2026
**Statut :** ✅ actée — précise D-08

**Choix.** Les **trois applications**, back-office compris, sont traduites en
français, anglais et sango. Mais **seuls les textes d'interface** sont traduits.
Le **contenu du catalogue reste en français**.

**Conséquence sur le modèle : quatre tables disparaissent.**

```text
❌ produit_traduction
❌ categorie_produit_traduction
❌ attribut_traduction
❌ valeur_attribut_traduction

✅ langue            (3 lignes, permet de désactiver une langue sans déployer)
✅ client.langue     préférence d'affichage
✅ commande.langue   PHOTO, la langue d'émission du document
```

**Le problème que ça crée, et sa solution.**
Le back-office affiche les ~180 libellés de `cas_utilisation`, qui sont du
**contenu en base**. Traduire l'interface mais pas ces libellés donnerait un
écran de permissions en français au milieu d'une interface en sango.

La solution retenue : **le `code` sert de clé de traduction**.

```text
cas_utilisation.code = PRODUIT_PUBLIER
                            │
                            ▼
        fr.json  "perm.PRODUIT_PUBLIER": "Publier un produit"
        en.json  "perm.PRODUIT_PUBLIER": "Publish a product"
        sg.json  "perm.PRODUIT_PUBLIER": "Sïgïgî na produit"
                            │
        clé absente ────────┴───▶ repli sur cas_utilisation.nom (français)
```

> 🎯 **La règle générale que ça donne :**
> un contenu **fini et stable** (180 permissions, créées par le SuperAdmin,
> qui ne changent presque jamais) peut être traité comme de l'interface.
> Un contenu **ouvert et vivant** (des milliers de produits, créés chaque jour)
> doit être traduit en base — sinon il faudrait redéployer à chaque produit.

**Ce que ça coûte.** Un client anglophone ou centrafricain voit une interface
dans sa langue mais un catalogue en français. C'est un compromis assumé.

**Ce que ça évite.** Trois onglets de saisie sur chaque écran produit, et la
recherche de traducteurs sango pour des milliers de fiches — sachant qu'il
n'existe pas de traduction automatique fiable pour cette langue.

**Réversible ?** Oui. Le chapitre 03 (§15) décrit la migration à faire le jour
où le catalogue devra être traduit, et le piège à éviter ce jour-là
(la table de traduction générique).

---

## D-10 — Frais d'acheminement par point de récupération

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Le client paie des frais d'acheminement **qui dépendent du point de
récupération choisi**. Plus le point est loin, plus les frais sont élevés.

```text
lieu.frais_acheminement       le tarif courant du point
        │
        │ copié à la commande
        ▼
commande.montant_frais        FIGÉ — ne bougera plus jamais
```

**Pourquoi une simple colonne sur `lieu`, et pas une table datée comme
`regle_commission` ?**

Parce que la commande **fige** le montant. Le tarif n'a donc pas besoin d'être
historisé pour reconstituer le passé : le passé est déjà dans les commandes.

`regle_commission` est datée, elle, parce qu'on doit pouvoir **recalculer** une
commission a posteriori en cas de litige avec un marchand.

> 📌 **La règle qui en sort :**
> on historise une donnée de référence uniquement quand on doit pouvoir
> **rejouer** un calcul. Sinon, la photo dans la transaction suffit.

**Question laissée ouverte.** Les frais dépendent-ils aussi du **poids** ou du
**nombre de colis** ? Pour l'instant : un montant fixe par point.

---

## D-11 — TVA présente dès la v1, au taux 0

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** La TVA est **modélisée dès maintenant**, avec un taux à **0** partout.
Le jour où elle devra s'appliquer, il suffira de changer un taux — aucune
migration, aucune commande passée non calculable.

**Le point décisif : les prix sont stockés en TTC**, et la TVA en est *extraite*.

```text
❌ Prix stocké en HT
   Activer la TVA à 19,25 % fait BONDIR tous les prix affichés de 19,25 %.
   Personne n'a décidé ça.

✅ Prix stocké en TTC  (choix retenu)
   Activer la TVA ne change AUCUN prix affiché.
   Elle est extraite :  montant_tva = montant_ligne × taux ÷ (100 + taux)
```

**Conséquences sur le modèle.**

```text
produit.taux_tva              le taux courant, défaut 0
ligne_commande.taux_tva       PHOTO, figé à la commande
ligne_commande.montant_tva    extrait du montant TTC
commande.montant_tva          somme des lignes, informatif
```

La contrainte `montant_total = articles + frais − remise` reste **inchangée**,
puisque tout est déjà en TTC. C'est le signe que le sens de calcul est le bon.

> ⚠️ **Simplifications assumées, à revoir le jour où le taux sera activé :**
>
> - une remise devrait réduire la TVA proportionnellement ;
> - les frais d'acheminement peuvent être taxables ;
> - une facture conforme demande une numérotation séquentielle sans trou.
>
> À 0 %, aucune de ces trois n'a d'effet. Elles sont notées ici pour ne pas
> être découvertes le jour de l'activation.

**À vérifier auprès du métier avant activation :** GARAH est-elle assujettie
à la TVA au Cameroun ? Les ventes vers la Centrafrique sont-elles de l'export
(donc exonérées) ? Ces deux réponses décideront si le taux vit sur le produit,
sur la catégorie, ou sur le couple produit × destination.

---

## D-12 — Pas d'annulation client après paiement

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** Un client **ne peut pas** annuler une commande qu'il a déjà payée.

```text
EN_ATTENTE_PAIEMENT ──▶ ANNULEE     ✅ le client peut (rien n'est engagé)
PAYEE               ──▶ ANNULEE     ⚠️ ADMIN uniquement, motif obligatoire
                                       + remboursement
```

**Pourquoi.** Une fois le paiement confirmé, la commande entre dans la chaîne :
le stock est engagé, la préparation démarre, l'acheminement vers Bangui peut
partir. Laisser le client défaire tout ça d'un clic ferait supporter à
l'entreprise le coût d'une décision qu'il ne mesure pas.

**La voie de recours du client** reste la **réclamation** : un humain examine,
et un Admin peut annuler avec remboursement si c'est justifié.

> ⚠️ **Ce que ça impose à l'interface** — et c'est souvent oublié :
> le bouton « Annuler » doit disparaître dès le passage à `PAYEE`, **et** le
> client doit être averti **avant de payer** que le paiement est définitif.
> Une règle métier invisible à l'écran ne protège de rien : elle produit
> des réclamations.

---

## D-13 — Frais d'acheminement paramétrables, pas basés sur le poids

**Date :** 06/09/2026
**Statut :** ✅ actée — précise D-10

**Choix.** Les frais **ne dépendront pas du poids**.

**La raison est de terrain, et elle est excellente :** la plupart des marchands
**ne connaissent pas le poids unitaire** de leur marchandise. Une grille au
kilo produirait des données inventées, donc des frais faux, donc des litiges.

**Ce sur quoi les frais dépendront, à terme :**

- le **point de récupération** (déjà en place, D-10) ;
- des **paramètres du produit** (volume, fragilité, catégorie — à définir) ;
- la **quantité commandée**.

**En v1 :** un montant fixe par point de récupération, figé dans la commande.
Le modèle ne s'oppose pas à l'évolution, puisque `commande.montant_frais` est
une photo : le mode de calcul peut changer sans invalider les commandes passées.

> 📌 **La leçon de modélisation :**
> ne modélise jamais une grille tarifaire sur une donnée que **la personne qui
> saisit ne connaît pas**. Elle inventera une valeur, et tes calculs seront
> faux avec l'apparence d'être justes.

---

## D-14 — Hébergement : Render, Vercel, Neon, Backblaze B2

**Date :** 06/09/2026
**Statut :** ✅ actée — provisoire, VPS envisagé sous une semaine

**Choix.** Pour les tests et la première mise en ligne, tout en gratuit :

| Élément | Hébergeur |
|---|---|
| API Spring Boot | **Render** |
| Les 3 frontends Angular | **Vercel** (3 projets) |
| Base PostgreSQL | **Neon** |
| Fichiers (photos, vidéos, pièces jointes) | **Backblaze B2** |

**Ce que ça impose au code, dès maintenant.**

| Contrainte | Ce qu'il faut faire |
|---|---|
| Render n'a **pas de disque persistant** | Aucun fichier écrit localement. Tout va sur B2, dès le premier jour. |
| Render (offre gratuite) **s'endort** | Premier appel lent après inactivité. Ne pas confondre avec un bug. |
| Neon limite les **connexions** | Pool HikariCP réduit (5 à 10), jamais le défaut de 10 par instance. |
| Neon impose **SSL** | `sslmode=require` dans l'URL JDBC. |
| Vercel sert du **statique** | Les 3 apps Angular sont construites en fichiers ; pas de rendu serveur en v1. |
| Trois domaines distincts | **CORS** à configurer sérieusement côté API. |
| B2 est **compatible S3** | Utiliser le SDK S3 — le jour du VPS, basculer vers MinIO ne change que l'URL. |

> 🎯 **Le vrai enjeu de ces choix : la réversibilité.**
> Un VPS est prévu sous une semaine. Chacune de ces briques doit donc pouvoir
> être remplacée **sans réécrire le code** :
>
> ```text
> Neon      → PostgreSQL sur le VPS      une URL JDBC à changer
> B2        → MinIO sur le VPS           une URL et des clés à changer
> Render    → un conteneur sur le VPS    la même image
> Vercel    → Nginx sur le VPS           les mêmes fichiers statiques
> ```
>
> C'est possible **à condition** que rien ne soit jamais codé en dur :
> toute URL, clé ou identifiant vit dans une **variable d'environnement**.
> Le jour de la bascule doit être un changement de configuration, pas un chantier.

**Conséquence sur `media.url`.** La table ne stocke **pas** une URL complète
(`https://f003.backblazeb2.com/...`), mais une **clé d'objet**
(`produits/42/photo-1.jpg`). L'URL est reconstruite à l'affichage à partir
d'une variable d'environnement. Sinon, changer d'hébergeur de fichiers
obligerait à réécrire toutes les lignes de la table.

---

## D-15 — Vues de produits : détail 90 jours, agrégat pour toujours

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** `vue_produit` enregistre une ligne par consultation de fiche produit.
Ces lignes sont **purgées au bout de 90 jours**, après avoir été agrégées
chaque nuit dans `statistique_produit_jour`, conservée **indéfiniment**.

```text
vue_produit                détail, ~1 ligne par consultation   → purgé à 90 jours
statistique_produit_jour   1 ligne par produit et par jour     → gardé toujours
```

**Ce qu'on garde pour toujours.** Toutes les statistiques : nombre de vues par
jour, vues uniques, taux de mise au panier, produits tendance.

**Ce qu'on perd au-delà de 3 mois.** « Qui a vu quoi, à quelle heure, depuis
quelle adresse IP. » C'est-à-dire le détail nominatif — celui qui sert à
l'analyse de comportement individuel et au score de risque.

**Pourquoi ce compromis est le bon ici.**

| | |
|---|---|
| Volume | Une table de détail grossit sans limite ; l'agrégat fait une ligne par produit et par jour, c'est minuscule |
| Utilité | Personne n'analyse le parcours détaillé d'un client sur un an |
| Données personnelles | Garder des IP nominatives indéfiniment sans usage est une mauvaise pratique |

> ⚠️ **La purge doit être écrite en même temps que la collecte**, pas « plus
> tard ». Une table de détail sans purge est une bombe à retardement : on la
> découvre le jour où elle fait 40 Go et où la base ralentit.
>
> L'index `vue_produit_purge_idx` sur `date_heure` existe précisément pour que
> la purge soit rapide.

---

## D-16 — Permissions dans le jeton, fraîcheur limitée à 60 minutes

**Date :** 06/09/2026
**Statut :** ✅ actée — à revoir avant la mise en production

**Choix.** Le JWT transporte la liste des permissions de l'utilisateur.
Autoriser un appel ne demande donc **aucune requête en base**.

```text
Requête ──▶ signature vérifiée ──▶ permissions lues DANS le jeton ──▶ décision
                                   (zéro accès base)
```

**Ce que ça apporte.** Une API réellement sans état. C'est précieux avec Neon,
dont l'offre gratuite limite fortement le nombre de connexions (D-14) : sans
ça, chaque appel authentifié en consommerait une.

**Ce que ça coûte — et c'est le point à assumer.**

```text
10 h 00   Paul se connecte, son jeton contient PRIX_MODIFIER
10 h 15   un Admin lui retire PRIX_MODIFIER
10 h 16   Paul modifie un prix                        ✅ accepté
11 h 00   le jeton expire
11 h 01   Paul se reconnecte                          ❌ enfin refusé
```

**Un droit retiré met jusqu'à 60 minutes à s'appliquer.**

Ce qui reste **immédiat**, parce que ça ne dépend pas du jeton : rien. Un
compte bloqué garde lui aussi son jeton valide jusqu'à expiration.

**Les trois sorties possibles, le jour où ça deviendra gênant.**

| Piste | Effet | Coût |
|---|---|---|
| Réduire l'expiration à 15 min + jeton de rafraîchissement | Révocation en ≤ 15 min | Une route de plus, et du travail côté frontend |
| Relire les permissions en base à chaque appel | Révocation **immédiate** | Une requête par appel — le problème que Neon rend concret |
| Liste de révocation en mémoire ou Redis | Immédiat et ciblé | Une dépendance de plus, et l'API n'est plus sans état |

**Recommandation retenue pour plus tard :** la première. Elle garde l'API sans
état et divise le délai par quatre.

> ⚠️ **À trancher avant la mise en production**, en même temps que la question
> du stockage du jeton côté frontend (`localStorage` contre cookie `HttpOnly`).
> Les deux décisions sont liées : passer au cookie oblige à réactiver CSRF.

**Amorçage du premier compte.** Le SuperAdmin initial est créé au démarrage à
partir de `GARAH_SUPERADMIN_EMAIL` et `GARAH_SUPERADMIN_MOT_DE_PASSE`, et
uniquement s'il n'existe aucun SuperAdmin. Jamais par une migration : un mot de
passe versionné dans git est un mot de passe public, et la migration serait
rejouée à l'identique en production.

---

## D-17 — Le webhook de paiement ne croit jamais ce qu'on lui envoie

**Date :** 06/09/2026
**Statut :** ✅ actée

**Choix.** La route `POST /api/paiements/notifications/campay` ne retient
**qu'un seul champ** de la notification reçue : la référence de transaction.
Le statut, le montant et l'opérateur qu'elle annonce sont **ignorés**. L'état
réel est ensuite **redemandé à Campay**, sur une connexion que nous ouvrons,
avec nos identifiants.

```text
ce que la notification apporte   « la transaction ABC a bougé »   ← non fiable
ce qui décide                    GET /transaction/ABC/ chez Campay ← fiable
```

**Pourquoi, plutôt qu'une vérification de signature.**

Un webhook est nécessairement **public** : l'opérateur n'a pas de compte chez
nous et ne portera jamais de jeton. La protection habituelle est une signature
partagée. Toute la sécurité repose alors sur trois choses : l'exactitude de
l'algorithme, le secret, et le fait qu'aucun des deux n'a fuité.

Ici, **aucune des trois n'est nécessaire**. Un inconnu qui poste la référence
de son choix déclenche une question dont il ne contrôle pas la réponse. Il ne
peut ni déclarer une commande payée, ni en changer le montant.

Un contrôle de cohérence complète le dispositif : un succès annoncé pour un
montant **inférieur** à celui du paiement le fait échouer, jamais réussir.

**Ce que ça coûte.** Un aller-retour HTTP vers Campay à chaque notification.
Négligeable comparé au risque.

**Ce que ça évite.** Le pire scénario d'une plateforme de commerce : une
commande déclarée payée par quelqu'un qui n'a rien payé — avec sortie de stock,
écriture au grand livre marchand et acheminement vers Bangui à la clé.

> ⚠️ **La signature reste implémentée, mais désactivée par défaut**
> (`GARAH_CAMPAY_WEBHOOK_STRICT=false`). Son format exact n'est pas documenté
> publiquement par Campay. L'activer sans l'avoir vérifié transformerait une
> inconnue en **panne totale et silencieuse** : toutes les notifications
> rejetées, plus aucune commande payée, et aucune erreur visible côté client.
>
> Les journaux disent à chaque notification si elle se vérifie. Passer à `true`
> uniquement quand ils affichent `signature=ok`.

**La règle générale qui en sort.**
> Quand une donnée arrive par un canal qu'on ne contrôle pas, ne l'utilise
> jamais comme **information**. Utilise-la comme **signal** — puis va chercher
> l'information à la source.

---

## D-18 — Les traitements périodiques supposent une seule instance

**Date :** 06/09/2026
**Statut :** ✅ actée — à revoir avant toute mise à l'échelle

**Choix.** Cinq traitements tournent en tâche de fond, sans verrou partagé :

| Traitement | Rythme | Sans lui |
|---|---|---|
| réconciliation des paiements | 2 min | un webhook perdu = un client débité dont la commande n'est jamais payée |
| libération des commandes impayées | 10 min | le stock disponible fond (D-06) |
| expiration des propositions de prix | 1 h | un prix négocié il y a six mois reste acceptable |
| agrégation des statistiques | 1 h du matin | `statistique_produit_jour` reste vide (D-15) |
| purge du détail des vues | 2 h du matin | `vue_produit` grossit sans fin (D-15) |

**Le point à assumer.** Ces cinq méthodes existaient, écrites et testées, mais
**aucune n'était appelée** : le projet n'avait ni `@EnableScheduling` ni
`@Scheduled`. Ce n'était pas une panne visible, c'était pire — un système qui a
l'air de marcher.

**Ce que ça suppose.** Un seul processus. C'est vrai sur l'offre gratuite de
Render (D-14). À la seconde instance, deux serveurs agrégeraient les mêmes
statistiques deux fois.

> ⚠️ **Le verrou partagé est à poser AVANT d'ajouter une instance, pas après.**
> Le double comptage ne produit aucune erreur, seulement des chiffres faux —
> et on ne s'en aperçoit qu'en comparant deux rapports.
>
> ShedLock sur une table PostgreSQL est le plus simple : la base est déjà là.

**Fuseau horaire.** Les tâches de nuit sont ancrées sur `Africa/Douala`, pas
sur celui du serveur (`TZ=UTC` sur Render). Sans zone explicite, « 2 h du
matin » tomberait à 3 h locales.

---

## D-19 — Jeton d'accès court + jeton de rafraîchissement en cookie HttpOnly

**Date :** 06/09/2026
**Statut :** ✅ actée — **remplace la partie « expiration » de [D-16](#d-16--permissions-dans-le-jeton-fraîcheur-limitée-à-60-minutes)**

**Choix.** Deux jetons, aux propriétés opposées et complémentaires.

| | Durée | Transport | Révocable ? |
|---|---|---|---|
| **accès** | 15 min | Bearer, **en mémoire JS** | ❌ jamais — c'est la nature d'un JWT |
| **rafraîchissement** | 14 j | cookie `HttpOnly` | ✅ ligne en base |

**Ce que ça règle.** D-16 assumait deux défauts, écrits noir sur blanc :
un droit retiré mettait jusqu'à 60 minutes à s'appliquer, et *« un compte
bloqué garde lui aussi son jeton valide jusqu'à expiration »*.

À chaque rafraîchissement, l'utilisateur et ses permissions sont **relus en
base**. Le délai tombe donc à 15 minutes au pire — et à **zéro** pour une
déconnexion, qui révoque la session côté serveur.

```text
D-16     droit retiré → effectif sous 60 min
         compte bloqué → effectif sous 60 min
         déconnexion   → sans effet réel

D-19     droit retiré → effectif sous 15 min
         compte bloqué → effectif sous 15 min
         déconnexion   → IMMÉDIATE
```

**Pourquoi le cookie plutôt que `localStorage`.** Le jeton de rafraîchissement
vaut quatorze jours d'accès. Dans `localStorage`, il est lisible par n'importe
quel JavaScript de la page — donc par la moindre faille XSS, y compris dans une
dépendance npm. En `HttpOnly`, il est hors de portée du JavaScript.

Le jeton d'accès, lui, reste en Bearer et **en mémoire** : 15 minutes, et il
disparaît au rechargement de l'onglet, où le cookie le régénère.

**Rotation et détection de vol.** Un jeton de rafraîchissement ne sert
qu'**une fois**. S'il revient après avoir été consommé, il n'y a que deux
explications, et aucune n'est bénigne : le voleur s'en sert après la victime,
ou l'inverse. Impossible de savoir lequel appelle — on révoque donc la
**famille entière**. Les deux sont déconnectés, et le légitime se reconnecte
avec son mot de passe, que le voleur n'a pas.

> 🎯 C'est la **rotation** qui rend le vol visible. La révocation n'est que la
> réaction. Sans rotation, un jeton volé reste valable quatorze jours sans que
> rien ne permette de s'en apercevoir.

**Ce que ça coûte — et c'est le point à assumer.**

- Une lecture en base toutes les 15 minutes par session. C'est exactement ce
  que D-16 voulait éviter avec Neon… mais **une requête par quart d'heure**,
  et non une par appel : trois ordres de grandeur en dessous de la piste
  « relire les permissions à chaque appel » que D-16 écartait.
- Une table qui grossit vite (un jeton par connexion, plus un par rotation) →
  purge quotidienne dès le premier jour (D-18).
- **CSRF réactivé** sur les deux routes à cookie. Voir ci-dessous.

**⚠️ La contrainte que l'hébergement impose : `SameSite=None`.**

```text
frontends   garah-client.vercel.app
API         garah-api.onrender.com     ← autre SITE, pas seulement autre origine
```

Pour le navigateur, `vercel.app` et `onrender.com` sont deux sites différents.
Avec `SameSite=Strict` ou même `Lax`, le cookie ne serait **jamais** envoyé :
le rafraîchissement échouerait systématiquement, et le symptôme serait une
déconnexion toutes les 15 minutes **sans aucune erreur serveur**.

`None` est donc obligatoire ici — et il impose `Secure`. La contrepartie est
que le cookie part aussi sur les requêtes inter-sites, d'où une protection CSRF
sur `/api/auth/rafraichir` et `/api/auth/deconnexion`, et **là seulement**.

**⚠️ Correction apportée à la mise en service.** Ce paragraphe annonçait
d'abord un jeton `XSRF-TOKEN` (le « double-submit cookie » de Spring). **Il est
inapplicable ici**, et l'essai réel l'a montré : un cookie n'est lisible en
JavaScript que depuis SON domaine. Angular, servi par Vercel, ne peut pas lire
un cookie posé par Render — il n'aurait jamais rien à renvoyer, et chaque
rafraîchissement aurait répondu 403.

La protection retenue est un **en-tête personnalisé** (`X-Garah-Client`,
`FiltreOrigineCsrf`) :

1. un en-tête non standard force un **préflight** `OPTIONS` ;
2. ce préflight est arbitré par CORS, qui n'autorise que `GARAH_CORS_ORIGINS` ;
3. une page tierce échoue au préflight — **sa requête n'est jamais envoyée**.

Un formulaire HTML ne peut poser aucun en-tête : le vecteur CSRF historique est
fermé d'office. La sécurité repose donc sur **CORS**, pas sur le secret de
l'en-tête — d'où l'interdiction du joker `*` dans les origines.

> 🎯 **Une raison de plus de prendre un vrai domaine tôt.** Avec
> `api.garah.cm` et `app.garah.cm`, `SameSite=Lax` redevient possible : la
> protection CSRF cesse d'être portée par un jeton et devient **structurelle**.
> C'est réglable par `GARAH_COOKIE_SAMESITE`, sans toucher au code.

**Sur le hachage du jeton en base.** SHA-256, **pas BCrypt** — contre-intuitif
après le chapitre 08. BCrypt est lent *exprès*, parce qu'un mot de passe humain
a peu d'entropie et doit résister à un dictionnaire. Ce jeton est 256 bits
tirés au sort : aucun dictionnaire n'existe. Le ralentir ne protégerait rien et
coûterait 250 ms à chaque rafraîchissement, toutes les 15 minutes, pour chaque
utilisateur connecté.

> **La règle :** BCrypt pour ce qu'un *humain* a choisi, hachage rapide pour ce
> que la *machine* a tiré au sort.

**Ce qui reste ouvert.** Le délai de 15 minutes est un compromis, pas une
garantie : un droit retiré s'applique toujours avec du retard. Pour une
révocation strictement immédiate, il faudrait la troisième piste de D-16 (liste
de révocation en mémoire ou Redis) — et l'API ne serait plus sans état.

---

## D-20 — Stockage des fichiers : le choix est repoussé, pas tranché

**Date :** 06/09/2026
**Statut :** ✅ actée — précise [D-14](#d-14--hébergement--render-vercel-neon-backblaze-b2)

**Le fait découvert.** D-14 retenait Backblaze B2 « en gratuit ». C'est vrai pour
le stockage, mais **pas pour un bucket public** : Backblaze exige un moyen de
paiement enregistré (« a small fee that is credited to your account balance »).
Cloudflare R2 impose la même chose pour activer le service, même dans le palier
gratuit.

Or **aucun produit ne peut être publié sans photo** (invariant I-12). Le
stockage n'est donc pas un accessoire : il bloque le catalogue entier.

**Choix.** On ne tranche pas maintenant. Quatre chemins restent ouverts, et le
code n'en connaît aucun.

| | Carte ? | Gratuit | Egress | Usage |
|---|---|---|---|---|
| **MinIO local** | non | — | — | développement |
| **Supabase** | **non** | 1 Go | 5 Go | première mise en ligne |
| **Cloudflare R2** | oui | 10 Go | **0 €** | production visée |
| **Backblaze B2** | oui * | 10 Go | payant | repli |

\* frais recrédités, mais carte exigée pour un bucket public.

**🎯 Le critère qui décidera : l'egress, pas le stockage.** Un catalogue sert
les mêmes images des milliers de fois — c'est de la bande passante *sortante*.
R2 la facture zéro, B2 la facture au-delà de 3× le stockage. Un catalogue qui
marche coûtera en trafic bien avant de coûter en disque. **R2 est donc la cible,
dès qu'une carte et un domaine sont disponibles.**

**Ce que ça coûte.** Rien, et c'est le point. Tout passe par le SDK S3 avec un
endpoint configurable : changer de fournisseur, c'est changer cinq variables
d'environnement et redémarrer. C'est la première fois que la réversibilité
exigée par D-14 sert concrètement — et elle transforme une décision bloquante
en décision reportable.

> ⚠️ **Piège R2** : le sous-domaine `r2.dev` est explicitement réservé au
> développement (*« rate-limited and should only be used for development
> purposes »*). En production, R2 exige un **domaine personnalisé** hébergé chez
> Cloudflare — le même domaine qui permettrait `SameSite=Lax` (D-19). Deux
> raisons convergentes d'en prendre un tôt.

> ⚠️ **Piège commun aux quatre** : `GARAH_S3_ENDPOINT` (écriture, API
> authentifiée) et `GARAH_MEDIA_BASE_URL` (lecture publique par le navigateur)
> ne sont **jamais** la même URL. Les confondre donne un catalogue dont toutes
> les images répondent 401.

---

## D-21 — Bucket privé et URL signées

**Date :** 06/09/2026
**Statut :** ✅ actée — précise [D-20](#d-20--stockage-des-fichiers--le-choix-est-repoussé-pas-tranché)

**Le fait.** Backblaze exige un moyen de paiement pour créer un bucket
**public**. Sans carte bancaire, le bucket `garah-medias` reste **privé**.

**Ce que ça casse, et pourquoi c'est invisible.** Un bucket privé accepte
parfaitement les téléversements : l'API S3 est authentifiée. Le back-office
fonctionne donc de bout en bout — on téléverse, on voit les miniatures, on
publie. **Ce n'est qu'au premier visiteur que toutes les images répondent
401**, et aucun journal serveur ne le signale.

Vérifié pour de vrai, pas déduit :

```text
INFO  Fichier depose : verification/e12daff4-….png (67 octets)   ✅ dépôt
[401 sur https://f004.backblazeb2.com/file/garah-medias/…]        ❌ lecture
```

**Choix.** L'API renvoie des **URL signées** (AWS SigV4), valables sept jours.

**⚠️ La conséquence structurelle, et c'est la vraie.** Le frontend recevait
jusqu'ici une **clé** (`produits/42/a3f9.jpg`) et la préfixait lui-même avec
`baseUrlMedias`. **Ce modèle est mort** : construire l'adresse demande une
signature, donc la clé secrète — qu'un frontend ne doit évidemment jamais
détenir.

```text
avant   API → clé          frontend → base + clé = URL
après   API → URL complète  frontend → affiche, point
```

`DetailProduit.MediaResume` et `ResumeProduit` portent donc un champ `url` en
plus de `cleObjet`, et `/api/configuration` annonce `urlsMediasSignees` pour
que les trois applications sachent à quoi s'en tenir.

**Ce que ça coûte.**

- Une URL de média **expire** au bout de sept jours (maximum imposé par SigV4).
  Une page HTML archivée plus longtemps affichera des images mortes.
- Le SEO en pâtit : `garah-web` existe pour le référencement (D-03), et une
  image dont l'adresse change n'est pas indexée durablement.
- Aucun CDN ne peut être placé devant efficacement.

**🎯 La mitigation : les URL sont mises en cache et réutilisées.**

Signer produit une chaîne différente à chaque appel. Sans cache, la même photo
changerait d'adresse à chaque affichage — et le catalogue entier serait
retéléchargé à chaque visite. On conserve donc la même URL signée tant qu'il
lui reste plus de 24 h, ce qui garantit qu'une adresse remise à un navigateur
est **toujours valable au moins une journée**.

> C'est le genre de défaut qu'une relecture ne voit pas : les images
> s'afficheraient parfaitement. D'où `SignataireS3Test`, dont le test central
> est « deux appels sur la même clé renvoient la MÊME URL ».

**Réversible en une variable.** Le jour où le bucket devient public — carte
enregistrée, ou passage à Supabase, ou VPS avec MinIO :

```bash
GARAH_S3_URLS_SIGNEES=false
```

Rien d'autre. Le champ `url` reste renseigné, il contient simplement une
concaténation au lieu d'une signature. Aucun frontend n'a à changer.

> ⚠️ **À faire dès que possible.** Ce n'est pas une architecture cible, c'est
> un contournement de contrainte financière. Il fonctionne, il est testé
> (`StockageReelTest` fait un aller-retour réel contre Backblaze), mais le
> bucket public reste la bonne réponse pour du contenu public.

---

## D-22 — Un Worker Cloudflare devant l'API, pour contourner le filtrage Orange

**Date :** 06/09/2026
**Statut :** ⛔ remplacée par D-28 — le Worker a été retiré le 08/09/2026

**Le fait, mesuré.** Depuis une connexion Orange Cameroun,
`garah-api.onrender.com` est inaccessible :

```text
DNS      216.24.57.15, 216.24.57.7   ✅ résolution correcte
TCP 443  établi                       ✅ la connexion s'ouvre
TLS      connection closed on send    ❌ poignée de main coupée
```

Le TCP passe, le TLS est coupé net : signature d'un **filtrage par SNI**.
L'opérateur lit le nom de domaine dans le premier paquet TLS et referme.

**Pourquoi ce n'est pas un détail technique.** GARAH vend à Douala et Bangui.
Orange est l'un des deux opérateurs majeurs du marché visé. Une vitrine que
la moitié des acheteurs ne peut pas ouvrir n'est pas une vitrine — et le
symptôme serait le plus déroutant qui soit : « le site ne marche pas » chez
certains, parfaitement chez d'autres.

**Choix.** Un **Cloudflare Worker** en proxy inverse, sur le sous-domaine
gratuit `*.workers.dev`.

```text
navigateur  ──▶  garah-api.<compte>.workers.dev   ← Cloudflare, non filtré
                     │
                     ▼
                 garah-api.onrender.com           ← jamais vu du navigateur
```

**Pourquoi un proxy et pas une redirection.** Un `301` vers `onrender.com`
ferait ouvrir au navigateur une connexion vers ce nom-là, qu'Orange couperait
comme avant. Il faut que Cloudflare aille chercher l'amont **lui-même**.

**Pourquoi pas un domaine personnalisé.** Il en faudrait un, et il n'y en a
pas encore. `workers.dev` est gratuit et immédiat.

**⚠️ Quatre points que le proxy doit respecter**, chacun corrigeant une panne
silencieuse :

| | Sans quoi |
|---|---|
| `X-Forwarded-For` = `CF-Connecting-IP` | tous les événements de sécurité portent l'IP de Cloudflare — le score de risque devient aveugle |
| `Set-Cookie` transmis intact | plusieurs en-têtes fusionnés = session impossible à prolonger (D-19) |
| Aucun cache (`cacheTtl: 0`) | Cloudflare est un cache **partagé** : `/api/commandes/miennes` servi au visiteur suivant |
| `redirect: "manual"` | un `Location` vers `onrender.com` renverrait le navigateur dans le filtrage |

**Ce que ça ne règle pas.** Le **démarrage à froid de 197 secondes**.
Cloudflare proxie, il ne réveille pas Render : l'instance gratuite s'endort
toujours après 15 minutes, et le premier visiteur attend toujours trois
minutes. Pour une vitrine, c'est équivalent à être hors ligne.

**Ce que ça coûte aussi.** L'API et les frontends restent sur deux **sites**
différents (`workers.dev` et `vercel.app`) : `SameSite=None` reste obligatoire,
et la protection CSRF continue de reposer sur l'en-tête `X-Garah-Client`
(D-19) plutôt que d'être structurelle.

> 🎯 **Ce contournement renforce l'argument du VPS.** Un domaine à soi
> (`api.garah.cm`) réglerait d'un coup : le filtrage Orange, la mise en veille,
> `SameSite=Lax` (D-19) et le domaine personnalisé qu'exige Cloudflare R2
> (D-20). Quatre décisions en attente se referment avec un seul domaine.
>
> D-14 annonçait un VPS « sous une semaine ». Ce n'est plus une optimisation,
> c'est la condition pour que le produit soit joignable par ses clients.

**Réversible.** Le fichier `deploiement/proxy-cloudflare.js` ne contient qu'une
constante à changer le jour de la bascule.

---

## D-23 — Confirmation de l'adresse e-mail, barrière posée à la commande

**Date :** 06/09/2026
**Statut :** ✅ actée

**Le manque.** L'inscription créait un compte sans aucune vérification :
n'importe qui pouvait s'inscrire avec l'adresse d'un autre.

**Pourquoi c'est un sujet métier, pas seulement de sécurité.** Le suivi de
commande, le **code de retrait** et les avis d'acheminement partent tous à
cette adresse (D-07). Une adresse fausse, et la marchandise arrive à Bangui
sans que personne ne puisse être prévenu. Le défaut ne se verrait pas à
l'inscription — il se verrait **devant le point de récupération**.

**Choix.** Un jeton de 256 bits, à usage unique, valable 48 heures, envoyé par
e-mail. Stocké en **empreinte SHA-256**, comme le jeton de rafraîchissement
(D-19) et pour la même raison : qui lirait la table pourrait sinon confirmer
l'adresse de n'importe qui.

**🎯 Où tombe la barrière — et c'est la vraie décision.**

```text
se connecter, parcourir, remplir un panier   ✅ autorisé
passer une commande                          ❌ refusé
```

Bloquer la **connexion** serait plus strict et plus mauvais : le client ne
pourrait même pas demander un nouveau lien, et le premier e-mail perdu
fermerait le compte définitivement. On barre au dernier moment utile, celui où
l'adresse commence réellement à servir.

**⚠️ Trois pièges traités, dont un non évident.**

| | Sans quoi |
|---|---|
| L'adresse visée est **figée** à l'émission | je m'inscris, je reçois le lien, je change mon e-mail pour celui d'un autre, je clique — et je « confirme » une adresse que je ne contrôle pas |
| Émettre un lien **invalide le précédent** | trois renvois laissent trois liens actifs, dont deux dans des boîtes qu'on ne contrôle plus |
| L'e-mail part **hors transaction** | un SMTP lent tient une connexion Neon ouverte ; un SMTP en panne annule le compte ; et le jeton arriverait avant d'exister en base |

**SMTP, pas l'API d'un fournisseur.** Resend ou SendGrid imposeraient leur SDK
et leur format dans notre code. SMTP est un protocole : passer de Brevo à
Mailjet, au VPS ou à Gmail, c'est changer un hôte et des identifiants. C'est la
réversibilité de D-14 appliquée à l'e-mail.

**Sans SMTP configuré, l'application démarre quand même** et écrit les liens
dans les journaux. Le bean `JavaMailSender` est optionnel — l'injecter
directement rendait le SMTP obligatoire et empêchait tout démarrage, ce que les
tests ont révélé. Comme pour S3 et Campay : l'absence d'une dépendance externe
dégrade une fonction, elle n'abat pas le service.

> ⚠️ Le repli par les journaux ne doit **jamais** servir en production : un
> lien de confirmation dans un journal est lisible par qui accède aux journaux.

---

## D-24 — Limitation de débit sur les routes ouvertes

**Date :** 06/09/2026
**Statut :** ✅ actée — en mémoire, donc valable pour une seule instance

**Le manque.** `POST /api/auth/inscription` crée un compte sans vérification
d'identité et coûte **250 ms de BCrypt** par appel. Un script y crée dix mille
comptes en une minute — et met au passage l'instance Render gratuite à genoux,
puisque 250 ms de CPU répétés saturent le seul cœur disponible.

**Choix.** Un compteur par fenêtre, en mémoire, sur les trois seules routes
qu'un inconnu peut marteler :

| Route | Plafond | Pourquoi |
|---|---|---|
| `/api/auth/inscription` | 5 / heure | une personne réelle en fait une |
| `/api/auth/connexion` | 10 / 5 min | assez pour chercher son mot de passe, pas pour un dictionnaire |
| `/api/auth/verification/renvoi` | 3 / heure | chaque appel envoie un e-mail, à notre nom et sur notre quota |

Le reste de l'API exige un jeton : en abuser suppose un compte, donc une
identité, donc la possibilité de le bloquer.

**⚠️ Par adresse IP, avec ce que ça implique.** Derrière un cybercafé de
Douala ou un opérateur qui masque ses abonnés, **plusieurs personnes partagent
un compteur**. Les plafonds sont donc larges : gêner un client légitime coûte
une vente, alors que ralentir un attaquant de 10 à 5 tentatives par minute
suffit à rendre son attaque inutile.

Ce n'est pas une protection contre un attaquant disposant de milliers
d'adresses. C'est une protection contre le script trivial — l'écrasante
majorité de ce qui frappe une API publique.

**Volontairement pas par adresse e-mail** sur la connexion : compter par compte
permettrait de verrouiller n'importe qui en échouant à sa place. La protection
deviendrait l'attaque.

**Un second plafond, par compte**, garde les renvois d'e-mail à 3 par heure :
un attaquant qui change d'IP ne doit pas pouvoir faire pleuvoir des messages
sur une même victime.

> ⚠️ **Le compteur vit dans le processus.** Avec deux instances, chacune
> autoriserait le quota complet — la limite serait doublée sans que rien ne le
> signale. C'est le même avertissement que D-18 pour les traitements
> périodiques, et il se paie de la même façon : silencieusement. Le jour de la
> mise à l'échelle, un compteur partagé (Redis, ou une table PostgreSQL
> puisque la base est là) devient obligatoire.

**Le limiteur est plafonné à 50 000 clés.** Sans cela il deviendrait lui-même
l'attaque : une requête par IP falsifiée ferait grossir la table jusqu'à
l'`OutOfMemoryError`. On aurait remplacé un déni de service par un autre, en
croyant se protéger.

---

## D-25 — Changer son mot de passe coupe TOUTES les sessions, la sienne comprise

**Date :** 07/09/2026
**Statut :** ✅ actée

**Le manque.** Il n'existait aucun moyen de changer son mot de passe. L'API
n'avait que `GET /api/auth/moi`, qui rend ce que le **jeton** porte — ni
e-mail, ni téléphone, ni dates. Un compte compromis ne pouvait donc être
repris par personne d'autre qu'un administrateur, à la main, en base.

**Choix.** Trois routes sur son propre compte, et un changement de mot de passe
qui **révoque toutes les sessions ouvertes**, y compris celle qui le demande.

```text
GET   /api/profil                 lire ses informations
PATCH /api/profil                 nom, prénom, téléphone, langue
POST  /api/profil/mot-de-passe    changer son mot de passe → tout est coupé
```

**Pourquoi toutes, et pas « toutes sauf la mienne ».** Le cas d'usage principal
d'un changement de mot de passe est le **soupçon de vol**. Épargner la session
courante reviendrait à faire confiance à l'idée qu'elle est bien celle du
propriétaire — or c'est exactement ce dont on doute. Ne rien révoquer serait
pire : le voleur garde son jeton de rafraîchissement **quatorze jours** (D-19),
et le propriétaire croit s'être protégé.

**Ce que ça coûte.** Celui qui change son mot de passe est déconnecté et doit se
reconnecter. L'interface doit donc l'annoncer **avant** l'action, et rediriger
elle-même avec un message : une déconnexion qu'on n'a pas prévenue passe pour
une panne, même quand elle est le comportement voulu.

> ⚠️ **Le jeton d'accès, lui, reste valide jusqu'à 15 minutes.** Un JWT ne se
> révoque pas, c'est sa définition (D-19). C'est pourquoi le frontend termine
> la session **lui-même** au lieu d'attendre le premier 401 : sinon
> l'application continue de fonctionner un quart d'heure, puis déconnecte sans
> rapport visible avec ce qu'on venait de faire.

**Un motif de révocation a été ajouté** (`MOT_DE_PASSE_CHANGE`, migration V24)
plutôt que de réutiliser `COMPTE_FERME`. Un journal d'audit ne se réécrit
jamais : confondre les deux ferait lire, six mois plus tard, qu'un compte a été
fermé alors que son propriétaire avait simplement changé son mot de passe.

> ⚠️ Le motif est verrouillé par une contrainte `CHECK` (V21). Ajouter une
> valeur à l'énumération Java **sans** la migration ne casse pas la
> compilation : la panne arrive à l'exécution, au premier changement.

**L'adresse e-mail n'est pas modifiable ici**, et ce n'est pas un oubli. Elle
est l'identifiant de connexion et la destination des liens de confirmation
(D-23). La changer suppose de vérifier qu'elle est libre, de repasser
`emailVerifie` à faux, de réémettre un lien, et de décider ce qui advient si le
propriétaire ne l'ouvre jamais — avec, au bout, un compte dont l'adresse ne
reçoit plus rien. C'est un parcours à part entière, pas un champ de formulaire.

**Aucune de ces routes ne prend d'identifiant.** Le compte visé est toujours
celui du jeton : pas de `/api/profil/{id}`, pas d'`utilisateurId` dans le
corps. L'élévation de privilège n'est donc pas une vérification qu'on pourrait
oublier — elle est impossible par la forme des routes. C'est aussi pourquoi ce
contrôleur ne porte aucun `@PreAuthorize` : il n'y a rien à autoriser au-delà
d'être connecté.

---

## D-26 — Les avatars par défaut sont engendrés chez un tiers

**Date :** 07/09/2026
**Statut :** ✅ actée — remplace la position tenue jusqu'ici par `gu-avatar`

**Ce qui change.** `gu-avatar` affichait deux lettres et une couleur dérivées du
nom, et sa propre documentation défendait ce choix contre toute image distante :
« sur une connexion mobile camerounaise, quarante avatars dans une liste font
quarante requêtes ». On adopte pourtant **DiceBear**, comme le projet de
référence, parce qu'un back-office où chaque personne a un visage se lit plus
vite qu'une colonne de monogrammes.

**Choix.** Une cascade à trois niveaux, où chaque cran rattrape le précédent :

```text
1. la vraie photo      si elle existe            ← toujours prioritaire
2. l'avatar DiceBear   engendré depuis le nom    ← demande le réseau
3. les initiales       deux lettres, une couleur ← zéro octet
```

**🎯 Les initiales sont peintes AVANT l'image, pas à sa place.** C'est ce qui
rend la décision tenable : la liste s'affiche complète immédiatement, l'image
recouvre les initiales quand elle arrive, et **si elle n'arrive jamais** — hors
ligne, service en panne, opérateur qui filtre — elles restent. L'écran est
correct dans les trois cas, et à aucun moment il n'est vide.
`loading="lazy"` complète le dispositif : les avatars sous la ligne de
flottaison ne sont même pas demandés.

**Ce que ça coûte.**

- **Le nom part chez un tiers**, puisqu'il sert de graine. Ce n'est pas
  sensible, mais c'est réel : noms de marchands et d'administrateurs transitent
  par `dicebear.com` à chaque affichage non mis en cache. **Ne jamais y mettre
  autre chose** — pas d'e-mail, pas de téléphone, pas d'identifiant interne.
- Une dépendance réseau de plus sur un chemin d'affichage.

**Ce que ça évite.** Aucun fichier engendré à l'inscription, donc rien à
stocker, rien à servir, et un avatar qui suit un changement de nom au lieu de
rester figé.

**La version est épinglée** (`7.x`, celle de la référence). Ne pas écrire « la
dernière » : une collection renommée entre deux majeures ne casse pas
bruyamment, elle renvoie une image vide — et l'écran se remplit de pastilles
blanches sans qu'aucune erreur n'apparaisse.

**L'hôte est une constante**, parce que DiceBear s'auto-héberge. Le jour où il
devient lent, payant, ou inatteignable depuis le Cameroun — comme l'est déjà
Render (D-22) — c'est une ligne à changer, et rien d'autre.

---

## D-27 — Le backend passe sur Azure App Service

**Date :** 07/09/2026
**Statut :** ✅ actée — remplace la partie « API » de D-14. **D-22 reste en
vigueur** jusqu'à preuve du contraire (voir plus bas).

**Choix.** Le backend quitte Render pour **Azure App Service B1** (France
Central), en conteneur, sur un abonnement *Azure for Students*.

**⚠️ L'ordre compte, et il a été choisi.** On déploie sur Azure **d'abord**,
on ne touche à rien d'autre. Le Worker Cloudflare reste devant, les frontends
continuent de l'appeler, et les visiteurs ne voient aucun changement tant que
la nouvelle instance n'a pas fait ses preuves.

Faire les deux d'un coup — changer d'hébergeur *et* changer le chemin réseau —
donnerait, en cas de panne, deux causes possibles et aucun moyen de les
départager. C'est la même règle que pour `bootstrap-mode` plus bas : un
changement à la fois, sinon le diagnostic devient une devinette.

**Ce que Render nous coûtait.** Trois problèmes, tous dus aux 512 Mo :

- l'application ne démarrait qu'au prix d'un réglage risqué
  (`bootstrap-mode: lazy`), qui déplace l'échec d'une requête invalide du
  démarrage vers la production ;
- l'instance s'endormait, et le réveil prenait **120 secondes** — payées par
  le premier visiteur ;
- le nom d'hôte `garah-api.onrender.com` était filtré par Orange Cameroun, ce
  qui avait imposé le Worker.

Azure B1 donne **1,75 Go** et **Always On**. Les deux premiers points
disparaissent ; le troisième est le pari de cette décision.

**Pourquoi un conteneur et pas la pile Java SE.** App Service ne propose pas
Java 24. Ce n'est pas un choix, c'est la seule voie — et elle ne coûte rien,
puisque c'est le `Dockerfile` de Render, inchangé. La réversibilité exigée par
D-14 vient de servir pour de bon.

**Le sort du Worker, et comment il se décidera.** Dominique estime que le
domaine Azure n'est pas filtré, puisqu'il a pu ouvrir le portail depuis une
connexion Orange.

> ⚠️ **L'argument n'est pas une preuve.** Le filtrage décrit par D-22 lisait le
> **nom d'hôte** dans la poignée de main TLS — `garah-api.onrender.com`
> précisément. `portal.azure.com` et `*.azurewebsites.net` sont deux domaines
> différents : ouvrir le premier ne dit rien du second.
>
> Le seul test qui tranche, une fois l'API en ligne, **depuis une connexion
> Orange Cameroun** :
>
> ```bash
> curl -s -o /dev/null -w "%{http_code}\n" https://<hôte-azure>/api/sante
> ```
>
> `200` → le Worker peut tomber, et D-22 sera marquée « remplacée par D-27 ».
> Connexion coupée → le Worker reste, et il aura justifié son existence une
> seconde fois.

Tant que ce test n'a pas été fait, **le Worker reste devant** et les frontends
continuent de l'appeler. Il ne coûte rien : plan gratuit, 100 000 requêtes par
jour.

**Ce que ça coûte, et c'est le vrai risque.** Le crédit étudiant est de
**100 $ sur 12 mois**. Le seul plan B1 consomme environ 13 $/mois : le crédit
s'épuise en **7 à 8 mois**, après quoi l'application s'arrête. Ce n'est pas
une offre gratuite, c'est une **échéance**.

D'où deux règles qui découlent directement :

- **La base reste sur Neon.** Azure PostgreSQL est gratuit douze mois puis
  facturé ; Neon est gratuit sans terme. Migrer la base consommerait le crédit
  deux fois plus vite pour remplacer ce qui marche.
- **Rien d'autre ne va sur Azure** sans se demander ce qu'il retire de mois à
  l'application.

**Ce que ça permet de réparer.** `bootstrap-mode: lazy` a été imposé par les
512 Mo, et son coût est écrit : une requête `@Query` cassée n'échoue plus au
démarrage mais devant un utilisateur. Avec 1,75 Go, ce filet peut être
retendu — **après** un premier déploiement réussi, jamais en même temps.

**Ce qu'on perd.** L'hébergement du backend cesse d'être gratuit et devient
daté. Render restait médiocre mais perpétuel ; Azure est confortable et
temporaire. Le jour où le crédit s'épuise, il faudra payer, redevenir
étudiant, ou revenir en arrière — et ce jour-là, `deploiement/azure.md` et
cette entrée disent où était le point de départ.

---

## D-28 — Le Worker Cloudflare est retiré, l'API se joint en direct

**Date :** 08/09/2026
**Statut :** ✅ actée — **remplace D-22**

**Choix.** Les frontends appellent directement
`garah-api-…​.azurewebsites.net`. Le Worker Cloudflare et son `wrangler.toml`
sont supprimés du dépôt.

**Pourquoi D-22 n'a plus d'objet.** Le Worker existait pour une raison
précise et mesurée : depuis une connexion Orange Cameroun, la poignée de main
TLS vers `garah-api.onrender.com` était **coupée net**. Un filtrage par SNI,
c'est-à-dire sur **ce nom d'hôte-là** — pas sur Render, pas sur une catégorie
d'hébergeurs. En quittant ce nom d'hôte, on quitte le filtre.

**Ce qui n'est pas prouvé, et qu'il faut dire.** Avoir ouvert `portal.azure.com`
ne démontre rien sur `*.azurewebsites.net` : ce sont deux domaines distincts,
et le filtrage porte sur le nom présenté dans le premier paquet TLS. Le seul
test qui tranche est un appel à l'API **depuis une connexion Orange**. Il n'a
pas été fait au moment de cette décision.

Le pari est raisonnable — le filtrage visait un nom précis, on en change — et
il est **réversible en une heure** : le code du Worker vit dans l'historique
git, et D-22 explique pourquoi il avait été écrit.

**Ce qu'on gagne.**

| | avec le Worker | sans |
|---|---|---|
| Sauts réseau | navigateur → Cloudflare → Azure | navigateur → Azure |
| Pièces à maintenir | 2 déploiements, 2 journaux | 1 |
| `Set-Cookie` du rafraîchissement | reconstruit par le proxy | direct |

Ce dernier point comptait plus qu'il n'y paraît : le Worker devait recopier la
réponse **sans toucher aux en-têtes**, sous peine de fusionner plusieurs
`Set-Cookie` en un seul et de rendre la session impossible à prolonger. Une
subtilité de moins à ne pas casser.

**Ce qu'on perd.** Le filet. Si Orange filtre aussi `*.azurewebsites.net`,
l'application marchera chez le développeur et pas chez le client — et le
symptôme sera une poignée de main TLS coupée, jamais une erreur applicative.

> 🎯 **Le signal à reconnaître** : l'API répond depuis un réseau et pas depuis
> un autre, sans qu'aucun journal serveur ne montre quoi que ce soit. Dans ce
> cas, remettre le Worker devant — c'est exactement ce pour quoi il avait été
> écrit.

**Ce que ça n'était pas.** Une question de coût : le Worker était gratuit et
le restait. On le retire parce qu'un contournement dont la cause a disparu
devient une pièce qu'on entretient sans savoir pourquoi.

---

## D-29 — Le service Render doit être SUPPRIMÉ, pas seulement délaissé

**Date :** 08/09/2026
**Statut :** ✅ actée

**Choix.** `render.yaml` est retiré du dépôt, et le service `garah-api` doit
être **supprimé dans le tableau de bord Render**. Cesser de s'en servir ne
suffit pas.

**Pourquoi ce n'est pas du rangement.** `render.yaml` portait
`autoDeploy: true`. Tant que le service existe, **chaque push sur `main` le
redéploie** — et il pointe sur la même base Neon qu'Azure.

Deux backends vivants sur une seule base, c'est exactement ce que D-18
interdit :

```text
Render  ─┐
         ├─→  Neon  ←  les mêmes tâches planifiées, deux fois
Azure   ─┘
```

**Ce que ça casse, et personne ne le verrait.** `TachesPeriodiques` suppose un
processus unique. À deux :

| Tâche | Conséquence du doublon |
|---|---|
| Agrégation des statistiques | chaque vue comptée **deux fois** — des chiffres faux, jamais une erreur |
| Réconciliation des paiements | deux instances interrogent Campay pour le même paiement |
| Libération des impayées | deux traitements libèrent le même stock |

Aucune de ces trois-là ne produit d'exception. Elles produisent des
**données fausses**, et on ne les découvre qu'en cherchant pourquoi un
tableau de bord annonce le double de la réalité.

> ⚠️ Le jour d'une vraie mise à l'échelle, la réponse est un verrou partagé
> (ShedLock sur une table PostgreSQL, D-18) — pas la suppression d'un
> concurrent. Ici, la seconde instance n'est pas voulue : c'est un reste.

**Ce qu'on perd.** Le repli immédiat. Redéployer sur Render redevient un
travail de mise en place — mais `render.yaml` reste dans l'historique git, et
D-14 dit pourquoi il avait été écrit ainsi.

**Ce qui a été fait le 08/09/2026, et la nuance.** Le service a été
**suspendu**, pas supprimé. Vérifié : `garah-api.onrender.com/api/sante`
répond `503` en 0,78 s — c'est Render qui répond, pas l'application. Aucun
processus ne tourne, donc aucun doublon de tâche planifiée.

> ⚠️ **Un service suspendu peut être repris**, d'un clic ou par Render
> lui-même. Ce jour-là, il redémarrerait avec `autoDeploy` et **la même base
> Neon** — et le doublon décrit plus haut reviendrait sans que rien ne le
> signale. La suppression est la seule forme définitive.
>
> Tant que le service existe, cette entrée reste ouverte.

**Le gain, mesuré le 08/09/2026 sur `/api/sante` :**

| | Render | Azure B1 |
|---|---|---|
| À chaud | 6 s | **1,24 s** |
| Au réveil | ~120 s | **aucun réveil** (Always On) |
