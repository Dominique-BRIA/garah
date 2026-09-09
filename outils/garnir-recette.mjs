#!/usr/bin/env node
/**
 * Garnit une base de recette : de quoi acheter quelque chose.
 *
 * ## 🎯 Pourquoi ce script existe
 *
 * Le profil `recette` donne une base VIDE. Une base vide ne prouve rien : les
 * listes reviennent vides, les écrans affichent « rien à afficher », et l'on
 * ne sait pas si c'est juste ou cassé.
 *
 * Le parcours d'achat complet a d'abord été monté à la main, appel par appel,
 * pour vérifier qu'il tenait. Ce script rejoue cette suite : quinze secondes
 * au lieu d'une heure, et surtout, la même à chaque fois.
 *
 * ## Ce qu'il pose
 *
 * ```
 * un marchand        Atelier Ateba
 * une categorie      Vannerie
 * un produit publie  Panier tresse — 12 000 FCFA, 10 en stock
 * un point           Marche Mokolo, Yaounde, 1 000 FCFA d'acheminement
 * un client          client.recette@garah.cm / MotDePasse123
 * ```
 *
 * ## ⚠️ Il n'est PAS anodin
 *
 * `GARAH_S3_*` et `GARAH_MAIL_*` viennent de `.env` et pointent sur les vrais
 * services : la photo part dans le bucket de PRODUCTION, et l'inscription
 * envoie un VRAI courriel. C'est pourquoi ce script :
 *
 *   - refuse de tourner ailleurs que sur `localhost` ;
 *   - efface la photo d'essai aussitôt le produit publié.
 *
 * ## Usage
 *
 *     node outils/garnir-recette.mjs
 *
 * Il faut l'API lancée avec le profil recette, et un super-administrateur
 * connu : `GARAH_RECETTE_ADMIN` / `GARAH_RECETTE_MOT_DE_PASSE` (par défaut
 * `admin@garah.cm` / `Recette2026`).
 */

const API = process.env.GARAH_RECETTE_API ?? 'http://localhost:8080';
const ADMIN = process.env.GARAH_RECETTE_ADMIN ?? 'admin@garah.cm';
const MOT_DE_PASSE = process.env.GARAH_RECETTE_MOT_DE_PASSE ?? 'Recette2026';

const CLIENT = 'client.recette@garah.cm';
const CLIENT_MOT_DE_PASSE = 'MotDePasse123';

// ⚠️ Le garde-fou. Ce script cree des donnees et depose un fichier ; le
//    laisser viser une adresse distante par megarde ecrirait en production.
if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:|$)/.test(API)) {
  console.error(`Refus : ${API} n'est pas local. Ce script ne vise que localhost.`);
  process.exit(1);
}

let jeton = null;

async function appeler(methode, chemin, corps, options = {}) {
  const entetes = {
    Accept: 'application/json',
    'X-Garah-Client': options.public ?? 'admin',
    ...(corps ? { 'Content-Type': 'application/json' } : {}),
    ...(options.jeton !== undefined
      ? options.jeton ? { Authorization: `Bearer ${options.jeton}` } : {}
      : jeton ? { Authorization: `Bearer ${jeton}` } : {}),
  };

  const reponse = await fetch(`${API}${chemin}`, {
    method: methode,
    headers: entetes,
    body: corps ? JSON.stringify(corps) : undefined,
  });

  const texte = await reponse.text();
  const donnees = texte ? JSON.parse(texte) : null;

  if (!reponse.ok && !options.tolere) {
    throw new Error(
      `${methode} ${chemin} → ${reponse.status}\n  ${donnees?.message ?? texte}`);
  }
  return { statut: reponse.status, donnees };
}

function dire(etape, detail = '') {
  console.log(`  ${etape.padEnd(28)} ${detail}`);
}

async function garnir() {
  console.log(`\nGarnissage de ${API}\n`);

  // --- 1. S'identifier -------------------------------------------------------
  const connexion = await appeler('POST', '/api/auth/connexion',
    { email: ADMIN, motDePasse: MOT_DE_PASSE });
  jeton = connexion.donnees.jeton;
  dire('super-administrateur', connexion.donnees.utilisateur.nom);

  // --- 2. Le catalogue -------------------------------------------------------
  const marchand = await appeler('POST', '/api/marchands', {
    nom: 'Atelier Ateba', type: 'EXTERNE', pays: 'CM',
    telephone: '+237690000010', email: 'atelier@garah.cm',
  });
  dire('marchand', marchand.donnees.code);

  const categorie = await appeler('POST', '/api/categories',
    { nom: 'Vannerie', parentId: null, ordre: 0 });
  dire('categorie', categorie.donnees.nom);

  const produit = await appeler('POST', '/api/produits', {
    marchandId: marchand.donnees.id,
    categorieId: categorie.donnees.id,
    nom: 'Panier tresse',
  });
  dire('produit', `${produit.donnees.reference} (${produit.donnees.statut})`);

  const variante = await appeler('POST',
    `/api/produits/${produit.donnees.id}/variantes`,
    { sku: 'PAN-TRESSE-01', libelle: 'Taille unique' });
  dire('declinaison', variante.donnees.sku);

  await appeler('PUT',
    `/api/produits/${produit.donnees.id}/variantes/${variante.donnees.id}/paliers`,
    { quantiteMin: 1, quantiteMax: null, prixUnitaire: 12000 });
  dire('prix', '12 000 FCFA');

  await appeler('POST', `/api/stock/${variante.donnees.id}/entrees`,
    { quantite: 10, commentaire: 'Garnissage de recette' });
  dire('stock', '10 pieces');

  // --- 3. La photo, sans laquelle on ne publie pas (I-12) --------------------
  //
  // ⚠️ Elle part dans le bucket de PRODUCTION : on l'efface juste apres. Un
  //    PNG de 1x1 pixel suffit — l'invariant demande une photo, pas une belle.
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ'
    + 'AAAABJRU5ErkJggg==', 'base64');
  const formulaire = new FormData();
  formulaire.append('fichier', new Blob([png], { type: 'image/png' }), 'essai.png');
  formulaire.append('principal', 'true');

  const media = await fetch(`${API}/api/produits/${produit.donnees.id}/medias`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${jeton}`, 'X-Garah-Client': 'admin' },
    body: formulaire,
  }).then(async (r) => {
    if (!r.ok) throw new Error(`televersement → ${r.status} ${await r.text()}`);
    return r.json();
  });
  dire('photo', 'deposee (provisoire)');

  await appeler('POST', `/api/produits/${produit.donnees.id}/publication`);
  dire('publication', 'PUBLIE');

  await appeler('DELETE',
    `/api/produits/${produit.donnees.id}/medias/${media.id}`);
  dire('photo', 'effacee du bucket');

  // --- 4. Ou l'on retire --------------------------------------------------
  const lieu = await appeler('POST', '/api/lieux', {
    type: 'POINT_RECUPERATION', nom: 'Marche Mokolo', pays: 'CM',
    ville: 'Yaounde', adresse: 'Face au marche', telephone: '+237690000020',
    horaires: '8h-18h', fraisAcheminement: 1000,
  });
  dire('point de recuperation', `${lieu.donnees.nom} (+1 000 FCFA)`);

  // --- 5. Un client ----------------------------------------------------------
  //
  // ⚠️ L'inscription envoie un VRAI courriel a cette adresse. Elle est
  //    volontairement inexistante.
  const inscription = await appeler('POST', '/api/auth/inscription', {
    nom: 'Recette', email: CLIENT, motDePasse: CLIENT_MOT_DE_PASSE,
    telephone: '+237690000001',
  }, { public: '1', jeton: null, tolere: true });

  dire('client', inscription.statut === 201 ? CLIENT : `${CLIENT} (existait deja)`);

  console.log(`
⚠️  Il reste UNE chose a faire a la main.

    Commander exige une adresse CONFIRMEE. Le courriel est bien parti, mais
    vers une adresse qui n'existe pas. En recette, on confirme en base :

      UPDATE utilisateur SET email_verifie = true WHERE email = '${CLIENT}';

    C'est volontairement laisse manuel : le faire ici reviendrait a offrir un
    contournement de la verification d'adresse dans un script du depot.

Le parcours est alors complet : vitrine → fiche → panier → commande.
`);
}

garnir().catch((e) => {
  console.error(`\nEchec : ${e.message}\n`);
  process.exit(1);
});
