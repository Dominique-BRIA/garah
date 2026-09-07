import { InjectionToken } from '@angular/core';

/**
 * L'adresse de l'API, fournie par chaque application.
 *
 * <p>Depuis D-27, c'est Azure App Service, joint <b>en direct</b> : le Worker
 * Cloudflare qui s'interposait a été retiré (D-28).</p>
 *
 * <p>⚠️ <b>Le symptôme à reconnaître si cette adresse devient mauvaise.</b>
 * Un nom d'hôte filtré par un opérateur — c'est arrivé avec
 * `onrender.com` chez Orange Cameroun (D-22) — ne produit <b>aucune erreur
 * serveur</b> : la poignée de main TLS est coupée avant que la requête
 * n'existe. L'application marche alors parfaitement chez le développeur et
 * pas chez le client, et on cherche longtemps dans le code.</p>
 */
export interface ConfigurationApi {
  /** Sans barre finale : l'intercepteur concatène des chemins commençant par `/`. */
  readonly baseUrl: string;
}

export const ConfigurationApi = new InjectionToken<ConfigurationApi>('ConfigurationApi');
