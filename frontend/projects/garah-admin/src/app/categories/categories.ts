import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Categorie, Icone, ReponseErreur, ServiceSession } from 'garah-ui';

/** Une categorie aplatie pour l'affichage, avec son niveau d'indentation. */
interface Ligne {
  readonly categorie: Categorie;
  readonly niveau: number;
}

@Component({
  selector: 'ga-categories',
  imports: [FormsModule, Icone],
  templateUrl: './categories.html',
})
export class Categories {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly arbre = signal<readonly Categorie[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly formulaireOuvert = signal(false);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);
  protected readonly nom = signal('');
  protected readonly parentId = signal<number | null>(null);

  /**
   * L'arbre aplati, avec le niveau de chaque nœud.
   *
   * <p>Un tableau lit mieux qu'une imbrication de listes quand chaque ligne
   * porte des actions : l'indentation suffit à montrer la hiérarchie, et les
   * colonnes restent alignées.</p>
   */
  protected readonly lignes = computed(() => aplatir(this.arbre(), 0));

  /**
   * Les parents possibles : tout sauf le dernier niveau.
   *
   * <p>L'API refuse au-delà de trois niveaux. Proposer un parent de niveau 3
   * dans la liste ferait échouer la création après coup — mieux vaut ne pas
   * l'offrir.</p>
   */
  protected readonly parentsPossibles = computed(() =>
    this.lignes().filter((l) => l.niveau < 2),
  );

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Categorie[]>('/api/categories').subscribe({
      next: (arbre) => {
        this.arbre.set(arbre);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Les catégories n’ont pas pu être chargées.'));
      },
    });
  }

  protected ouvrirFormulaire(parent: number | null): void {
    this.nom.set('');
    this.parentId.set(parent);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);

    this.http
      .post<Categorie>('/api/categories', {
        nom: this.nom().trim(),
        parentId: this.parentId(),
        ordre: 0,
      })
      .subscribe({
        next: () => {
          this.enregistrement.set(false);
          this.formulaireOuvert.set(false);
          this.charger();
        },
        error: (e: unknown) => {
          this.enregistrement.set(false);
          this.erreurFormulaire.set(message(e, 'La catégorie n’a pas pu être créée.'));
        },
      });
  }

  protected basculerActivation(c: Categorie): void {
    const active = c.statut === 'ACTIVE';
    const requete = active
      ? this.http.delete<Categorie>(`/api/categories/${c.id}/activation`)
      : this.http.post<Categorie>(`/api/categories/${c.id}/activation`, null);

    requete.subscribe({
      next: () => this.charger(),
      error: (e: unknown) => this.erreur.set(message(e, 'L’opération a échoué.')),
    });
  }

  /** Le décalage visuel du niveau, en rem. */
  protected decalage(niveau: number): string {
    return `${niveau * 1.4}rem`;
  }
}

function aplatir(noeuds: readonly Categorie[], niveau: number): Ligne[] {
  return noeuds.flatMap((c) => [{ categorie: c, niveau }, ...aplatir(c.enfants, niveau + 1)]);
}

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Service momentanément indisponible. Réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
