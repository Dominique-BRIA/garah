# La marque GARAH

Tout ce dossier est **engendré**. Rien ne s'y retouche à la main.

```bash
cd marque
node engendrer-marque.mjs
```

Le script n'a besoin d'aucune bibliothèque — seulement de **Google Chrome**,
qui sert à convertir les tracés en images. Ce qui se corrige se corrige dans
`engendrer-marque.mjs`, jamais dans un fichier produit : la prochaine
exécution l'écraserait.

---

## La charte

| | Code | Emploi |
|---|---|---|
| **Bleu** | `#2E9CE9` | le symbole, le mot GARAH |
| **Vert** | `#4FBF4B` | la signature « au-delà des frontières » |
| **Nuit** | `#101C3D` | le fond des icônes et des blocs sombres |

Le symbole est un **téléphone dont le flanc se prolonge en chariot** : on
achète depuis son téléphone, et la marchandise part. Les deux formes ne sont
pas posées côte à côte — l'anse du chariot naît du téléphone.

Tout est en **trait**, jamais en aplat. C'est ce qui le fait survivre au
tampon, à la broderie et au fax, et non seulement à l'écran.

---

## ⚠️ Trois dessins, pas un seul

Un logo qui rétrécit ne se contente pas de rétrécir. Le symbole existe en
**trois paliers**, et le script choisit lui-même lequel employer :

| Palier | À partir de | Ce qu'il garde |
|---|---|---|
| Complet | 48 px | tout — barreaux, roues, barre de poussée |
| Simplifié | 32 px | téléphone, anse, panse, roues — traits épaissis |
| Minuscule | 16 px | téléphone et panse, rien d'autre |

Sans ces paliers, le favicon de 16 px n'est qu'une tache bleue : l'anse, les
roues et la panse tiennent dans huit pixels de haut.

---

## Ce qu'il y a dans chaque dossier

### `source/` — les originaux vectoriels

| Fichier | Quand s'en servir |
|---|---|
| `garah-symbole.svg` | le symbole seul, en couleur |
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

## ⚠️ Ce logo ne correspond pas à la charte actuelle de l'application

L'application utilise aujourd'hui **une autre marque** : la calebasse verte,
`--marque: #12a594`, définie dans `garah-ui/theme/_jetons.scss` et dessinée
par le composant `<gu-marque>`.

Les icônes livrées ici sont donc en place, mais **l'intérieur des écrans est
resté vert**. Il y a deux marques dans le projet, et c'est visible.

Aligner l'application demande trois gestes :

1. changer `--marque` en `#2E9CE9` dans `_jetons.scss` ;
2. remplacer les tracés de `<gu-marque>` par ceux du nouveau symbole ;
3. reprendre `--primary` (indigo `#6366f1`) si l'on veut que toute
   l'interface suive le bleu.

Le troisième est le plus lourd : `--primary` colore les boutons, les états
actifs et les liens de tous les écrans.
