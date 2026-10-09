# Chapitre 13 bis — Deux fournisseurs de paiement : Campay et MoneyFusion

> Prérequis : chapitre [13](13-paiement-mobile-money.md).
> Durée de lecture : ~25 min.
> **Un seul sujet compte vraiment ici : un paiement se souvient de chez qui il vient.**

---

## 1. Ce qu'on veut faire

Encaisser pour de vrai.

Jusqu'ici, GARAH passait par **Campay**, et Campay tournait en **bac à sable** :
aucun franc n'a jamais circulé. On ajoute **MoneyFusion**, sur lequel le compte
marchand est ouvert et vérifié.

Trois exigences, décidées avant d'écrire une ligne :

| Question | Décision |
|---|---|
| Que devient Campay ? | Il reste dans le code, **en réserve**. Un réglage choisit le fournisseur actif. |
| Où le client choisit-il MTN ou Orange ? | Sur la **page MoneyFusion**, pas chez nous. |
| Comment le mobile ouvre-t-il la page ? | Dans une **page intégrée** à l'application. |

---

## 2. La notion

### 2.1 Deux façons d'encaisser par mobile money

Les deux fournisseurs ne fonctionnent pas du tout de la même manière.

```text
CAMPAY — « pousser »
  GARAH  ── demande ──▶  Campay  ── fait sonner ──▶  téléphone du client
                                                     le client tape son code

MONEYFUSION — « rediriger »
  GARAH  ── demande ──▶  MoneyFusion  ── renvoie ──▶  l'adresse d'une PAGE
  le client ouvre la page, choisit MTN ou Orange, saisit son numéro, valide
  la page le renvoie vers GARAH (l'« adresse de retour »)
```

Avec la redirection, **GARAH ne voit rien** de ce qui se passe sur la page. Il
ne sait même pas quel opérateur le client va choisir.

### 2.2 Le patron « passerelle »

Quand deux services font la même chose différemment, on écrit **un contrat**
(une interface Java) et **une classe par service**. Le reste du code ne
connaît que le contrat.

```text
            ServicePaiementMobile   (le domaine)
                      │
                      ▼
            PasserellePaiement      (le contrat)
              │               │
              ▼               ▼
        ClientCampay    ClientMoneyFusion
```

Ce n'est pas de l'abstraction pour le plaisir : `ClientCampay` avait été écrit
**dès le départ** comme le seul fichier qui parle à Campay. C'est ce qui a
rendu l'ajout de MoneyFusion possible sans toucher aux règles métier.

### 2.3 Le réglage décide de l'avenir, pas du passé

C'est la notion centrale du chapitre.

Le réglage `GARAH_PAIEMENT_FOURNISSEUR` dit **chez qui créer les nouveaux
paiements**. Il ne dit **rien** des paiements déjà créés.

Imagine qu'on bascule de Campay à MoneyFusion à 14 h 00. Un client a lancé un
paiement Campay à 13 h 58 et valide sur son téléphone à 14 h 01. Si le code
demande à « celui qui est actif » où en est ce paiement, il interroge
MoneyFusion, qui ne connaît pas cette référence. Au bout de 20 minutes, le
paiement est abandonné. **Le client est débité et sa commande est annulée.**

La règle : **un paiement mémorise son fournisseur**, et c'est toujours chez
lui qu'on l'interroge.

---

## 3. Appliqué à GARAH

### 3.1 La migration V40

```sql
ALTER TABLE paiement ADD COLUMN fournisseur varchar(20);       -- CAMPAY, MONEYFUSION ou NULL
ALTER TABLE paiement ALTER COLUMN moyen DROP NOT NULL;          -- inconnu jusqu'à la confirmation
ALTER TABLE paiement ADD COLUMN moyen_fournisseur varchar(40);  -- « orange », mot pour mot

ALTER TABLE paiement ADD CONSTRAINT paiement_moyen_connu
    CHECK (moyen IS NOT NULL OR fournisseur IS NOT NULL);
```

Lis la contrainte à voix haute : *« le moyen peut être inconnu, à condition
qu'un fournisseur le connaisse »*. Un virement saisi à la main n'a personne à
qui redemander son moyen : il doit le dire à la saisie.

Les paiements mobiles existants sont rattachés à Campay par un `UPDATE` dans
la même migration : c'était le seul fournisseur.

### 3.2 Le contrat

```java
public interface PasserellePaiement {
    FournisseurPaiement fournisseur();
    boolean estConfigure();
    boolean estDemonstration();
    Collecte encaisser(DemandeEncaissement demande);
    Optional<EtatTransaction> statut(String reference);
}
```

Une seule forme de réponse pour les deux fournisseurs :

```java
record Collecte(String reference, String codeUssd, String operateur, String urlPaiement)
//                                 ▲ Campay                               ▲ MoneyFusion
```

Chacun remplit le champ qui le concerne. L'écran sait quoi faire selon ce qui
est rempli.

### 3.3 Le choix de la passerelle

```java
// Pour CRÉER : le fournisseur actif.
PasserellePaiement passerelle = passerelles.get(actif);

// Pour INTERROGER : le fournisseur du paiement.
private PasserellePaiement passerelleDe(Paiement paiement) { ... }
```

Deux méthodes, deux questions différentes. Les confondre, c'est le bug du §2.3.

### 3.4 Le webhook ne croit toujours rien

MoneyFusion **ne signe pas** ses notifications. Ça ne change rien pour nous,
parce que la sécurité du chapitre 13 n'a jamais reposé sur la signature :

```text
ce que la notification apporte   « le jeton ABC a bougé »          ← non fiable
ce qui décide                    GET pay.moneyfusion.net/paiementNotif/ABC  ← fiable
```

Un inconnu qui poste un faux `{"tokenPay": "...", "statut": "paid"}` sur notre
webhook n'obtient rien : on ignore son `statut`, et on ne contacte même pas
MoneyFusion si le jeton nous est inconnu.

### 3.5 L'adresse de retour

Après le paiement, la page MoneyFusion renvoie le client vers :

```text
https://www.garah.me/paiement/{commande}?paiement={paiement}
```

Elle porte **l'identifiant du paiement**, parce que la boutique web est
rechargée de zéro au retour : sans lui, elle ne saurait pas quoi vérifier.

L'application mobile, elle, **guette** cette adresse dans sa page intégrée et
la referme d'elle-même.

---

## 4. Les pièges

### 4.1 Retomber sur une valeur par défaut en silence

`GARAH_PAIEMENT_FOURNISSEUR=MONEY_FUSION` (avec un tiret bas) n'existe pas. Si
le code retombait sur Campay, la boutique encaisserait **en bac à sable** sans
que personne ne le voie. Le serveur **refuse de démarrer** : une panne au
déploiement se voit, un encaissement fantôme non.

### 4.2 Inventer une valeur pour boucher un trou

Pour éviter de rendre `moyen` nullable, on aurait pu ajouter `MOBILE_MONEY` à
la liste. Mauvaise idée : une valeur qui veut dire « pas encore connu » dans
une colonne qui veut dire « MTN ou Orange » finit comptée comme **un troisième
opérateur** dans les statistiques.

### 4.3 Refuser de confirmer ce qu'on ne sait pas nommer

Si MoneyFusion annonce un moyen qu'on ne sait pas traduire (« wave »),
faut-il refuser la confirmation ? **Non** : l'argent est encaissé. Refuser
laisserait un client débité avec une commande impayée. On confirme, le moyen
reste `NULL`, et l'annonce brute est gardée dans `moyen_fournisseur`.

### 4.4 Le montant brut ou net ?

La documentation MoneyFusion montre `Montant: 200, frais: 5` dans un exemple
et `Montant: 194, frais: 6` dans un autre. Si `Montant` est le **net** après
frais, comparer `Montant` au prix de la commande ferait échouer **chaque**
paiement réussi. On compare donc `Montant + frais`, et on journalise les deux
à chaque confirmation. **Le premier paiement réel tranchera** : c'est un point
à vérifier, pas une certitude.

### 4.5 Les IP déclarées

MoneyFusion refuse tout appel venant d'une IP non déclarée. Une application
Azure a **plusieurs** adresses sortantes possibles. N'en déclarer qu'une, c'est
un paiement qui marche une fois sur deux — le pire des bugs, celui qu'on ne
reproduit pas.

### 4.6 Les anciennes versions de l'application

Une application mobile installée ne se met pas à jour toute seule. Une
ancienne version reçoit une réponse avec `urlPaiement` et sans `codeUssd` :
elle attend un code qui ne viendra jamais. **On ne bascule le réglage qu'après
avoir diffusé la nouvelle version.**

---

## 5. À retenir

1. Deux services qui font la même chose différemment : **un contrat, une classe par service**.
2. Le réglage décide des paiements **à venir** ; un paiement existant est interrogé **chez celui qui l'a reçu**.
3. Une valeur de réglage inconnue doit **empêcher le démarrage**, pas retomber en silence sur une valeur par défaut.
4. On n'invente pas de valeur pour boucher un trou : on rend la colonne nullable **et on dit par une contrainte quand c'est permis**.
5. Le webhook reste un **signal** : sa signature, présente ou absente, n'est pas ce qui protège.

---

## 6. Exercices

**Exercice 1.**
Écris la requête SQL qui liste les paiements **en attente** par fournisseur.
Combien de paiements Campay faudra-t-il surveiller juste après la bascule ?

**Exercice 2.**
On ajoute un troisième fournisseur. Liste les fichiers à créer et ceux à
modifier. Lequel des deux tests de `ChoixDuFournisseurTest` doit changer ?

**Exercice 3.**
Pourquoi l'adresse de retour est-elle fabriquée par le **serveur**, et jamais
envoyée par l'écran ? Décris ce qu'un attaquant pourrait faire sinon.

**Exercice 4.**
Le premier paiement réel montre `attendu 15000, encaisse 15300` dans les
journaux. Qu'en conclus-tu sur `Montant` ? Que faut-il changer dans
`ClientMoneyFusion.montantPaye`, et quel test doit suivre ?

**Exercice 5.**
`GARAH_MONEYFUSION_WEBHOOK_URL` est vide. Les paiements sont-ils perdus ?
Combien de temps au plus un client attend-il sa confirmation ?

---

➡️ **Chapitre suivant :** [14 — Les conversations et la négociation](14-conversations-et-negociation.md)
