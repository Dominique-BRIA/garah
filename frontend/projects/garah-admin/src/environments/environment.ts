/**
 * Configuration de PRODUCTION (et du developpement, voir plus bas).
 *
 * ⚠️ L'API est le WORKER CLOUDFLARE, pas Render directement.
 *
 * Depuis une connexion Orange Cameroun, `garah-api.onrender.com` est filtre au
 * niveau TLS : le DNS resout, le TCP s'etablit, la poignee de main est coupee
 * (D-22). Pointer sur Render produirait une application qui marche chez le
 * developpeur et pas chez le client — le pire type de panne.
 */
export const environnement = {
  production: true,
  urlApi: 'https://garah-api.d-bria00.workers.dev',
};
