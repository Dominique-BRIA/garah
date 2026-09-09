// =============================================================================
//  Vectorise le logo fourni — du PNG vers un tracé SVG
// =============================================================================
//  🎯 ON NE REDESSINE PAS LE LOGO, ON LE SUIT.
//
//  Redessiner « d'après » une image donne toujours un deuxième logo : les
//  proportions dérivent de quelques pour cent, et deux marques cohabitent
//  sans que personne ne sache laquelle fait foi.
//
//  Ici le contour est relevé pixel par pixel, puis simplifié. Le résultat est
//  le MÊME dessin, en vectoriel.
//
//  Aucune bibliothèque : le PNG est décodé à la main (zlib suffit), le
//  contour suivi par les arêtes de pixels, la ligne brisée réduite par
//  Douglas-Peucker.
//
//    node vectoriser.mjs <source.png> <sortie.svg>
// =============================================================================
import fs from 'node:fs';
import zlib from 'node:zlib';

// -----------------------------------------------------------------------------
// 1. Décoder le PNG
// -----------------------------------------------------------------------------

/**
 * ⚠️ Seul le cas utile est traité : 8 bits, RVB + alpha, non entrelacé.
 *    Un PNG entrelacé ou en palette produirait des pixels faux SANS erreur —
 *    on refuse explicitement plutôt que de livrer un tracé absurde.
 */
function decoder(fichier) {
  const b = fs.readFileSync(fichier);
  if (b.slice(0, 8).toString('hex') !== '89504e470d0a1a0a') {
    throw new Error('Ce fichier n’est pas un PNG.');
  }

  const largeur = b.readUInt32BE(16);
  const hauteur = b.readUInt32BE(20);
  const profondeur = b.readUInt8(24);
  const type = b.readUInt8(25);
  const entrelace = b.readUInt8(28);

  if (profondeur !== 8) throw new Error(`Profondeur ${profondeur} bits non gérée (8 attendus).`);
  if (type !== 6) throw new Error(`Type ${type} non géré (6 = RVB+alpha attendu).`);
  if (entrelace !== 0) throw new Error('PNG entrelacé non géré.');

  // Rassembler les morceaux IDAT — ils sont souvent découpés en plusieurs.
  const morceaux = [];
  let i = 8;
  while (i < b.length) {
    const taille = b.readUInt32BE(i);
    const nom = b.slice(i + 4, i + 8).toString('ascii');
    if (nom === 'IDAT') morceaux.push(b.slice(i + 8, i + 8 + taille));
    if (nom === 'IEND') break;
    i += 12 + taille;                       // taille + nom + données + CRC
  }
  const brut = zlib.inflateSync(Buffer.concat(morceaux));

  // Défiltrage. Chaque ligne commence par son type de filtre.
  const CANAL = 4;
  const parLigne = largeur * CANAL;
  const px = Buffer.alloc(hauteur * parLigne);

  for (let y = 0; y < hauteur; y++) {
    const filtre = brut[y * (parLigne + 1)];
    const source = brut.subarray(y * (parLigne + 1) + 1, (y + 1) * (parLigne + 1));
    const ligne = px.subarray(y * parLigne, (y + 1) * parLigne);
    const dessus = y > 0 ? px.subarray((y - 1) * parLigne, y * parLigne) : null;

    for (let x = 0; x < parLigne; x++) {
      const a = x >= CANAL ? ligne[x - CANAL] : 0;              // gauche
      const c = dessus ? dessus[x] : 0;                          // dessus
      const d = dessus && x >= CANAL ? dessus[x - CANAL] : 0;    // diagonale
      let v = source[x];
      switch (filtre) {
        case 0: break;
        case 1: v += a; break;
        case 2: v += c; break;
        case 3: v += (a + c) >> 1; break;
        case 4: {
          // Paeth : on garde le voisin le plus proche de la prédiction.
          const p = a + c - d;
          const pa = Math.abs(p - a), pc = Math.abs(p - c), pd = Math.abs(p - d);
          v += pa <= pc && pa <= pd ? a : pc <= pd ? c : d;
          break;
        }
        default: throw new Error(`Filtre ${filtre} inconnu ligne ${y}.`);
      }
      ligne[x] = v & 0xff;
    }
  }

  return { largeur, hauteur, px };
}

// -----------------------------------------------------------------------------
// 2. Suivre les contours
// -----------------------------------------------------------------------------

/**
 * Les contours, par les ARÊTES des pixels.
 *
 * Chaque pixel plein pose un segment ORIENTÉ sur chacun de ses côtés qui
 * touche le vide. Bout à bout, ces segments forment des boucles fermées —
 * le tour extérieur, et le tour de chaque trou.
 *
 * ⚠️ L'orientation compte : elle garantit qu'un segment sortant existe pour
 *    chaque segment entrant, donc qu'on peut toujours refermer la boucle. Une
 *    version non orientée se bloque au premier pixel en diagonale.
 */
function contours(masque, largeur, hauteur) {
  const plein = (x, y) =>
    x >= 0 && y >= 0 && x < largeur && y < hauteur && masque[y * largeur + x];

  const depuis = new Map();
  const clef = (x, y) => x + ',' + y;
  const poser = (x1, y1, x2, y2) => {
    const k = clef(x1, y1);
    if (!depuis.has(k)) depuis.set(k, []);
    depuis.get(k).push([x2, y2]);
  };

  for (let y = 0; y < hauteur; y++) {
    for (let x = 0; x < largeur; x++) {
      if (!plein(x, y)) continue;
      if (!plein(x, y - 1)) poser(x, y, x + 1, y);
      if (!plein(x + 1, y)) poser(x + 1, y, x + 1, y + 1);
      if (!plein(x, y + 1)) poser(x + 1, y + 1, x, y + 1);
      if (!plein(x - 1, y)) poser(x, y + 1, x, y);
    }
  }

  const boucles = [];
  for (const [depart] of depuis) {
    while (depuis.get(depart)?.length) {
      const boucle = [];
      let [x, y] = depart.split(',').map(Number);
      const premier = clef(x, y);
      do {
        boucle.push([x, y]);
        const suites = depuis.get(clef(x, y));
        if (!suites?.length) break;
        [x, y] = suites.pop();
      } while (clef(x, y) !== premier);
      if (boucle.length > 8) boucles.push(boucle);
    }
  }
  return boucles;
}

// -----------------------------------------------------------------------------
// 3. Simplifier
// -----------------------------------------------------------------------------

/**
 * Chaikin : on coupe les coins, deux fois.
 *
 * 🎯 LE CONTOUR BRUT EST UN ESCALIER. Il suit les arêtes des pixels : chaque
 *    marche fait un pixel. Réduit tel quel, il garde ses angles droits — et
 *    à 1024 px, un cercle relevé sur 453 px montre ses marches.
 *
 * Chaikin remplace chaque segment par ses points à 25 % et 75 %. Les angles
 * s'arrondissent, les droites restent droites. Deux passes suffisent : la
 * troisième rétrécit le dessin sans rien gagner de visible.
 *
 * ⚠️ AVANT Douglas-Peucker, jamais après. Après, on lisserait une ligne déjà
 *    réduite : les quelques points restants se mettraient à flotter loin du
 *    contour d'origine, et les angles vifs — le bout de la barre de poussée —
 *    seraient rabotés.
 */
function adoucir(points, passes = 2) {
  let p = points;
  for (let n = 0; n < passes; n++) {
    const sortie = [];
    for (let i = 0; i < p.length; i++) {
      const [x1, y1] = p[i];
      const [x2, y2] = p[(i + 1) % p.length];       // fermé : on boucle
      sortie.push([x1 + (x2 - x1) * 0.25, y1 + (y2 - y1) * 0.25]);
      sortie.push([x1 + (x2 - x1) * 0.75, y1 + (y2 - y1) * 0.75]);
    }
    p = sortie;
  }
  return p;
}

/**
 * Douglas-Peucker : on garde les points qui s'écartent le plus de la corde.
 *
 * Sans lui, le SVG pèserait des centaines de kilo-octets pour dessiner des
 * marches invisibles.
 */
function simplifier(points, tolerance) {
  if (points.length < 3) return points;

  const distance = ([px, py], [ax, ay], [bx, by]) => {
    const dx = bx - ax, dy = by - ay;
    const norme = dx * dx + dy * dy;
    if (norme === 0) return Math.hypot(px - ax, py - ay);
    let t = ((px - ax) * dx + (py - ay) * dy) / norme;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
  };

  const garder = new Uint8Array(points.length);
  garder[0] = garder[points.length - 1] = 1;

  const pile = [[0, points.length - 1]];
  while (pile.length) {
    const [a, b] = pile.pop();
    let pire = -1, ecart = tolerance;
    for (let i = a + 1; i < b; i++) {
      const d = distance(points[i], points[a], points[b]);
      if (d > ecart) { ecart = d; pire = i; }
    }
    if (pire !== -1) {
      garder[pire] = 1;
      pile.push([a, pire], [pire, b]);
    }
  }
  return points.filter((_, i) => garder[i]);
}

// -----------------------------------------------------------------------------
// 4. Écrire
// -----------------------------------------------------------------------------

const [, , SOURCE, SORTIE, TOL = '0.9', EPAISSIR = '0'] = process.argv;
if (!SOURCE || !SORTIE) {
  console.error('usage : node vectoriser.mjs <source.png> <sortie.svg> [tolerance] [epaissir]');
  process.exit(1);
}

const { largeur, hauteur, px } = decoder(SOURCE);

// Le masque : opaque à plus de la moitié. Le seuil se place au milieu de la
// frange d'anticrénelage — plus haut, le trait maigrit ; plus bas, il grossit.
const masque = new Uint8Array(largeur * hauteur);
let r = 0, v = 0, bl = 0, n = 0;
for (let i = 0; i < largeur * hauteur; i++) {
  const a = px[i * 4 + 3];
  if (a > 128) {
    masque[i] = 1;
    // La couleur dominante, relevée sur les pixels FRANCHEMENT opaques :
    // la frange d'anticrénelage tirerait la moyenne vers le fond.
    if (a > 240) { r += px[i * 4]; v += px[i * 4 + 1]; bl += px[i * 4 + 2]; n++; }
  }
}
const couleur = '#' + [r / n, v / n, bl / n]
  .map((c) => Math.round(c).toString(16).padStart(2, '0')).join('').toUpperCase();

/**
 * Épaissir le dessin, pour les petites tailles.
 *
 * 🎯 UN LOGO QUI RÉTRÉCIT NE SE CONTENTE PAS DE RÉTRÉCIR. À 32 px, les dix
 *    trous du panier font des carrés d'un pixel et demi : ils se remplissent
 *    d'anticrénelage et le panier redevient un bloc sale.
 *
 * Plutôt que de REDESSINER une version simplifiée — ce qui produirait un
 * second logo, aux proportions dérivées — on dilate le masque. Le trait
 * grossit, les trous se referment, et c'est toujours le MÊME dessin.
 *
 * Dilatation par distance de Chebyshev : un pixel s'allume si un pixel plein
 * se trouve dans le carré de rayon n. Suffisant ici, et sans dépendance.
 */
function epaissir(masque, largeur, hauteur, rayon) {
  if (rayon <= 0) return masque;
  let courant = masque;
  for (let passe = 0; passe < rayon; passe++) {
    const suivant = new Uint8Array(courant.length);
    for (let y = 0; y < hauteur; y++) {
      for (let x = 0; x < largeur; x++) {
        if (courant[y * largeur + x]) { suivant[y * largeur + x] = 1; continue; }
        for (let dy = -1; dy <= 1 && !suivant[y * largeur + x]; dy++) {
          for (let dx = -1; dx <= 1; dx++) {
            const nx = x + dx, ny = y + dy;
            if (nx >= 0 && ny >= 0 && nx < largeur && ny < hauteur
                && courant[ny * largeur + nx]) { suivant[y * largeur + x] = 1; break; }
          }
        }
      }
    }
    courant = suivant;
  }
  return courant;
}

const boucles = contours(epaissir(masque, largeur, hauteur, Number(EPAISSIR)), largeur, hauteur);
const reduites = boucles.map((b) => simplifier(adoucir(b), Number(TOL)));
const points = reduites.reduce((s, b) => s + b.length, 0);

// Recadrer sur le dessin : la source porte souvent des marges vides.
let x0 = largeur, y0 = hauteur, x1 = 0, y1 = 0;
for (const b of reduites) for (const [x, y] of b) {
  if (x < x0) x0 = x; if (x > x1) x1 = x;
  if (y < y0) y0 = y; if (y > y1) y1 = y;
}
const L = x1 - x0, H = y1 - y0;

const d = reduites
  .map((b) => 'M' + b.map(([x, y]) => `${(x - x0).toFixed(1)} ${(y - y0).toFixed(1)}`).join('L') + 'Z')
  .join('');

fs.writeFileSync(SORTIE,
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${L} ${H}" width="${L}" height="${H}"
     role="img" aria-label="GARAH">
  <title>GARAH — le symbole</title>
  <!-- Relevé au contour depuis ${SOURCE.split(/[\\/]/).pop()}, tolérance ${TOL} px.
       Ne pas retoucher : régénérer avec vectoriser.mjs. -->
  <path fill="${couleur}" fill-rule="evenodd" d="${d}"/>
</svg>\n`);

console.log('source    :', largeur + '×' + hauteur);
console.log('couleur   :', couleur);
console.log('contours  :', boucles.length, '(' + reduites.length + ' retenus)');
console.log('points    :', points, '— brut :', boucles.reduce((s, b) => s + b.length, 0));
console.log('cadre     :', L + '×' + H);
console.log('sortie    :', SORTIE, '(' + Math.round(fs.statSync(SORTIE).size / 1024) + ' Ko)');
