# Feuille de route — lots, fonctionnalités et intentions

> **À quoi sert ce document.**
> Il dit, pour chaque fonctionnalité : **ce qu'elle fait**, **pourquoi elle est
> ainsi**, et **ce qui casse si on y touche**.
>
> La troisième colonne est la raison d'être du document. Beaucoup de choix
> paraissent arbitraires vus de l'extérieur et ne le sont pas : ils répondent à
> un piège précis, souvent découvert en production. Corriger un détail sans
> connaître ce piège, c'est le rouvrir.

> **Méthode de travail (à partir du 07/09/2026).**
> L'implémentation des lots avance sans retours en arrière. Les corrections de
> détail se font derrière, en s'appuyant sur ce document pour ne pas défaire une
> intention.

---

## 0. Les règles qui traversent tout

Ces règles ne sont d'aucun lot en particulier. Elles sont supposées vraies
partout, et une correction qui les enfreint est une régression même si elle
« marche ».

| Règle | Pourquoi | Ce qui casse si on l'enfreint |
|---|---|---|
| **Un statut est une photo, un événement est un fait** | Le statut s'écrase, l'événement reste. | On perd la réponse à « que s'est-il passé le 12 mars ? », qu'un litige exigera. |
| **Ce qui doit être vrai ensemble s'écrit ensemble** | Une seule transaction. | Du stock réservé pour une commande qui n'existe pas — invisible jusqu'à l'inventaire. |
| **Les montants d'une commande sont figés** | Désignation, prix, TVA, commission, frais sont copiés à l'achat. | Une facture de mars devient fausse en septembre. |
| **Deux publics, deux routes** | Jamais un `if (estResponsable)` dans un endpoint. | Un client finit par voir les commandes de tout le monde. |
| **On liste ce qui est ouvert, jamais ce qui est fermé** | Sécurité : un oubli laisse une route *fermée*. | Un joker `/**` ouvre les routes qui n'existent pas encore. |
| **Une requête par page, jamais une par ligne** | Les listes résolvent leurs jointures en lot. | Invisible en local avec trois lignes ; très visible sur base distante. |
| **Le serveur est seul juge, l'écran évite le clic inutile** | L'interface peut dupliquer une règle pour désactiver un bouton. | Si la copie diverge, l'écran ment — mais il ne peut jamais autoriser. |
| **Désactiver, jamais supprimer** | Des lignes historiques pointent vers l'objet. | Des orphelins, ou un échec de clé étrangère devant l'utilisateur. |
| **Dire ce qui manque AVANT le clic** | Le serveur refuserait de toute façon. | L'utilisateur découvre par une erreur ce qu'on savait déjà. |
| **Ce qui est engendré n'est jamais saisi** | Codes, références, matricules, numéros. | « 202020 », « test2 », « AD20 » — et deux fois le même. |
| **Vérifier avec la commande du Dockerfile** | `./mvnw -o clean package -DskipTests`. | `test-compile` sans `clean` ne recompile rien et dit BUILD SUCCESS sur du code cassé. |

---

## Lot 0 — Dettes courtes ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Compteur produits du tableau de bord | Compte via la route d'**administration**, pas le catalogue public. | Le catalogue public ne renvoie que les publiés : la carte annoncerait « 1 » quand la liste en montre quatre. |
| Pagination du catalogue | `gu-pagination` vit dans la **librairie**, pas dans l'écran. | Huit listes s'en servent. La corriger ici, c'est la corriger partout. L'index part de 0 côté serveur, de 1 à l'affichage ; le dernier élément est borné par le total. |
| Recherche produits | Deux signaux distincts : le texte **saisi** et le filtre **appliqué**. | Les fondre ferait changer le message « aucun résultat pour… » pendant qu'on tape, alors que la liste montre encore l'ancien filtre. |
| Deux états vides | « Aucun résultat » ≠ « catalogue vide ». | Proposer « créez votre premier produit » à qui en a trois cents mais cherche mal, c'est répondre à côté. |

**Reste dans ce lot :** le téléversement du logo marchand et de la photo de
profil — colonnes `logo_cle` et `photo_cle` créées en V23, aucune route.
*(Reporté à la demande.)*

---

## Lot 1 — Commandes et paiements ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| `GET /api/commandes` | Répond à « **qu'est-ce qui attend une action ?** ». Distincte de `/miennes`, qui répond à « où en sont *mes* commandes ? ». | Les fondre derrière un booléen finirait par montrer à un client les commandes de tout le monde. |
| Nom du client dans la liste | Résolu **en une requête pour toute la page** (`NomClient`, projection de quatre colonnes). | Une commande ne porte qu'un `clientId`. Le résoudre ligne par ligne : 25 requêtes pour 25 lignes. |
| Filtres de statut en onglets | La question principale de l'écran mérite un clic, pas un menu. Un point marque les statuts qui **appellent un geste**. | Enterrer le filtre dans un `<select>` fait passer la question pour un détail. |
| Transitions proposées seulement si possibles | `TRANSITIONS_COMMANDE` est **dupliqué** côté frontend, sciemment. | Le serveur reste seul juge. Si sa machine à états change, cette table doit suivre — sinon l'écran propose un bouton qui échouera. |
| Annulation : bouton séparé, motif obligatoire | Annuler une commande payée déclenche un **remboursement**. | « Pourquoi cette commande a-t-elle été annulée ? » est la première question posée quand un client réclame des mois plus tard. |
| Montants = photographies | La fiche l'écrit noir sur blanc. | Sans cette phrase, un écart avec le prix courant du catalogue passe pour un bug. |
| TVA affichée « dont », sous le total | Les prix sont **TTC** : la taxe est contenue, pas ajoutée. | Au même niveau, elle ferait croire à une addition. |
| Remboursement en rouge avec signe | Dans une colonne de montants, un « − » seul se confond avec un tiret de valeur absente. | |
| Bouton « Vérifier » sur les paiements en attente | Redemande l'état à l'opérateur sans attendre la réconciliation. | N'apparaît que sur les encaissements en attente : ailleurs il n'a rien à demander. |
| « Jamais transmis » si aucune référence | Ce n'est pas une donnée manquante, c'est un **fait** : le paiement n'a pas atteint l'opérateur. | Afficher un tiret laisserait croire à un défaut d'affichage. |

---

## Lot 2 — Stock ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Le stock naît **avec** la déclinaison | Invariant I-15. Un événement `VarianteCreee`, écouté par le stock. | **Synchrone, même transaction.** Un `@Async` ou un `AFTER_COMMIT` rouvrirait le trou : une déclinaison sans stock échoue à la *réservation*, c'est-à-dire devant le client, très loin de sa cause. |
| Événement plutôt qu'appel | Le stock dépend du catalogue (pour nommer ce qu'il compte). L'appel inverse ferait un **cycle**, qu'ArchUnit refuse. | Ne pas « simplifier » en appelant `ServiceStock` depuis `ServiceCatalogue` : le build casse. |
| Trois compteurs, jamais un seul | Disponible / réservé / endommagé, plus le total encadré à part. | N'afficher qu'un nombre laisse croire qu'une réservation a fait disparaître la marchandise. |
| Réception ≠ Inventaire | La première **ajoute** ce qui arrive ; le second déclare ce qu'on a **compté**. Pré-remplissages différents, exprès. | Les confondre fait passer un inventaire de 8 pour une livraison de 8 : le stock double sans alerte. |
| L'inventaire envoie le **constat**, jamais l'écart | La quantité théorique a pu bouger entre le comptage et la saisie. | Calculer l'écart côté client donne un ajustement faux dès qu'une commande passe entre-temps. |
| Motif obligatoire sur l'ajustement | Un trou d'inventaire sans explication. | Personne ne saura le combler six mois plus tard. |
| Journal : « disponible 10 → 15 » | Les quantités avant/après sont **conservées**, pas recalculées. | C'est en suivant cette colonne qu'on repère un mouvement qui ne part pas d'où le précédent est arrivé : la trace d'une écriture hors service. |
| Seuil d'alerte réglable | À zéro, l'alerte ne se déclenche qu'à la rupture — trop tard, la marchandise met des jours à venir de Douala. | Gardé par `STOCK_AJUSTER`, pas `STOCK_CONSULTER` : décider quand l'équipe est alertée n'est pas de la consultation. |
| Ni réservation ni libération exposées | Ce sont des **conséquences** du paiement, jamais des gestes d'administration. | Les exposer permettrait de libérer du stock réservé par une commande en cours de paiement. |

---

## Lot 3 — Les déclinaisons structurées 🎯 *à faire — priorité*

> **C'est le lot qui règle la confusion actuelle.** Voir l'analyse en annexe :
> le modèle de GARAH est déjà celui d'Amazon, mais l'interface ne s'en sert pas.

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Référentiel d'**attributs** (`Taille`, `Couleur`, `Conditionnement`) | Défini **une fois pour tout le catalogue**, pas par produit. | Sinon chaque produit invente ses valeurs — « M », « m », « Medium », « Moyen » — et aucun filtre transversal n'est possible. |
| Valeurs ordonnées (`38, 39, 40, 41, 42`) | La colonne `ordre` existe. | Trier alphabétiquement donne `10, 38, 9`. Une taille se lit dans l'ordre des tailles. |
| `type_affichage` : LISTE ou PASTILLE | La pastille porte une couleur (`valeur_affichage`, un hexadécimal). | Une couleur affichée en texte oblige le client à imaginer « Bleu ciel ». |
| **Thème de déclinaison** par produit | Un produit se décline selon *une* combinaison d'attributs : `Taille`, ou `Taille × Couleur`. | Mélanger des dimensions différentes entre déclinaisons d'un même produit rend la grille de choix impossible à afficher. |
| Le SKU est **engendré** | `CH-2026-42-BLANC`, dérivé de la référence produit et des valeurs. | Saisi à la main, il devient « Taille », « BL460 », « 42 » — c'est arrivé. |
| L'intitulé est **calculé** | « Taille 42 — Blanc », composé des valeurs choisies. | Saisi à la main, il diverge du SKU et des attributs, et plus rien ne concorde. |
| Création par **grille** | On choisit `42, 43` × `Blanc, Noir` → quatre déclinaisons d'un coup. | Les créer une par une garantit des oublis et des intitulés incohérents. |
| La déclinaison **par défaut** reste | Un sac de ciment n'a aucune déclinaison réelle et en a quand même une (D-01). | Sans elle, tout le code aval devrait tester « ce produit a-t-il des déclinaisons ? » — test qui finira par être oublié au panier. |

**Migration nécessaire :** les déclinaisons existantes ont un intitulé libre
(« 42 », « Blanc ») sans attribut rattaché. Elles restent valides — le champ
`libelle` ne disparaît pas — mais ne bénéficient pas des sélecteurs tant qu'on
ne leur rattache pas de valeurs.

---

## Lot 4 — Logistique

Le plus gros lot du back-office. C'est l'axe **Douala → Bertoua → Bangui**.

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Lieux : points de transit et de récupération | `ControleurLieu` n'a que des `GET` — **impossible d'ajouter un point**. | Sans cette route, aucune commande ne peut être passée : le point de récupération est obligatoire. |
| Itinéraires | Une suite d'étapes types entre deux villes. | |
| Expéditions et colis | Une expédition groupe des colis ; un colis porte des lignes de commande. | Un colis peut contenir des articles de plusieurs commandes. |
| **Événements datés**, pas un statut | « Réceptionné à Bertoua le 12/03 à 14 h par David ». | Une colonne `statut` écrasée perd le parcours dès que le colis repart. C'est la règle fondatrice n°1. |
| Suivi public par numéro | `/suivi/:numero`, **sans compte**. | Un client qui doit se connecter pour savoir où est son colis appellera au téléphone. |
| Retrait : confirmation et refus | Le retrait clôt le parcours. | |

**Backend manquant :** `GET /api/expeditions` (liste), création et modification
de lieux, gestion des itinéraires.

---

## Lot 5 — SAV : réclamations et retours

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Réclamations à traiter | Prise en charge puis résolution. | |
| Retours : acceptation, refus, réception, validation, clôture | Cinq étapes, pas un booléen. | Un retour accepté n'est pas un retour reçu, ni un retour remboursé. Les fondre fait rembourser de la marchandise jamais rentrée. |
| Un retour **en bon état** redevient vendable | Un retour abîmé rejoint le compteur `ENDOMMAGEE`. | Sans cette distinction, on revend un article inutilisable. |

**Backend manquant :** `GET /api/sav/retours` (liste).

---

## Lot 6 — Service client et négociation

| Fonctionnalité | Intention | Attention |
|---|---|---|
| File d'attente, affectation, réaffectation | Qui traite quoi. | |
| Messages et pièces jointes | | |
| **Propositions de prix** | Le client négocie ; une proposition acceptée devient une commande. | C'est un vrai flux commercial, pas une messagerie. |
| Évaluation après clôture | | |

**Backend manquant :** liste des conversations d'un agent (seule la file
d'attente se liste).

---

## Lot 7 — Finance marchand

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Solde d'un marchand | C'est une **somme d'écritures**, jamais un total stocké. | Un total stocké finit par mentir. |
| Grand livre | Chaque vente crée une écriture, commission comprise. | |
| Règlements : création, confirmation, annulation | | |
| Ajustements | | |
| Règles de commission | Table `regle_commission`, **aucune route**. | Sans elle, le taux appliqué à la commande ne peut pas être configuré. |

**Backend manquant :** liste des marchands avec leur solde, routes sur les
règles de commission.

---

## Lot 8 — Clients et surveillance

| Fonctionnalité | Intention | Attention |
|---|---|---|
| **Contrôleur `Client` entier** | Les 7 permissions du module `CLIENT` ne sont branchées nulle part. | |
| Fiche client : commandes, adresses, activité | | |
| Score de risque **explicable** | L'écran doit répondre à « pourquoi HIGH ? » par la liste des signaux. | Un score sans explication ne permet aucune décision. |
| Alertes et décision | La surveillance **observe**, elle ne décide pas. Un humain tranche. | Bloquer automatiquement sur un score, c'est refuser des clients légitimes sans recours. |
| Journal d'audit | Qui a modifié quoi. | Ne pas mélanger avec `activite_client` (parcours) ni `evenement_securite` (authentification). |

---

## Lot 9 — Statistiques

Aucun backend à écrire, tout existe.

| Fonctionnalité | Intention |
|---|---|
| Produits tendance, vues, favoris | Ces chiffres **ne se calculent pas rétroactivement** : c'est pourquoi les tables de mesure existent depuis la v1, avant les écrans. |

---

## Lot 10 — L'application client

> Rien n'existe. `frontend/projects/` ne contient que `garah-admin` et
> `garah-ui`. C'est **le plus gros morceau restant**, probablement plus que les
> lots 1 à 9 réunis.

| Écran | Intention |
|---|---|
| Vitrine et catégories | Le catalogue public, produits publiés seulement. |
| Fiche produit | **Sélecteurs de déclinaison** (lot 3) et grille de prix par quantité. |
| Panier | |
| Tunnel de commande | Adresse, **point de récupération**, récapitulatif. |
| Paiement MTN MoMo / Orange Money | Asynchrone : l'écran doit attendre sans mentir. |
| Mes commandes, suivi de colis | |
| Réclamations et retours | |
| Conversation et négociation | |
| Compte : inscription, vérification e-mail | Un client n'a **aucune permission** : son accès repose sur la propriété de ses données. |

---

## Lot 11 — Finitions

| Fonctionnalité | Intention |
|---|---|
| Exceptions de permission (ADD/REMOVE) | `poserException()` existe, aucune route. **Le piège de l'exception orpheline (ch. 01 §4.4) n'est toujours pas tranché.** |
| Affectation marchand ↔ responsable | Table `gestion_marchand`, aucun service. |
| Notifications | Table `notification`, aucune route. |
| Appareils connus | Table `appareil_connu`, aucune route. |
| Téléversement logo et photo de profil | Colonnes prêtes depuis V23. |
| Variables Render `GARAH_MAIL_*` | Sans elles, la vérification d'adresse ne part pas. |
| Base de recette | Aujourd'hui `ng serve` vise la **production**. |

---

## Annexe — Pourquoi les déclinaisons ressemblent déjà à Amazon

### Le modèle d'Amazon

| Amazon | GARAH | Rôle |
|---|---|---|
| **Parent ASIN** | `produit` | Non achetable. Porte le titre, la description, les photos, la catégorie. |
| **Child ASIN** | `variante` | **C'est lui qu'on achète.** Son propre SKU, son prix, son stock. |
| **Variation theme** | `attribut` | La dimension : Taille, Couleur. |
| Valeurs du thème | `valeur_attribut` | 42, 43, Blanc, Noir — avec ordre et couleur d'affichage. |
| Rattachement | `variante_attribut` | Quelle déclinaison porte quelles valeurs. |

Les règles d'Amazon sur ce qui *peut* être une déclinaison :

- les produits sont **fondamentalement les mêmes** ;
- ils pourraient partager le même titre ;
- ils ne diffèrent que d'une façon qui ne change pas l'usage ;
- **le client s'attend à les trouver sur la même page**.

Ce qu'Amazon interdit : réunir une valise à roulettes et une valise à
bandoulière. Ce ne sont pas deux déclinaisons, ce sont deux produits.

### Ce qui manque chez GARAH

Le schéma est complet. **L'interface ne s'en sert pas** : elle demande un
`libelle` en texte libre et rien d'autre. `ServiceCatalogue.ajouterVariante()`
accepte pourtant une `List<ValeurAttribut>` — le contrôleur passe toujours
`List.of()`.

D'où ce qu'on observe :

```
Intitulé « 42 »      Référence « Taille »     ← les deux champs inversés
Intitulé « Blanc »   Référence « BL460 »      ← référence inventée
```

Rien de tout cela n'est filtrable, ordonnable, ni affichable en sélecteur. Le
lot 3 branche la machinerie qui existe déjà.

---

*Sources sur le modèle Amazon :*
[ecomEngine — Creating Variations on Amazon](https://www.ecomengine.com/blog/creating-variations-on-amazon) ·
[My Amazon Guy — Variation Relationships](https://myamazonguy.com/parentage/amazon-variation-relationships-guide/) ·
[SellerApp — Amazon Variation Listings](https://www.sellerapp.com/blog/amazon-variation-listings/)
