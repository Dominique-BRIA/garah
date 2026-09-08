# L'application client — écrans, intentions et pièges

> **À quoi sert ce document.**
> Même contrat que `feuille-de-route.md` : pour chaque écran, **ce qu'il fait**,
> **pourquoi il est ainsi**, et **ce qui casse si on y touche**.
>
> Il décrit le lot 10. C'est le plus gros du projet, et le seul qui débloque la
> recette manuelle : rien dans le back-office ne crée de commande, donc toute la
> chaîne commande → expédition → retrait reste invérifiable tant que cette
> application n'existe pas (`recette.md` §6).

---

## 0. Ce que le domaine impose au dessin

Ces quatre règles ne sont pas des préférences d'interface. Elles viennent du
métier, elles sont déjà appliquées côté serveur, et une maquette qui les ignore
produit un écran que le backend refusera.

| Règle | Conséquence directe sur l'écran |
|---|---|
| **Pas de livraison à domicile** (D-05) | Il n'existe **aucune** table `adresse`, et c'est volontaire. Jamais de formulaire d'adresse : le client choisit un **point de récupération**, et ce choix change le total. |
| **Commander exige un compte** (D-07) | La vitrine est ouverte, le paiement non. La connexion arrive **au moment de commander**, pas à l'entrée. |
| **On ne s'annule pas soi-même après paiement** (D-12) | La réclamation n'est pas un accessoire : c'est la **contrepartie** de cette règle. Sans elle, le client payé est sans recours. |
| **Les montants sont figés à l'achat** (D-10, D-11) | Une commande passée n'est jamais relue depuis le catalogue. Le prix affiché dans « mes commandes » est celui **payé**, même si le produit a changé de nom et de tarif depuis. |

---

## 1. Les trois décisions prises

### 1.1 Le panier vit dans le navigateur, et fusionne à la connexion

`POST /api/panier/lignes` exige un jeton. Forcer la connexion au premier
« Ajouter » serait franc, mais ferait fuir le visiteur qui découvre — et sur
l'axe Douala → Bangui, le premier contact se fait souvent depuis un lien
partagé, sans compte.

Le panier est donc **local** tant qu'on n'est pas connecté, puis repris **en un
seul appel** à la connexion : `POST /api/panier/fusion`.

> 🎯 **La fusion garde LE PLUS GRAND des deux, jamais la somme.** La plupart
> des boutiques additionnent. On ne le fait pas, pour une raison de terrain :
> sur une connexion instable, une requête est réémise — et une fusion additive
> rejouée **double les quantités**, ce que le client ne découvre qu'à la
> facture. Prendre le maximum rend l'opération **idempotente** : la rejouer dix
> fois donne le même panier.
>
> C'est aussi le comportement juste : le visiteur non connecté ne voyait pas le
> panier du serveur, il ne peut donc pas avoir voulu « ajouter » à quelque
> chose qu'il ignorait.

La route rend **les écarts, pas seulement le panier** — trois natures :
`INDISPONIBLE` (article dépublié ou disparu), `DEJA_PLUS_GRANDE` (le serveur en
avait plus, on garde le sien), `RELEVEE` (le local l'emporte). Une ligne reprise
à l'identique ne produit **aucun** écart : l'écran n'a alors rien à dire.

> ⚠️ **Une ligne périmée n'interrompt pas la fusion.** Un panier local peut
> dormir des semaines dans un navigateur pendant que le catalogue bouge. Tout
> annuler pour un article disparu ferait perdre un panier entier.

> ⚠️ **Le stock n'est pas contrôlé à la fusion**, exactement comme à l'ajout.
> La fusion n'est pas plus stricte qu'un ajout ordinaire, sinon le même geste
> réussirait connecté et échouerait à la connexion. Les ruptures se disent à
> l'**affichage**, comme pour tout autre panier.

> ⚠️ **Ce qui casse si on y touche.** Le panier local n'est pas la vérité : il
> ne connaît ni le stock, ni le prix courant. À la fusion, le serveur peut
> refuser une ligne (rupture) ou en retenir un autre prix (palier franchi,
> tarif modifié). **L'écran doit annoncer chaque écart**, pas les avaler en
> silence — un panier qui change tout seul entre deux pages est la meilleure
> façon de perdre la confiance juste avant de payer.

> ⚠️ Le panier local ne doit **jamais** survivre à une déconnexion sur un poste
> partagé. On le vide à la déconnexion.

### 1.2 Les prix suivent le modèle d'Alibaba

GARAH vend par **paliers de quantité** (`PalierPrix` : `quantiteMin`,
`quantiteMax` nul = « et au-delà », `prixUnitaire`). C'est exactement la
*ladder pricing* d'Alibaba, où le prix unitaire baisse par tranches et où le
palier applicable est mis en avant sur la fiche, à côté de la quantité
minimale.

Ce qu'on reprend, et pourquoi :

| Élément | Intention | Attention |
|---|---|---|
| La **grille des paliers** en haut de fiche, avant le bouton d'achat | « 1–6 : 5 000 · 7 et + : 3 000 » se lit d'un coup. | 🎯 Si la grille n'est pas visible **avant** le panier, le prix change en cours de route et l'écran ressemble à une arnaque. C'est la raison d'être de tout ce paragraphe. |
| Le palier **actif se met en évidence** quand la quantité change | Le client voit où il est, et ce qu'il gagnerait à monter. | Recalculer le prix sans montrer quel palier s'applique laisse croire à un bug. |
| Une invite au palier suivant | « encore 2 articles et le prix passe à 3 000 » | À n'afficher que si le palier suivant existe **et** que le stock suit. Promettre un tarif indisponible est pire que se taire. |
| La **quantité minimale** est le `quantiteMin` du premier palier | C'est le MOQ d'Alibaba, et il existe déjà dans le modèle. | Il vaut presque toujours 1 : ne l'afficher que lorsqu'il est **supérieur à 1**, sinon c'est du bruit sur toutes les fiches. |
| **Négocier** est au même niveau que « Ajouter au panier » | Comme « Contact supplier » chez Alibaba. GARAH a déjà les conversations et les propositions de prix : c'est un vrai flux commercial, pas une messagerie. | L'enterrer dans un menu reviendrait à ne pas l'avoir. |

> ⚠️ **Le prix affiché reste indicatif jusqu'à la commande.** C'est le serveur
> qui fige le tarif au moment de `POST /commandes`. L'écran ne doit jamais
> présenter son propre calcul comme un engagement.

### 1.3 Le paiement mobile est asynchrone, et l'écran le dit

Le client valide sur son téléphone ; GARAH l'apprend par un **webhook** Campay
qui peut mettre plusieurs secondes — ou ne jamais arriver.

| Ce que l'écran fait | Pourquoi |
|---|---|
| Un état d'attente explicite : « validez sur votre téléphone » | Une roue qui tourne sans phrase fait raccrocher. |
| Un bouton **Vérifier** → `POST /paiements/{id}/verification` | Redemande l'état à l'opérateur au lieu d'attendre la réconciliation nocturne. |
| **Jamais** de succès optimiste | 🎯 Afficher « payé » avant confirmation ferait repartir un client persuadé d'avoir réglé. Le litige qui suit coûte plus cher que l'attente. |
| Une sortie honnête si rien ne vient | « Le paiement n'est pas confirmé. Votre commande est conservée, vous pouvez réessayer. » La commande reste `EN_ATTENTE_PAIEMENT`, rien n'est perdu. |

---

## 2. Les écrans

### 2.1 Sans compte — la vitrine est ouverte

| Écran | Intention | Attention |
|---|---|---|
| **Accueil** | Tendances et catégories. Répond à « qu'est-ce qui se vend ici ? » avant toute recherche. | `GET /produits/tendance` est **public** et borné à 50. Le classement vient des agrégats : un catalogue neuf le rend vide, et l'écran doit alors montrer autre chose plutôt qu'un trou. |
| **Catalogue** | Recherche, filtres, pagination. | Deux états vides distincts : « aucun résultat pour X » ≠ « catalogue vide ». Proposer de changer de recherche à qui n'a rien à chercher est une réponse à côté. |
| **Fiche produit** | Déclinaisons, **grille de paliers**, disponibilité, points de récupération et leurs frais. | Le cœur de l'alignement Alibaba (§1.2). C'est aussi le seul écran qui appelle `POST /produits/{id}/vues` — et cet appel ne doit **jamais** bloquer l'affichage. |
| **Suivi de colis** | ✅ **Déjà construit.** `/suivi/:numero`, hors compte. | Le numéro est dans l'URL parce qu'il **se partage** ; le code de retrait n'y va jamais. |
| **Connexion / inscription / confirmation** | | L'inscription exige une confirmation d'e-mail. Tant que `GARAH_MAIL_*` n'est pas posé sur Azure, le compte se crée et **le courriel ne part pas** : le dire à l'écran plutôt que laisser attendre. |

### 2.2 Avec compte

| Écran | Intention | Attention |
|---|---|---|
| **Panier** | Ce que je vais payer, **frais d'acheminement compris**. | Le total sans les frais est un mensonge : ils dépendent du point choisi, et ils sont figés sur la commande. Voir §1.1 pour la fusion. |
| **Passer commande** | Choisir le point de récupération, voir le total définitif. | 🎯 **Un seul écran, pas un tunnel en quatre étapes** : il n'y a ni adresse, ni transporteur, ni créneau à choisir. Le seul choix réel est le lieu de retrait. Un tunnel long ferait abandonner pour rien. |
| **Paiement** | Voir §1.3. | |
| **Mes commandes** + détail | Où en est chaque commande, et **le code de retrait** quand elle est disponible. | Le code n'apparaît que pour le client propriétaire, et seulement une fois la marchandise arrivée. Les montants viennent de la commande, jamais du catalogue. |
| **Négocier** | Proposer un prix, discuter, accepter. | Le **sens** d'une proposition vient du jeton, jamais du corps : un client qui pourrait écrire `sens: "RESPONSABLE"` s'accorderait n'importe quelle remise. |
| **Mes réclamations** | La voie de recours (D-12). | Ouvrir une réclamation ne rembourse rien et ne promet rien : l'écran doit dire qu'un humain examine, sous peine de faire attendre un virement qui ne viendra pas. |
| **Demander un retour** | Le client désigne des **lignes de commande**, pas des produits. | C'est la ligne qui porte le prix figé, donc le montant remboursable. Un trigger (I-40) refuse de retourner plus qu'on n'a acheté, retours précédents compris. |
| **Favoris** | | |
| **Mon profil** | Coordonnées, mot de passe, photo. | L'adresse e-mail ne se change pas comme un champ ordinaire : elle identifie le compte et a été vérifiée. |

---

## 3. Le backend à écrire

> **Correction.** J'ai d'abord annoncé « aucune route backend à écrire ». C'est
> faux, et la vérification route par route l'a montré : la **fiche publique ne
> porte aucun prix**.

`DetailProduit.VarianteResumee` rend `id, sku, libelle, parDefaut, statut` —
ni tarif, ni palier, ni disponibilité. La liste (`ResumeProduit`) porte bien un
`prixMin` et une quantité, mais la fiche, elle, ne peut pas afficher la grille
de paliers du §1.2.

| À écrire | Pourquoi |
|---|---|
| Enrichir la fiche publique : **paliers et disponibilité par déclinaison** | Sans ça, l'alignement Alibaba est impossible — c'est toute la §1.2 qui tombe. |
| ~~Une route de fusion de panier~~ ✅ **faite** | `POST /api/panier/fusion`. Voir §1.1 : idempotente, tolérante aux lignes périmées, et elle rend les écarts. |

Tout le reste existe : catalogue, panier, commande, paiement, suivi, SAV,
conversations, favoris, profil.

---

## 4. Ce qui ne doit pas bouger

| Règle | Ce qui casse si on l'enfreint |
|---|---|
| **Le panier local n'est jamais la vérité** | Le serveur décide du stock et du prix. Faire confiance au panier local, c'est vendre ce qu'on n'a plus. |
| **Aucun succès de paiement optimiste** | Un client repart persuadé d'avoir payé. |
| **Jamais de formulaire d'adresse** | GARAH ne livre pas à domicile. Un champ « adresse de livraison » promettrait un service qui n'existe pas. |
| **Les montants d'une commande ne se relisent pas dans le catalogue** | Une facture de mars devient fausse en septembre. |
| **Le code de retrait ne va jamais dans une URL** | Journaux du serveur, historique du navigateur, en-tête `Referer`. Le numéro de **suivi**, lui, y va : il se partage, c'est son rôle. |
| **La grille de paliers se voit avant le panier** | Le prix change en cours de route, et l'écran ressemble à une arnaque. |
