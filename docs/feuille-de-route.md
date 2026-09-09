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

## Lot 3 — Les déclinaisons structurées ✅ *fait*

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

## Lot 4 — Logistique ✅ *fait*

Le plus gros lot du back-office. C'est l'axe **Douala → Bertoua → Bangui**.

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Lieux : points de transit et de récupération | `ControleurLieu` n'avait que des `GET` — **impossible d'ajouter un point**. | Sans cette route, aucune commande ne peut être passée : le point de récupération est obligatoire. |
| Itinéraires | Un **modèle** de trajet, réutilisable. Il sert à ne pas resaisir le même chemin et à annoncer un délai. | ⚠️ Ce n'est **pas** une contrainte : le colis peut s'en écarter, et rien ne l'en empêche. Une route coupée, ça arrive — et un contrôle qui refuse la réalité fait saisir n'importe quoi d'autre. |
| Étapes d'itinéraire remplacées **en bloc** | La contrainte I-34 refuse deux étapes de même rang, **même transitoirement**. | Réordonner rang par rang traverse forcément cet état. Une route « monter cette étape » réussirait ou échouerait selon l'ordre des clics. |
| Expéditions et colis | Une expédition groupe des colis ; un colis porte des lignes de commande. | Un colis peut contenir des articles de plusieurs commandes. |
| Création de l'expédition depuis la **fiche commande** | C'est là qu'on décide qu'une commande payée doit partir. | La destination **ne se saisit pas** : le client l'a choisie et payée. Un champ « destination » permettrait d'expédier ailleurs qu'à l'endroit facturé. |
| **Événements datés**, pas un statut | « Réceptionné à Bertoua le 12/03 à 14 h par David ». | Une colonne `statut` écrasée perd le parcours dès que le colis repart. C'est la règle fondatrice n°1. |
| Suivi public par numéro | `/suivi/:numero`, **sans compte**, hors de la coque. | Le numéro **est** dans l'URL — il se partage, c'est son rôle. La réponse ne porte ni destinataire, ni contenu, ni agent : un numéro circule par SMS. |
| Comptoir : **voir**, puis remettre | Saisir le code, voir les colis qu'il désigne, les sortir, confirmer. | 🎯 Fusionner recherche et confirmation « pour gagner un clic » rendrait la remise **aveugle** : le code validé, la marchandise au hasard. |
| Retrait : confirmation et refus | Le retrait clôt le parcours. Un refus se motive. | Le code voyage dans le **corps**, jamais dans l'URL : journaux, historique, en-tête `Referer`. |

> **Trois fois le même défaut, corrigé trois fois.** Le `clientId` du retrait,
> la destination de l'expédition, le nom des lieux dans le suivi : chaque fois,
> l'information existait déjà en base et l'interface la redemandait — ou la
> rendait illisible. La règle qui s'en dégage vaut pour les lots suivants :
> **ce que le système sait déjà ne se saisit pas.**

**Ce qui reste hors de portée :** dérouler la chaîne à la main. Une expédition
part d'une commande, et rien dans le back-office n'en crée. Voir `recette.md`
section 6 — c'est la vitrine (lot 10) qui débloquera la recette manuelle.

---

## Lot 5 — SAV : réclamations et retours ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Réclamations à traiter | Prise en charge puis résolution. | |
| Retours : acceptation, refus, réception, validation, clôture | Cinq étapes, pas un booléen. | Un retour accepté n'est pas un retour reçu, ni un retour remboursé. Les fondre fait rembourser de la marchandise jamais rentrée. |
| Un retour **en bon état** redevient vendable | Un retour abîmé rejoint le compteur `ENDOMMAGEE`. | Sans cette distinction, on revend un article inutilisable. |
| Deux chiffres par retour, jamais un | *Articles annoncés* est ce que le client **déclare** ; *montant remboursé* est ce qui a été **constaté** après ouverture du colis. | Les afficher comme un seul « montant du retour » serait un contresens : entre les deux, quelqu'un a vu la marchandise. Le second vaut zéro tant que le retour n'est pas validé. |
| Réclamations et retours : **deux écrans**, pas deux onglets | Une réclamation est une plainte qu'un humain tranche ; un retour est une marchandise qui revient. | Les gestes, les permissions et les gens ne sont pas les mêmes. |
| Trancher en faveur du client **ne rembourse pas** | Rendre l'argent est une décision distincte, avec sa propre permission. | Lier les deux ferait qu'un agent de SAV déclenche des mouvements d'argent sans en avoir le droit. |
| Les plus **anciennes** en tête | L'inverse des listes de catalogue, et c'est voulu. | Une réclamation qui traîne est un client qui s'énerve : c'est celle-là qu'il faut voir en haut, pas la dernière arrivée. |

---

## Lot 6 — Service client et négociation ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| File d'attente, affectation, réaffectation | Qui traite quoi. | |
| Messages et pièces jointes | | |
| **Propositions de prix** | Le client négocie ; une proposition acceptée devient une commande. | C'est un vrai flux commercial, pas une messagerie. |
| Évaluation après clôture | Uniquement sur une conversation **fermée**. | Demander un avis sur un problème non résolu ne mesure pas le service, il mesure l'agacement. |
| Le chiffre qui trie est celui des **non lus** | Un agent qui ouvre l'écran se pose une seule question : « laquelle attend ma réponse ? ». | Le total des messages n'y répond pas — une conversation de quarante messages tous lus n'attend rien. |
| Filtre « mes dossiers », responsable lu dans le **jeton** | Jamais un `?responsableId=`. | Un paramètre laisserait n'importe quel agent lire la file d'un collègue, et se l'attribuer. |
| La file est **partagée** : le premier qui clique gagne | `UPDATE … WHERE statut = 'WAITING'`, et on compte les lignes modifiées. | Un échec est un `409`, pas une erreur de l'utilisateur : le monde a changé entre l'affichage et le clic. L'écran recharge la liste, sinon on reclique sur des dossiers déjà partis. |
| Le sens d'une proposition vient du **jeton**, jamais du corps | Un client qui pourrait écrire `sens: "RESPONSABLE"` contournerait le seul garde-fou de la négociation. | Il s'accorderait n'importe quelle remise, en une ligne de JSON. |

---

## Lot 7 — Finance marchand ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| Solde d'un marchand | C'est une **somme d'écritures**, jamais un total stocké. | Un total stocké finit par mentir. |
| Grand livre | Chaque vente crée une écriture, commission comprise. | |
| Règlements : **préparer** puis **confirmer** | Un règlement `PRÉVU` n'écrit rien : la dette ne baisse qu'au moment où l'argent part. | Fondre les deux gestes ferait qu'un virement raté laisserait quand même une dette soldée dans nos livres. La référence de versement est obligatoire — c'est la seule preuve six mois plus tard. |
| Ajustements : on **ajoute**, on ne corrige pas | Une écriture fausse s'annule par une autre écriture. | Un grand livre ne se réécrit pas. Le libellé est obligatoire et l'auteur enregistré. |
| Règles de commission | Table `regle_commission` depuis V11, lue à chaque vente, **et aucune route pour y écrire**. | 🎯 Le symptôme est trompeur : *rien n'échoue*. Les commandes passent, le grand livre s'écrit, et les marchands sont crédités de 100 % de la vente. La perte ne se voit qu'en lisant les écritures, longtemps après. |
| L'écran classe les règles dans l'ordre du **calcul** | Priorité, puis marchand nommé, puis catégorie nommée — miroir exact du `ORDER BY` du dépôt. | S'ils divergent, la liste montre une règle en tête et la vente en applique une autre : un écart qu'on ne remarque qu'en comparant deux factures. |
| Une règle se **ferme**, ne se supprime pas | Elle est datée ; on pose une date de fin. | Les commandes passées ont figé leur taux. Effacer la règle rendrait inexplicable une ligne du grand livre vieille de six mois. Les règles expirées et futures restent listées — les masquer ferait chercher pourquoi un taux paramétré ne s'applique pas. |
| Un taux > 50 % demande confirmation | Garde-fou contre la virgule mal placée, pas une limite métier. | « 4,5 » saisi « 45 » multiplie la commission par dix, et rien ne le signalerait avant le premier règlement. |
| Un marchand sans écriture a un solde de **zéro**, et reste listé | L'agrégat ne le renvoie pas ; c'est le service qui complète. | Sinon il disparaît d'une liste qui prétend montrer tous les marchands. |

---

## Lot 8 — Clients et surveillance ✅ *fait*

| Fonctionnalité | Intention | Attention |
|---|---|---|
| **Contrôleur `Client` entier** | Les 7 permissions du module `CLIENT` ne menaient nulle part. | Réservé aux comptes internes : un client gère le sien par `/api/profil`, une route qui ne connaît que **lui**. Les fusionner derrière un `if (estResponsable)` ouvrirait toutes les fiches au premier oubli de condition. |
| La liste est une **projection**, jamais l'entité | Huit colonnes construites en SQL. | Le jour où `Utilisateur` gagne une colonne sensible, la réponse ne la reçoit pas. Un test vérifie la **forme du record** — ajouter un champ sans y penser le fait passer au rouge. |
| Recherche sur code, nom **et** e-mail à la fois | Un agent au téléphone a l'un des trois, jamais les trois. | Obliger à choisir un champ ferait échouer une recherche sur deux. |
| Suspendre met `INACTIF`, **jamais** `BLOQUE` | `INACTIF` est administratif ; `BLOQUE` est une décision de **sécurité**, prise sur alerte. | Les confondre ferait passer pour fraudeur un client simplement désactivé. Un blocage ne se lève pas depuis la fiche : il se tranche depuis l'alerte qui l'a motivé. |
| Ni suppression, ni changement d'e-mail, ni réinitialisation de mot de passe | Le client porte des commandes, des paiements, des écritures ; l'e-mail identifie le compte et sert à s'y connecter. | Effacer rendrait inexplicables des lignes qui restent. Changer l'e-mail depuis le back-office reviendrait à donner un compte à quelqu'un d'autre, sans que le premier soit prévenu. |
| « Adresse non confirmée » affiché dans la liste | Ce compte ne reçoit **aucun** courriel. | C'est la réponse à « je n'ai rien reçu », et elle est visible sans ouvrir la fiche. |
| « Ne s'est jamais connecté » s'écrit en toutes lettres | Une date nulle est une **information**, pas un trou. | Un compte créé et jamais utilisé explique la moitié des litiges de ce genre. |
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

## Lot 10 — L'application client ✅ *fait, et livrée deux fois*

> ⚠️ **Cette section annonçait « rien n'existe »** alors que **deux**
> applications client tournaient. Un document de pilotage faux est pire
> qu'absent : il fait chercher du travail déjà fait, et rate celui qui reste.

| Application | Où | État |
|---|---|---|
| Boutique web | `garah-client/web` | Angular, déployée sur Vercel |
| Mobile | `garah-client/mobile` | Flutter, non déployée |

Les deux couvrent la vitrine, la fiche produit avec ses déclinaisons, le
panier, la commande, le paiement, le suivi, les réclamations et retours, le
compte et les discussions.

> ⚠️ **Aucune des deux ne pouvait se connecter** jusqu'à D-36 : toutes deux
> lisaient une réponse de connexion qui n'existe pas. Elles compilaient, se
> déployaient, et échouaient devant l'utilisateur. Chaque client porte
> maintenant un test de contrat, et le serveur fige les noms.

**Ce qui reste sur le mobile**

| Manque | Note |
|---|---|
| Déploiement | Aucun `.apk` publié, aucun magasin. |
| Propositions de prix | Volontairement absentes : le prix est ferme (D-39). |

---

## Lot 11 — Finitions

| Fonctionnalité | État |
|---|---|
| Exceptions de permission (ADD/REMOVE) | `poserException()` existe, **aucune route**. Le piège de l'exception orpheline (ch. 01 §4.4) n'est toujours pas tranché. |
| Affectation marchand ↔ responsable | Table `gestion_marchand`, aucun service. |
| Notifications | ✅ Module livré (V29), routes et appareils. |
| Messagerie interne | API et WebSocket livrés (V30) — **aucun écran ne les consomme**. |
| Appareils connus | Table `appareil_connu`, aucune route. |
| Téléversement logo et photo | Colonnes prêtes depuis V23. |
| `GARAH_MAIL_*` sur Azure | Sans elles, la vérification d'adresse ne part pas. **Ne bloque rien** : aucune route n'exige un e-mail vérifié. |
| `GARAH_FIREBASE_CREDENTIALS` | À poser **avec une clé renouvelée** — l'ancienne est compromise. |
| Azure Static Web Apps | Jamais créé : le workflow est *skipped* à chaque exécution. |
| Base de recette | ✅ Faite (D-37). |
| `activite_client` | Table déclarée en V12, **ni entité ni écriture**. Le parcours client n'est mesuré que par `vue_produit`. |
| Tests frontend | Boutique : 4 (premiers du dépôt). Back-office : **aucun**. |

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
