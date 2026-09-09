# La marque GARAH

## 🎯 Le logo fourni est la source — on ne le redessine pas, on le SUIT

Tout part de **`source/logo-original.png`**, le fichier livré par le
graphiste. `vectoriser.mjs` en relève le contour pixel par pixel ;
`engendrer-marque.mjs` compose et décline ce relevé.

Redessiner « d'après » l'image donnerait un **second logo** : les proportions
dérivent de quelques pour cent, et deux marques cohabitent sans que personne
ne sache laquelle fait foi. C'est exactement ce qui est arrivé au premier
essai.

```bash
cd marque

# 1. Relever le contour — trois paliers (voir plus bas)
node vectoriser.mjs source/logo-original.png source/garah-symbole.svg           0.55
node vectoriser.mjs source/logo-original.png source/garah-symbole-petit.svg     0.7  4
node vectoriser.mjs source/logo-original.png source/garah-symbole-minuscule.svg 1.0  9

# 2. Composer et décliner
node engendrer-marque.mjs
```

Aucune bibliothèque — seulement **Google Chrome**, qui convertit les tracés en
images. Ce qui se corrige se corrige dans les scripts ou dans le PNG
d'origine, **jamais** dans un fichier produit : la prochaine exécution
l'écraserait.

### Comment le relevé fonctionne

| Étape | Ce qu'elle fait |
|---|---|
| Décodage | le PNG est lu à la main — `zlib` suffit |
| Seuil | opaque à plus de la moitié : le milieu de la frange d'anticrénelage |
| Contour | suivi par les **arêtes des pixels**, orienté, donc toujours refermable |
| Adoucissement | Chaikin, deux passes : l'escalier d'un pixel s'arrondit |
| Réduction | Douglas-Peucker : 4 870 points bruts → 733 retenus |

⚠️ L'adoucissement passe **avant** la réduction, jamais après. Après, on
lisserait une ligne déjà réduite : les points restants flotteraient loin du
contour, et les angles vifs — le bout de la barre de poussée — seraient
rabotés.

---

## La charte

| | Code | Emploi |
|---|---|---|
| **Bleu** | `#2E9CE9` | le symbole, le mot GARAH |
| **Vert** | `#4FBF4B` | la signature « au-delà des frontières » |
| **Nuit** | `#101C3D` | le fond des icônes et des blocs sombres |

Le symbole est **un seul ruban**. Le téléphone n'est pas posé sur un chariot :
son flanc droit descend, tourne, et devient le bord haut du panier. On achète
depuis son téléphone, et la marchandise part.

Le bleu n'est pas choisi : il est **relevé** sur le fichier d'origine par
`vectoriser.mjs`. Le vert et le nuit viennent de la maquette du logo complet.

---

## ⚠️ Trois dessins, pas un seul

Un logo qui rétrécit ne se contente pas de rétrécir. Le symbole existe en
**trois paliers**, et le script choisit lui-même lequel employer :

| Palier | Employé à | Épaississement |
|---|---|---|
| Complet | 48 px et plus | aucun |
| Épaissi | 32 et 48 px | 4 pixels |
| Minuscule | 16 px | 9 pixels |

Sans ces paliers, le favicon de 16 px n'est qu'une tache : les dix trous du
panier y font des carrés d'un pixel, qui se remplissent d'anticrénelage.

⚠️ **Les réductions ne sont pas redessinées.** Elles sortent du même fichier,
dont le masque a été **dilaté** avant le relevé — le trait grossit, les trous
se referment, et les proportions ne bougent pas d'un pixel. Redessiner une
version simplifiée produirait, là encore, un second logo.

---

## Ce qu'il y a dans chaque dossier

### `source/` — les originaux vectoriels

| Fichier | Quand s'en servir |
|---|---|
| `logo-original.png` | **le fichier du graphiste** — la source de tout |
| `garah-symbole.svg` | le relevé, cadré au dessin |
| `garah-symbole-petit.svg`, `-minuscule.svg` | les deux relevés épaissis |
| `garah-symbole-carre.svg` | le symbole centré dans un carré, transparent |
| `garah-symbole-mono.svg` | une seule couleur (`currentColor`) — tampon, gravure, aplat |
| `garah-symbole-nuit.svg` | le symbole sur pastille nuit — base des icônes d'application |
| `garah-vertical-nuit.svg` | le bloc complet sur fond nuit |
| `garah-vertical.svg` | le bloc complet, fond transparent |
| `garah-vertical-clair.svg` | idem, mot en nuit — pour poser sur du blanc |
| `garah-horizontal*.svg` | symbole à gauche, texte à droite — en-têtes, barres latérales |
| `garah-maskable.svg` | l'icône recadrable d'Android |

### `web/` — l'application web et le back-office

| Fichier | Où il va |
|---|---|
| `favicon.ico` | à la racine servie — 16, 32 et 48 px dans un seul fichier |
| `favicon.svg` | idem — préféré par les navigateurs qui le comprennent |
| `apple-touch-icon.png` | 180 px, pour l'écran d'accueil iOS |
| `icone-192.png`, `icone-512.png` | le manifeste d'application installable |
| `icone-maskable-512.png` | Android, qui recadre l'icône |
| `og-image.png` | l'aperçu au partage d'un lien (1200 × 630) |

**Déjà installés** dans `frontend/projects/garah-admin/public/`.

Pour la boutique, qui vit dans un autre dépôt :

```bash
cp marque/web/favicon.ico marque/web/favicon.svg \
   marque/web/apple-touch-icon.png marque/web/icone-*.png \
   ../garah-client/web/public/
```

### `mobile/` — les applications

**Android.** Les `mipmap-*/ic_launcher.png` se déposent dans
`app/src/main/res/`. `ic_launcher_foreground.png` sert à l'icône adaptative,
avec `#101C3D` en couleur de fond. `play-store-512.png` est ce que demande le
Play Store.

**iOS.** Les `AppIcon-*.png` se glissent dans le catalogue d'assets Xcode.
`AppIcon-1024.png` est celui de l'App Store.

### `admin/` — les supports du back-office

`logo-horizontal.png` et sa version `@2x` (fond transparent), et le symbole
seul en 256 px.

---

## ⚠️ Le texte n'est pas vectorisé

Le mot GARAH et la signature sont du **texte**, en Arial / Helvetica.

- Les **PNG** ont été rasterisés sur cette machine : ils sont figés, et
  identiques partout. Aucun risque.
- Les **SVG** dépendent des polices du lecteur. Arial et Helvetica sont
  présentes sur tous les systèmes courants, mais un rendu peut varier au
  demi-pixel.

Pour une fidélité absolue — enseigne, impression offset, dépôt de marque —
faire **vectoriser le texte** par un graphiste : c'est une opération de dix
minutes dans Illustrator ou Inkscape (*Texte → Vectoriser*), et elle rend les
SVG indépendants de toute police.

---

## Où la marque vit, dans les trois applications

La calebasse verte a été retirée partout le 09/09/2026. Voici les endroits
qu'il a fallu toucher — la liste vaut pour le prochain changement.

| Où | Quoi |
|---|---|
| `garah-ui/theme/_jetons.scss` | `--marque`, la couleur |
| `garah-ui/src/lib/marque/traces.ts` | **engendré** — les deux tracés |
| `garah-ui/src/lib/marque/marque.ts` | le composant `<gu-marque>` |
| `garah-ui/assets/marque/*.svg` | **engendrés** — trois fichiers de travail |
| `garah-admin/src/index.html` | l'écran d'attente, tracé **en dur** |
| `garah-admin/.../statistiques/rapport.ts` | l'en-tête des PDF et Word |
| `garah-client/charte/jetons.json` | la couleur, pour la boutique et le mobile |
| `garah-client/web/src/app/marque.ts` | le composant `<gb-marque>` |
| `garah-client/web/src/index.html` | l'écran d'attente de la boutique |
| `garah-client/mobile/.../marque_garah*.xml` | **engendrés** — les VectorDrawable |

⚠️ **Les écrans d'attente portent le tracé en dur**, et c'est justifié : ils
s'affichent avant que le JavaScript n'existe. Ils ne peuvent donc pas lire
`--marque` ni importer un composant. Ce sont les deux endroits qu'on oublie.

⚠️ `rapport.ts` gardait sa propre copie du tracé et de la couleur. Elle aurait
survécu au changement : les documents seraient sortis avec l'ancienne marque
pendant que tous les écrans portaient la nouvelle, et personne ne l'aurait vu
sans ouvrir un PDF. Il lit maintenant `MARQUE_COMPLETE` et `MARQUE_BLEU`
depuis la librairie.

### Ce qui n'a pas changé

`--primary`, l'indigo `#6366f1`, colore les boutons, les états actifs et les
liens de tous les écrans. Il est **distinct de la marque** par construction
(D-03) et n'a pas été touché. Le faire suivre le bleu de la marque est une
décision d'interface, pas de logo.
