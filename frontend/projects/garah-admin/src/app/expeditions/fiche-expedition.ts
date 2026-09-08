import { HttpClient } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  badgeStatutExpedition,
  Colis,
  Expedition,
  Icone,
  libelleEvenement,
  libelleStatutExpedition,
  Lieu,
  messageErreur,
  ParcoursColis,
  Retrait,
  ServiceSession,
  TypeEvenement,
  TYPES_EVENEMENT,
} from 'garah-ui';

/**
 * Une expédition, ses colis, et leur parcours.
 *
 * <h2>Le parcours, pas le statut</h2>
 *
 * <p>« EN_TRANSIT » ne dit pas <b>où</b>. « Réceptionné à Bertoua le 12 mars à
 * 14 h » le dit — et reste vrai même quand le colis est reparti.</p>
 *
 * <p>C'est la première règle fondatrice du projet : un statut est une photo,
 * un événement est un fait. Le statut affiché ici est une <b>projection</b>,
 * recalculée depuis les événements ; ce sont eux la vérité.</p>
 */
@Component({
  selector: 'ga-fiche-expedition',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './fiche-expedition.html',
  styleUrl: './fiche-expedition.scss',
})
export class FicheExpedition {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  /** Lié depuis la route par `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  protected readonly expedition = signal<Expedition | null>(null);
  protected readonly parcours = signal<readonly ParcoursColis[]>([]);
  protected readonly lieux = signal<readonly Lieu[]>([]);
  protected readonly retrait = signal<Retrait | null>(null);

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly erreurForm = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly typesEvenement = TYPES_EVENEMENT;

  /** Le colis dont on saisit un événement, ou `null`. */
  protected readonly colisOuvert = signal<number | null>(null);
  protected readonly typeEvenement = signal<TypeEvenement>('ARRIVEE');
  protected readonly lieuId = signal<number | null>(null);
  protected readonly observation = signal('');

  protected readonly formColis = signal(false);
  protected readonly numeroSuivi = signal('');

  constructor() {
    // `input.required` n'est pas lisible dans le constructeur : on charge au
    // premier rendu, quand la valeur est posée.
    queueMicrotask(() => this.charger());
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Expedition>(`/api/expeditions/${this.id()}`).subscribe({
      next: (e) => {
        this.expedition.set(e);
        this.chargement.set(false);
        this.chargerParcours();
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Cette expédition n’a pas pu être chargée.'));
      },
    });

    // Les lieux servent à saisir un événement. Chargés sans bloquer : la fiche
    // reste lisible sans eux, seul le formulaire en dépend.
    this.http.get<Lieu[]>('/api/lieux').subscribe({
      next: (l) => this.lieux.set(l.filter((x) => x.statut === 'ACTIF')),
      error: () => this.lieux.set([]),
    });
  }

  private chargerParcours(): void {
    if (!this.session.peut('EXPEDITION_CONSULTER_HISTORIQUE')) {
      return;
    }

    this.http.get<ParcoursColis[]>(`/api/expeditions/${this.id()}/parcours`).subscribe({
      next: (p) => this.parcours.set(p),
      error: () => this.parcours.set([]),
    });
  }

  /** Les événements d'un colis, ou une liste vide si le parcours manque. */
  protected evenementsDe(colisId: number): ParcoursColis | null {
    return this.parcours().find((p) => p.id === colisId) ?? null;
  }

  // -------------------------------------------------------------------------
  // Ajouter un colis
  // -------------------------------------------------------------------------

  protected ouvrirColis(): void {
    this.colisOuvert.set(null);
    this.numeroSuivi.set('');
    this.erreurForm.set(null);
    this.formColis.set(true);
  }

  protected ajouterColis(): void {
    if (this.action()) {
      return;
    }
    this.action.set('colis');
    this.erreurForm.set(null);

    this.http
      .post<Colis>(`/api/expeditions/${this.id()}/colis`, {
        // Vide = le serveur engendre le numéro. Le laisser saisir donnerait
        // des numéros de suivi inventés, qu'aucun bordereau ne porterait.
        numeroSuivi: this.numeroSuivi().trim() || null,
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.formColis.set(false);
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(messageErreur(e, 'Le colis n’a pas pu être ajouté.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Enregistrer une étape
  // -------------------------------------------------------------------------

  protected ouvrirEvenement(colisId: number): void {
    this.formColis.set(false);
    this.typeEvenement.set('ARRIVEE');
    this.lieuId.set(this.lieux()[0]?.id ?? null);
    this.observation.set('');
    this.erreurForm.set(null);
    this.colisOuvert.set(colisId);
  }

  protected fermer(): void {
    this.colisOuvert.set(null);
    this.formColis.set(false);
    this.erreurForm.set(null);
  }

  /**
   * Une anomalie doit être décrite.
   *
   * <p>Elle <b>bloque</b> le colis. Sans description, personne ne saura
   * pourquoi il est resté à Bertoua — ni comment le débloquer.</p>
   */
  protected observationObligatoire(): boolean {
    return this.typeEvenement() === 'ANOMALIE';
  }

  protected enregistrerEvenement(): void {
    const colisId = this.colisOuvert();
    if (colisId === null || this.action()) {
      return;
    }
    this.action.set('evenement');
    this.erreurForm.set(null);

    this.http
      .post(`/api/expeditions/colis/${colisId}/evenements`, {
        lieuId: this.lieuId(),
        type: this.typeEvenement(),
        observation: this.observation().trim() || null,
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.fermer();
          // Le statut du colis ET celui de l'expédition sont des projections
          // recalculées côté serveur : on recharge tout plutôt que de deviner.
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(messageErreur(e, 'L’étape n’a pas pu être enregistrée.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Le retrait
  // -------------------------------------------------------------------------

  /**
   * Prépare le retrait et engendre le code.
   *
   * <p>Le code exclut les caractères ambigus : il sera lu à voix haute,
   * recopié à la main, parfois épelé au téléphone.</p>
   */
  protected preparerRetrait(): void {
    if (this.action()) {
      return;
    }
    this.action.set('retrait');
    this.erreur.set(null);

    this.http
      .post<Retrait>(`/api/expeditions/${this.id()}/retrait`, null)
      .subscribe({
        next: (r) => {
          this.action.set(null);
          this.retrait.set(r);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(messageErreur(e, 'Le retrait n’a pas pu être préparé.'));
        },
      });
  }

  protected retraitPossible(): boolean {
    return this.expedition()?.statut === 'DISPONIBLE';
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

  protected typeLisible(type: string): string {
    return libelleEvenement(type);
  }

  protected nomLieu(lieuId: number): string {
    const lieu = this.lieux().find((l) => l.id === lieuId);
    return lieu ? `${lieu.nom} · ${lieu.ville}` : `lieu ${lieuId}`;
  }

  protected dateHeure(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  /** Une anomalie se distingue du reste : elle bloque. */
  protected estAnomalie(type: string): boolean {
    return type === 'ANOMALIE';
  }
}

