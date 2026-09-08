/*
 * L'API publique de garah-ui.
 *
 * Ce qui n'est pas exporté ici n'existe pas pour les applications. C'est la
 * frontiere de la librairie, et la garder etroite est ce qui permet de
 * remanier l'interieur sans casser les trois frontends.
 */

// --- Le pont vers l'API ------------------------------------------------------
export * from './lib/api/configuration-api';
export * from './lib/api/intercepteur-api';
export * from './lib/api/service-session';

// --- Les modeles, alignes sur les DTO du backend -----------------------------
export * from './lib/modeles/authentification';
export * from './lib/modeles/erreur';
export * from './lib/modeles/profil';
export * from './lib/modeles/page';
export * from './lib/modeles/catalogue';
export * from './lib/modeles/marchand';
export * from './lib/modeles/categorie';
export * from './lib/modeles/variante';
export * from './lib/modeles/attribut';
export * from './lib/modeles/logistique';
export * from './lib/modeles/equipe';
export * from './lib/modeles/commerce';
export * from './lib/modeles/stock';
export * from './lib/modeles/sav';
export * from './lib/modeles/serviceclient';
export * from './lib/modeles/finance';
export * from './lib/modeles/client';
export * from './lib/modeles/statistiques';

// --- Les listes ---
export * from './lib/liste/bascule-vue';
export * from './lib/liste/pagination';

// --- Les icones ---
export * from './lib/icones/icone';
export * from './lib/icones/traces';

// --- La marque ---
export * from './lib/marque/marque';
export * from './lib/marque/logo';
export * from './lib/marque/avatar';

// --- Le theme ----------------------------------------------------------------
export * from './lib/theme/service-theme';
