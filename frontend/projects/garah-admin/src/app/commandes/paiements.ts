import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Icone,
  libelleMoyen,
  messageErreur,
  montantLisible,
  Page,
  Pagination,
  Paiement,
  ServiceSession,
  StatutPaiement,
  STATUTS_PAIEMENT,
  TypePaiement,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Les mouvements d'argent.
 *
 * <p>Encaissements et remboursements dans la même liste par défaut : « qu'est
 * -il arrivé à l'argent ? » ne se répond pas en consultant deux écrans.</p>
 *
 * <p>🎯 Le filtre le plus utile est <b>EN_ATTENTE</b> : ce sont les paiements
 * partis chez l'opérateur dont on n'a pas encore la réponse. Un client qui
 * appelle en disant « j'ai payé » se trouve presque toujours là.</p>
 */
@Component({
  selector: 'ga-paiements',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './paiements.html',
  styleUrl: './paiements.scss',
})
export class Paiements {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly Paiement[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<number | null>(null);

  protected readonly statuts = STATUTS_PAIEMENT;
  protected readonly statut = signal<StatutPaiement | ''>('');
  protected readonly type = signal<TypePaiement | ''>('');
  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? (STATUTS_PAIEMENT.find((s) => s.code === code)?.libelle ?? code) : null;
  });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const parametres = new URLSearchParams({
      page: String(this.page()),
      taille: String(TAILLE_PAGE),
    });
    if (this.statut()) {
      parametres.set('statut', this.statut());
    }
    if (this.type()) {
      parametres.set('type', this.type());
    }
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }

    this.http.get<Page<Paiement>>(`/api/paiements?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, { repli: 'Les paiements n’ont pas pu être chargés.', sujet: 'les paiements' }));
      },
    });
  }

  protected filtrer(statut: StatutPaiement | ''): void {
    this.statut.set(statut);
    this.page.set(0);
    this.charger();
  }

  protected filtrerType(type: TypePaiement | ''): void {
    this.type.set(type);
    this.page.set(0);
    this.charger();
  }

  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected changerPage(page: number): void {
    this.page.set(page);
    this.charger();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /**
   * Redemande l'état à l'opérateur, tout de suite.
   *
   * <p>Un paiement reste « en attente » quand la notification de l'opérateur
   * n'est jamais arrivée — réseau coupé, instance endormie. Plutôt que
   * d'attendre la réconciliation périodique, on va chercher la réponse.</p>
   */
  protected verifier(paiement: Paiement): void {
    if (this.action() !== null) {
      return;
    }
    this.action.set(paiement.id);
    this.erreur.set(null);

    this.http.post(`/api/paiements/${paiement.id}/verification`, null).subscribe({
      next: () => {
        this.action.set(null);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(messageErreur(e, { repli: 'Les paiements n’ont pas pu être chargés.', sujet: 'les paiements' }));
      },
    });
  }

  /** Ce paiement mérite-t-il qu'on redemande à l'opérateur ? */
  protected verifiable(p: Paiement): boolean {
    return (
      (p.statut === 'EN_ATTENTE' || p.statut === 'INITIE') &&
      p.type === 'ENCAISSEMENT' &&
      this.session.peut('PAIEMENT_VERIFIER')
    );
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected badge(statut: StatutPaiement): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
  }

  protected libelle(statut: StatutPaiement): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.libelle ?? statut;
  }

  protected moyen(code: string): string {
    return libelleMoyen(code);
  }

  protected montant(valeur: number, devise: string): string {
    return montantLisible(valeur, devise);
  }

  protected dateHeure(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

