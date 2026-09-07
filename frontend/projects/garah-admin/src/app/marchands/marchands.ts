import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  Avatar,
  Icone,
  Marchand,
  PAYS_DESSERVIS,
  Page,
  ReponseErreur,
  ServiceSession,
  TypeMarchand,
  libellePays,
} from 'garah-ui';

@Component({
  selector: 'ga-marchands',
  imports: [FormsModule, Icone, Avatar],
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

  /** Les pays proposés à la saisie. */
  protected readonly listePays = PAYS_DESSERVIS;

  /** Le libellé d'un code pays, pour l'affichage. */
  protected libelle(code: string): string {
    return libellePays(code);
  }

  // --- Le formulaire, en création ou en modification ---
  //
  // Un seul panneau pour les deux. Les champs sont les mêmes, les règles de
  // saisie aussi : en dédoubler un serait se condamner à corriger chaque
  // libellé deux fois.
  protected readonly formulaireOuvert = signal(false);
  protected readonly enEdition = signal<Marchand | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  protected readonly nom = signal('');
  protected readonly pays = signal('CM');
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
        this.total.set(page.page.totalElements);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'La liste des marchands n’a pas pu être chargée.'));
      },
    });
  }

  protected ouvrirFormulaire(): void {
    this.enEdition.set(null);
    this.nom.set('');
    this.pays.set('CM');
    this.type.set('EXTERNE');
    this.telephone.set('');
    this.email.set('');
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected ouvrirEdition(marchand: Marchand): void {
    this.enEdition.set(marchand);
    this.nom.set(marchand.nom);
    this.pays.set(marchand.pays);
    this.type.set(marchand.type);
    this.telephone.set(marchand.telephone ?? '');
    this.email.set(marchand.email ?? '');
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected fermer(): void {
    this.formulaireOuvert.set(false);
    this.enEdition.set(null);
  }

  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);

    const existant = this.enEdition();

    const corps = {
      // Aucun code : il est ENGENDRÉ par le serveur (MAR-00042). Le laisser
      // saisir produisait « 202020 » — une valeur qui ne dit rien et qu'on
      // devait inventer a chaque fois.
      nom: this.nom().trim(),
      pays: this.pays(),
      // Chaîne vide plutôt qu'omission : le backend accepte les deux, mais
      // envoyer `undefined` retirerait la clé du JSON et rendrait le contrat
      // implicite. Ce qui est optionnel doit se voir.
      telephone: this.telephone().trim(),
      email: this.email().trim(),
    };

    // ⚠️ `type` n'est envoyé qu'à la CRÉATION. Il détermine la commission :
    // le changer après coup modifierait la règle de partage sur un compte qui
    // a déjà vendu, et le serveur ne l'accepte d'ailleurs pas en modification.
    const requete = existant
      ? this.http.put<Marchand>(`/api/marchands/${existant.id}`, corps)
      : this.http.post<Marchand>('/api/marchands', { ...corps, type: this.type() });

    requete.subscribe({
      next: () => {
        this.enregistrement.set(false);
        this.fermer();
        this.charger();
      },
      error: (e: unknown) => {
        this.enregistrement.set(false);
        this.erreurFormulaire.set(
          message(
            e,
            existant
              ? 'Le marchand n’a pas pu être enregistré.'
              : 'Le marchand n’a pas pu être créé.',
          ),
        );
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
