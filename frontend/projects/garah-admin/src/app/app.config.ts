import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { ConfigurationApi, ServiceSession, intercepteurApi } from 'garah-ui';

import { environnement } from '../environments/environment';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),

    // ⚠️ L'intercepteur est le SEUL endroit qui connait l'URL de l'API, pose
    // l'en-tete X-Garah-Client et gere le rafraichissement a 401. Un appel
    // HttpClient qui le contournerait perdrait les trois d'un coup.
    provideHttpClient(withInterceptors([intercepteurApi])),

    { provide: ConfigurationApi, useValue: { baseUrl: environnement.urlApi } },

    /*
     * 🎯 LA SESSION EST RESTAUREE AVANT QU'ANGULAR NE DEMARRE.
     *
     * ⚠️ Sans cela, l'ecran reste BLANC pendant tout l'appel — parfois deux
     *    minutes — et personne ne comprend pourquoi.
     *
     * Le mecanisme, decouvert en regardant une page vide :
     *
     *   1. index.html affiche l'ecran d'attente (le mot GARAH, la calebasse)
     *   2. Angular demarre et REMPLACE le contenu de <ga-root> :
     *      l'ecran d'attente disparait
     *   3. le garde d'authentification appelle SEULEMENT MAINTENANT l'API
     *   4. tant que la reponse n'arrive pas, le routeur n'a aucun composant
     *      a afficher — page blanche
     *
     * L'ecran d'attente couvrait donc le mauvais intervalle : le
     * telechargement du JavaScript, qui est rapide, et il s'effacait juste
     * avant l'attente reseau, qui est lente. L'API repond en 6 s a chaud, et
     * jusqu'a deux minutes quand l'instance Render s'est endormie (D-14) —
     * d'ou un defaut qui n'apparait QUE parfois, le pire genre.
     *
     * Ici, Angular ne termine pas son demarrage tant que la promesse n'est pas
     * tenue : l'ecran d'attente reste affiche pendant toute la duree reelle,
     * ce pour quoi il avait ete ecrit.
     *
     * ⚠️ `restaurer()` ne touche le reseau QUE si un indice de session existe
     *    dans localStorage. Un visiteur qui n'a jamais ouvert de session
     *    n'attend rien du tout.
     *
     * Il n'echoue jamais : une erreur reseau donne « pas de session », et
     * l'application demarre sur l'ecran de connexion. Bloquer le demarrage
     * sur une API injoignable serait echanger une page blanche contre une
     * autre.
     */
    provideAppInitializer(() => inject(ServiceSession).restaurer()),
  ],
};
