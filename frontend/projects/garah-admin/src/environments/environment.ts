/**
 * Configuration de PRODUCTION (et du developpement, voir environment.development).
 *
 * L'API est Azure App Service, joint EN DIRECT.
 *
 * ⚠️ Le Worker Cloudflare a ete retire (D-28). Il existait pour contourner un
 *    filtrage TLS d'Orange Cameroun sur `garah-api.onrender.com` (D-22) — un
 *    filtrage qui visait CE nom d'hote la, pas l'hebergeur.
 *
 *    Si un jour l'API redevenait injoignable depuis une connexion Orange
 *    alors qu'elle repond ailleurs, c'est ici qu'il faudrait regarder en
 *    premier : le symptome est une poignee de main TLS coupee, pas une
 *    erreur applicative. Le Worker se remet en une heure — son code vit dans
 *    l'historique git, et D-22 explique pourquoi il avait ete ecrit.
 */
export const environnement = {
  production: true,
  urlApi: 'https://garah-api-anfeapebbth7h7an.francecentral-01.azurewebsites.net',
};
