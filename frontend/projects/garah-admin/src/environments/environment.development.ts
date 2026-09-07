/**
 * Configuration de DEVELOPPEMENT.
 *
 * On vise volontairement la MEME API que la production : Azure App Service.
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
 *
 * ⚠️ Le backend doit accepter `http://localhost:4200` dans
 *    GARAH_CORS_ORIGINS. Sans cela, l'API repond correctement et le
 *    NAVIGATEUR jette la reponse : page vide, aucune erreur serveur — le
 *    symptome le plus deroutant qui soit.
 */
export const environnement = {
  production: false,
  urlApi: 'https://garah-api-anfeapebbth7h7an.francecentral-01.azurewebsites.net',
};
