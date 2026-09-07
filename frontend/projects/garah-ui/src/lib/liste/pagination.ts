import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Icone } from '../icones/icone';

/**
 * Le pied de page d'une liste paginée.
 *
 * <pre>
 *   25–48 sur 137            ‹ Précédent   Page 2 sur 6   Suivant ›
 * </pre>
 *
 * <h2>Pourquoi un composant plutôt que trois lignes recopiées</h2>
 *
 * <p>Huit écrans de liste vont suivre — commandes, paiements, stock,
 * expéditions, réclamations, retours, clients, conversations. Recopier la
 * pagination huit fois, c'est se condamner à corriger huit fois le jour où
 * elle se trompe d'une unité.</p>
 *
 * <p>⚠️ {@code page} est l'index envoyé au serveur, qui commence à
 * <b>zéro</b> ; l'affichage, lui, commence à <b>un</b>. C'est la source
 * d'erreur classique de toute pagination : on la traite ici, une fois, plutôt
 * que dans chaque écran.</p>
 */
@Component({
  selector: 'gu-pagination',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (totalPages() > 1) {
      <nav class="gu-pagination" role="navigation" aria-label="Pagination">
        <span class="gu-pagination__compte">
          {{ premier() }}–{{ dernier() }} sur {{ total() }}
        </span>

        <div class="gu-pagination__boutons">
          <button type="button" class="gu-btn gu-btn--secondaire petit"
                  [disabled]="page() === 0" (click)="aller(page() - 1)">
            <gu-icone nom="chevron-right" class="gu-pagination__avant" />
            Précédent
          </button>

          <span class="gu-pagination__position">
            Page {{ page() + 1 }} sur {{ totalPages() }}
          </span>

          <button type="button" class="gu-btn gu-btn--secondaire petit"
                  [disabled]="page() + 1 >= totalPages()" (click)="aller(page() + 1)">
            Suivant
            <gu-icone nom="chevron-right" />
          </button>
        </div>
      </nav>
    }
  `,
  styles: `
    .gu-pagination {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      flex-wrap: wrap;
      margin-top: 1.25rem;
      font-size: 0.82rem;
    }

    .gu-pagination__compte {
      color: var(--texte-attenue);
      font-variant-numeric: tabular-nums;
    }

    .gu-pagination__boutons {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    .gu-pagination__position {
      color: var(--texte-attenue);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

    /* Le chevron pointe à droite dans le jeu d'icônes : on le retourne plutôt
       que d'embarquer une seconde icône pour la même forme. */
    .gu-pagination__avant { transform: rotate(180deg); }
  `,
})
export class Pagination {
  /** L'index de page envoyé au serveur : il commence à zéro. */
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly total = input.required<number>();
  readonly taille = input(24);

  /** L'index de la page demandée. L'écran recharge, ce composant ne sait pas. */
  readonly changement = output<number>();

  protected readonly premier = computed(() => this.page() * this.taille() + 1);

  /**
   * Le dernier élément affiché.
   *
   * <p>Borné par le total : la dernière page est presque toujours incomplète,
   * et annoncer « 121–144 sur 137 » ferait douter de tout le reste.</p>
   */
  protected readonly dernier = computed(() =>
    Math.min((this.page() + 1) * this.taille(), this.total()),
  );

  protected aller(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.changement.emit(page);
    }
  }
}
