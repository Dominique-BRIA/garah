/**
 * =============================================================================
 * Proxy inverse Cloudflare Worker — contourne le filtrage d'Orange Cameroun
 * =============================================================================
 *
 * POURQUOI CE FICHIER EXISTE
 *
 * Depuis une connexion Orange Cameroun, `garah-api.onrender.com` est
 * inaccessible : le DNS resout, le TCP s'etablit, et la poignee de main TLS est
 * coupee net. C'est un filtrage par SNI — l'operateur lit le nom de domaine
 * dans le premier paquet TLS et referme la connexion.
 *
 * Ce n'est pas un probleme de code : c'est le chemin reseau entre nos clients
 * et Render. Or GARAH vend a Douala et Bangui. Une vitrine que la moitie du
 * marche ne peut pas ouvrir n'est pas une vitrine.
 *
 * Ce Worker s'interpose :
 *
 *     navigateur  ──▶  garah-api.<compte>.workers.dev   ← Cloudflare, non filtre
 *                          │
 *                          ▼
 *                      garah-api.onrender.com           ← jamais vu du navigateur
 *
 * ⚠️ UNE REDIRECTION NE MARCHERAIT PAS. Un 301 vers onrender.com ferait ouvrir
 *    au navigateur une connexion vers ce nom-la, et Orange la couperait comme
 *    avant. Il faut que Cloudflare aille chercher l'amont LUI-MEME.
 *
 * =============================================================================
 * DEPLOIEMENT
 * =============================================================================
 *   Cloudflare → Compute → Workers & Pages → Create → Start from Hello World
 *   Nommer le Worker « garah-api », coller ce fichier, Deploy.
 *
 *   L'URL devient  https://garah-api.<votre-compte>.workers.dev
 *
 * Plan gratuit : 100 000 requetes par jour. Tres au-dela de nos besoins.
 * =============================================================================
 */

/** L'amont. Le seul endroit a changer le jour du VPS. */
const AMONT = "https://garah-api.onrender.com";

export default {
  async fetch(requete) {
    const url = new URL(requete.url);
    const cible = new URL(url.pathname + url.search, AMONT);

    const entetes = new Headers(requete.headers);

    // 🎯 L'ADRESSE IP REELLE DU VISITEUR.
    //
    // Sans cette ligne, toutes les requetes arriveraient a l'API avec
    // l'adresse d'un serveur Cloudflare. Consequence : chaque evenement de
    // securite, chaque echec de connexion et chaque vue de produit porterait
    // LA MEME adresse.
    //
    // Le score de risque compte les adresses distinctes d'un client : il
    // deviendrait rigoureusement aveugle, sans qu'aucune erreur ne le signale.
    // C'est exactement ce que AdresseClient.de() cherche a eviter cote API.
    const ip = requete.headers.get("CF-Connecting-IP");
    if (ip) {
      entetes.set("X-Forwarded-For", ip);
    }

    // Host est un en-tete interdit : fetch pose celui de l'amont tout seul.
    // Render route par Host — lui envoyer workers.dev ne correspondrait a
    // aucun service.
    entetes.delete("Host");

    const amont = new Request(cible, {
      method: requete.method,
      headers: entetes,
      // GET et HEAD n'ont pas de corps ; en fournir un fait echouer la requete.
      body: ["GET", "HEAD"].includes(requete.method) ? undefined : requete.body,
      // ⚠️ « manual » : on transmet les redirections telles quelles.
      // Les suivre ici ferait perdre au navigateur l'information, et un
      // Location vers onrender.com le renverrait droit dans le filtrage.
      redirect: "manual",
    });

    const reponse = await fetch(amont, {
      cf: {
        // 🎯 AUCUN CACHE. Ce n'est pas une optimisation qu'on refuse, c'est une
        // fuite qu'on ferme.
        //
        // Cloudflare est un cache PARTAGE. Une reponse de /api/auth/moi ou
        // /api/commandes/miennes mise en cache serait servie au visiteur
        // SUIVANT. Notre API ne pose pas d'en-tetes Cache-Control : on ne
        // laisse pas la confidentialite des commandes dependre d'une
        // heuristique du fournisseur.
        cacheTtl: 0,
        cacheEverything: false,
      },
    });

    // ⚠️ On reconstruit la reponse SANS toucher aux en-tetes.
    //
    // C'est indispensable pour Set-Cookie : la connexion en pose un
    // (garah_refresh, HttpOnly, 14 jours, D-19). Recopier les en-tetes a la
    // main en fusionne facilement plusieurs en un seul, et la session
    // deviendrait impossible a prolonger — panne invisible pendant les 15
    // minutes de vie du jeton d'acces, puis deconnexion inexpliquee.
    //
    // Passer la reponse d'origine comme init preserve la liste complete.
    return new Response(reponse.body, reponse);
  },
};
