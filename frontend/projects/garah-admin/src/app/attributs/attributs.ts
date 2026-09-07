import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Attribut,
  Icone,
  ReponseErreur,
  ServiceSession,
  TYPES_AFFICHAGE,
  TypeAffichage,
} from 'garah-ui';

/**
 * Le référentiel des dimensions de déclinaison.
 *
 * <h2>Ce que cet écran empêche</h2>
 *
 * <p>Avant lui, une déclinaison se décrivait par un texte libre. Sur ce
 * catalogue, cela a donné une déclinaison dont l'intitulé était « 42 » et la
 * référence « Taille » — les deux champs inversés — et une autre, « Blanc » et
 * « BL460 », une référence inventée qui ne se rattachait à rien.</p>
 *
 * <p>Une dimension définie <b>une fois</b> et réutilisée partout supprime la
 * question : on ne tape plus, on choisit. Et « montre-moi tout ce qui existe
 * en taille 42 » devient une question à laquelle le catalogue peut répondre.</p>
 */
@Component({
  selector: 'ga-attributs',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './attributs.html',
  styleUrl: './attributs.scss',
})
export class Attributs {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly Attribut[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly typesAffichage = TYPES_AFFICHAGE;

  // --- Création d'une dimension ---
  protected readonly formDimension = signal(false);
  protected readonly nom = signal('');
  protected readonly typeAffichage = signal<TypeAffichage>('LISTE');

  // --- Ajout d'une valeur ---
  protected readonly formValeur = signal<number | null>(null);
  protected readonly libelle = signal('');
  protected readonly couleur = signal('#1E88E5');

  protected readonly erreurForm = signal<string | null>(null);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Attribut[]>('/api/attributs').subscribe({
      next: (l) => {
        this.liste.set(l);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Les dimensions n’ont pas pu être chargées.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Les formulaires — un seul ouvert à la fois
  // -------------------------------------------------------------------------

  protected ouvrirDimension(): void {
    this.formValeur.set(null);
    this.nom.set('');
    this.typeAffichage.set('LISTE');
    this.erreurForm.set(null);
    this.formDimension.set(true);
  }

  protected ouvrirValeur(attribut: Attribut): void {
    this.formDimension.set(false);
    this.libelle.set('');
    this.couleur.set('#1E88E5');
    this.erreurForm.set(null);
    this.formValeur.set(attribut.id);
  }

  protected fermer(): void {
    this.formDimension.set(false);
    this.formValeur.set(null);
    this.erreurForm.set(null);
  }

  protected creerDimension(): void {
    if (this.action()) {
      return;
    }
    this.action.set('dimension');
    this.erreurForm.set(null);

    this.http
      .post<Attribut>('/api/attributs', {
        nom: this.nom().trim(),
        typeAffichage: this.typeAffichage(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.fermer();
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La dimension n’a pas pu être créée.'));
        },
      });
  }

  protected ajouterValeur(attribut: Attribut): void {
    if (this.action()) {
      return;
    }
    this.action.set('valeur');
    this.erreurForm.set(null);

    this.http
      .post<Attribut>(`/api/attributs/${attribut.id}/valeurs`, {
        libelle: this.libelle().trim(),
        // La couleur n'a de sens que sur une pastille. Sur une liste, le
        // serveur l'ignore de toute façon — on ne l'envoie même pas.
        valeurAffichage: attribut.typeAffichage === 'PASTILLE' ? this.couleur() : '',
        ordre: null,
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          // Le formulaire RESTE ouvert : on ajoute rarement une seule taille.
          // Le fermer obligerait à re-cliquer entre chaque valeur.
          this.libelle.set('');
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La valeur n’a pas pu être ajoutée.'));
        },
      });
  }

  /**
   * Retire une valeur du référentiel.
   *
   * <p>Le serveur refuse si des déclinaisons la portent, en disant combien.
   * On ne devine pas ici : c'est lui qui sait.</p>
   */
  protected supprimerValeur(attribut: Attribut, valeurId: number, libelle: string): void {
    if (this.action()) {
      return;
    }
    if (!confirm(`Retirer « ${libelle} » de ${attribut.nom} ?`)) {
      return;
    }

    this.action.set('valeur');
    this.erreur.set(null);

    this.http.delete(`/api/attributs/${attribut.id}/valeurs/${valeurId}`).subscribe({
      next: () => {
        this.action.set(null);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(message(e, 'La valeur n’a pas pu être retirée.'));
      },
    });
  }

  protected estPastille(attribut: Attribut): boolean {
    return attribut.typeAffichage === 'PASTILLE';
  }
}

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    if (e.status === 403) {
      return 'Votre compte n’a pas le droit de gérer les dimensions.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
