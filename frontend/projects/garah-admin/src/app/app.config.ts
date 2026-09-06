import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { ConfigurationApi, intercepteurApi } from 'garah-ui';

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
  ],
};
