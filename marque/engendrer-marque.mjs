// =============================================================================
//  GARAH — la marque, dans tous ses formats
// =============================================================================
//  Une seule source : les tracés définis ici. Tout le reste en découle —
//  les SVG, les PNG de chaque plateforme, le .ico.
//
//  ⚠️ NE PAS retoucher les fichiers produits. Ils sont réécrits à chaque
//     exécution. Ce qui se corrige se corrige ICI.
//
//  Prérequis : Google Chrome (pour la rasterisation). Aucune bibliothèque.
//    node engendrer-marque.mjs
// =============================================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const ICI = path.dirname(fileURLToPath(import.meta.url));

// --- La charte ---------------------------------------------------------------
const BLEU = '#2E9CE9';
const VERT = '#4FBF4B';
const NUIT = '#101C3D';

// Le chemin de Chrome. Ajouter le vôtre si la liste ne le trouve pas.
const CHROMES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
];
const CHROME = CHROMES.find((c) => fs.existsSync(c));
if (!CHROME) throw new Error('Chrome introuvable : compléter la liste CHROMES.');

// =============================================================================
//  LES TRACÉS
// =============================================================================

/**
 * Le symbole, centré dans une boîte de 512.
 *
 * 🎯 UN SEUL RUBAN. Le téléphone n'est pas posé sur un chariot : son flanc
 *    droit descend, tourne, et devient le bord haut du panier. C'est ce trait
 *    continu qui fait le dessin — deux formes voisines ne le remplaceraient
 *    pas. On achète depuis son téléphone, et la marchandise part.
 *
 * ⚠️ Le bord haut du panier est donc EN PENTE, et la première rangée de trous
 *    SUIT cette pente. C'est le détail qui rend la forme lisible : à plat, on
 *    verrait une caisse posée sous un téléphone, et le lien se perdrait.
 *
 * ⚠️ Le panier est une forme PLEINE percée de trous, pas une grille de
 *    barreaux. `fill-rule="evenodd"` creuse les rectangles intérieurs au lieu
 *    de les peindre. Des barreaux en trait se refermeraient en pâté dès 48 px.
 */
function symbole(couleur = BLEU) {
  // ⚠️ Recentré. Le dessin naturel penche à droite — la barre de poussée
  //    dépasse — et vers le bas : sans ce décalage, toutes les compositions
  //    héritent du déséquilibre, et on le corrige ensuite cinq fois.
  return `  <g transform="translate(-37 -6.5)" fill="${couleur}">
    <path fill="none" stroke="${couleur}" stroke-width="30"
          stroke-linecap="round" stroke-linejoin="round"
          d="M118 262 V 74 a30 30 0 0 1 30-30 H 370 a30 30 0 0 1 30 30 V 252"/>
    <path fill="none" stroke="${couleur}" stroke-width="22" stroke-linecap="round"
          d="M224 92 h70"/>
    <path fill="none" stroke="${couleur}" stroke-width="26" stroke-linecap="round"
          d="M406 288 L470 258"/>
    <path fill-rule="evenodd" d="
      M104 330 L400 252 L400 414 a28 28 0 0 1 -28 28 H132 a28 28 0 0 1 -28 -28 Z
      M129 347 h38 v30 h-38 z
      M181 334 h38 v30 h-38 z
      M233 320 h38 v30 h-38 z
      M285 306 h38 v30 h-38 z
      M337 293 h38 v30 h-38 z
      M129 388 h38 v30 h-38 z
      M181 388 h38 v30 h-38 z
      M233 388 h38 v30 h-38 z
      M285 388 h38 v30 h-38 z
      M337 388 h38 v30 h-38 z"/>
    <circle cx="176" cy="468" r="18" fill="none" stroke="${couleur}" stroke-width="20"/>
    <circle cx="328" cy="468" r="18" fill="none" stroke="${couleur}" stroke-width="20"/>
  </g>`;
}

/**
 * Le symbole SIMPLIFIÉ, pour 48 px et moins.
 *
 * 🎯 UN LOGO QUI RÉTRÉCIT NE SE CONTENTE PAS DE RÉTRÉCIR.
 *
 * Dix trous de 38 unités font, à 48 px, des carrés de trois pixels et demi :
 * ils se remplissent d'anticrénelage et le panier redevient un bloc sale.
 * On passe à SIX trous, plus grands, sur une seule rangée — la pente reste
 * lisible, et c'est elle qui porte le sens.
 *
 * Les roues deviennent pleines : un anneau de deux pixels d'épaisseur n'est
 * plus un anneau, c'est un point flou.
 */
function symbolePetit(couleur = BLEU) {
  return `  <g transform="translate(-36.5 -5)" fill="${couleur}">
    <path fill="none" stroke="${couleur}" stroke-width="36"
          stroke-linecap="round" stroke-linejoin="round"
          d="M120 258 V 76 a32 32 0 0 1 32-32 H 368 a32 32 0 0 1 32 32 V 250"/>
    <path fill="none" stroke="${couleur}" stroke-width="30" stroke-linecap="round"
          d="M408 292 L470 262"/>
    <path fill-rule="evenodd" d="
      M100 332 L402 250 L402 412 a30 30 0 0 1 -30 30 H130 a30 30 0 0 1 -30 -30 Z
      M132 356 h50 v48 h-50 z
      M198 338 h50 v48 h-50 z
      M264 320 h50 v48 h-50 z
      M330 302 h50 v48 h-50 z"/>
    <circle cx="176" cy="470" r="26" fill="${couleur}"/>
    <circle cx="330" cy="470" r="26" fill="${couleur}"/>
  </g>`;
}

/**
 * Le symbole RÉDUIT À L'OS, pour 16 px.
 *
 * 🎯 Trois paliers, pas deux. À 16 px, même la version simplifiée se referme :
 *    quatre trous et deux roues tiennent dans huit pixels de haut, et il n'en
 *    reste qu'une tache bleue.
 *
 * Ce qui survit : le téléphone, et le panier plein en pente sous lui. Deux
 * masses, un vide franc entre les deux. On ne lit plus « chariot », on lit
 * « quelque chose sous un téléphone » — et à cette taille, c'est tout ce
 * qu'un favicon doit faire : se distinguer des vingt autres onglets.
 */
function symboleMinuscule(couleur = BLEU) {
  return `  <g transform="translate(-4 25)" fill="${couleur}">
    <path fill="none" stroke="${couleur}" stroke-width="52"
          stroke-linecap="round" stroke-linejoin="round"
          d="M126 250 V 84 a38 38 0 0 1 38-38 H 356 a38 38 0 0 1 38 38 V 244"/>
    <path d="M100 344 L404 262 L404 408 a34 34 0 0 1 -34 34 H134 a34 34 0 0 1 -34 -34 Z"/>
  </g>`;
}

/**
 * Le mot, et la signature.
 *
 * ⚠️ Le texte reste du TEXTE, il n'est pas vectorisé — je n'ai pas d'outil
 *    de conversion ici. Les PNG, eux, sont rasterisés sur cette machine :
 *    ils sont donc figés et fidèles partout. Seuls les SVG dépendent des
 *    polices du lecteur, d'où la pile Arial / Helvetica, présente
 *    partout. Voir le LISEZ-MOI.
 */
function mot({ y, taille, couleur, interlettre }) {
  return `  <text x="256" y="${y}" text-anchor="middle"
        font-family="Arial, Helvetica, sans-serif" font-weight="700"
        font-size="${taille}" letter-spacing="${interlettre}"
        fill="${couleur}">GARAH</text>`;
}

function signature({ y, taille, couleur, interlettre }) {
  return `  <text x="256" y="${y}" text-anchor="middle"
        font-family="Arial, Helvetica, sans-serif" font-weight="600"
        font-size="${taille}" letter-spacing="${interlettre}"
        fill="${couleur}">AU-DELÀ DES FRONTIÈRES</text>`;
}

// =============================================================================
//  LES FICHIERS SVG
// =============================================================================

const enTete = (w, h, titre) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}"
     role="img" aria-label="GARAH">\n  <title>${titre}</title>\n`;

const svgs = {};

// --- Le symbole seul ---------------------------------------------------------
svgs['source/garah-symbole.svg'] =
  enTete(512, 512, 'GARAH — le symbole') + symbole() + '\n</svg>\n';

svgs['source/garah-symbole-petit.svg'] =
  enTete(512, 512, 'GARAH — le symbole, version 32 px et moins')
  + symbolePetit() + '\n</svg>\n';

// currentColor : la marque prend la couleur du texte qui l'entoure. C'est la
// version qui survit à l'aplat, au tampon et à la broderie.
svgs['source/garah-symbole-mono.svg'] =
  enTete(512, 512, 'GARAH — le symbole, une seule couleur')
  + symbole('currentColor') + '\n</svg>\n';

// --- Le symbole sur pastille nuit (pour les icônes d'application) ------------
svgs['source/garah-symbole-nuit.svg'] =
  enTete(512, 512, 'GARAH — le symbole sur fond nuit')
  + `  <rect width="512" height="512" rx="112" fill="${NUIT}"/>\n`
  + `  <g transform="translate(256 256) scale(0.78) translate(-256 -256)">\n`
  + symbole() + '\n  </g>\n</svg>\n';

// --- Le bloc vertical : symbole, mot, signature ------------------------------
function vertical({ fond, couleurMot, couleurSignature }) {
  let s = enTete(512, 512, 'GARAH — bloc vertical');
  if (fond) s += `  <rect width="512" height="512" fill="${fond}"/>\n`;
  // Le symbole occupe les deux tiers hauts, le texte le tiers bas.
  //
  // ⚠️ Le symbole est remonté à 180 et non centré à 196 : à 196, le bas des
  //    roues arrivait exactement sur la hampe du G. Deux formes qui se
  //    touchent sans se recouvrir se lisent comme un défaut d'impression.
  s += `  <g transform="translate(256 180) scale(0.66) translate(-256 -256)">\n`
     + symbole() + '\n  </g>\n';
  s += mot({ y: 414, taille: 76, couleur: couleurMot, interlettre: 12 }) + '\n';
  s += signature({ y: 462, taille: 25, couleur: couleurSignature, interlettre: 5.5 }) + '\n';
  return s + '</svg>\n';
}

svgs['source/garah-vertical-nuit.svg'] =
  vertical({ fond: NUIT, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-vertical.svg'] =
  vertical({ fond: null, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-vertical-clair.svg'] =
  vertical({ fond: null, couleurMot: NUIT, couleurSignature: VERT });

// --- Le bloc horizontal : pour un en-tête, une barre latérale ----------------
function horizontal({ fond, couleurMot, couleurSignature }) {
  const W = 1000, H = 280;
  let s = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}"
     role="img" aria-label="GARAH">\n  <title>GARAH — bloc horizontal</title>\n`;
  if (fond) s += `  <rect width="${W}" height="${H}" fill="${fond}"/>\n`;
  // Le symbole à gauche, calé sur la hauteur du bloc de texte.
  //
  // ⚠️ L'échelle et le décalage sont CALCULÉS, pas ajustés à l'œil : le
  //    dessin mesure 413 unités de haut, il en fait 215 une fois réduit,
  //    et il reste 32 de marge en haut comme en bas. À 0,6 les roues
  //    touchaient le bord bas — invisible sur fond nuit, très visible dès
  //    qu'on pose le bloc sur autre chose.
  s += `  <g transform="translate(9.6 22.1) scale(0.46)">\n` + symbole() + '\n  </g>\n';
  s += `  <text x="280" y="152" font-family="Arial, Helvetica, sans-serif" font-weight="700"
        font-size="112" letter-spacing="16" fill="${couleurMot}">GARAH</text>\n`;
  s += `  <text x="286" y="208" font-family="Arial, Helvetica, sans-serif" font-weight="600"
        font-size="30" letter-spacing="6" fill="${couleurSignature}">AU-DELÀ DES FRONTIÈRES</text>\n`;
  return s + '</svg>\n';
}

svgs['source/garah-horizontal-nuit.svg'] =
  horizontal({ fond: NUIT, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-horizontal.svg'] =
  horizontal({ fond: null, couleurMot: BLEU, couleurSignature: VERT });
svgs['source/garah-horizontal-clair.svg'] =
  horizontal({ fond: null, couleurMot: NUIT, couleurSignature: VERT });

// --- L'icône « maskable » d'Android ------------------------------------------
// ⚠️ Android RECADRE l'icône en cercle, en goutte ou en carré arrondi selon
//    le lanceur. Le dessin doit tenir dans les 80 % centraux, sinon il est
//    rogné — c'est le défaut le plus courant des icônes de PWA.
svgs['source/garah-maskable.svg'] =
  enTete(512, 512, 'GARAH — icône recadrable Android')
  + `  <rect width="512" height="512" fill="${NUIT}"/>\n`
  + `  <g transform="translate(256 256) scale(0.56) translate(-256 -256)">\n`
  + symbole() + '\n  </g>\n</svg>\n';

// --- Le favicon SVG ----------------------------------------------------------
svgs['web/favicon.svg'] =
  enTete(512, 512, 'GARAH')
  + `  <rect width="512" height="512" rx="96" fill="${NUIT}"/>\n`
  + `  <g transform="translate(256 256) scale(0.74) translate(-256 -256)">\n`
  + symbolePetit() + '\n  </g>\n</svg>\n';

// --- Le favicon des toutes petites tailles -----------------------------------
svgs['web/favicon-minuscule.svg'] =
  enTete(512, 512, 'GARAH — favicon 16 px')
  + `  <rect width="512" height="512" rx="96" fill="${NUIT}"/>\n`
  + symboleMinuscule() + '\n</svg>\n';

// --- La bannière de partage --------------------------------------------------
svgs['web/og-image.svg'] = (() => {
  const W = 1200, H = 630;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">
  <title>GARAH</title>
  <rect width="${W}" height="${H}" fill="${NUIT}"/>
  <rect width="${W}" height="8" fill="${BLEU}"/>
  <g transform="translate(420 60) scale(0.72)">
${symbole()}
  </g>
  <text x="600" y="500" text-anchor="middle" font-family="Arial, Helvetica, sans-serif"
        font-weight="700" font-size="96" letter-spacing="16" fill="${BLEU}">GARAH</text>
  <text x="600" y="556" text-anchor="middle" font-family="Arial, Helvetica, sans-serif"
        font-weight="600" font-size="30" letter-spacing="7" fill="${VERT}">AU-DELÀ DES FRONTIÈRES</text>
</svg>\n`;
})();

// =============================================================================
//  ÉCRITURE
// =============================================================================

for (const [rel, contenu] of Object.entries(svgs)) {
  const cible = path.join(ICI, rel);
  fs.mkdirSync(path.dirname(cible), { recursive: true });
  fs.writeFileSync(cible, contenu);
}
console.log('SVG :', Object.keys(svgs).length, 'fichiers');

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

  const args = [
    '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    `--window-size=${largeur},${hauteur}`,
    `--screenshot=${sortie}`,
    ...(transparent ? ['--default-background-color=00000000'] : []),
    'file:///' + page.replace(/\\/g, '/'),
  ];
  execFileSync(CHROME, args, { stdio: 'ignore' });
}

/** Les cibles : source, taille, chemin, fond transparent ou non. */
const PNGS = [
  // --- Web -----------------------------------------------------------------
  // ⚠️ Le 16 vient d'une AUTRE source. Réduire le dessin de 32 donnerait une
  //    tache : à cette taille, l'anse et les roues tiennent dans huit pixels.
  ['web/favicon-minuscule.svg',    16,  16,  'web/favicon-16.png',            false],
  ['web/favicon.svg',              32,  32,  'web/favicon-32.png',            false],
  ['web/favicon.svg',              48,  48,  'web/favicon-48.png',            false],
  // ⚠️ apple-touch-icon : iOS n'accepte PAS la transparence — il la remplit
  //    en noir. Le fond nuit est donc dessiné, pas laissé au hasard.
  ['source/garah-symbole-nuit.svg', 180, 180, 'web/apple-touch-icon.png',     false],
  ['source/garah-symbole-nuit.svg', 192, 192, 'web/icone-192.png',            false],
  ['source/garah-symbole-nuit.svg', 512, 512, 'web/icone-512.png',            false],
  ['source/garah-maskable.svg',     512, 512, 'web/icone-maskable-512.png',   false],
  ['web/og-image.svg',             1200, 630, 'web/og-image.png',             false],

  // --- Mobile : Android ------------------------------------------------------
  ['source/garah-symbole-nuit.svg',  48,  48, 'mobile/android/mipmap-mdpi/ic_launcher.png',    false],
  ['source/garah-symbole-nuit.svg',  72,  72, 'mobile/android/mipmap-hdpi/ic_launcher.png',    false],
  ['source/garah-symbole-nuit.svg',  96,  96, 'mobile/android/mipmap-xhdpi/ic_launcher.png',   false],
  ['source/garah-symbole-nuit.svg', 144, 144, 'mobile/android/mipmap-xxhdpi/ic_launcher.png',  false],
  ['source/garah-symbole-nuit.svg', 192, 192, 'mobile/android/mipmap-xxxhdpi/ic_launcher.png', false],
  // L'avant-plan d'une icône adaptative : 432 px, dessin dans les 66 % centraux.
  ['source/garah-maskable.svg',     432, 432, 'mobile/android/ic_launcher_foreground.png',     true],
  ['source/garah-symbole-nuit.svg', 512, 512, 'mobile/android/play-store-512.png',             false],

  // --- Mobile : iOS ----------------------------------------------------------
  ['source/garah-symbole-nuit.svg',  40,  40, 'mobile/ios/AppIcon-40.png',   false],
  ['source/garah-symbole-nuit.svg',  60,  60, 'mobile/ios/AppIcon-60.png',   false],
  ['source/garah-symbole-nuit.svg',  58,  58, 'mobile/ios/AppIcon-58.png',   false],
  ['source/garah-symbole-nuit.svg',  87,  87, 'mobile/ios/AppIcon-87.png',   false],
  ['source/garah-symbole-nuit.svg', 120, 120, 'mobile/ios/AppIcon-120.png',  false],
  ['source/garah-symbole-nuit.svg', 180, 180, 'mobile/ios/AppIcon-180.png',  false],
  ['source/garah-symbole-nuit.svg', 1024, 1024, 'mobile/ios/AppIcon-1024.png', false],

  // --- Back-office et supports ----------------------------------------------
  ['source/garah-horizontal.svg',   500, 140, 'admin/logo-horizontal.png',     true],
  ['source/garah-horizontal.svg',  1000, 280, 'admin/logo-horizontal@2x.png',  true],
  ['source/garah-symbole.svg',      256, 256, 'admin/symbole-256.png',         true],
  ['source/garah-vertical-nuit.svg', 1024, 1024, 'apercu/garah-vertical-nuit.png', false],
  ['source/garah-vertical.svg',     1024, 1024, 'apercu/garah-vertical.png',   true],
  ['source/garah-horizontal-nuit.svg', 2000, 560, 'apercu/garah-horizontal-nuit.png', false],
];

for (const [src, w, h, dest, transp] of PNGS) {
  const cible = path.join(ICI, dest);
  fs.mkdirSync(path.dirname(cible), { recursive: true });
  rasteriser(src, w, h, cible, transp);
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

  const enTete = Buffer.alloc(6);
  enTete.writeUInt16LE(0, 0);            // réservé
  enTete.writeUInt16LE(1, 2);            // 1 = icône
  enTete.writeUInt16LE(images.length, 4);

  const REP = 16;
  let offset = 6 + REP * images.length;
  const repertoire = [];

  for (const img of images) {
    const e = Buffer.alloc(REP);
    e.writeUInt8(img.cote >= 256 ? 0 : img.cote, 0);   // largeur
    e.writeUInt8(img.cote >= 256 ? 0 : img.cote, 1);   // hauteur
    e.writeUInt8(0, 2);                                 // palette : aucune
    e.writeUInt8(0, 3);                                 // réservé
    e.writeUInt16LE(1, 4);                              // plans
    e.writeUInt16LE(32, 6);                             // bits par pixel
    e.writeUInt32LE(img.donnees.length, 8);
    e.writeUInt32LE(offset, 12);
    repertoire.push(e);
    offset += img.donnees.length;
  }

  fs.writeFileSync(path.join(ICI, sortie),
    Buffer.concat([enTete, ...repertoire, ...images.map((i) => i.donnees)]));
}

fabriquerIco(
  ['web/favicon-16.png', 'web/favicon-32.png', 'web/favicon-48.png'],
  'web/favicon.ico',
);
console.log('ICO : web/favicon.ico');

fs.rmSync(TEMP, { recursive: true, force: true });
console.log('\nTerminé.');
