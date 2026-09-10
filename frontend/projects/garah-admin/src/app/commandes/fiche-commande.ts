import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  badgeStatutExpedition,
  DetailCommande,
  Icone,
  Itineraire,
  libelleMoyen,
  libelleStatutExpedition,
  Lieu,
  messageErreur,
  montantLisible,
  Paiement,
  ResumeExpedition,
  ServiceSession,
  StatutCommande,
  STATUTS_COMMANDE,
  STATUTS_PAIEMENT,
  TRANSITIONS_COMMANDE,
} from 'garah-ui';

/**
 * Une commande, et ce qu'on peut en faire.
 *
 * <h2>Deux idées gouvernent cet écran</h2>
 *
 * <p><b>Les montants sont des photographies.</b> Désignation, prix unitaire,
 * TVA : tout a été figé au moment de l'achat. Une facture de mars doit rester
 * juste en septembre, même si le produit a changé de nom et de prix
 * entre-temps. L'écran ne relit donc <b>jamais</b> le catalogue.</p>
 *
 * <p><b>Seules les transitions possibles sont proposées.</b> Le serveur refuse
 * les autres, mais un bouton « Expédier » sur une commande impayée ferait
 * cliquer pour rien — et l'erreur arriverait après coup.</p>
 */
@Component({
  selector: 'ga-fiche-commande',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './fiche-commande.html',
  styleUrl: './fiche-commande.scss',
})
export class FicheCommande {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly session = inject(ServiceSession);

  /** Lié depuis la route par `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  protected readonly commande = signal<DetailCommande | null>(null);
  protected readonly paiements = signal<readonly Paiement[]>([]);
  protected readonly resteAPayer = signal<number | null>(null);

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly formAnnulation = signal(false);
  protected readonly motif = signal('');

  // --- L'expédition ---
  protected readonly expeditions = signal<readonly ResumeExpedition[]>([]);
  protected readonly formExpedition = signal(false);
  protected readonly lieux = signal<readonly Lieu[]>([]);
  protected readonly itineraires = signal<readonly Itineraire[]>([]);
  protected readonly lieuDepartId = signal<number | null>(null);
  protected readonly itineraireId = signal<number | null>(null);

  /**
   * D'où la marchandise peut partir.
   *
   * <p>Un entrepôt, ou un point de transit où elle est déjà groupée. Pas un
   * point de récupération : c'est un comptoir de retrait, rien n'en part.</p>
   */
  protected readonly departsPossibles = computed(() =>
    this.lieux().filter((l) => l.type !== 'POINT_RECUPERATION'),
  );

  /**
   * Une commande devient expédiable une fois payée.
   *
   * <p>Avant, il n'y a rien à envoyer. Après retrait ou annulation, il n'y a
   * plus rien à envoyer non plus.</p>
   */
  protected readonly expediable = computed(() => {
    const statut = this.commande()?.statut;
    return (
      statut === 'PAYEE' ||
      statut === 'EN_PREPARATION' ||
      statut === 'PRETE' ||
      statut === 'EXPEDIEE'
    );
  });

  constructor() {
    // `input.required` n'est pas lisible dans le constructeur : on charge au
    // premier rendu, quand la valeur est posée.
    queueMicrotask(() => this.charger());
  }

  protected readonly annulable = computed(() => {
    const c = this.commande();
    return !!c && TRANSITIONS_COMMANDE[c.statut].includes('ANNULEE');
  });

  // -------------------------------------------------------------------------
  // Chargement
  // -------------------------------------------------------------------------

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<DetailCommande>(`/api/commandes/${this.id()}`).subscribe({
      next: (c) => {
        this.commande.set(c);
        this.chargement.set(false);
        this.chargerArgent();
        this.chargerExpeditions();
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Cette commande n’a pas pu être chargée.'));
      },
    });
  }

  /**
   * Ce qui s'est passé sur l'argent.
   *
   * <p>Chargé séparément et sans bloquer : la commande reste lisible même si
   * l'historique des paiements manque. On n'efface pas ce qui s'affiche déjà
   * pour un morceau absent.</p>
   */
  private chargerArgent(): void {
    if (!this.session.peut('PAIEMENT_CONSULTER')) {
      return;
    }

    this.http.get<Paiement[]>(`/api/paiements/commandes/${this.id()}`).subscribe({
      next: (p) => this.paiements.set(p),
      error: () => this.paiements.set([]),
    });

    this.http
      .get<{ resteAPayer: number }>(`/api/paiements/commandes/${this.id()}/reste-a-payer`)
      .subscribe({
        next: (r) => this.resteAPayer.set(r.resteAPayer),
        error: () => this.resteAPayer.set(null),
      });
  }

  // -------------------------------------------------------------------------
  // L'expédition
  // -------------------------------------------------------------------------

  /**
   * Les expéditions déjà créées pour cette commande.
   *
   * <p>Il peut y en avoir plusieurs : une commande à deux marchands part
   * rarement d'un seul entrepôt le même jour.</p>
   */
  private chargerExpeditions(): void {
    if (!this.session.peut('EXPEDITION_CONSULTER')) {
      return;
    }

    this.http
      .get<ResumeExpedition[]>(`/api/expeditions/commandes/${this.id()}`)
      .subscribe({
        next: (e) => this.expeditions.set(e),
        error: () => this.expeditions.set([]),
      });
  }

  protected ouvrirExpedition(): void {
    this.lieuDepartId.set(this.departsPossibles()[0]?.id ?? null);
    this.itineraireId.set(null);
    this.erreur.set(null);
    this.formExpedition.set(true);

    // Chargés à l'ouverture, pas au chargement de la fiche : la plupart des
    // consultations d'une commande ne mènent à aucune expédition, et deux
    // requêtes de référentiel à chaque ouverture de fiche ne servent alors
    // à rien.
    this.http.get<Lieu[]>('/api/lieux').subscribe({
      next: (l) => this.lieux.set(l.filter((x) => x.statut === 'ACTIF')),
      error: () => this.lieux.set([]),
    });

    if (this.session.peut('ITINERAIRE_CONSULTER')) {
      this.http.get<Itineraire[]>('/api/itineraires?actifs=true').subscribe({
        next: (i) => this.itineraires.set(i),
        error: () => this.itineraires.set([]),
      });
    }
  }

  /**
   * Crée l'expédition.
   *
   * <p>🎯 <b>La destination n'est pas dans ce formulaire.</b> Le client a
   * choisi son point de récupération en commandant, et il a payé
   * l'acheminement de <b>ce</b> point. La faire ressaisir donnerait le moyen
   * d'expédier vers une autre ville que celle où le client viendra — et rien,
   * ensuite, ne rapprocherait les deux. Le serveur la déduit.</p>
   *
   * <p>Ce qui se saisit, c'est le <b>départ</b> : la même commande peut partir
   * de Douala ou d'un stock déjà groupé à Bertoua, et ça, seul l'opérateur le
   * sait.</p>
   */
  protected creerExpedition(): void {
    if (this.lieuDepartId() === null || this.action()) {
      return;
    }
    this.action.set('expedition');
    this.erreur.set(null);

    this.http
      .post<{ id: number }>('/api/expeditions', {
        commandeId: Number(this.id()),
        lieuDepartId: this.lieuDepartId(),
        itineraireId: this.itineraireId(),
      })
      .subscribe({
        next: (e) => {
          this.action.set(null);
          this.formExpedition.set(false);
          // On y va directement : l'expédition vient de naître VIDE, et le
          // geste suivant — y ranger les colis — est sur sa fiche.
          this.router.navigate(['/expeditions', e.id]);
        },
        error: (err: unknown) => {
          this.action.set(null);
          this.erreur.set(messageErreur(err, 'L’expédition n’a pas pu être créée.'));
        },
      });
  }

  protected nomLieu(lieuId: number): string {
    const lieu = this.lieux().find((l) => l.id === lieuId);
    return lieu ? `${lieu.nom} · ${lieu.ville}` : `Lieu ${lieuId}`;
  }

  // -------------------------------------------------------------------------
  // Actions
  // -------------------------------------------------------------------------

  protected annuler(): void {
    if (this.action()) {
      return;
    }
    this.action.set('annulation');
    this.erreur.set(null);

    this.http
      .post<DetailCommande>(`/api/commandes/${this.id()}/annulation`, {
        motif: this.motif().trim(),
      })
      .subscribe({
        next: (c) => {
          this.action.set(null);
          this.formAnnulation.set(false);
          this.motif.set('');
          this.commande.set(c);
          this.chargerArgent();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(messageErreur(e, 'La commande n’a pas pu être annulée.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected badge(statut: StatutCommande): string {
    return STATUTS_COMMANDE.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
  }

  protected libelle(statut: StatutCommande): string {
    return STATUTS_COMMANDE.find((s) => s.code === statut)?.libelle ?? statut;
  }

  protected badgeExpedition(statut: string): string {
    return badgeStatutExpedition(statut);
  }

  protected libelleExpedition(statut: string): string {
    return libelleStatutExpedition(statut);
  }

  protected badgePaiement(statut: string): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
  }

  protected libellePaiement(statut: string): string {
    return STATUTS_PAIEMENT.find((s) => s.code === statut)?.libelle ?? statut;
  }

  protected moyen(code: string): string {
    return libelleMoyen(code);
  }

  protected montant(valeur: number | null, devise: string): string {
    return montantLisible(valeur, devise);
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
}

