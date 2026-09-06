# Chapitre 02 — Revue critique du modèle initial

> Prérequis : [chapitre 01](01-le-domaine-et-les-acteurs.md).
> Durée de lecture : ~35 min. C'est le chapitre le plus dense du cours.

---

## 1. Ce qu'on veut faire

On a reçu une spécification complète : 32 sections, 6 diagrammes UML, un modèle
de données de ~40 tables. C'est un **très bon point de départ** : la séparation
Marchand / Responsable / Client est juste, le modèle de permissions est solide,
la logistique est correctement pensée en événements.

Mais un modèle n'est jamais juste du premier coup. Ce chapitre est une **revue** :
on va chercher les endroits où le modèle, tel qu'il est écrit, **ne peut pas
répondre à une question que le métier va poser**.

> 🎯 **La méthode de revue — à retenir pour toute ta carrière :**
> on ne juge pas un modèle sur son élégance.
> On lui **pose des questions métier** et on regarde s'il sait répondre.
> S'il ne sait pas → il manque une table, une colonne, ou une cardinalité est fausse.

---

## 2. La notion : les trois niveaux de modélisation

Avant de critiquer, il faut nommer ce qu'on critique.

| Niveau | Nom | Ce qu'il contient | Outil |
|---|---|---|---|
| **Conceptuel** | MCD | Les *concepts* métier et leurs liens. **Aucune** clé étrangère, aucun type SQL. | Merise, UML |
| **Logique** | MLD | Les tables, les clés primaires/étrangères, les cardinalités résolues. Encore indépendant du SGBD. | Diagramme ER |
| **Physique** | MPD | Les types PostgreSQL, les index, les contraintes, les partitions. | DDL SQL |

> ⚠️ **Première correction, de vocabulaire :**
> le diagramme Mermaid `erDiagram` de la spec est appelé « MCD ».
> Il n'en est pas un : il contient déjà des `bigint`, des `PK` et des `FK`.
> **C'est un MLD.** Ce n'est pas grave — c'est même utile — mais il faut le
> nommer correctement, sinon on mélange les débats
> (« faut-il une table d'association ? » est une question de MLD,
> pas de MCD).

Pour GARAH on gardera les deux : un vrai MCD (concepts, pour comprendre)
au chapitre 03, et le MLD qui en découle.

---

## 3. Les anomalies trouvées

Classées par gravité. **🔴 bloquant** = le métier ne peut pas fonctionner.
**🟠 important** = ça fonctionnera, mais ça fera mal. **🟡 à décider** = c'est un
choix, pas une erreur.

---

### 🔴 A1 — L'Admin et le SuperAdmin n'existent pas dans le modèle

**Le constat.**
La section 29 annonce :

```text
UTILISATEUR ├── ADMIN ├── RESPONSABLE └── CLIENT
```

Mais dans le MLD, seules `RESPONSABLE` et `CLIENT` existent.
Il n'y a **aucun moyen** de savoir qu'un utilisateur est Admin ou SuperAdmin.
Une table `ROLE` est citée en section 30 mais n'apparaît dans aucun diagramme.

**La question à laquelle le modèle ne sait pas répondre :**
> « Donne-moi la liste des Admins actifs. »

**La correction.**
Ajouter un **discriminant** sur `UTILISATEUR` :

```text
UTILISATEUR.type ∈ { SUPER_ADMIN, ADMIN, RESPONSABLE, CLIENT }
```

Et créer les tables filles manquantes si elles portent des attributs propres.
Pour GARAH : `ADMIN` n'a probablement aucun attribut spécifique →
un SuperAdmin et un Admin sont juste des `UTILISATEUR` avec un `type` différent.

> 📌 **Notion : héritage en base de données.**
> Trois stratégies existent, et JPA les nomme :
> - `SINGLE_TABLE` — tout dans `UTILISATEUR` + une colonne `type`. Rapide, mais colonnes nulles.
> - `JOINED` — une table par sous-classe (ce que fait la spec pour `CLIENT`/`RESPONSABLE`). Propre, mais une jointure de plus.
> - `TABLE_PER_CLASS` — à éviter presque toujours.
>
> **Choix GARAH :** `JOINED` pour `CLIENT` et `RESPONSABLE` (ils ont de vrais
> attributs propres), et le `type` sur `UTILISATEUR` couvre `ADMIN`/`SUPER_ADMIN`.
> On détaillera au chapitre 07.

---

### 🔴 A2 — Les statistiques demandées sont impossibles à calculer

**Le constat.**
La section 20 demande, pour les produits : *vues, favoris, paniers, ventes, retours*.
Et pour les « produits tendance » : *vues + favoris + ajouts au panier + vitesse de vente*.

Or dans le modèle il n'existe **aucune** table :
- `VUE_PRODUIT`
- `FAVORI`

**La question sans réponse :**
> « Quels sont les 10 produits les plus vus cette semaine ? »

**Pourquoi c'est bloquant et pas « à faire plus tard » :**
une vue non enregistrée est **définitivement perdue**. Contrairement à un écran
qu'on peut coder après, une donnée non collectée ne se rattrape jamais.

**La correction.** Trois tables à ajouter dès la v1 :

```text
VUE_PRODUIT         (produit, client?, date, session, source)
FAVORI              (produit, client, date)
STATISTIQUE_PRODUIT_JOUR   ← agrégat pré-calculé (voir ci-dessous)
```

> 💡 **Notion : la table d'agrégat.**
> Compter les vues d'un produit sur 30 jours en scannant `VUE_PRODUIT`
> devient lent dès quelques millions de lignes. On pré-calcule chaque nuit
> une ligne par (produit, jour) avec les compteurs. Les écrans lisent l'agrégat,
> jamais la table brute.
> C'est le principe **écriture détaillée / lecture agrégée**. Chapitre 25.

---

### 🔴 A3 — La commission marchand est inutilisable telle que modélisée

**Le constat.**

```text
COMMISSION { id, marchand_id, montant, taux }
DETTE_MARCHAND { id, marchand_id, montant, statut }
```

Deux problèmes graves :

1. **La commission est attachée au Marchand, pas à la vente.**
   Or une commission se calcule **sur chaque vente** (« 10 % sur ce produit »).
   Avec ce modèle, on ne peut pas dire *combien* on a prélevé sur la commande n°812.

2. **`montant` et `taux` cohabitent** sans dire lequel s'applique.
   Est-ce 10 % *ou* 500 FCFA ? Les deux ? Le modèle ne tranche pas.

3. **`DETTE_MARCHAND.montant` est un solde stocké.**
   C'est le grand classique de la comptabilité mal modélisée : dès qu'une écriture
   est ratée, le solde ment, et **plus personne ne peut prouver la vérité**.

**La question sans réponse :**
> « Pourquoi doit-on 1 250 000 FCFA au marchand ABC ? Détaille. »

**La correction — le principe du grand livre.**

```text
On ne stocke JAMAIS un solde comme source de vérité.
On stocke des ÉCRITURES, et le solde est leur SOMME.
```

```text
COMMISSION            → devient un TAUX applicable (règle), rattachée au
                        marchand et/ou à la catégorie de produit,
                        avec une période de validité.

LIGNE_COMMANDE        → porte le taux et le montant de commission FIGÉS
                        au moment de la vente (comme le prix).

ECRITURE_MARCHAND     → le grand livre : +vente, −commission, −retour, −règlement
                        (marchand, type, montant, date, référence de la pièce)

SOLDE marchand        = SUM(ECRITURE_MARCHAND.montant)   ← calculé, jamais stocké
                        (éventuellement mis en cache, mais recalculable)
```

> 📌 **Règle fondatrice n°3 :**
> tout montant dû est **prouvable ligne par ligne**, ou il est faux.

---

### 🔴 A4 — Un retour partiel est impossible

**Le constat.**

```text
RETOUR { id, commande_id, motif, statut, date_creation }
```

Le retour porte sur **la commande entière**, sans quantité ni ligne.

**La question sans réponse :**
> « Le client a commandé 10 chemises et 2 pantalons. Il retourne 3 chemises. »

**La correction.**

```text
RETOUR         (commande, client, motif, statut, dates)
LIGNE_RETOUR   (retour, ligne_commande, quantite, motif, etat_article)
```

Et il faut relier le retour à ses **conséquences** :
- un mouvement de stock (l'article revient — ou pas, s'il est endommagé) ;
- un remboursement (`PAIEMENT` de type remboursement) ;
- une écriture négative chez le marchand.

Sans ces liens, un retour est un post-it, pas une opération.

---

### 🟠 A5 — Le stock mélange un état et un historique

**Le constat.**

```text
STOCK { quantite_disponible, quantite_reservee, quantite_vendue }
```

`disponible` et `reservee` sont des **états** (ça monte et ça descend).
`vendue` est un **cumul** (ça ne descend jamais). Les trois dans la même table,
au même niveau, invitent à des calculs faux du genre
`disponible + reservee + vendue = stock total`, qui est absurde.

De plus, la section 13 annonce cinq états (`Disponible, Réservé, Vendu, Retourné, Endommagé`)
mais la table n'en a que trois.

**La correction.**

```text
STOCK              → uniquement les quantités PHYSIQUES actuelles :
                     disponible, reservee, endommagee
MOUVEMENT_STOCK    → l'historique complet
Les cumuls (vendu, retourné) → dérivés de MOUVEMENT_STOCK ou d'un agrégat
```

**Et surtout :** `MOUVEMENT_STOCK` doit dire **pourquoi** il a eu lieu.

```text
MOUVEMENT_STOCK {
  stock_id, type, quantite,
  quantite_avant, quantite_apres,     ← permet de rejouer et d'auditer
  origine_type, origine_id,           ← COMMANDE / RETOUR / EXPEDITION / AJUSTEMENT
  responsable_id,                     ← qui l'a fait (null si automatique)
  date_operation, commentaire
}
```

Sans `origine_*`, un écart de stock est indébrouillable.

> ⚠️ **Le vrai piège du stock : la concurrence.**
> Deux clients commandent le dernier article **à la même seconde**.
> Un simple `SELECT quantite` puis `UPDATE quantite - 1` vend l'article deux fois.
> La solution passe par un verrou ou une contrainte SQL — pas par du code Java.
> C'est le sujet entier du **chapitre 17**.

---

### 🟠 A6 — La négociation ne dit pas sur quoi elle porte

**Le constat.**

```text
PROPOSITION_PRIX { id, conversation_id, montant, statut, date_creation }
```

Il manque :
- **le produit** concerné (une conversation peut parler de trois produits) ;
- **la quantité** (proposer 12 000 FCFA pour 1 ou pour 50 unités n'est pas pareil) ;
- **l'auteur** (le client propose, ou le responsable contre-propose ?) ;
- **la validité** (une proposition acceptée reste-t-elle valable 3 mois ?) ;
- **le lien vers la commande** qui l'a consommée.

**La question sans réponse :**
> « Ce client a une remise négociée. Sur quoi, à quel prix, jusqu'à quand,
> et qui l'a accordée ? »

**La correction.**

```text
PROPOSITION_PRIX {
  conversation_id, produit_id, quantite,
  prix_unitaire_propose, auteur_id, sens (CLIENT | RESPONSABLE),
  statut, date_creation, date_expiration,
  proposition_parente_id     ← pour chaîner les contre-propositions
}
```

Et dans `LIGNE_COMMANDE`, une colonne `proposition_prix_id` **nullable** :
c'est la **justification** d'un prix inférieur au tarif. Sans elle, un contrôle
comptable ne peut pas expliquer l'écart.

---

### 🟠 A7 — Le lien Conversation ↔ Commande existe en UML mais pas en base

**Le constat.**
Le diagramme de la section 25 dit :

```text
Conversation "0..1" -- "1" Commande
```

Le MLD ne contient **aucune** clé entre les deux.
Et la cardinalité elle-même est douteuse : elle impose qu'une conversation
soit toujours liée à exactement une commande — or la plupart des conversations
sont de simples questions.

**La correction.** La relation correcte est :

```text
COMMANDE "0..1" ──► "1" CONVERSATION      (« cette commande vient de cette discussion »)
```

soit une colonne `commande.conversation_id` nullable.

> 💡 **Comment lire une cardinalité sans se tromper.**
> Lis-la **dans les deux sens**, à voix haute, avec des mots métier :
> - « Une conversation concerne **au plus une** commande. » → plausible
> - « Une commande vient de **exactement une** conversation. » → **faux**, la plupart
>   des commandes viennent du catalogue.
>
> Dès qu'un des deux sens sonne faux, la cardinalité est fausse.

---

### 🟠 A8 — Le panier : 1 ou N ?

**Le constat.** Contradiction interne :

```text
CLIENT ||--|| PANIER        ← exactement un panier
PANIER { statut }           ← un statut n'a de sens que s'il y en a plusieurs
```

**La correction.** Deux options, à choisir :

| Option | Modèle | Conséquence |
|---|---|---|
| **A — panier unique vivant** | `CLIENT 1 ──1 PANIER`, vidé après commande | Simple. On perd les paniers abandonnés (or c'est une stat demandée). |
| **B — panier historisé** | `CLIENT 1 ──* PANIER`, un seul `statut = ACTIF` | Permet la stat « paniers abandonnés » de la section 20. |

**Recommandation : option B**, à cause de la section 20 qui demande la statistique
« paniers ». Avec une contrainte d'unicité partielle PostgreSQL :

```sql
CREATE UNIQUE INDEX panier_actif_unique
  ON panier (client_id) WHERE statut = 'ACTIF';
```

> 💡 **Notion PostgreSQL : l'index unique partiel.**
> C'est une des meilleures fonctionnalités de Postgres, et elle n'existe pas
> en MySQL. Elle permet de faire porter une **règle métier** par la base
> (« un seul panier actif par client ») plutôt que par du code Java qu'on peut
> oublier d'appeler. Chapitre 05.

---

### 🟠 A9 — L'itinéraire est obligatoire pour toute expédition

**Le constat.**

```text
EXPEDITION }o--|| ITINERAIRE : "suit"
```

Le `||` impose un itinéraire à **toute** expédition.
Or une livraison locale (Douala → Douala) n'a pas d'itinéraire multi-étapes.

**La correction :** `EXPEDITION 0..1 ──► ITINERAIRE`.

**Et une question plus profonde :** un `ITINERAIRE` est-il un **modèle réutilisable**
(« la route Douala–Bangui ») ou le **trajet réel de ce colis-là** ?

Les deux sont légitimes, mais ce n'est pas la même table :

```text
ITINERAIRE          = le modèle, réutilisable        (« Douala → Bangui »)
EXPEDITION          = l'instance, avec son trajet réel
EVENEMENT_EXPEDITION = ce qui s'est VRAIMENT passé   (y compris les détours)
```

⚠️ Si le colis dévie de l'itinéraire prévu (route coupée, incident), le modèle
doit pouvoir enregistrer un passage par un point **hors itinéraire**.
Les événements le permettent — à condition de ne pas contraindre
`EVENEMENT_EXPEDITION.point_transit_id` à appartenir à l'itinéraire.

---

### 🟠 A10 — Un événement d'expédition ne peut pas être ailleurs qu'en transit

**Le constat.**

```text
EVENEMENT_EXPEDITION { colis_id FK, point_transit_id FK, ... }
```

Mais les deux événements les plus importants du parcours ne se produisent
**pas** dans un point de transit :

- le **départ** de l'entrepôt d'origine ;
- l'**arrivée au point de récupération** ;
- la **remise au client**.

**La correction.** Rendre le lieu polymorphe ou générique :

```text
EVENEMENT_EXPEDITION {
  colis_id,
  lieu_type  ∈ { ENTREPOT, POINT_TRANSIT, POINT_RECUPERATION },
  lieu_id,
  type_evenement, date_heure, responsable_id, observation, preuve_url
}
```

Alternative plus propre : une table `LIEU` unique dont `POINT_TRANSIT` et
`POINT_RECUPERATION` sont des spécialisations. On tranchera au chapitre 21.

---

### 🟠 A11 — Deux sources de vérité pour l'état d'une expédition

**Le constat.**
`EXPEDITION` a un `statut`, `COLIS` a un `statut`, et `EVENEMENT_EXPEDITION`
raconte l'histoire. Trois endroits, une seule vérité.

Le jour où ils divergent — et **ils divergeront** — lequel croire ?

**La correction : poser la règle par écrit, dans le code.**

```text
La VÉRITÉ  = la suite des événements.
Le STATUT  = une projection du dernier événement, mis à jour dans la MÊME
             transaction que l'insertion de l'événement.
             Il existe pour la performance d'affichage, rien d'autre.
             Il doit être RECALCULABLE à tout moment.
```

Et écrire un test qui recalcule les statuts depuis les événements et vérifie
qu'ils correspondent. Ce test attrapera les bugs des années suivantes.

---

### 🟠 A12 — Les paiements ne peuvent pas gérer le mobile money ni le remboursement

**Le constat.**

```text
PAIEMENT { commande_id, montant, moyen, statut, date_paiement }
```

Manquent :
- **`reference_transaction`** — l'identifiant chez l'opérateur (MTN MoMo, Orange Money).
  Sans lui, aucun rapprochement possible en cas de litige. **Indispensable.**
- **`type`** (`ENCAISSEMENT` / `REMBOURSEMENT`) — sinon un remboursement doit être
  un montant négatif, ce qui casse toutes les sommes.
- **`devise`** — même si tout est en FCFA aujourd'hui.
- Le lien vers le **retour** ou la **réclamation** qui justifie un remboursement.
- Les **tentatives échouées** : la section 18 veut surveiller les « échecs de paiement ».
  Si on n'enregistre que les paiements réussis, ce signal n'existe pas.

**La correction.**

```text
PAIEMENT {
  commande_id, type, montant, devise, moyen,
  reference_transaction, reference_externe,
  statut, date_initiation, date_confirmation,
  origine_type, origine_id      ← RETOUR / RECLAMATION pour un remboursement
}
TENTATIVE_PAIEMENT {  ← ou statut = ECHEC conservé dans PAIEMENT
  paiement_id, statut, code_erreur, message, date
}
```

---

### 🟠 A13 — La multi-marchand n'est pas exploitable dans la commande

**Le constat.**
La section 15 dit qu'une commande peut contenir des produits de plusieurs
marchands et donner plusieurs expéditions. Bien vu. Mais :

- `LIGNE_COMMANDE` ne porte **pas** le marchand (il faut passer par `PRODUIT`) ;
- `EXPEDITION` ne dit **pas** quelles lignes de commande elle transporte.

**Les questions sans réponse :**
> « Combien doit-on au marchand ABC sur la commande 812 ? »
> → possible seulement en joignant `PRODUIT`, **et faux** si le produit
> a changé de marchand depuis (or `PRODUIT_MODIFIER_MARCHAND` existe !).
>
> « Quels articles sont dans le colis n°3 ? »
> → impossible.

**La correction.**

```text
LIGNE_COMMANDE   + marchand_id     ← FIGÉ à la commande, comme le prix
                 + taux_commission, montant_commission   (figés aussi)

LIGNE_COLIS      (colis_id, ligne_commande_id, quantite)   ← table manquante
```

> 📌 **Règle fondatrice n°4 — la photographie.**
> Tout ce qui a une **valeur juridique ou financière** au moment d'une
> transaction doit être **copié** dans la transaction, pas référencé.
> Prix, marchand, taux de commission, adresse de livraison, nom du produit.
> Une référence pointe vers une donnée **qui changera**. Une copie est un fait.

---

### 🟠 A14 — Le score de risque n'est pas explicable

**Le constat.** La section 19 exige :
> « Le score doit être explicable. »

Mais la table ne contient que :

```text
SCORE_RISQUE_CLIENT { score, niveau, date_calcul }
```

Un nombre. Aucun signal. L'exigence n'est pas satisfaite par le modèle.

**La correction.**

```text
SCORE_RISQUE_CLIENT {
  client_id, score, niveau, date_calcul,
  version_algorithme,      ← indispensable : sinon on ne peut pas comparer
                             deux scores calculés par deux versions du calcul
  details jsonb            ← les signaux et leur poids
}
```

Ou, plus rigoureux, une table fille `SIGNAL_RISQUE (score_id, code, libelle, poids, valeur)`.

> 💡 **Notion : `jsonb` en PostgreSQL.**
> Postgres sait stocker et **indexer** du JSON. C'est parfait pour ce cas :
> une structure qui varie selon l'algorithme et qu'on ne requête pas
> en jointure. À ne PAS utiliser pour des données qu'on filtre et joint
> tout le temps — là, il faut de vraies colonnes. Chapitre 05.

---

### 🟡 A15 — Le produit sans variantes : décision à assumer

**Le constat.**

```text
PRODUIT ||--|| STOCK
```

Un produit = un stock. Donc **pas de variantes** (taille, couleur, capacité).
Une chemise en S/M/L doit devenir trois produits distincts.

Ce n'est pas une erreur — c'est un **choix**. Mais il faut le faire consciemment,
parce que le rattraper plus tard est **le refactoring le plus douloureux**
d'une plateforme e-commerce : ça touche produit, stock, prix, panier, commande,
colis et toutes les statistiques.

| Sans variantes (spec actuelle) | Avec variantes |
|---|---|
| Simple, livrable vite | `PRODUIT 1──* VARIANTE`, le stock et le prix passent sur la variante |
| Catalogue gonflé, doublons de photos et descriptions | Modèle propre, mais tout le code est plus complexe |

**✅ Décision prise (06/09/2026) : GARAH aura des variantes.**
Le stock, le prix et le SKU descendent au niveau de la variante.
Détail et conséquences : [D-01 dans le journal des décisions](../decisions.md#d-01--produits-avec-variantes).

---

### 🟡 A16 — Un responsable, une seule catégorie

`RESPONSABLE "*" ──► "1" CATEGORIE` : un responsable a **un seul** profil,
qui est aussi son titre.

C'est cohérent et élégant. Mais dans une petite structure, la même personne
est souvent « commerciale **et** logistique ». Le système de `ADD`/`REMOVE`
peut compenser, au prix d'une longue liste d'exceptions.

**✅ Décision prise (06/09/2026) : plusieurs catégories par responsable**,
dont une marquée « principale » qui donne le titre affiché.
Attention : la soustraction `REMOVE` s'applique **après** l'union des catégories.
Détail : [D-02](../decisions.md#d-02--un-responsable-peut-avoir-plusieurs-catégories).

---

### 🟡 A17 — Ce qui manque complètement au modèle

Ces entités ne sont nulle part et seront nécessaires :

| Manquant | Pourquoi c'est nécessaire |
|---|---|
| **ADRESSE** | Où livrer ? Le client n'a ni adresse ni téléphone de livraison. |
| **FRAIS_LIVRAISON** | La commande a un `montant_total` mais aucun frais de port, ni taxe, ni remise. Le total ne peut pas être justifié. |
| **CATEGORIE_PRODUIT.parent_id** | Les catégories sont plates. Aucune arborescence (« Électronique > Téléphones »). |
| **PIECE_JOINTE** | Un message, une réclamation, un incident, un contrôle qualité ont besoin de photos. La spec en parle (« preuves ») mais aucune table. |
| **SESSION / APPAREIL** | La section 18 veut détecter un « nouvel appareil ». Impossible sans mémoriser les appareils connus. |
| **DEVISE** | Aujourd'hui FCFA uniquement. À prévoir, au moins comme colonne. |
| **PREPARATION** | 4 cas d'utilisation lui sont consacrés (§9.10), mais aucune table. Est-ce un statut de commande, ou une entité ? |

---

### 🟡 A18 — Détails techniques PostgreSQL à corriger

| Point | Correction |
|---|---|
| `AUDIT_LOG.ancienne_valeur text` | → **`jsonb`**. On veut requêter « qui a changé le prix », pas lire du texte. |
| `AUDIT_LOG.utilisateur_id FK` | Un log doit survivre à la suppression de l'utilisateur → garder aussi une **copie du nom/email** de l'acteur. |
| `decimal` pour les montants | → **`numeric(15,2)`** explicite. Jamais `float`/`double` pour de l'argent : `0.1 + 0.2 ≠ 0.3`. |
| `datetime` | → **`timestamptz`** (avec fuseau). Sinon les dates seront fausses le jour d'un serveur en UTC. |
| Les `statut` en `string` | → soit un **type `ENUM` PostgreSQL**, soit un `varchar` + contrainte `CHECK`. Jamais un texte libre. |
| Pas d'index mentionné | Les tables d'événements (`ACTIVITE_CLIENT`, `EVENEMENT_EXPEDITION`, `AUDIT_LOG`) vont grossir vite : index obligatoires sur `(entité, date)`. |

> 📌 **Règle fondatrice n°5 :**
> une règle métier qui **peut** être portée par une contrainte SQL
> **doit** l'être. Le code Java s'oublie, se contourne, se duplique.
> Une contrainte, non.

---

## 4. Récapitulatif

| # | Anomalie | Gravité |
|---|---|---|
| A1 | Admin / SuperAdmin absents du modèle | 🔴 |
| A2 | Vues et favoris manquants → statistiques impossibles | 🔴 |
| A3 | Commission et dette marchand non prouvables | 🔴 |
| A4 | Retour partiel impossible | 🔴 |
| A5 | Stock : état et cumul mélangés, mouvements sans cause | 🟠 |
| A6 | Négociation sans produit, quantité, auteur ni validité | 🟠 |
| A7 | Lien Conversation ↔ Commande absent, cardinalité fausse | 🟠 |
| A8 | Panier : cardinalité contradictoire | 🟠 |
| A9 | Itinéraire obligatoire ; modèle vs trajet réel confondus | 🟠 |
| A10 | Événement d'expédition limité aux points de transit | 🟠 |
| A11 | Trois sources de vérité pour l'état d'une expédition | 🟠 |
| A12 | Paiement : ni référence opérateur, ni remboursement, ni échec | 🟠 |
| A13 | Multi-marchand non exploitable (ligne ↔ marchand ↔ colis) | 🟠 |
| A14 | Score de risque non explicable | 🟠 |
| A15 | Pas de variantes produit | 🟡 décision |
| A16 | Une seule catégorie par responsable | 🟡 décision |
| A17 | Adresse, frais, taxes, pièces jointes, appareils absents | 🟡 |
| A18 | Types SQL, index et contraintes à préciser | 🟡 |

---

## 5. À retenir

1. On critique un modèle en lui **posant des questions métier**, pas en le regardant.
2. **Photographier, pas référencer** : prix, marchand, taux, adresse sont copiés dans la transaction.
3. **Un solde ne se stocke pas**, il se calcule à partir d'écritures.
4. Une donnée **non collectée est perdue à jamais** : les tables de mesure existent dès le jour 1.
5. **Une seule source de vérité.** Les statuts sont des projections recalculables.
6. Une cardinalité se vérifie en la **lisant à voix haute dans les deux sens**.
7. Ce qui peut être une **contrainte SQL** ne doit pas être du code Java.

---

## 6. Exercices

**Exercice 1.**
Le marchand ABC réclame son règlement. Écris, en pseudo-SQL, la requête qui
justifie le montant dû — d'abord avec le modèle initial (et explique pourquoi
c'est impossible), puis avec `ECRITURE_MARCHAND`.

**Exercice 2.**
Un produit passe du marchand ABC au marchand XYZ. Trois commandes existaient déjà.
Que se passe-t-il, avec le modèle initial, sur le calcul de ce qu'on doit à ABC ?
Quelle correction (A13) règle le problème, et pourquoi ?

**Exercice 3.**
Trouve, dans le modèle initial, **une anomalie que ce chapitre n'a pas listée**.
Applique la méthode : formule une question métier à laquelle il ne sait pas répondre.

**Exercice 4.**
`RETOUR` : le client renvoie 3 chemises, dont 1 endommagée.
Liste toutes les lignes créées en base (retour, stock, paiement, marchand),
avec le modèle corrigé.

---

➡️ **Chapitre suivant :** 03 — Le modèle de données corrigé
*(sera écrit après tes arbitrages sur A15, A16 et les décisions ouvertes)*
