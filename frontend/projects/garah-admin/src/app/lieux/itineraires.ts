import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  EtapeSouhaitee,
  Icone,
  Itineraire,
  Lieu,
  messageErreur,
  ServiceSession,
} from 'garah-ui';

/**
 * Les trajets types : Douala → Bertoua → Garoua-Boulaï → Bangui.
 *
 * <h2>Un modèle, pas une contrainte</h2>
 *
 * <p>⚠️ Un itinéraire décrit ce qui est <b>prévu</b>. Le trajet réel du colis
 * vit dans ses événements, et rien ne vérifie qu'un scan a lieu sur
 * l'itinéraire annoncé : une route coupée, un déroutement, ça arrive — et une
 * contrainte qui empêche d'enregistrer la réalité pousse l'opérateur à saisir
 * n'importe quoi d'autre.</p>
 *
 * <p>Cet écran sert donc à deux choses, pas trois : ne pas resaisir le même
 * trajet à chaque expédition, et annoncer un délai.</p>
 *
 * <h2>Le trajet se soumet EN ENTIER</h2>
 *
 * <p>🎯 Il n'y a pas de « déplacer cette étape vers le haut » qui envoie une
 * requête. Réordonner rang par rang traverserait un état où deux étapes
 * portent le même rang, ce que la base refuse — et chaque clic réussirait ou
 * échouerait selon l'ordre des précédents.</p>
 *
 * <p>On réordonne donc <b>localement</b>, et on envoie le trajet complet à
 * l'enregistrement. C'est aussi ce qui permet d'annuler sans avoir rien
 * cassé.</p>
 */
@Component({
  selector: 'ga-itineraires',
  imports: [FormsModule, Icone],
  templateUrl: './itineraires.html',
  styleUrl: './itineraires.scss',
})
export class Itineraires {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly Itineraire[]>([]);
  protected readonly lieux = signal<readonly Lieu[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  // --- Le formulaire ---
  protected readonly formulaireOuvert = signal(false);
  protected readonly enEdition = signal<Itineraire | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  protected readonly nom = signal('');
  protected readonly lieuDepartId = signal<number | null>(null);
  protected readonly lieuArriveeId = signal<number | null>(null);
  protected readonly etapes = signal<readonly EtapeSouhaitee[]>([]);

  /** Le lieu qu'on s'apprête à insérer comme étape. */
  protected readonly lieuAAjouter = signal<number | null>(null);
  protected readonly dureeAAjouter = signal<number | null>(null);

  /**
   * Les départs possibles : tout sauf un point de récupération.
   *
   * <p>Une marchandise part d'un entrepôt, ou d'un stock déjà consolidé dans
   * un point de transit. Elle ne repart pas d'un comptoir de retrait.</p>
   */
  protected readonly departsPossibles = computed(() =>
    this.lieux().filter((l) => l.type !== 'POINT_RECUPERATION'),
  );

  /** GARAH ne livre pas à domicile : on arrive à un point de récupération. */
  protected readonly arriveesPossibles = computed(() =>
    this.lieux().filter((l) => l.type === 'POINT_RECUPERATION'),
  );

  /**
   * Ce qui peut encore devenir une étape.
   *
   * <p>Ni le départ, ni l'arrivée — ils sont déjà dans le trajet — ni un lieu
   * déjà placé. Le serveur refuse les trois cas ; les retirer d'ici évite
   * d'aller chercher l'erreur après le clic.</p>
   */
  protected readonly etapesPossibles = computed(() => {
    const pris = new Set<number>(this.etapes().map((e) => e.lieuId));
    const depart = this.lieuDepartId();
    const arrivee = this.lieuArriveeId();

    return this.lieux().filter(
      (l) => l.id !== depart && l.id !== arrivee && !pris.has(l.id) && l.statut === 'ACTIF',
    );
  });

  protected readonly trajetValide = computed(
    () =>
      this.nom().trim().length > 0 &&
      this.lieuDepartId() !== null &&
      this.lieuArriveeId() !== null &&
      this.lieuDepartId() !== this.lieuArriveeId(),
  );

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    // `actifs=false` : le back-office voit TOUT, y compris les trajets
    // retirés. Des expéditions passées les référencent, et un trajet qui
    // disparaît de la liste donne l'impression qu'il a été supprimé.
    this.http.get<Itineraire[]>('/api/itineraires?actifs=false').subscribe({
      next: (i) => {
        this.liste.set(i);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Les itinéraires n’ont pas pu être chargés.'));
      },
    });

    this.http.get<Lieu[]>('/api/lieux').subscribe({
      next: (l) => this.lieux.set(l),
      error: () => this.lieux.set([]),
    });
  }

  // -------------------------------------------------------------------------
  // Le formulaire
  // -------------------------------------------------------------------------

  protected ouvrirCreation(): void {
    this.enEdition.set(null);
    this.nom.set('');
    this.lieuDepartId.set(this.departsPossibles()[0]?.id ?? null);
    this.lieuArriveeId.set(this.arriveesPossibles()[0]?.id ?? null);
    this.etapes.set([]);
    this.reinitialiserAjout();
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected ouvrirEdition(i: Itineraire): void {
    this.enEdition.set(i);
    this.nom.set(i.nom);
    this.lieuDepartId.set(i.lieuDepartId);
    this.lieuArriveeId.set(i.lieuArriveeId);
    this.etapes.set(
      i.etapes.map((e) => ({ lieuId: e.lieuId, dureeEstimeeHeures: e.dureeEstimeeHeures })),
    );
    this.reinitialiserAjout();
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected fermer(): void {
    this.formulaireOuvert.set(false);
    this.erreurFormulaire.set(null);
  }

  private reinitialiserAjout(): void {
    this.lieuAAjouter.set(null);
    this.dureeAAjouter.set(null);
  }

  // -------------------------------------------------------------------------
  // Les étapes — tout se passe LOCALEMENT jusqu'à l'enregistrement
  // -------------------------------------------------------------------------

  protected ajouterEtape(): void {
    const lieuId = this.lieuAAjouter();
    if (lieuId === null) {
      return;
    }
    const duree = this.dureeAAjouter();
    this.etapes.update((e) => [
      ...e,
      { lieuId, dureeEstimeeHeures: duree && duree > 0 ? duree : null },
    ]);
    this.reinitialiserAjout();
  }

  protected retirerEtape(index: number): void {
    this.etapes.update((e) => e.filter((_, i) => i !== index));
  }

  protected deplacer(index: number, sens: -1 | 1): void {
    const cible = index + sens;
    this.etapes.update((e) => {
      if (cible < 0 || cible >= e.length) {
        return e;
      }
      const copie = [...e];
      [copie[index], copie[cible]] = [copie[cible], copie[index]];
      return copie;
    });
  }

  // -------------------------------------------------------------------------

  protected enregistrer(): void {
    if (!this.trajetValide() || this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);

    const corps = {
      nom: this.nom().trim(),
      lieuDepartId: this.lieuDepartId(),
      lieuArriveeId: this.lieuArriveeId(),
      etapes: this.etapes(),
    };

    const enCours = this.enEdition();
    const requete = enCours
      ? this.http.put<Itineraire>(`/api/itineraires/${enCours.id}`, corps)
      : this.http.post<Itineraire>('/api/itineraires', corps);

    requete.subscribe({
      next: () => {
        this.enregistrement.set(false);
        this.formulaireOuvert.set(false);
        this.charger();
      },
      error: (e: unknown) => {
        this.enregistrement.set(false);
        this.erreurFormulaire.set(messageErreur(e, 'Le trajet n’a pas pu être enregistré.'));
      },
    });
  }

  /** Désactiver, jamais supprimer : des expéditions passées le référencent. */
  protected basculerActivation(i: Itineraire): void {
    this.http
      .put<Itineraire>(`/api/itineraires/${i.id}/activation`, { actif: i.statut !== 'ACTIF' })
      .subscribe({
        next: () => this.charger(),
        error: (e: unknown) =>
          this.erreur.set(messageErreur(e, 'L’état du trajet n’a pas pu être changé.')),
      });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected nomLieu(lieuId: number): string {
    const lieu = this.lieux().find((l) => l.id === lieuId);
    return lieu ? `${lieu.nom} · ${lieu.ville}` : 'Lieu retiré';
  }

  /** Le trajet sur une ligne : départ → étapes → arrivée. */
  protected trace(i: Itineraire): string {
    return [i.lieuDepart, ...i.etapes.map((e) => e.lieu), i.lieuArrivee]
      .map((n) => n ?? '?')
      .join(' → ');
  }

  /**
   * Un délai en heures se lit mal au-delà d'une journée.
   *
   * <p>« 54 h » oblige à compter ; « 2 j 6 h » se lit. Le calcul reste
   * volontairement grossier — une durée estimée n'est pas un horaire.</p>
   */
  protected delai(heures: number | null): string {
    if (heures === null) {
      return '—';
    }
    if (heures < 24) {
      return `${heures} h`;
    }
    const jours = Math.floor(heures / 24);
    const reste = heures % 24;
    return reste === 0 ? `${jours} j` : `${jours} j ${reste} h`;
  }
}

