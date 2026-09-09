#!/usr/bin/env node
/**
 * L'API accepte-t-elle cette adresse ?
 *
 * ## 🎯 L'étape qu'on oublie, et qui échoue EN SILENCE
 *
 * `deploiement/frontends.md` la met en majuscules : « SANS CETTE ÉTAPE, RIEN
 * NE MARCHERA ». Chaque nouveau domaine doit figurer dans
 * `GARAH_CORS_ORIGINS`, côté serveur.
 *
 * Quand on l'oublie, le symptôme est le plus déroutant qui soit : **l'API
 * répond correctement et le navigateur jette la réponse**. Pas d'erreur
 * serveur, pas de trace dans les journaux, une page vide. On cherche du côté
 * du réseau, de la session, du build — rarement d'une liste d'origines.
 *
 * Ce script pose la question directement, en envoyant le préflight que le
 * navigateur enverrait.
 *
 * ## Usage
 *
 *     node outils/verifier-origines.mjs
 *     node outils/verifier-origines.mjs https://garah-admin.vercel.app
 *     GARAH_API=http://localhost:8080 node outils/verifier-origines.mjs
 *
 * Sans argument, il vérifie les adresses connues du projet.
 */

const API = process.env.GARAH_API
  ?? 'https://garah-api-anfeapebbth7h7an.francecentral-01.azurewebsites.net';

/**
 * Les adresses du projet.
 *
 * ⚠️ Elles n'étaient écrites NULLE PART. Celle de la boutique a été retrouvée
 *    en essayant des origines contre l'API jusqu'à ce qu'un préflight passe.
 *    Un projet dont personne ne sait où il est déployé est un projet qu'on ne
 *    sait pas dépanner.
 */
const CONNUES = [
  { url: 'https://garah.vercel.app', quoi: 'la boutique (Vercel)' },

  // ⚠️ Les adresses locales ne comptent que si l'on POINTE un serveur de
  //    développement sur cette API. Depuis D-37 ce n'est plus le défaut : le
  //    back-office et la boutique visent le poste. Un refus ci-dessous n'est
  //    donc un problème que pour qui repasse volontairement sur le déployé —
  //    ce qu'il faut faire avant de livrer, justement.
  { url: 'http://localhost:4200', quoi: 'le back-office en développement' },
  { url: 'http://localhost:4300', quoi: 'la boutique en développement' },

  // ⚠️ Le back-office n'a AUCUNE adresse déployée : son workflow Azure est
  //    désactivé (vars.AZURE_SWA_ADMIN_ACTIVE), et aucun projet Vercel ne
  //    répond. Le jour où il en aura une, elle vient ici — et l'oublier
  //    donnerait un back-office qui affiche des écrans vides.
];

/**
 * ⚠️ On interroge `/api/auth/connexion`, et pas une route publique.
 *
 * Le préflight ne part que sur les requêtes « non simples » — celles qui
 * portent un en-tête comme `Content-Type: application/json`. Une route
 * publique en GET n'en déclenche aucun : elle répondrait 200 même avec une
 * origine refusée, et le script dirait que tout va bien.
 */
async function accepte(origine) {
  try {
    const reponse = await fetch(`${API}/api/auth/connexion`, {
      method: 'OPTIONS',
      headers: {
        Origin: origine,
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type,x-garah-client',
      },
    });
    return {
      ok: reponse.ok,
      statut: reponse.status,
      // L'en-tête que le navigateur exige. Sa présence est la vraie réponse :
      // un 200 sans lui ne servirait à rien.
      autorise: reponse.headers.get('access-control-allow-origin'),
    };
  } catch (e) {
    return { ok: false, statut: 0, erreur: e.message };
  }
}

const demandees = process.argv.slice(2);
const cibles = demandees.length
  ? demandees.map((url) => ({ url, quoi: 'demandée en argument' }))
  : CONNUES;

console.log(`\nAPI : ${API}\n`);

let refusees = 0;
for (const { url, quoi } of cibles) {
  const r = await accepte(url);
  const verdict = r.ok && r.autorise ? '✅ acceptée' : '❌ REFUSÉE';
  if (!r.ok || !r.autorise) refusees++;

  console.log(`  ${verdict.padEnd(12)} ${url.padEnd(42)} ${quoi}`);
  if (!r.ok) {
    console.log(`               préflight ${r.statut}${r.erreur ? ' — ' + r.erreur : ''}`);
  } else if (!r.autorise) {
    console.log('               préflight 200, mais SANS Access-Control-Allow-Origin');
  }
}

if (refusees > 0) {
  console.log(`
⚠️  ${refusees} adresse(s) refusée(s).

    Une application servie depuis une origine refusée AFFICHE DES PAGES VIDES,
    sans la moindre erreur serveur. Ajoutez-la à GARAH_CORS_ORIGINS, dans les
    paramètres de l'application Azure, puis redémarrez-la.

    Voir deploiement/frontends.md, section 4.
`);
  process.exit(1);
}

console.log('\nToutes les adresses sont acceptées.\n');
