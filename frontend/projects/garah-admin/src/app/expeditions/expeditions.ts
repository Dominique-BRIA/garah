import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Icone,
  Page,
  Pagination,
  ReponseErreur,
  ResumeExpedition,
  STATUTS_EXPEDITION,
  ServiceSession,
  StatutExpedition,
  badgeStatutExpedition,
  libelleStatutExpedition,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Les expéditions.
 *
 * <p>🎯 L'écran répond à deux questions, et les filtres les mettent à un
 * clic : <b>qu'est-ce qui est en route ?</b> et <b>qu'est-ce qui attend un
 * départ ?</b></p>
 *
 * <p>Le statut affiché est une <b>projection</b>, recalculée depuis les
 * événements des colis. Ce n'est pas lui la vérité — c'est le parcours, qu'on
 * lit sur la fiche.</p>
 */
@Component({
  selector: 'ga-expeditions',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './expeditions.html',
  styleUrl: './expeditions.scss',
})
export class Expeditions {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeExpedition[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_EXPEDITION;
  protected readonly statut = signal<StatutExpedition | ''>('');
  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? libelleStatutExpedition(code) : null;
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
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }

    this.http.get<Page<ResumeExpedition>>(`/api/expeditions?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e));
      },
    });
  }

  protected filtrer(statut: StatutExpedition | ''): void {
    this.statut.set(statut);
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

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected libelle(statut: string): string {
    return libelleStatutExpedition(statut);
  }

  protected badge(statut: string): string {
    return badgeStatutExpedition(statut);
  }

  /** Cette expédition attend-elle un geste ? */
  protected attend(statut: StatutExpedition): boolean {
    return STATUTS_EXPEDITION.find((s) => s.code === statut)?.attendUneAction ?? false;
  }

  protected destination(e: ResumeExpedition): string {
    if (!e.pointNom) {
      return '—';
    }
    return e.pointVille ? `${e.pointNom} · ${e.pointVille}` : e.pointNom;
  }

  protected dateLisible(iso: string | null): string {
    if (!iso) {
      return '—';
    }
    return new Date(iso).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  }
}

function message(e: unknown): string {
  if (!(e instanceof HttpErrorResponse)) {
    return 'Les expéditions n’ont pas pu être chargées.';
  }
  if (e.status === 0) {
    return 'Le service ne répond pas. Il peut être en train de se réveiller : réessayez dans deux minutes.';
  }
  if (e.status === 403) {
    return 'Votre compte n’a pas le droit de consulter les expéditions.';
  }
  if (e.status >= 500) {
    return 'Le service a rencontré une erreur. Réessayez dans un instant.';
  }
  const corps = e.error as ReponseErreur | null;
  return corps?.message ?? 'Les expéditions n’ont pas pu être chargées.';
}
