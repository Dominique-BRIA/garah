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
    return this.rafraichir().pipe(map((jeton) => jeton !== null));
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
    return reponse.utilisateur;
  }

  private vider(): void {
    this._jeton.set(null);
    this._utilisateur.set(null);
    this._permissions.set(new Set());
    this.rafraichissementEnCours = null;
  }
}
