import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  DetailCommande,
  Icone,
  Paiement,
  ReponseErreur,
  STATUTS_COMMANDE,
  STATUTS_PAIEMENT,
  ServiceSession,
  StatutCommande,
  TRANSITIONS_COMMANDE,
  libelleMoyen,
  montantLisible,
} from 'garah-ui';

/**
 * Une commande, et ce qu'on peut en faire.
 *
 * <h2>Deux idées gouvernent cet écran</h2>
 *
 * <p><b>Les montants sont des photographies.</b> Désignation, prix unitaire,
 * TVA : tout a été figé au moment de l'achat. Une facture de mars doit rester
 * juste en septembre, même si le produit a changé de nom et de prix
 * entre-temps. L'écran ne relit donc <b>jamais</b> le catalogue.</p>
 *
 * <p><b>Seules les transitions possibles sont proposées.</b> Le serveur refuse
 * les autres, mais un bouton « Expédier » sur une commande impayée ferait
 * cliquer pour rien — et l'erreur arriverait après coup.</p>
 */
@Component({
  selector: 'ga-fiche-commande',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './fiche-commande.html',
  styleUrl: './fiche-commande.scss',
})
export class FicheCommande {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  /** Lié depuis la route par `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  protected readonly commande = signal<DetailCommande | null>(null);
  protected readonly paiements = signal<readonly Paiement[]>([]);
  protected readonly resteAPayer = signal<number | null>(null);

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly formAnnulation = signal(false);
  protected readonly motif = signal('');

  constructor() {
    // `input.required` n'est pas lisible dans le constructeur : on charge au
    // premier rendu, quand la valeur est posée.
    queueMicrotask(() => this.charger());
  }

  /**
   * Les statuts vers lesquels cette commande peut aller.
   *
   * <p>L'annulation en est retirée : elle a sa propre permission et son propre
   * bouton, parce qu'annuler une commande payée entraîne un remboursement —
   * ce n'est pas du même ordre que de la faire avancer d'un cran.</p>
   */
  protected readonly suites = computed<readonly StatutCommande[]>(() => {
    const c = this.commande();
    if (!c) {
      return [];
    }
    return TRANSITIONS_COMMANDE[c.statut].filter((s) => s !== 'ANNULEE');
  });

  protected readonly annulable = computed(() => {
    const c = this.commande();
    return !!c && TRANSITIONS_COMMANDE[c.statut].includes('ANNULEE');
  });

  // -------------------------------------------------------------------------
  // Chargement
  // -------------------------------------------------------------------------

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<DetailCommande>(`/api/commandes/${this.id()}`).subscribe({
      next: (c) => {
        this.commande.set(c);
        this.chargement.set(false);
        this.chargerArgent();
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Cette commande n’a pas pu être chargée.'));
      },
    });
  }

  /**
   * Ce qui s'est passé sur l'argent.
   *
   * <p>Chargé séparément et sans bloquer : la commande reste lisible même si
   * l'historique des paiements manque. On n'efface pas ce qui s'affiche déjà
   * pour un morceau absent.</p>
   */
  private chargerArgent(): void {
    if (!this.session.peut('PAIEMENT_CONSULTER')) {
      return;
    }

    this.http.get<Paiement[]>(`/api/paiements/commandes/${this.id()}`).subscribe({
      next: (p) => this.paiements.set(p),
      error: () => this.paiements.set([]),
    });

    this.http
      .get<{ resteAPayer: number }>(`/api/paiements/commandes/${this.id()}/reste-a-payer`)
      .subscribe({
        next: (r) => this.resteAPayer.set(r.resteAPayer),
        error: () => this.resteAPayer.set(null),
      });
  }

  // -------------------------------------------------------------------------
  // Actions
  // -------------------------------------------------------------------------

  protected avancer(statut: StatutCommande): void {
    if (this.action()) {
      return;
    }
    this.action.set('statut');
    this.erreur.set(null);

    this.http
      .post<DetailCommande>(`/api/commandes/${this.id()}/statut`, { statut })
      .subscribe({
        next: (c) => {
          this.action.set(null);
          this.commande.set(c);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(message(e, 'Le statut n’a pas pu être changé.'));
        },
      });
  }

  protected annuler(): void {
    if (this.action()) {
      return;
    }
    this.action.set('annulation');
    this.erreur.set(null);

    this.http
      .post<DetailCommande>(`/api/commandes/${this.id()}/annulation`, {
        motif: this.motif().trim(),
      })
      .subscribe({
        next: (c) => {
          this.action.set(null);
          this.formAnnulation.set(false);
          this.motif.set('');
          this.commande.set(c);
          this.chargerArgent();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(message(e, 'La commande n’a pas pu être annulée.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected badge(statut: StatutCommande): string {
    return STATUTS_COMMANDE.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
  }

  protected libelle(statut: StatutCommande): string {
    return STATUTS_COMMANDE.find((s) => s.code === statut)?.libelle ?? statut;
  }

  protected badgePaiement(statut: string): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
  }

  protected libellePaiement(statut: string): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.libelle ?? statut;
  }

  protected moyen(code: string): string {
    return libelleMoyen(code);
  }

  protected montant(valeur: number | null, devise: string): string {
    return montantLisible(valeur, devise);
  }

  protected dateHeure(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
