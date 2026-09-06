import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, switchMap, take, throwError } from 'rxjs';

import { ConfigurationApi } from './configuration-api';
import { ServiceSession } from './service-session';

/**
 * Les routes où un {@code 401} veut dire autre chose qu'« expiré ».
 *
 * <p>Ce sont celles qui <b>établissent</b> ou <b>détruisent</b> la session,
 * jamais celles qui s'en servent. Y tenter un rafraîchissement n'a aucun sens :
 * il n'y a rien à rafraîchir.</p>
 */
const REPONDENT_401_METIER = [
  '/api/auth/connexion',
  '/api/auth/inscription',
  '/api/auth/rafraichir',
  '/api/auth/deconnexion',
] as const;


/**
 * L'intercepteur unique : URL absolue, en-tête client, jeton, rafraîchissement.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TROIS CONTRATS QUE LE BACKEND IMPOSE, ET QUI NE SE VOIENT PAS
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. `X-Garah-Client` sur TOUTE requête.
 *    Le cookie de rafraîchissement est en `SameSite=None` — obligatoire, car
 *    Vercel et le Worker Cloudflare sont deux sites différents (D-19, D-22).
 *    Le navigateur l'envoie donc aussi depuis une page tierce. Le backend exige
 *    un en-tête personnalisé sur `/rafraichir` et `/deconnexion` : il force un
 *    préflight CORS que seules nos origines passent.
 *
 *    ⚠️ Sans cet en-tête, la session meurt au bout de 15 minutes avec un 403 —
 *       et rien avant ne le laisse deviner.
 *
 * 2. `withCredentials: true`, sinon le cookie ne part jamais.
 *    Une requête cross-origin n'emporte AUCUN cookie par défaut. Le
 *    rafraîchissement échouerait systématiquement, sans erreur serveur.
 *
 * 3. Le jeton d'accès vit 15 MINUTES.
 *    Il n'est pas rangé dans `localStorage` : il reste en mémoire (voir
 *    {@link ServiceSession}). Un 401 déclenche un rafraîchissement automatique
 *    et la requête d'origine est rejouée.
 * ═══════════════════════════════════════════════════════════════════════════
 */
export function intercepteurApi(
  requete: HttpRequest<unknown>,
  suite: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  const config = inject(ConfigurationApi);
  const session = inject(ServiceSession);

  // Les appels sortants vers autre chose que notre API (les médias sur
  // Backblaze, par exemple) ne doivent recevoir NI notre jeton NI nos cookies.
  // Y joindre l'un ou l'autre les enverrait à un tiers.
  if (!estAppelApi(requete.url, config.baseUrl)) {
    return suite(requete);
  }

  return suite(preparer(requete, config, session)).pipe(
    catchError((erreur: unknown) => {
      if (!(erreur instanceof HttpErrorResponse) || erreur.status !== 401) {
        return throwError(() => erreur);
      }

      // ⚠️ SUR CES ROUTES, UN 401 EST UNE RÉPONSE MÉTIER, PAS UN JETON EXPIRÉ.
      //
      // « Mauvais mot de passe » se dit 401. Le confondre avec « ton jeton a
      // expiré » déclenchait un rafraîchissement inutile avant de rendre la
      // main — jusqu'à une minute au réveil de l'hébergement, pendant laquelle
      // le bouton restait bloqué sur « Connexion… » sans aucun message.
      //
      // Sur /rafraichir, c'est pire encore : réessayer produirait une boucle
      // infinie.
      //
      // 🎯 Un rafraîchissement ne se justifie que là où le jeton était
      //    RÉELLEMENT le moyen d'authentification. Ces routes n'en portent
      //    aucun : elles l'établissent, ou le détruisent.
      if (REPONDENT_401_METIER.some((chemin) => requete.url.includes(chemin))) {
        if (requete.url.includes('/api/auth/rafraichir')) {
          session.terminer();
        }
        return throwError(() => erreur);
      }

      return rejouerApresRafraichissement(requete, suite, config, session, erreur);
    }),
  );
}

/**
 * Rejoue la requête après avoir renouvelé le jeton.
 *
 * <p>🎯 <b>Le point délicat : plusieurs requêtes échouent EN MÊME TEMPS.</b>
 * Un tableau de bord lance six appels au chargement ; le jeton expire ; les six
 * reçoivent un 401 en même temps. Sans coordination, six rafraîchissements
 * partent en parallèle.</p>
 *
 * <p>Ce serait pire qu'inefficace : le backend fait tourner le jeton à chaque
 * rafraîchissement (D-19). Le deuxième appel présenterait un jeton déjà
 * consommé, le backend y verrait un VOL, et <b>révoquerait toute la
 * famille</b> — déconnectant l'utilisateur au moment précis où l'on essayait
 * de le maintenir connecté.</p>
 *
 * <p>{@link ServiceSession.rafraichir} garantit donc un seul appel en vol, et
 * les autres attendent son résultat.</p>
 */
function rejouerApresRafraichissement(
  requete: HttpRequest<unknown>,
  suite: HttpHandlerFn,
  config: ConfigurationApi,
  session: ServiceSession,
  erreurOrigine: HttpErrorResponse,
): Observable<HttpEvent<unknown>> {
  return session.rafraichir().pipe(
    take(1),

    // 🎯 UN OBSERVABLE DOIT TOUJOURS ÉMETTRE OU ÉCHOUER, JAMAIS SE TAIRE.
    //
    // La première version filtrait le cas `null` :
    //
    //     filter((jeton): jeton is string => jeton !== null)
    //
    // Ça paraissait raisonnable — « ignorer l'absence de jeton ». En réalité,
    // `rafraichir()` renvoie `null` quand le renouvellement échoue : le filtre
    // supprimait donc la SEULE émission, et l'observable se terminait sans
    // rien dire.
    //
    // Le composant appelant, qui n'attendait que `next` ou `error`, ne
    // recevait ni l'un ni l'autre. Son indicateur de chargement tournait
    // indéfiniment, sans message, sans erreur en console — le pire symptôme
    // qui soit, parce qu'il n'y a rien à chercher.
    //
    // On transforme donc explicitement l'absence de jeton en erreur.
    switchMap((jeton) => {
      if (jeton === null) {
        session.terminer();
        return throwError(() => erreurOrigine);
      }
      return suite(preparer(requete, config, session));
    }),

    catchError(() => {
      session.terminer();
      return throwError(() => erreurOrigine);
    }),
  );
}

/** Pose l'URL absolue, l'en-tête client, les cookies et le jeton. */
function preparer(
  requete: HttpRequest<unknown>,
  config: ConfigurationApi,
  session: ServiceSession,
): HttpRequest<unknown> {
  const jeton = session.jetonAcces();

  return requete.clone({
    url: absolue(requete.url, config.baseUrl),
    withCredentials: true,
    setHeaders: {
      'X-Garah-Client': '1',
      ...(jeton ? { Authorization: `Bearer ${jeton}` } : {}),
    },
  });
}

/**
 * Les composants écrivent `/api/produits` ; l'API vit ailleurs.
 *
 * <p>Centraliser ici évite que chaque service concatène l'URL de base — et
 * qu'un oubli produise un appel vers l'origine du frontend, qui répondrait la
 * page d'accueil au lieu d'un JSON.</p>
 */
function absolue(url: string, base: string): string {
  return url.startsWith('/') ? `${base}${url}` : url;
}

function estAppelApi(url: string, base: string): boolean {
  return url.startsWith('/api/') || url.startsWith(base);
}
