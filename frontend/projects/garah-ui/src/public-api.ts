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
export * from './lib/modeles/page';
export * from './lib/modeles/catalogue';

// --- Les icones ---
export * from './lib/icones/icone';
export * from './lib/icones/traces';

// --- Le theme ----------------------------------------------------------------
export * from './lib/theme/service-theme';
