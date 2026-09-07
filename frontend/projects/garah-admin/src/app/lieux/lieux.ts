import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  Icone,
  Lieu,
  PAYS_DESSERVIS,
  ReponseErreur,
  ServiceSession,
  TYPES_LIEU,
  TypeLieu,
  libelleTypeLieu,
  libellePays,
  montantLisible,
} from 'garah-ui';

/**
 * Les lieux de la chaîne logistique.
 *
 * <h2>Pourquoi cet écran débloque tout le reste</h2>
 *
 * <p>Une commande <b>exige</b> un point de récupération (D-05). Tant qu'aucun
 * n'existe, aucune commande ne peut être passée — et le tunnel de vente entier
 * reste impossible à parcourir, quel que soit l'état du catalogue.</p>
 *
 * <p>Les routes de lecture existaient depuis le début ; la création, non. Cet
 * écran est le premier du lot logistique parce qu'il conditionne les autres.</p>
 */
@Component({
  selector: 'ga-lieux',
  imports: [FormsModule, Icone],
  templateUrl: './lieux.html',
  styleUrl: './lieux.scss',
})
export class Lieux {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly Lieu[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly typesLieu = TYPES_LIEU;
  protected readonly listePays = PAYS_DESSERVIS;

  /** Le filtre par rôle. Vide = tous. */
  protected readonly filtreType = signal<TypeLieu | ''>('');

  protected readonly visibles = computed(() => {
    const type = this.filtreType();
    return type ? this.liste().filter((l) => l.type === type) : this.liste();
  });

  /**
   * Combien de points de récupération sont actifs.
   *
   * <p>À zéro, plus personne ne peut commander. L'écran le dit en haut, parce
   * que rien d'autre dans l'application ne le signalerait — la commande
   * échouerait simplement, chez le client.</p>
   */
  protected readonly pointsActifs = computed(
    () =>
      this.liste().filter((l) => l.type === 'POINT_RECUPERATION' && l.statut === 'ACTIF')
        .length,
  );

  // --- Le formulaire, en création ou en modification ---
  protected readonly formulaireOuvert = signal(false);
  protected readonly enEdition = signal<Lieu | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  protected readonly type = signal<TypeLieu>('POINT_RECUPERATION');
  protected readonly nom = signal('');
  protected readonly pays = signal('CM');
  protected readonly ville = signal('');
  protected readonly adresse = signal('');
  protected readonly telephone = signal('');
  protected readonly horaires = signal('');
  protected readonly frais = signal<number | null>(0);

  /** Les frais ne se saisissent que sur un point de récupération. */
  protected readonly porteDesFrais = computed(() => this.type() === 'POINT_RECUPERATION');

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Lieu[]>('/api/lieux').subscribe({
      next: (l) => {
        this.liste.set(l);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Les lieux n’ont pas pu être chargés.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Le formulaire
  // -------------------------------------------------------------------------

  protected ouvrirCreation(): void {
    this.enEdition.set(null);
    this.type.set('POINT_RECUPERATION');
    this.nom.set('');
    this.pays.set('CM');
    this.ville.set('');
    this.adresse.set('');
    this.telephone.set('');
    this.horaires.set('');
    this.frais.set(0);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected ouvrirEdition(lieu: Lieu): void {
    this.enEdition.set(lieu);
    this.type.set(lieu.type);
    this.nom.set(lieu.nom);
    this.pays.set(lieu.pays);
    this.ville.set(lieu.ville);
    this.adresse.set(lieu.adresse ?? '');
    this.telephone.set(lieu.telephone ?? '');
    this.horaires.set(lieu.horaires ?? '');
    this.frais.set(lieu.fraisAcheminement);
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

    const corps = {
      nom: this.nom().trim(),
      pays: this.pays(),
      ville: this.ville().trim(),
      adresse: this.adresse().trim(),
      telephone: this.telephone().trim(),
      horaires: this.horaires().trim(),
      // Le serveur les remet à zéro hors point de récupération ; on n'envoie
      // pas une valeur qu'il ignorera, cela laisserait croire qu'elle compte.
      fraisAcheminement: this.porteDesFrais() ? (this.frais() ?? 0) : 0,
    };

    const existant = this.enEdition();

    // ⚠️ Le `type` n'est envoyé qu'à la CRÉATION. Un point de récupération
    // transformé en point de transit laisserait des commandes dont le point de
    // retrait n'en est plus un — et ces commandes ne peuvent pas changer d'avis.
    const requete = existant
      ? this.http.put<Lieu>(`/api/lieux/${existant.id}`, corps)
      : this.http.post<Lieu>('/api/lieux', { ...corps, type: this.type() });

    requete.subscribe({
      next: () => {
        this.enregistrement.set(false);
        this.fermer();
        this.charger();
      },
      error: (e: unknown) => {
        this.enregistrement.set(false);
        this.erreurFormulaire.set(
          message(e, existant
            ? 'Le lieu n’a pas pu être enregistré.'
            : 'Le lieu n’a pas pu être créé.'),
        );
      },
    });
  }

  /**
   * Active ou désactive.
   *
   * <p>Jamais de suppression : des commandes passées portent ce lieu comme
   * point de retrait. Le serveur refuse d'ailleurs de désactiver le dernier
   * point de récupération actif — sans lui, plus personne ne commande.</p>
   */
  protected basculerActivation(lieu: Lieu): void {
    const actif = lieu.statut === 'ACTIF';
    const url = `/api/lieux/${lieu.id}/activation`;
    const requete = actif ? this.http.delete<Lieu>(url) : this.http.post<Lieu>(url, null);

    requete.subscribe({
      next: () => this.charger(),
      error: (e: unknown) => this.erreur.set(message(e, 'L’opération a échoué.')),
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected libelleType(type: string): string {
    return libelleTypeLieu(type);
  }

  protected badgeType(type: string): string {
    switch (type) {
      case 'POINT_RECUPERATION':
        return 'gu-badge--succes';
      case 'POINT_TRANSIT':
        return 'gu-badge--info';
      default:
        return 'gu-badge--neutre';
    }
  }

  protected libelle(code: string): string {
    return libellePays(code);
  }

  protected montant(valeur: number): string {
    return montantLisible(valeur, 'XAF');
  }
}

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    if (e.status === 403) {
      return 'Votre compte n’a pas le droit de gérer ce type de lieu.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
