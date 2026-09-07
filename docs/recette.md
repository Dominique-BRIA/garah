# Recette — ce qu'il faut tester, dans l'ordre

> **L'ordre n'est pas indifférent.** La chaîne a des dépendances : sans
> dimension pas de déclinaison propre, sans déclinaison pas de stock, sans
> point de récupération pas de commande. Tester dans le désordre fait conclure
> à des pannes qui n'en sont pas.

> **Avant de commencer**
> 1. `cd frontend && npm run admin` — et laisser tourner.
> 2. Si un écran manque au menu : `Ctrl + Shift + R`. La coque est un composant
>    persistant, le rechargement à chaud ne la remplace pas toujours.
> 3. Vérifier que le backend est à jour : `GET /api/sante` doit annoncer
>    `versionSchema: 26` ou plus.

---

## 0. Ce qui bloque tout le reste

| # | Geste | Attendu | Si ça échoue |
|---|---|---|---|
| 0.1 | Ouvrir **Lieux** | La bannière ambre « Aucun point de récupération actif » s'affiche | La bannière ne doit apparaître que si le compte est à zéro |
| 0.2 | Créer un lieu, rôle **Point de récupération**, ville Bangui, frais 5000 | Il apparaît dans la liste, badge vert | |
| 0.3 | La bannière disparaît | | Si elle reste, le compteur ne se recalcule pas |
| 0.4 | Tenter de le **désactiver** | **Refus** : « c'est le dernier point de récupération actif » | S'il se désactive, le garde-fou ne marche pas — et plus personne ne peut commander |
| 0.5 | Créer un second point, puis désactiver le premier | Accepté cette fois | |
| 0.6 | Modifier un lieu | Le champ **Rôle** n'apparaît pas | S'il apparaît, c'est un bug : le rôle est figé |
| 0.7 | Créer un **Point de transit** | Le champ « Frais d'acheminement » **disparaît** du formulaire | Les frais n'existent que sur un point de récupération |
| 0.8 | Regarder la colonne Acheminement | Un **tiret** sur le transit, un montant sur la récupération | « 0 FCFA » serait faux : l'acheminement ne s'y facture pas, il n'y est pas gratuit |

---

## 1. Les dimensions *(lot 3)*

| # | Geste | Attendu |
|---|---|---|
| 1.1 | Ouvrir **Dimensions** | Écran vide avec son explication |
| 1.2 | Créer « Taille », affichage **Liste** | |
| 1.3 | Y ajouter 40, 41, 42, 43 | Le formulaire **reste ouvert** et le champ se vide entre chaque |
| 1.4 | Vérifier l'ordre affiché | 40, 41, 42, 43 — **pas** un ordre alphabétique |
| 1.5 | Créer « Couleur », affichage **Pastille** | Le sélecteur de couleur apparaît |
| 1.6 | Ajouter Blanc `#FFFFFF` et Noir `#111111` | Les pastilles montrent la teinte |
| 1.7 | Ajouter « 42 » une seconde fois dans Taille | **Refus** : « 42 existe déjà dans Taille » |
| 1.8 | Retirer une valeur non utilisée | Accepté |

---

## 2. Le produit et ses déclinaisons

| # | Geste | Attendu | Point de vigilance |
|---|---|---|---|
| 2.1 | **Produits → Nouveau produit** | Aucun champ « Référence » | Elle est engendrée |
| 2.2 | Nom « Chemise Oxford », marchand, catégorie | La fiche s'ouvre, référence du type `202020-CHE-CHEMISE-OXFORD` | Composée du code marchand, de la catégorie et du nom |
| 2.3 | Créer un **second** produit du même nom | Référence suffixée `-2`, et la fiche s'ouvre | Sans ça : « conflit avec des données existantes » |
| 2.4 | Déclinaisons → **Par dimensions** | Les dimensions du lot 1 s'affichent en puces | |
| 2.5 | Cocher 42, 43 × Blanc, Noir | « **4 déclinaison(s) seront créées** » avant le clic | Le nombre doit suivre les cases en temps réel |
| 2.6 | Créer | Quatre déclinaisons, SKU `…-42-BLANC`, intitulés « 42 — Blanc » | Jamais de saisie manuelle |
| 2.7 | Chaque déclinaison affiche ses **valeurs** | Pastille de couleur visible sur les couleurs | |
| 2.8 | Refaire la grille en ajoutant une couleur | Les combinaisons existantes sont **ignorées en silence**, les nouvelles créées | Un refus global serait un bug |
| 2.9 | Refaire la grille à l'identique | **409** : « toutes ces combinaisons existent déjà » | |
| 2.10 | Cocher 42 **et** Blanc dans la même dimension | Impossible par construction — chaque dimension est un bloc | |

---

## 3. Le stock *(lot 2)*

| # | Geste | Attendu | Point de vigilance |
|---|---|---|---|
| 3.1 | Ouvrir **Stock** | Les déclinaisons créées y sont **déjà**, à 0 | Une ligne de stock naît avec la déclinaison (I-15) |
| 3.2 | Toutes portent le triangle ambre | Normal : `disponible (0) ≤ seuil (0)` | |
| 3.3 | Fiche produit → bloc **Stock** → Réception, 20 | Le disponible passe à 20, l'alerte tombe | |
| 3.4 | **Seuil** → 5 | La ligne redevient normale | |
| 3.5 | Réception de 20, puis **Inventaire** à 18 | Le disponible tombe à 18 | ⚠️ L'inventaire prend le **constat**, pas l'écart |
| 3.6 | Inventaire **sans motif** | Refusé | |
| 3.7 | Ouvrir le journal des mouvements | « disponible : 20 → 18 », le plus récent en premier | Chaque ligne doit partir d'où la précédente est arrivée |
| 3.8 | Chercher « chemise » dans Stock | Filtre par produit, déclinaison ou référence | La recherche passe par le catalogue |
| 3.9 | Bouton **Sous le seuil** | Ne montre que les ruptures | |
| 3.10 | Produits → filtre **En rupture** / **Stock faible** | Le total en tête change avec le filtre | Le filtre est appliqué en base, pas sur la page reçue |

---

## 4. La publication

| # | Geste | Attendu |
|---|---|---|
| 4.1 | Fiche d'un produit sans photo | L'encart liste **ce qui manque**, bouton Publier **désactivé** |
| 4.2 | Ajouter un prix sur une déclinaison | « un prix » disparaît de la liste des manques |
| 4.3 | Poser 1–6 puis rouvrir « Ajouter un prix » | Le champ « À partir de » propose **7** |
| 4.4 | Forcer 1–6 puis 6–10 | Refus : chevauchement sur 6 |
| 4.5 | Ajouter une photo | « une photo » disparaît, **Publier** s'active |
| 4.6 | Publier | Statut PUBLIE |
| 4.7 | Désactiver la dernière déclinaison active d'un produit publié | **Refus** — sinon le produit reste en vitrine sans rien à vendre |

---

## 5. L'équipe et les droits

| # | Geste | Attendu |
|---|---|---|
| 5.1 | **Équipe → Profils** → créer « Gestionnaire de catalogue » | Les 188 fonctionnalités sont repliées par module |
| 5.2 | Filtrer sur « produit » | Tous les modules se déplient |
| 5.3 | Cocher un module entier | Le compteur « 12 / 12 » suit |
| 5.4 | Enregistrer sans aucune permission | Refusé |
| 5.5 | **Équipe → Nouveau compte**, type Responsable | Le choix du niveau est en **deux cartes**, pas une liste |
| 5.6 | Sans profil coché | Bouton désactivé |
| 5.7 | Cocher deux profils | L'**étoile** désigne le principal, celui qui donne le titre |
| 5.8 | Créer, puis se connecter avec ce compte | Le menu ne montre **que** les écrans autorisés |
| 5.9 | Tenter de désactiver le compte d'amorçage | Aucun bouton — le super administrateur est intouchable |

---

## 6. Ce qui ne peut **pas** encore être testé

Ce n'est pas une panne, c'est l'état du projet.

| Quoi | Pourquoi |
|---|---|
| **Passer une commande** | `POST /api/commandes` transforme le **panier d'un client**. Le back-office n'a pas de panier, et un administrateur n'est pas un client. Il faut la vitrine (lot 10) ou un `INSERT` manuel. |
| Les écrans **Commandes** et **Paiements** | Ils fonctionnent, mais resteront vides tant qu'aucune commande n'existe. Vérifier seulement qu'ils affichent leur état vide sans erreur. |
| Le **suivi de colis** | Dépend d'une expédition, donc d'une commande. |
| La **vérification d'e-mail** | Les variables `GARAH_MAIL_*` ne sont pas posées sur Render. Le compte se crée, le courriel ne part pas. |
| Le **téléversement de logo / photo de profil** | Colonnes prêtes depuis V23, routes non écrites. Reporté. |

---

## 7. Les pièges connus, à re-tester après chaque lot

| Piège | Comment le repérer |
|---|---|
| Un écran manque au menu | La coque est persistante : `Ctrl + Shift + R` avant de conclure |
| Une modification de `garah-ui` n'arrive pas | Redémarrer `ng serve`, ou lancer `npm run ui:watch` en parallèle |
| Le déploiement Render casse | `-DskipTests` ne saute pas la **compilation** des tests. Vérifier avec `./mvnw -o clean package -DskipTests` — le `clean` est indispensable |
| Un bloc disparaît sans message | Toute absence doit se **dire**. Un `@if` sans `@else` fait croire à une fonctionnalité manquante |
| Les données sont celles de **production** | `ng serve` vise l'API déployée. Une suppression supprime pour de bon |
