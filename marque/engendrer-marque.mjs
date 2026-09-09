// =============================================================================
//  GARAH — la marque, dans tous ses formats
// =============================================================================
//  🎯 LE LOGO FOURNI EST LA SOURCE. On ne le redessine pas, on le SUIT.
//
//  `vectoriser.mjs` relève son contour pixel par pixel depuis
//  `source/logo-original.png`. Ce script-ci ne fait que composer et décliner
//  ce relevé. Redessiner « d'après » l'image donnerait un second logo : les
//  proportions dérivent de quelques pour cent, et deux marques cohabitent
//  sans que personne ne sache laquelle fait foi.
//
//  ⚠️ NE PAS retoucher les fichiers produits. Ils sont réécrits à chaque
//     exécution. Ce qui se corrige se corrige ICI, ou dans le PNG d'origine.
//
//  Prérequis : Google Chrome, pour la rasterisation. Aucune bibliothèque.
//
//    node vectoriser.mjs source/logo-original.png source/garah-symbole.svg 0.9
//    node vectoriser.mjs source/logo-original.png source/garah-symbole-petit.svg 1.0 4
//    node vectoriser.mjs source/logo-original.png source/garah-symbole-minuscule.svg 1.4 9
//    node engendrer-marque.mjs
// =============================================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ICI = path.dirname(fileURLToPath(import.meta.url));

// --- La charte ---------------------------------------------------------------
// Le bleu n'est pas choisi : il est RELEVÉ sur le fichier d'origine par
// vectoriser.mjs. Le vert et le nuit viennent de la maquette du logo complet.
const BLEU = '#31AEF3';
const VERT = '#4FBF4B';
const NUIT = '#101C3D';

const CHROMES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
];
const CHROME = CHROMES.find((c) => fs.existsSync(c));
if (!CHROME) throw new Error('Chrome introuvable : compléter la liste CHROMES.');

// =============================================================================
//  LES TRACÉS RELEVÉS
// =============================================================================

/**
 * Lit un SVG produit par vectoriser.mjs : sa boîte, et son tracé.
 *
 * ⚠️ Volontairement naïf — il ne lit QUE les fichiers de ce dossier, dont on
 *    connaît la forme exacte. Un analyseur XML complet serait une dépendance
 *    de plus pour lire trois fichiers qu'on a écrits soi-même.
 */
function lireTrace(fichier) {
  const s = fs.readFileSync(path.join(ICI, fichier), 'utf8');
  const boite = s.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
  const d = s.match(/ d="([^"]+)"/);
  if (!boite || !d) throw new Error(`Tracé illisible : ${fichier}`);
  return { l: Number(boite[1]), h: Number(boite[2]), d: d[1] };
}

/**
 * Les trois paliers.
 *
 * 🎯 UN LOGO QUI RÉTRÉCIT NE SE CONTENTE PAS DE RÉTRÉCIR. À 32 px, les dix
 *    trous du panier font des carrés d'un pixel et demi : ils se remplissent
 *    d'anticrénelage et le panier redevient un bloc sale.
 *
 * Les deux réductions ne sont pas redessinées — elles sortent du MÊME fichier,
 * dont le masque a été dilaté avant le relevé. Le trait grossit, les trous se
 * referment, les proportions ne bougent pas d'un pixel.
 */
const COMPLET = lireTrace('source/garah-symbole.svg');
const PETIT = lireTrace('source/garah-symbole-petit.svg');
const MINUSCULE = lireTrace('source/garah-symbole-minuscule.svg');

/**
 * Place un tracé dans une boîte, centré, occupant `part` de sa hauteur.
 *
 * Le calcul est fait ICI une fois pour toutes : à l'œil, chaque composition
 * dérive un peu, et l'on finit avec cinq cadrages différents pour un seul
 * dessin.
 */
function poser(trace, boiteL, boiteH, part, decalageY = 0) {
  const echelle = (boiteH * part) / trace.h;
  const x = (boiteL - trace.l * echelle) / 2;
  const y = (boiteH - trace.h * echelle) / 2 + decalageY;
  return `  <g transform="translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${echelle.toFixed(4)})">
    <path fill="${BLEU}" fill-rule="evenodd" d="${trace.d}"/>
  </g>`;
}

/** Le mot, et la signature. Voir le LISEZ-MOI sur la question des polices. */
const mot = ({ x, y, taille, couleur, interlettre, ancre = 'middle' }) =>
  `  <text x="${x}" y="${y}" text-anchor="${ancre}"
        font-family="Arial, Helvetica, sans-serif" font-weight="700"
        font-size="${taille}" letter-spacing="${interlettre}"
        fill="${couleur}">GARAH</text>`;

const signature = ({ x, y, taille, couleur, interlettre, ancre = 'middle' }) =>
  `  <text x="${x}" y="${y}" text-anchor="${ancre}"
        font-family="Arial, Helvetica, sans-serif" font-weight="600"
        font-size="${taille}" letter-spacing="${interlettre}"
        fill="${couleur}">AU-DELÀ DES FRONTIÈRES</text>`;

// =============================================================================
//  LES FICHIERS SVG
// =============================================================================

const enTete = (l, h, titre) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${l} ${h}" width="${l}" height="${h}"
     role="img" aria-label="GARAH">\n  <title>${titre}</title>\n`;

const svgs = {};

// --- Le symbole seul, sur fond transparent -----------------------------------
svgs['source/garah-symbole-carre.svg'] =
  enTete(512, 512, 'GARAH — le symbole') + poser(COMPLET, 512, 512, 0.88) + '\n</svg>\n';

// currentColor : la marque prend la couleur du texte qui l'entoure. C'est la
// version qui survit à l'aplat, au tampon et à la broderie.
svgs['source/garah-symbole-mono.svg'] =
  enTete(512, 512, 'GARAH — le symbole, une seule couleur')
  + poser(COMPLET, 512, 512, 0.88).replace(`fill="${BLEU}"`, 'fill="currentColor"')
  + '\n</svg>\n';

// --- Le symbole sur pastille nuit, base des icônes d'application -------------
svgs['source/garah-symbole-nuit.svg'] =
  enTete(512, 512, 'GARAH — le symbole sur fond nuit')
  + `  <rect width="512" height="512" rx="112" fill="${NUIT}"/>\n`
  + poser(COMPLET, 512, 512, 0.64) + '\n</svg>\n';

// --- L'icône recadrable d'Android --------------------------------------------
// ⚠️ Android RECADRE l'icône en cercle, en goutte ou en carré arrondi selon le
//    lanceur. Le dessin doit tenir dans les 80 % centraux, sinon il est rogné —
//    c'est le défaut le plus courant des icônes de PWA.
svgs['source/garah-maskable.svg'] =
  enTete(512, 512, 'GARAH — icône recadrable Android')
  + `  <rect width="512" height="512" fill="${NUIT}"/>\n`
  + poser(COMPLET, 512, 512, 0.50) + '\n</svg>\n';

// --- Le bloc vertical : symbole, mot, signature ------------------------------
function vertical({ fond, couleurMot, couleurSignature }) {
  let s = enTete(512, 512, 'GARAH — bloc vertical');
  if (fond) s += `  <rect width="512" height="512" fill="${fond}"/>\n`;
  // Le symbole occupe les deux tiers hauts, le texte le tiers bas. Remonté de
  // 74 : centré, le bas des roues arriverait sur la hampe du G.
  s += poser(COMPLET, 512, 512, 0.60, -74) + '\n';
  s += mot({ x: 256, y: 414, taille: 76, couleur: couleurMot, interlettre: 12 }) + '\n';
  s += signature({ x: 256, y: 462, taille: 25, couleur: couleurSignature, interlettre: 5.5 }) + '\n';
  return s + '</svg>\n';
}

svgs['source/garah-vertical-nuit.svg'] = vertical({ fond: NUIT, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-vertical.svg'] = vertical({ fond: null, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-vertical-clair.svg'] = vertical({ fond: null, couleurMot: NUIT, couleurSignature: VERT });

// --- Le bloc horizontal : en-tête, barre latérale -----------------------------
function horizontal({ fond, couleurMot, couleurSignature }) {
  const L = 1000, H = 280;
  let s = enTete(L, H, 'GARAH — bloc horizontal');
  if (fond) s += `  <rect width="${L}" height="${H}" fill="${fond}"/>\n`;

  // Le symbole calé à gauche, à 78 % de la hauteur du bloc.
  const echelle = (H * 0.78) / COMPLET.h;
  s += `  <g transform="translate(40 ${((H - COMPLET.h * echelle) / 2).toFixed(1)}) scale(${echelle.toFixed(4)})">
    <path fill="${BLEU}" fill-rule="evenodd" d="${COMPLET.d}"/>
  </g>\n`;

  const x = 40 + COMPLET.l * echelle + 56;
  s += mot({ x, y: 152, taille: 112, couleur: couleurMot, interlettre: 16, ancre: 'start' }) + '\n';
  s += signature({ x: x + 6, y: 208, taille: 30, couleur: couleurSignature, interlettre: 6, ancre: 'start' }) + '\n';
  return s + '</svg>\n';
}

svgs['source/garah-horizontal-nuit.svg'] = horizontal({ fond: NUIT, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-horizontal.svg'] = horizontal({ fond: null, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-horizontal-clair.svg'] = horizontal({ fond: null, couleurMot: NUIT, couleurSignature: VERT });

// --- Les favicons -------------------------------------------------------------
svgs['web/favicon.svg'] =
  enTete(512, 512, 'GARAH')
  + `  <rect width="512" height="512" rx="96" fill="${NUIT}"/>\n`
  + poser(PETIT, 512, 512, 0.66) + '\n</svg>\n';

svgs['web/favicon-minuscule.svg'] =
  enTete(512, 512, 'GARAH — favicon 16 px')
  + `  <rect width="512" height="512" rx="96" fill="${NUIT}"/>\n`
  + poser(MINUSCULE, 512, 512, 0.68) + '\n</svg>\n';

// --- Les assets de la librairie -----------------------------------------------
// ⚠️ Ces trois fichiers vivent dans `garah-ui/assets/marque/`. Ils ne sont
//    utilisés par aucun code — seuls les deux `logo-*.jpg` le sont — mais ils
//    servent de source à qui prépare un support. Laissés en place, ils
//    montreraient encore la calebasse verte des mois après le changement de
//    marque, et quelqu'un finirait par la reprendre en croyant bien faire.
const ASSETS = path.resolve(ICI, '../frontend/projects/garah-ui/assets/marque');
if (fs.existsSync(ASSETS)) {
  fs.writeFileSync(path.join(ASSETS, 'marque.svg'),
    enTete(512, 512, 'GARAH — le symbole') + poser(COMPLET, 512, 512, 0.88) + '\n</svg>\n');
  fs.writeFileSync(path.join(ASSETS, 'marque-mono.svg'),
    enTete(512, 512, 'GARAH — le symbole, une seule couleur')
    + poser(COMPLET, 512, 512, 0.88).replace(`fill="${BLEU}"`, 'fill="currentColor"')
    + '\n</svg>\n');
  // La version animée : elle respire, elle ne se trace pas. Voir <gu-marque>.
  fs.writeFileSync(path.join(ASSETS, 'marque-animee.svg'),
    enTete(512, 512, 'GARAH — le symbole, animé')
    + `  <style>
    .dessin { animation: respirer 2s ease-in-out infinite; transform-origin: center; }
    @keyframes respirer {
      0% { opacity: .5; transform: scale(.93); }
      45%, 60% { opacity: 1; transform: none; }
      100% { opacity: .5; transform: scale(.93); }
    }
    @media (prefers-reduced-motion: reduce) {
      .dessin { animation: none; opacity: 1; transform: none; }
    }
  </style>\n`
    + poser(COMPLET, 512, 512, 0.88).replace('<path ', '<path class="dessin" ')
    + '\n</svg>\n');
  console.log('SVG : 3 assets de garah-ui');
}

// --- La bannière de partage ---------------------------------------------------
svgs['web/og-image.svg'] = (() => {
  const L = 1200, H = 630;
  const echelle = (H * 0.46) / COMPLET.h;
  return enTete(L, H, 'GARAH')
    + `  <rect width="${L}" height="${H}" fill="${NUIT}"/>\n`
    + `  <rect width="${L}" height="8" fill="${BLEU}"/>\n`
    + `  <g transform="translate(${((L - COMPLET.l * echelle) / 2).toFixed(1)} 88) scale(${echelle.toFixed(4)})">
    <path fill="${BLEU}" fill-rule="evenodd" d="${COMPLET.d}"/>
  </g>\n`
    + mot({ x: 600, y: 500, taille: 96, couleur: BLEU, interlettre: 16 }) + '\n'
    + signature({ x: 600, y: 556, taille: 30, couleur: VERT, interlettre: 7 }) + '\n</svg>\n';
})();

for (const [rel, contenu] of Object.entries(svgs)) {
  const cible = path.join(ICI, rel);
  fs.mkdirSync(path.dirname(cible), { recursive: true });
  fs.writeFileSync(cible, contenu);
}
console.log('SVG :', Object.keys(svgs).length, 'fichiers');

// =============================================================================
//  LES TRACÉS POUR LA LIBRAIRIE
// =============================================================================
//  🎯 Le composant <gu-marque> ne recopie pas le dessin : il le REÇOIT.
//
//  Même raison que pour tout le reste — une forme recopiée à la main dérive.
//  Ce fichier est engendré ; le composant, lui, reste écrit à la main et
//  documenté. C'est le partage que fait déjà `icones/traces.ts`.
// =============================================================================
const CIBLE_TS = path.resolve(ICI, '../frontend/projects/garah-ui/src/lib/marque/traces.ts');

if (fs.existsSync(path.dirname(CIBLE_TS))) {
  fs.writeFileSync(CIBLE_TS,
`/*
 * ⚠️ FICHIER ENGENDRÉ — ne pas modifier.
 *
 * Produit par \`marque/engendrer-marque.mjs\`, lui-même nourri du relevé de
 * \`marque/source/logo-original.png\`. Toute retouche ici sera écrasée à la
 * prochaine exécution.
 *
 * Deux paliers, et c'est délibéré : sous 44 px, les trous du panier se
 * remplissent d'anticrénelage et le dessin devient une tache. Le second
 * tracé n'est pas un autre dessin — c'est le MÊME, relevé après dilatation
 * du masque : le trait grossit, les proportions ne bougent pas.
 */

export interface TraceMarque {
  /** Le repère de coordonnées du tracé. */
  readonly boite: string;
  readonly trace: string;
}

/** Le dessin complet. À partir de 44 px. */
export const MARQUE_COMPLETE: TraceMarque = {
  boite: '0 0 ${COMPLET.l} ${COMPLET.h}',
  trace:
    '${COMPLET.d}',
};

/** Le dessin épaissi. En dessous de 44 px. */
export const MARQUE_EPAISSIE: TraceMarque = {
  boite: '0 0 ${PETIT.l} ${PETIT.h}',
  trace:
    '${PETIT.d}',
};

/** Le bleu de la marque, relevé sur le fichier d'origine. */
export const MARQUE_BLEU = '${BLEU}';
`);
  console.log('TS  : garah-ui/src/lib/marque/traces.ts');
}

// =============================================================================
//  RASTERISATION
// =============================================================================

const TEMP = fs.mkdtempSync(path.join(process.env.TEMP || '/tmp', 'marque-'));

/**
 * Rend un SVG en PNG à la taille voulue.
 *
 * ⚠️ Le SVG est enveloppé dans une page HTML plutôt que capturé directement.
 *    Chrome, sur un fichier .svg ouvert seul, ajoute ses propres marges et
 *    ignore --window-size : on obtient une image décalée, à la mauvaise
 *    taille, sans le moindre message.
 */
function rasteriser(svgRelatif, largeur, hauteur, sortie, transparent) {
  const svg = fs.readFileSync(path.join(ICI, svgRelatif), 'utf8');
  const page = path.join(TEMP, 'p.html');
  fs.writeFileSync(page,
    `<!doctype html><meta charset="utf-8">
     <style>html,body{margin:0;padding:0;overflow:hidden}
     svg{display:block;width:${largeur}px;height:${hauteur}px}</style>${svg}`);

  execFileSync(CHROME, [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    `--window-size=${largeur},${hauteur}`,
    `--screenshot=${sortie}`,
    ...(transparent ? ['--default-background-color=00000000'] : []),
    'file:///' + page.replace(/\\/g, '/'),
  ], { stdio: 'ignore' });
}

const PNGS = [
  // --- Web -------------------------------------------------------------------
  // ⚠️ Le 16 vient d'une AUTRE source : le tracé le plus épaissi. Réduire le
  //    dessin complet donnerait une tache.
  ['web/favicon-minuscule.svg',     16,  16, 'web/favicon-16.png',            false],
  ['web/favicon.svg',               32,  32, 'web/favicon-32.png',            false],
  ['web/favicon.svg',               48,  48, 'web/favicon-48.png',            false],
  // ⚠️ apple-touch-icon : iOS n'accepte PAS la transparence — il la remplit en
  //    noir. Le fond nuit est donc dessiné, pas laissé au hasard.
  ['source/garah-symbole-nuit.svg', 180, 180, 'web/apple-touch-icon.png',     false],
  ['source/garah-symbole-nuit.svg', 192, 192, 'web/icone-192.png',            false],
  ['source/garah-symbole-nuit.svg', 512, 512, 'web/icone-512.png',            false],
  ['source/garah-maskable.svg',     512, 512, 'web/icone-maskable-512.png',   false],
  ['web/og-image.svg',             1200, 630, 'web/og-image.png',             false],

  // --- Mobile : Android --------------------------------------------------------
  ['source/garah-symbole-nuit.svg',  48,  48, 'mobile/android/mipmap-mdpi/ic_launcher.png',    false],
  ['source/garah-symbole-nuit.svg',  72,  72, 'mobile/android/mipmap-hdpi/ic_launcher.png',    false],
  ['source/garah-symbole-nuit.svg',  96,  96, 'mobile/android/mipmap-xhdpi/ic_launcher.png',   false],
  ['source/garah-symbole-nuit.svg', 144, 144, 'mobile/android/mipmap-xxhdpi/ic_launcher.png',  false],
  ['source/garah-symbole-nuit.svg', 192, 192, 'mobile/android/mipmap-xxxhdpi/ic_launcher.png', false],
  ['source/garah-maskable.svg',     432, 432, 'mobile/android/ic_launcher_foreground.png',     true],
  ['source/garah-symbole-nuit.svg', 512, 512, 'mobile/android/play-store-512.png',             false],

  // --- Mobile : iOS ------------------------------------------------------------
  ['source/garah-symbole-nuit.svg',   40,   40, 'mobile/ios/AppIcon-40.png',   false],
  ['source/garah-symbole-nuit.svg',   58,   58, 'mobile/ios/AppIcon-58.png',   false],
  ['source/garah-symbole-nuit.svg',   60,   60, 'mobile/ios/AppIcon-60.png',   false],
  ['source/garah-symbole-nuit.svg',   87,   87, 'mobile/ios/AppIcon-87.png',   false],
  ['source/garah-symbole-nuit.svg',  120,  120, 'mobile/ios/AppIcon-120.png',  false],
  ['source/garah-symbole-nuit.svg',  180,  180, 'mobile/ios/AppIcon-180.png',  false],
  ['source/garah-symbole-nuit.svg', 1024, 1024, 'mobile/ios/AppIcon-1024.png', false],

  // --- Back-office et supports --------------------------------------------------
  ['source/garah-horizontal.svg',      500, 140, 'admin/logo-horizontal.png',        true],
  ['source/garah-horizontal.svg',     1000, 280, 'admin/logo-horizontal@2x.png',     true],
  ['source/garah-symbole-carre.svg',   256, 256, 'admin/symbole-256.png',            true],
  ['source/garah-vertical-nuit.svg',  1024, 1024, 'apercu/garah-vertical-nuit.png',  false],
  ['source/garah-vertical.svg',       1024, 1024, 'apercu/garah-vertical.png',       true],
  ['source/garah-horizontal-nuit.svg', 2000, 560, 'apercu/garah-horizontal-nuit.png', false],
];

for (const [src, l, h, dest, transp] of PNGS) {
  const cible = path.join(ICI, dest);
  fs.mkdirSync(path.dirname(cible), { recursive: true });
  rasteriser(src, l, h, cible, transp);
}
console.log('PNG :', PNGS.length, 'fichiers');

// =============================================================================
//  LE .ICO
// =============================================================================

/**
 * Un .ico moderne : un en-tête, un répertoire, puis les PNG tels quels.
 *
 * ⚠️ Le champ « taille » vaut 0 pour 256 px et au-delà — un octet ne va pas
 *    plus loin. Ici on reste sous 256, mais la règle mérite d'être écrite :
 *    c'est le piège classique de ce format.
 */
function fabriquerIco(sources, sortie) {
  const images = sources.map((f) => ({
    cote: Number(f.match(/-(\d+)\.png$/)[1]),
    donnees: fs.readFileSync(path.join(ICI, f)),
  }));

  const enTeteIco = Buffer.alloc(6);
  enTeteIco.writeUInt16LE(1, 2);
  enTeteIco.writeUInt16LE(images.length, 4);

  let offset = 6 + 16 * images.length;
  const repertoire = images.map((img) => {
    const e = Buffer.alloc(16);
    e.writeUInt8(img.cote >= 256 ? 0 : img.cote, 0);
    e.writeUInt8(img.cote >= 256 ? 0 : img.cote, 1);
    e.writeUInt16LE(1, 4);
    e.writeUInt16LE(32, 6);
    e.writeUInt32LE(img.donnees.length, 8);
    e.writeUInt32LE(offset, 12);
    offset += img.donnees.length;
    return e;
  });

  fs.writeFileSync(path.join(ICI, sortie),
    Buffer.concat([enTeteIco, ...repertoire, ...images.map((i) => i.donnees)]));
}

fabriquerIco(['web/favicon-16.png', 'web/favicon-32.png', 'web/favicon-48.png'], 'web/favicon.ico');
console.log('ICO : web/favicon.ico');

fs.rmSync(TEMP, { recursive: true, force: true });
console.log('\nTerminé.');
