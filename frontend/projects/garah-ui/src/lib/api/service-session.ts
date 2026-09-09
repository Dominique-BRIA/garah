import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, shareReplay, tap } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import { ConfigurationApi } from './configuration-api';
import { ReponseConnexion, UtilisateurConnecte } from '../modeles/authentification';

/**
 * La session : le jeton d'accès, l'utilisateur, et le renouvellement.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠️ LE JETON D'ACCÈS N'EST JAMAIS ÉCRIT DANS localStorage
 * ═══════════════════════════════════════════════════════════════════════════
 * Il reste dans un signal, donc en mémoire, et disparaît au rechargement de
 * l'onglet — où {@link #restaurer} le régénère à partir du cookie.
 *
 * C'est tout l'intérêt du découpage de D-19 :
 *
 *   accès              15 min   en mémoire         volé = 15 min de dégâts
 *   rafraîchissement   14 j     cookie HttpOnly    JavaScript NE PEUT PAS le lire
 *
 * Ranger le jeton dans `localStorage` annulerait ce raisonnement : la moindre
 * faille XSS — y compris dans une dépendance npm — le lirait. Et y ranger le
 * jeton de RAFRAÎCHISSEMENT serait pire encore : quatorze jours d'accès au
 * compte, en clair, lisibles par n'importe quel script de la page.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Injectable({ providedIn: 'root' })
export class ServiceSession {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ConfigurationApi);

  /**
   * Un indice : « une session a ete ouverte sur ce navigateur ».
   *
   * ⚠️ CE N EST PAS UN SECRET, et c est ce qui le rend utilisable.
   *
   * Le cookie de rafraichissement est HttpOnly : le JavaScript ne peut pas
   * savoir s il existe. Sans indice, il faut APPELER le serveur pour le
   * decouvrir — et cet appel prend jusqu a une minute au reveil de
   * l hebergement.
   *
   * Un visiteur qui ne s est jamais connecte payait donc une minute d ecran
   * vide pour apprendre ce qu on savait deja : il n a pas de session.
   *
   * Ce drapeau ne contient aucune donnee sensible. Le falsifier ne donne
   * aucun acces : il fait tenter un rafraichissement, que le serveur refuse
   * faute de cookie valide.
   */
  private static readonly CLE_INDICE = 'garah.session';

  private readonly _jeton = signal<string | null>(null);
  private readonly _utilisateur = signal<UtilisateurConnecte | null>(null);
  private readonly _permissions = signal<ReadonlySet<string>>(new Set());

  /**
   * Le rafraîchissement en cours, s'il y en a un.
   *
   * <p>🎯 <b>C'est ce champ qui empêche de déconnecter l'utilisateur en
   * essayant de le maintenir connecté.</b></p>
   *
   * <p>Un tableau de bord lance six appels au chargement. Le jeton expire, les
   * six reçoivent un 401 simultanément. Sans coordination, six
   * rafraîchissements partent en parallèle — or le backend fait TOURNER le
   * jeton à chaque appel (D-19). Le deuxième présenterait un jeton déjà
   * consommé, le backend y verrait un vol, et révoquerait toute la famille.</p>
   *
   * <p>`shareReplay` fait que tous les appelants partagent le même appel.</p>
   */
  private rafraichissementEnCours: Observable<string | null> | null = null;

  readonly utilisateur = this._utilisateur.asReadonly();
  readonly connecte = computed(() => this._utilisateur() !== null);

  /**
   * Ce compte appartient-il a la MAISON ?
   *
   * ⚠️ « Connecte » et « interne » ne sont pas la meme question, et les
   *    confondre a laisse un compte client entrer dans le back-office.
   *
   *    Le cookie de rafraichissement est pose par l'API, pour l'API. Les deux
   *    applications — la boutique et le back-office — partagent donc UNE
   *    session par navigateur : se connecter en client sur la boutique remplace
   *    la session d'administration ouverte a cote. Le formulaire de connexion
   *    du back-office refusait bien un client ; la RESTAURATION au demarrage,
   *    elle, ne refaisait pas ce controle. La regle ne vivait qu'a un seul des
   *    deux endroits par ou l'on entre.
   *
   * ⚠️ Confort et clarte, JAMAIS securite. Un client n'a aucune permission :
   *    le serveur refusait deja chaque appel. Ce qu'on repare ici, c'est une
   *    interface qui accueillait quelqu'un pour lui refuser ensuite chaque
   *    ecran, un par un.
   */
  readonly estInterne = computed(() => {
    const u = this._utilisateur();
    return u !== null && u.type !== 'CLIENT';
  });

  jetonAcces(): string | null {
    return this._jeton();
  }

  /**
   * A-t-il ce droit ?
   *
   * <p>⚠️ <b>Confort d'affichage, jamais sécurité.</b> Masquer un bouton
   * empêche le clic, pas l'appel : l'autorisation réelle est vérifiée par le
   * backend à chaque requête, avec les mêmes codes de `cas_utilisation`.</p>
   *
   * <p>Un client n'a AUCUNE permission — son accès repose sur la propriété de
   * ses données, pas sur des droits. Cette méthode renverra donc toujours faux
   * pour lui, et c'est normal.</p>
   */
  peut(code: string): boolean {
    return this._permissions().has(code);
  }

  /**
   * Corrige l'utilisateur affiché après une modification du profil.
   *
   * <p>⚠️ <b>Purement local, et volontairement.</b> Le nom et la langue vivent
   * aussi dans le jeton, qui est signé : on ne peut pas le réécrire, et il
   * portera l'ancienne valeur jusqu'au prochain rafraîchissement — quinze
   * minutes au plus (D-19).</p>
   *
   * <p>Sans cet appel, quelqu'un qui corrige son nom verrait la barre latérale
   * continuer d'afficher l'ancien pendant un quart d'heure, et conclurait que
   * l'enregistrement a échoué. On affiche donc ce que la BASE contient, qui
   * est la vérité, plutôt que ce que le jeton transporte.</p>
   *
   * <p>Ne touche <b>pas</b> aux permissions : elles, seul le serveur les
   * décide, et les modifier ici ouvrirait des écrans que l'API refusera.</p>
   */
  actualiserUtilisateur(champs: Partial<UtilisateurConnecte>): void {
    this._utilisateur.update((u) => (u ? { ...u, ...champs } : u));
  }

  connecter(email: string, motDePasse: string): Observable<UtilisateurConnecte> {
    return this.http
      .post<ReponseConnexion>('/api/auth/connexion', { email, motDePasse })
      .pipe(map((reponse) => this.adopter(reponse)));
  }

  inscrire(demande: {
    email: string;
    motDePasse: string;
    nom: string;
    prenom?: string;
    telephone?: string;
    langue?: string;
  }): Observable<UtilisateurConnecte> {
    return this.http
      .post<ReponseConnexion>('/api/auth/inscription', demande)
      .pipe(map((reponse) => this.adopter(reponse)));
  }

  /**
   * Rejoue le cookie pour obtenir un jeton neuf.
   *
   * <p>Un seul appel en vol à la fois — voir {@link #rafraichissementEnCours}.
   * Renvoie `null` si la session est finie, sans lever : l'appelant décide
   * alors de rediriger, et un échec de renouvellement n'est pas une erreur
   * exceptionnelle mais un cas de vie normal.</p>
   */
  rafraichir(): Observable<string | null> {
    if (this.rafraichissementEnCours) {
      return this.rafraichissementEnCours;
    }

    this.rafraichissementEnCours = this.http
      .post<ReponseConnexion>('/api/auth/rafraichir', null)
      .pipe(
        map((reponse) => {
          this.adopter(reponse);
          return reponse.jeton;
        }),
        catchError(() => {
          this.vider();
          return of(null);
        }),
        // ⚠️ Remis à null DANS le tap, pas dans un finalize : `finalize` se
        // déclenche aussi au désabonnement. Une requête annulée — un
        // changement de page pendant le chargement — libérerait alors le
        // verrou pendant que l'appel HTTP est encore en vol.
        tap({
          next: () => (this.rafraichissementEnCours = null),
          error: () => (this.rafraichissementEnCours = null),
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );

    return this.rafraichissementEnCours;
  }

  /**
   * Au démarrage de l'application : y a-t-il une session à reprendre ?
   *
   * <p>Le jeton d'accès a disparu avec le rechargement de l'onglet, mais le
   * cookie de rafraîchissement, lui, vit quatorze jours. Sans cet appel, tout
   * rechargement de page déconnecterait l'utilisateur — alors qu'il a une
   * session parfaitement valide.</p>
   */
  restaurer(): Observable<boolean> {
    // Aucune session connue : on repond NON tout de suite, sans reseau.
    if (!this.indicePose()) {
      return of(false);
    }
    return this.rafraichir().pipe(map((jeton) => jeton !== null));
  }

  private indicePose(): boolean {
    try {
      return localStorage.getItem(ServiceSession.CLE_INDICE) === '1';
    } catch {
      // Navigation privee, cookies bloques : on tente le rafraichissement.
      // Mieux vaut une attente qu une deconnexion injustifiee.
      return true;
    }
  }

  private poserIndice(pose: boolean): void {
    try {
      if (pose) {
        localStorage.setItem(ServiceSession.CLE_INDICE, '1');
      } else {
        localStorage.removeItem(ServiceSession.CLE_INDICE);
      }
    } catch {
      // Sans consequence : on retombe sur une tentative reseau.
    }
  }

  /**
   * Déconnexion.
   *
   * <p>⚠️ L'appel au backend est <b>indispensable</b> : il révoque la famille
   * de jetons côté serveur. Se contenter d'oublier le jeton côté frontend
   * laisserait un cookie valide quatorze jours dans le navigateur — c'est
   * exactement ce que fait une « déconnexion côté client seulement ».</p>
   *
   * <p>On vide l'état local dans tous les cas, même si l'appel échoue :
   * l'utilisateur a demandé à partir, l'interface doit le refléter.</p>
   */
  deconnecter(): Observable<void> {
    return this.http.post<void>('/api/auth/deconnexion', null).pipe(
      catchError(() => of(undefined)),
      tap(() => this.vider()),
      map(() => undefined),
    );
  }

  /** Coupe la session localement, sans appeler le serveur. */
  terminer(): void {
    this.vider();
  }

  private adopter(reponse: ReponseConnexion): UtilisateurConnecte {
    this._jeton.set(reponse.jeton);
    this._utilisateur.set(reponse.utilisateur);
    this._permissions.set(new Set(reponse.permissions ?? []));
    this.poserIndice(true);
    return reponse.utilisateur;
  }

  private vider(): void {
    this._jeton.set(null);
    this._utilisateur.set(null);
    this._permissions.set(new Set());
    this.rafraichissementEnCours = null;
    this.poserIndice(false);
  }
}
