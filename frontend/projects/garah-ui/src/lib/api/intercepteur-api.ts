import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, retry, switchMap, take, throwError, timer } from 'rxjs';

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
    reessayerSiConnexionMorte(requete),
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
 * Un seul réessai, et seulement quand la requête n'a jamais abouti.
 *
 * <h2>🎯 Le défaut que cela corrige</h2>
 *
 * <p>Le navigateur garde ses connexions ouvertes pour les réutiliser. Quand le
 * serveur est remplacé — c'est ce que fait <b>chaque mise en ligne</b> —
 * celles-ci meurent sans que le navigateur en soit averti. La requête suivante
 * part dans le vide et échoue <b>instantanément</b>, avec un statut 0 : pas de
 * réponse, donc pas de code.</p>
 *
 * <p>À l'écran, cela donnait « Le service ne répond pas » alors que le service
 * répondait parfaitement — et le seul remède connu était de <b>recharger la
 * page</b>, ce qui ouvre de nouvelles connexions. Un réessai fait la même
 * chose, sans que personne ait à le savoir.</p>
 *
 * <h2>⚠️ SEULEMENT GET et HEAD, et c'est la partie qui compte</h2>
 *
 * <p>Un statut 0 ne dit pas si la requête est arrivée. Elle a pu être traitée
 * et c'est la <b>réponse</b> qui s'est perdue. Rejouer un {@code POST} dans ce
 * cas créerait une seconde commande, un second paiement, un second
 * remboursement — un défaut bien pire que celui qu'on répare, et invisible
 * jusqu'à ce qu'un client soit débité deux fois.</p>
 *
 * <p>Une lecture, elle, peut se rejouer sans conséquence. C'est toute la
 * différence, et c'est la seule raison pour laquelle ce réessai est
 * acceptable.</p>
 *
 * <p>⚠️ Un seul essai supplémentaire. Une connexion morte échoue en quelques
 * millisecondes ; une coupure réseau, elle, échouera autant de fois qu'on
 * insistera. Boucler transformerait une panne en attente muette.</p>
 */
function reessayerSiConnexionMorte(requete: HttpRequest<unknown>) {
  const rejouable = requete.method === 'GET' || requete.method === 'HEAD';

  return retry<HttpEvent<unknown>>({
    count: 1,
    delay: (erreur: unknown) => {
      if (rejouable && erreur instanceof HttpErrorResponse && erreur.status === 0) {
        // Un court délai : le temps que la connexion morte soit écartée du
        // pool. Immédiat, le navigateur peut reprendre la même.
        return timer(300);
      }
      return throwError(() => erreur);
    },
  });
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
      // 🎯 « admin », et non « 1 » : cette valeur NOMME le public, et le
      //    serveur en déduit quel cookie de session lire et poser.
      //
      //    Un nom de cookie unique voulait dire une session par navigateur,
      //    partagée avec la boutique : se connecter en client d'un côté
      //    remplaçait la session d'administration de l'autre, sans un mot
      //    (D-33). Deux noms, deux sessions qui coexistent.
      //
      // ⚠️ La PRÉSENCE de cet en-tête reste la protection CSRF : elle force un
      //    préflight que seules nos origines passent. Sa VALEUR, elle, ne
      //    protège rien — n'importe qui peut prétendre être le back-office, et
      //    cela ne fait que choisir un tiroir. Le jeton qui s'y trouve reste
      //    engendré et vérifié par le serveur.
      'X-Garah-Client': 'admin',
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
