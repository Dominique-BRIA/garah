import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  FiltresAudit,
  Icone,
  LigneAudit,
  messageErreur,
  Page,
  Pagination,
} from 'garah-ui';

const TAILLE_PAGE = 50;

/**
 * Le journal des actions internes.
 *
 * <h2>🎯 La question à laquelle cet écran répond</h2>
 *
 * <p>« Qui a annulé cette commande ? », « qui a mis ce taux à 30 % ? », « qui
 * a remboursé ce client ? ». Elles se posent toutes des semaines après le
 * geste, quand la table concernée ne porte plus que son état courant.</p>
 *
 * <h2>⚠️ Trois journaux, et celui-ci n'est pas les deux autres</h2>
 *
 * <pre>
 * ce que fait un CLIENT          → son parcours, mesuré à part
 * les faits d'AUTHENTIFICATION   → le journal de sécurité
 * les actions INTERNES           → ici
 * </pre>
 *
 * <p>Les fondre en un seul rendrait les trois inexploitables : les quelques
 * gestes internes d'une journée disparaîtraient sous le trafic de la
 * boutique.</p>
 *
 * <h2>⚠️ Ce que l'écran affiche est sensible</h2>
 *
 * <p>Les clichés « avant / après » contiennent l'état des objets modifiés :
 * potentiellement n'importe quelle donnée du système. La route est réservée
 * au module sécurité, donc au seul super-administrateur — et un chef de
 * service, lui, reçoit une vue allégée qui ne les porte pas.</p>
 */
@Component({
  selector: 'ga-journal',
  imports: [FormsModule, Icone, Pagination],
  templateUrl: './journal.html',
  styleUrl: './journal.scss',
})
export class Journal {
  private readonly http = inject(HttpClient);

  protected readonly lignes = signal<readonly LigneAudit[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  /** Les valeurs réellement présentes — on ne propose pas un filtre vide. */
  protected readonly actions = signal<readonly string[]>([]);
  protected readonly entites = signal<readonly string[]>([]);

  protected readonly action = signal('');
  protected readonly entite = signal('');

  /** La ligne dépliée : les clichés ne s'affichent qu'à la demande. */
  protected readonly ouverte = signal<number | null>(null);

  constructor() {
    this.chargerLesFiltres();
    this.charger();
  }

  private chargerLesFiltres(): void {
    // Leur absence n'empêche pas de lire le journal : on ne bloque pas
    // l'écran entier pour des listes déroulantes.
    this.http.get<FiltresAudit>('/api/surveillance/audit/filtres').subscribe({
      next: (f) => {
        this.actions.set(f.actions);
        this.entites.set(f.entites);
      },
      error: () => {
        this.actions.set([]);
        this.entites.set([]);
      },
    });
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    let parametres = new HttpParams()
      .set('page', this.page())
      .set('taille', this.taille);

    // ⚠️ On n'envoie pas un filtre vide : le serveur chercherait les actions
    //    dont le code est « », c'est-à-dire aucune, et l'écran dirait « rien à
    //    afficher » sur un journal plein.
    if (this.action()) {
      parametres = parametres.set('action', this.action());
    }
    if (this.entite()) {
      parametres = parametres.set('entite', this.entite());
    }

    this.http
      .get<Page<LigneAudit>>('/api/surveillance/audit', { params: parametres })
      .subscribe({
        next: (p) => {
          this.lignes.set(p.content);
          this.total.set(p.page.totalElements);
          this.totalPages.set(p.page.totalPages);
          this.chargement.set(false);
        },
        error: (e: unknown) => {
          this.chargement.set(false);
          this.erreur.set(messageErreur(e, 'Le journal n’a pas pu être chargé.'));
        },
      });
  }

  protected filtrer(): void {
    // Retour à la première page : rester en page 4 après avoir filtré
    // afficherait un écran vide alors qu'il y a des résultats.
    this.page.set(0);
    this.ouverte.set(null);
    this.charger();
  }

  protected effacer(): void {
    this.action.set('');
    this.entite.set('');
    this.filtrer();
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.ouverte.set(null);
    this.charger();
  }

  protected basculer(ligne: LigneAudit): void {
    this.ouverte.set(this.ouverte() === ligne.id ? null : ligne.id);
  }

  protected aDesDetails(ligne: LigneAudit): boolean {
    return !!(ligne.ancienneValeur || ligne.nouvelleValeur);
  }

  // ---------------------------------------------------------------------------
  // Affichage
  // ---------------------------------------------------------------------------

  /**
   * Le code du geste, rendu lisible.
   *
   * <p>{@code REGLEMENT_CONFIRMER} devient « Règlement confirmer ». Ce n'est
   * pas une traduction — il n'y a pas de table de libellés, et en inventer une
   * demanderait de la tenir à jour à chaque geste ajouté, sous peine
   * d'afficher un code brut le jour où on l'oublie.</p>
   */
  protected libelle(code: string): string {
    const mots = code.toLowerCase().replace(/_/g, ' ');
    return mots.charAt(0).toUpperCase() + mots.slice(1);
  }

  protected date(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  /** Le JSON, mis en forme. Une ligne illisible n'apprend rien à personne. */
  protected lisible(json: string | null): string {
    if (!json) {
      return '—';
    }
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      // Le journal est immuable : on n'y touche pas, même pour le réparer.
      return json;
    }
  }
}
