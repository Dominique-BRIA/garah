# Chapitre 08 — Authentification, JWT et contrôle des permissions

> Prérequis : chapitre [07](07-entites-jpa-et-transactions.md).
> Durée de lecture : ~40 min.
> **Le chapitre le plus sensible du cours** : une erreur ici ne se voit pas,
> et se paie cher.

---

## 1. Ce qu'on veut faire

Trois questions, dans cet ordre :

| Question | Nom | Réponse dans GARAH |
|---|---|---|
| **Qui es-tu ?** | Authentification | E-mail + mot de passe → un jeton |
| **Qu'as-tu le droit de faire ?** | Autorisation | Les codes de `cas_utilisation` |
| **Comment le prouver à chaque appel ?** | Session | Un JWT signé, sans état serveur |

---

## 2. Le problème de l'amorçage

Avant toute chose, un paradoxe :

```text
Toutes les routes exigent un jeton.
Un jeton s'obtient en se connectant.
Se connecter demande un compte.
Créer un compte demande… un jeton.
```

Il faut donc **un** point d'entrée. Deux mauvaises solutions et une bonne :

| Solution | Verdict |
|---|---|
| Un compte par défaut dans une migration | ❌ Un mot de passe versionné dans git est un mot de passe **public**. Et la migration serait rejouée à l'identique en production. |
| Une route « créer le premier admin » ouverte | ❌ On oublie de la fermer. C'est arrivé à beaucoup de monde. |
| Un `ApplicationRunner` qui lit l'environnement | ✅ Retenu |

```java
if (email.isBlank() || motDePasse.isBlank()) return;          // rien à faire
if (!utilisateurs.findByType(SUPER_ADMIN).isEmpty()) return;  // déjà amorcé
```

**Deux propriétés importantes :** il ne fait rien si les variables sont
absentes, et il est **idempotent** — redémarrer l'application ne réinitialise
pas un mot de passe déjà changé.

```bash
GARAH_SUPERADMIN_EMAIL=admin@garah.cm
GARAH_SUPERADMIN_MOT_DE_PASSE=…
mvn spring-boot:run
# puis on retire les variables
```

---

## 3. Les mots de passe

### 3.1 On ne stocke jamais un mot de passe

On stocke une **empreinte** : le résultat d'une fonction à sens unique.
Vérifier consiste à recalculer l'empreinte et à comparer.

```text
"MotDePasse!2026"  ──BCrypt──▶  $2a$12$Yl3zvfM8xUqOl0lWq4mBOe…
                                 ▲
                                 └── impossible de revenir en arrière
```

### 3.2 Pourquoi BCrypt et surtout pas SHA-256

C'est contre-intuitif : **une bonne fonction de hachage de mot de passe est
volontairement LENTE**.

| | SHA-256 | BCrypt coût 12 |
|---|---|---|
| Durée d'un calcul | ~0,000001 s | ~0,25 s |
| Essais/seconde pour un attaquant | des **milliards** | quelques **dizaines** |
| Conçu pour | vérifier des fichiers vite | résister à la force brute |

SHA-256 est excellent — pour ce à quoi il sert. Sur un mot de passe, sa
rapidité travaille **pour l'attaquant**.

Chaque incrément de coût **double** le temps de calcul. Le coût 12 est
imperceptible pour un humain qui se connecte une fois, et ruineux pour qui
essaie des millions de combinaisons.

```java
new BCryptPasswordEncoder(12);
```

BCrypt intègre aussi un **sel** aléatoire : deux personnes avec le même mot de
passe ont des empreintes différentes. Une table pré-calculée devient inutile.

---

## 4. Deux fuites d'information à ne pas laisser

### 4.1 Le message d'erreur qui trahit

```text
❌ "Cette adresse e-mail n'existe pas."     ← le formulaire devient un ANNUAIRE
❌ "Mot de passe incorrect."                ← confirme que le compte existe

✅ "Adresse e-mail ou mot de passe incorrect."   dans les DEUX cas
```

Un attaquant essaierait des adresses jusqu'à voir le message changer, et
saurait alors exactement qui possède un compte sur GARAH.

### 4.2 La fuite par le temps de réponse

Celle-ci est plus subtile, et beaucoup l'ignorent.

```text
Adresse INCONNUE  → on répond tout de suite            ~2 ms
Adresse EXISTANTE → on calcule BCrypt puis on répond   ~250 ms
```

Le message est identique, mais **le chronomètre parle**. Un simple script
mesure les temps de réponse et reconstitue la liste des comptes.

La parade : calculer BCrypt **même quand le compte n'existe pas**, contre une
empreinte factice.

```java
if (trouve.isEmpty()) {
    encodeur.matches(motDePasse, EMPREINTE_LEURRE);   // ← ~250 ms, pour rien
    securite.enregistrerEchecConnexion(null, adresseIp, "Adresse inconnue");
    throw new IdentifiantsInvalides();
}
```

> 🎯 **La leçon générale : une réponse ne fuit pas que par son contenu.**
> Elle fuit aussi par sa durée, sa taille et son code HTTP. C'est ce qu'on
> appelle un **canal auxiliaire**.

### 4.3 L'exception assumée : le compte bloqué

```java
if (!utilisateur.estActif()) throw new CompteBloque();   // message CLAIR
```

Pourquoi être précis ici alors qu'on était vague avant ? Parce qu'à ce stade
la personne a **prouvé son identité** : elle connaît le mot de passe. Il n'y a
plus rien à protéger en restant flou — et un message vague la ferait réessayer
indéfiniment.

---

## 5. Le jeton JWT

### 5.1 Trois parties, séparées par des points

```text
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwicGVybWlzc2lvbnMiOls… . 3Vk8f2…
└── en-tête ─────────┘ └── contenu ──────────────────────┘ └ signature ┘
     algorithme            qui, quoi, jusqu'à quand         preuve
```

### 5.2 Signé n'est pas chiffré

C'est **la** confusion à ne pas faire. Voici le contenu réel d'un jeton GARAH,
décodé en une commande, sans aucune clé :

```json
{ "sub": "1", "permissions": ["ADMIN_ACTIVER", "ADMIN_CONSULTER", …],
  "nom": "Super Administrateur", "langue": "fr", "exp": 1757155200 }
```

**N'importe qui peut lire ça.** La signature garantit seulement qu'on ne peut
pas le **modifier** sans la clé.

```text
✅ à mettre dans un jeton   identifiant, type d'acteur, nom affiché,
                            permissions, date d'expiration
❌ à ne JAMAIS y mettre     mot de passe (même haché), e-mail, téléphone,
                            adresse, toute donnée personnelle
```

### 5.3 Le piège HS256 / RS256

Ma première version échouait avec un message peu parlant :

```text
Failed to select a JWK signing key
```

**Cause :** deux familles de signature existent.

| | Clé | Usage |
|---|---|---|
| **HS256** | Un **secret partagé** | Un seul service signe et vérifie |
| **RS256** | Une paire **privée / publique** | Un service signe, plusieurs vérifient |

Nimbus suppose **RS256** par défaut, cherche une clé RSA, n'en trouve pas.
Il faut déclarer l'algorithme dans l'en-tête :

```java
JwsHeader entete = JwsHeader.with(MacAlgorithm.HS256).build();
encodeur.encode(JwtEncoderParameters.from(entete, claims));
```

GARAH n'a qu'une API : **HS256 suffit**. Le jour où plusieurs services devront
vérifier les jetons sans pouvoir en fabriquer, il faudra passer à RS256.

> ⚠️ **Le secret doit faire au moins 32 octets** (256 bits). Plus court,
> HMAC-SHA256 refuse la clé et l'application ne démarre pas — ce qui est
> exactement le bon comportement.

---

## 6. Les permissions dans le jeton, et le prix à payer

Le jeton transporte les permissions. Autoriser un appel ne demande donc
**aucune requête en base** :

```text
Requête ──▶ signature vérifiée ──▶ permissions lues DANS le jeton ──▶ décision
                                   (zéro accès base)
```

C'est ce qui rend l'API réellement **sans état** — précieux avec Neon, qui
limite le nombre de connexions (D-14).

### Le prix : la fraîcheur des droits

```text
10 h 00   Paul se connecte. Son jeton contient PRIX_MODIFIER.
10 h 15   Un Admin lui retire PRIX_MODIFIER.
10 h 16   Paul modifie un prix.                    ✅ ACCEPTÉ
10 h 59   Paul modifie un prix.                    ✅ ACCEPTÉ
11 h 00   Le jeton expire.
11 h 01   Paul se reconnecte.                      ❌ enfin refusé
```

**Un droit retiré met jusqu'à 60 minutes à s'appliquer.**

L'alternative — relire les permissions en base à chaque appel — donne une
révocation immédiate, au prix d'une requête par requête. Le choix est
documenté en [D-16](../decisions.md#d-16--permissions-dans-le-jeton-fraîcheur-limitée-à-60-minutes),
avec les trois façons d'en sortir le jour où ça deviendra gênant.

> 📌 **Ce qu'il faut retenir :** ce n'est pas « la bonne » solution, c'est un
> **compromis assumé et écrit**. Un compromis non documenté devient une
> surprise ; documenté, il devient une décision.

---

## 7. Autoriser

### 7.1 Les codes du référentiel, tels quels

Par défaut, Spring Security préfixe les autorisations (`SCOPE_`, `ROLE_`).
On désactive ce préfixe :

```java
autorites.setAuthoritiesClaimName("permissions");
autorites.setAuthorityPrefix("");
```

Pourquoi ça compte :

```java
@PreAuthorize("hasAuthority('PRODUIT_PUBLIER')")   // exactement le code en base
```

**Aucune traduction entre la base, le jeton et le code Java.** Une traduction,
c'est un endroit où se tromper. Et c'est le même code qui sert de clé de
traduction dans les fichiers i18n (D-09) : un seul identifiant, trois usages.

### 7.2 Les droits selon le type d'acteur

```java
case SUPER_ADMIN -> tousLesCodesActifs();                      // 188
case ADMIN       -> codesActifsHorsModule("SECURITE");         // 177
case RESPONSABLE -> permissions.permissionsEffectives(id);     // ses catégories
case CLIENT      -> Set.of();                                  // aucune
```

Le `CLIENT` n'a **aucune** permission, et ce n'est pas un oubli : son accès
repose sur la **propriété** de ses données (« c'est ma commande »), pas sur des
droits. Deux mécanismes différents, à ne pas mélanger.

L'`ADMIN` a tout **sauf** le module `SECURITE` — c'est la ligne de partage du
chapitre 01 : le SuperAdmin agit **sur** le SI, l'Admin **dans** le SI.

---

## 8. Le piège qui m'a ouvert une route

Encore un défaut trouvé en appelant l'API, pas en relisant.

```java
.requestMatchers("/api/sante", "/api/auth/**").permitAll()    // ❌
```

Ça paraît raisonnable : « les routes d'authentification sont publiques ».
Sauf que `/api/auth/**` couvre aussi **`/api/auth/moi`**, qui doit être
protégée.

Le symptôme n'était même pas un accès non autorisé — c'était un `500` :

```text
GET /api/auth/moi   sans jeton   →   500 ERREUR_INTERNE
```

La route étant ouverte, la requête atteignait le contrôleur, le jeton était
`null`, et le code plantait.

```java
.requestMatchers("/api/sante").permitAll()                        // ✅
.requestMatchers(HttpMethod.POST, "/api/auth/connexion").permitAll()
.anyRequest().authenticated()
```

Après correction :

```text
sans jeton      →  401
jeton bidon     →  401
jeton valide    →  200
```

> 🎯 **Deux règles, et la seconde annule presque la première si on l'oublie :**
>
> 1. On liste ce qui est **ouvert**, jamais ce qui est fermé. Un oubli laisse
>    alors une route **fermée** — le bon sens de l'erreur.
> 2. **Pas de joker dans une règle de sécurité.** `/**` ouvre toujours plus que
>    ce qu'on avait en tête, y compris les routes qui n'existent pas encore.

Et note le détail : on précise même la **méthode** (`POST`). `GET /api/auth/connexion`
n'a aucune raison d'être public.

---

## 9. Journaliser les échecs — sans perdre la trace

La spec §18 veut surveiller les échecs de connexion. Il y a un piège
transactionnel.

```text
1. La connexion échoue
2. On enregistre l'événement de sécurité
3. On lève l'exception
4. Spring annule la transaction…  et efface l'événement ❌
```

**On perdrait la trace au moment précis où elle compte.**

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void enregistrerEchecConnexion(…) { … }
```

`REQUIRES_NEW` ouvre une transaction **indépendante**, qui valide même si
l'appelante est annulée.

> 📌 **Le journal de sécurité doit survivre à ce qu'il journalise.**

### La gravité vient de la répétition

```java
if (echecsDeLaDerniereHeure >= 5) gravite = HAUTE;
```

Un échec isolé est banal — on se trompe de mot de passe. Six en une heure ne le
sont pas. **C'est la répétition qui fait le signal, pas l'événement.**

Et — règle fondatrice n°2 — ce service ne bloque **aucun** compte. Il constate.
Un humain décidera.

### La conséquence dans les tests

`REQUIRES_NEW` a un effet visible : dans un test annulé en fin d'exécution, les
événements de sécurité **restent en base**, puisqu'ils ont été validés à part.
`ServiceAuthentificationTest` n'est donc pas `@Transactional` et nettoie
explicitement. C'est le comportement voulu qui se manifeste, pas un défaut.

---

## 10. CSRF : pourquoi le désactiver est correct ici

```java
.csrf(csrf -> csrf.disable())
```

Désactiver CSRF fait sursauter, à juste titre. Mais il faut savoir **contre
quoi** CSRF protège.

```text
L'attaque CSRF exploite le fait que le NAVIGATEUR envoie
automatiquement les COOKIES d'un site, même depuis un autre site.

GARAH n'utilise pas de cookie de session.
Le jeton voyage dans un en-tête Authorization.
Un en-tête n'est JAMAIS envoyé automatiquement : il faut du JavaScript,
et la politique d'origine l'empêche depuis un autre site.
```

**Pas de cookie ⇒ pas de CSRF possible.**

> ⚠️ Mais si un jour on stocke le jeton dans un cookie — ce qui se discute,
> parce que `localStorage` est vulnérable au XSS — **il faudra réactiver CSRF**.
> La décision « pas de CSRF » est liée à « pas de cookie », pas à « c'est une API ».

---

## 11. À retenir

1. **L'amorçage se fait par l'environnement**, jamais par une migration : un mot de passe dans git est public.
2. **BCrypt, volontairement lent.** SHA-256 est rapide, donc parfait pour l'attaquant.
3. **Même message d'erreur** pour compte inconnu et mot de passe faux — sinon le formulaire devient un annuaire.
4. **Même temps de réponse aussi** : une réponse fuit par sa durée, pas seulement par son contenu.
5. Un JWT est **signé, pas chiffré**. Tout le monde peut le lire.
6. **HS256** pour un service unique ; RS256 quand plusieurs services vérifient.
7. Les permissions dans le jeton évitent une requête par appel, au prix d'une **révocation différée de 60 min** (D-16).
8. **Pas de joker dans une règle de sécurité.** `/api/auth/**` ouvre plus que prévu.
9. Le **journal de sécurité** s'écrit en `REQUIRES_NEW` : il doit survivre à l'échec qu'il enregistre.
10. **Pas de cookie ⇒ pas de CSRF.** Le jour où on met le jeton dans un cookie, il faut le réactiver.

---

## 12. Ce qui tourne aujourd'hui

```text
GET  /api/sante                 public
POST /api/auth/connexion        public
GET  /api/auth/moi              jeton obligatoire

sans jeton     → 401
jeton invalide → 401
jeton valide   → 200

SuperAdmin connecté : 188 permissions dans son jeton
17 tests automatisés au vert
```

---

## 13. Exercices

**Exercice 1.**
Décode le contenu d'un jeton GARAH (la partie centrale, en base64url).
Liste ce qu'il révèle sur toi, et dis si l'un de ces éléments te gêne.

**Exercice 2.**
Un Admin retire `PAIEMENT_REMBOURSER` à Paul, connecté depuis 5 minutes.
Combien de temps Paul peut-il encore rembourser ? Propose deux façons de
réduire ce délai, avec leur coût.

**Exercice 3.**
Un développeur remplace `BCryptPasswordEncoder(12)` par `(4)` parce que
« les tests sont lents ». Explique le raisonnement, calcule combien de fois
l'attaque devient plus rapide, et propose une solution qui garde les tests
rapides sans affaiblir la production.

**Exercice 4.**
Écris le test qui vérifie qu'un `RESPONSABLE` sans la permission
`PRODUIT_PUBLIER` reçoit bien un `403` sur une route protégée par
`@PreAuthorize`. Quelle différence de sens entre `401` et `403` ?

**Exercice 5.**
On veut stocker le jeton dans un cookie `HttpOnly` plutôt que dans
`localStorage`. Liste ce que ça améliore, ce que ça dégrade, et **tout** ce
qu'il faudrait changer dans la configuration de sécurité.

---

➡️ **Chapitre suivant :** 15 — Le catalogue : produits, variantes et médias
*(les chapitres Angular 09 à 14 sont repoussés après le backend — voir le sommaire)*
