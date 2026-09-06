import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  Categorie,
  DetailProduit,
  Icone,
  Marchand,
  Page,
  ReponseErreur,
} from 'garah-ui';

/** Une catégorie aplatie, avec son niveau, pour la liste déroulante. */
interface OptionCategorie {
  readonly id: number;
  readonly nom: string;
  readonly niveau: number;
}

@Component({
  selector: 'ga-nouveau-produit',
  imports: [FormsModule, RouterLink, Icone],
  templateUrl: './nouveau-produit.html',
  styleUrl: './fiche-produit.scss',
})
export class NouveauProduit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  protected readonly marchands = signal<readonly Marchand[]>([]);
  protected readonly categories = signal<readonly OptionCategorie[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enregistrement = signal(false);

  protected readonly reference = signal('');
  protected readonly nom = signal('');
  protected readonly marchandId = signal<number | null>(null);
  protected readonly categorieId = signal<number | null>(null);

  /**
   * Peut-on seulement créer un produit ?
   *
   * <p>Il faut au moins un marchand actif et une catégorie. Sans eux, le
   * formulaire est un piège : deux listes vides, un bouton qui échoue, et
   * aucune indication de ce qu'il faut faire d'abord.</p>
   */
  protected readonly prerequisManquants = computed(() => ({
    marchand: this.marchands().length === 0,
    categorie: this.categories().length === 0,
  }));

  protected readonly possible = computed(
    () => !this.prerequisManquants().marchand && !this.prerequisManquants().categorie,
  );

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    let restants = 2;
    const fini = () => {
      if (--restants === 0) {
        this.chargement.set(false);
      }
    };

    // ⚠️ Seulement les marchands ACTIFS. Proposer un marchand désactivé
    // reviendrait à laisser créer un produit qu'on ne pourra jamais vendre.
    this.http.get<Page<Marchand>>('/api/marchands/selectionnables?taille=100').subscribe({
      next: (p) => {
        this.marchands.set(p.content);
        if (p.content.length === 1) {
          // Un seul choix possible : on le pose. Faire cliquer quelqu'un sur
          // l'unique option d'une liste est une politesse inutile.
          this.marchandId.set(p.content[0].id);
        }
        fini();
      },
      error: () => {
        this.marchands.set([]);
        fini();
      },
    });

    this.http.get<Categorie[]>('/api/categories').subscribe({
      next: (arbre) => {
        const plates = aplatir(arbre, 0);
        this.categories.set(plates);
        if (plates.length === 1) {
          this.categorieId.set(plates[0].id);
        }
        fini();
      },
      error: () => {
        this.categories.set([]);
        fini();
      },
    });
  }

  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreur.set(null);

    this.http
      .post<DetailProduit>('/api/produits', {
        reference: this.reference().trim(),
        nom: this.nom().trim(),
        marchandId: this.marchandId(),
        categorieId: this.categorieId(),
      })
      .subscribe({
        next: (p) => {
          this.enregistrement.set(false);
          // On enchaîne sur la fiche : le produit vient d'être créé en
          // brouillon, et il lui manque encore variante, prix et photo pour
          // être publiable. Renvoyer vers la liste obligerait à le retrouver.
          void this.router.navigate(['/produits', p.id]);
        },
        error: (e: unknown) => {
          this.enregistrement.set(false);
          this.erreur.set(message(e, 'Le produit n’a pas pu être créé.'));
        },
      });
  }

  protected decalage(niveau: number): string {
    return '— '.repeat(niveau);
  }
}

function aplatir(noeuds: readonly Categorie[], niveau: number): OptionCategorie[] {
  return noeuds.flatMap((c) => [
    { id: c.id, nom: c.nom, niveau },
    ...aplatir(c.enfants, niveau + 1),
  ]);
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
