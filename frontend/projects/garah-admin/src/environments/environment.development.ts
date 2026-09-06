/**
 * Configuration de DEVELOPPEMENT.
 *
 * On vise volontairement la MEME API que la production : le Worker Cloudflare.
 *
 * Deux raisons :
 *   - pas besoin de lancer le backend en local pour travailler l'interface ;
 *   - on exerce pour de vrai le CORS, les cookies SameSite=None et les URL
 *     signees des medias — trois mecanismes qui ne se manifestent qu'en
 *     cross-origin, et qu'un backend sur localhost masquerait completement.
 *
 * ⚠️ La contrepartie est reelle : on travaille sur les DONNEES DE PRODUCTION.
 *    Une suppression depuis le back-office supprime pour de bon. Le jour ou
 *    l'application aura de vrais clients, il faudra une base de recette et
 *    cette ligne devra changer.
 */
export const environnement = {
  production: false,
  urlApi: 'https://garah-api.d-bria00.workers.dev',
};
