import { InjectionToken } from '@angular/core';

/**
 * L'adresse de l'API, fournie par chaque application.
 *
 * <p>⚠️ En production, c'est l'URL du <b>Worker Cloudflare</b>, pas celle de
 * Render : depuis une connexion Orange Cameroun, `onrender.com` est filtré au
 * niveau TLS et injoignable (D-22). Se tromper ici produit une application qui
 * fonctionne parfaitement chez le développeur et pas chez le client.</p>
 */
export interface ConfigurationApi {
  /** Sans barre finale : l'intercepteur concatène des chemins commençant par `/`. */
  readonly baseUrl: string;
}

export const ConfigurationApi = new InjectionToken<ConfigurationApi>('ConfigurationApi');
