import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icone, Marchand, Page, ReponseErreur, ServiceSession, TypeMarchand } from 'garah-ui';

@Component({
  selector: 'ga-marchands',
  imports: [FormsModule, Icone],
  templateUrl: './marchands.html',
  styleUrl: './marchands.scss',
})
export class Marchands {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly Marchand[]>([]);
  protected readonly total = signal(0);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly recherche = signal('');

  // --- Le formulaire de création ---
  protected readonly formulaireOuvert = signal(false);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  protected readonly code = signal('');
  protected readonly nom = signal('');
  protected readonly type = signal<TypeMarchand>('EXTERNE');
  protected readonly telephone = signal('');
  protected readonly email = signal('');

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const q = this.recherche().trim();
    const url = q
      ? `/api/marchands?recherche=${encodeURIComponent(q)}&taille=50`
      : '/api/marchands?taille=50';

    this.http.get<Page<Marchand>>(url).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.totalElements);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'La liste des marchands n’a pas pu être chargée.'));
      },
    });
  }

  protected ouvrirFormulaire(): void {
    this.code.set('');
    this.nom.set('');
    this.type.set('EXTERNE');
    this.telephone.set('');
    this.email.set('');
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
      .post<Marchand>('/api/marchands', {
        code: this.code().trim(),
        nom: this.nom().trim(),
        type: this.type(),
        // Chaîne vide plutôt qu'omission : le backend accepte les deux, mais
        // envoyer `undefined` retirerait la clé du JSON et rendrait le contrat
        // implicite. Ce qui est optionnel doit se voir.
        telephone: this.telephone().trim(),
        email: this.email().trim(),
      })
      .subscribe({
        next: () => {
          this.enregistrement.set(false);
          this.formulaireOuvert.set(false);
          this.charger();
        },
        error: (e: unknown) => {
          this.enregistrement.set(false);
          this.erreurFormulaire.set(message(e, 'Le marchand n’a pas pu être créé.'));
        },
      });
  }

  /**
   * Active ou désactive.
   *
   * <p>Il n'y a pas de suppression, et c'est délibéré : les ventes passées
   * portent l'identifiant du marchand, et il lui reste peut-être un solde à
   * régler.</p>
   */
  protected basculerActivation(marchand: Marchand): void {
    const actif = marchand.statut === 'ACTIF';
    const requete = actif
      ? this.http.delete<Marchand>(`/api/marchands/${marchand.id}/activation`)
      : this.http.post<Marchand>(`/api/marchands/${marchand.id}/activation`, null);

    requete.subscribe({
      next: () => this.charger(),
      error: (e: unknown) => this.erreur.set(message(e, 'L’opération a échoué.')),
    });
  }
}

/** Le message du backend s'il y en a un, sinon un repli. */
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
