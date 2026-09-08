import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  badgeStatutReglement,
  Ecriture,
  explicationEcriture,
  Icone,
  libelleStatutReglement,
  libelleTypeEcriture,
  messageErreur,
  montantLisible,
  Page,
  Pagination,
  Reglement,
  ServiceSession,
  SoldeMarchand,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Ce que GARAH doit à ses marchands.
 *
 * <h2>Le solde n'est stocké nulle part</h2>
 *
 * <p>🎯 Il est la <b>somme des écritures</b>, recalculée à chaque lecture. Un
 * total stocké finit toujours par mentir : il suffit d'une écriture ajoutée
 * par un chemin qui a oublié de le mettre à jour, et l'écart ne se voit qu'au
 * moment de payer quelqu'un.</p>
 *
 * <p>D'où la structure de l'écran : une liste de soldes, et pour chacun le
 * <b>détail qui le justifie</b>. « Pourquoi doit-on 1 250 000 FCFA à ce
 * marchand ? » doit avoir une réponse ligne par ligne, ou le chiffre est
 * faux.</p>
 *
 * <h2>Préparer n'est pas payer</h2>
 *
 * <p>Un règlement naît <b>PRÉVU</b> et n'écrit rien dans le grand livre. La
 * dette ne baisse qu'à la confirmation, avec sa référence bancaire. Fondre les
 * deux gestes ferait qu'un virement raté laisserait quand même une dette
 * soldée dans nos livres.</p>
 */
@Component({
  selector: 'ga-finance',
  imports: [FormsModule, Icone, Pagination],
  templateUrl: './finance.html',
  styleUrl: './finance.scss',
})
export class Finance {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly SoldeMarchand[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  // --- Le marchand ouvert ---
  protected readonly ouvert = signal<SoldeMarchand | null>(null);
  protected readonly ecritures = signal<readonly Ecriture[]>([]);
  protected readonly chargementDetail = signal(false);
  protected readonly erreurDetail = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  // --- Le règlement en préparation ---
  protected readonly formReglement = signal(false);
  protected readonly montant = signal<number | null>(null);
  protected readonly moyen = signal('VIREMENT');
  protected readonly prepare = signal<Reglement | null>(null);
  protected readonly reference = signal('');

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const parametres = new URLSearchParams({
      page: String(this.page()),
      taille: String(TAILLE_PAGE),
    });

    this.http.get<Page<SoldeMarchand>>(`/api/finance/marchands/soldes?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Les soldes n’ont pas pu être chargés.'));
      },
    });
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.charger();
  }

  // -------------------------------------------------------------------------
  // Le détail qui justifie le solde
  // -------------------------------------------------------------------------

  protected ouvrir(m: SoldeMarchand): void {
    this.ouvert.set(m);
    this.ecritures.set([]);
    this.erreurDetail.set(null);
    this.fermerFormulaire();

    if (!this.session.peut('HISTORIQUE_FINANCIER_MARCHAND_CONSULTER')) {
      return;
    }

    this.chargementDetail.set(true);
    this.http
      .get<Page<Ecriture>>(`/api/finance/marchands/${m.marchandId}/ecritures?taille=50`)
      .subscribe({
        next: (page) => {
          this.chargementDetail.set(false);
          this.ecritures.set(page.content);
        },
        error: (e: unknown) => {
          this.chargementDetail.set(false);
          this.erreurDetail.set(messageErreur(e, 'Le détail n’a pas pu être chargé.'));
        },
      });
  }

  protected fermer(): void {
    this.ouvert.set(null);
    this.ecritures.set([]);
    this.erreurDetail.set(null);
    this.fermerFormulaire();
  }

  // -------------------------------------------------------------------------
  // Régler
  // -------------------------------------------------------------------------

  protected ouvrirReglement(): void {
    // Pré-rempli au solde entier : c'est le cas courant. Le laisser vide
    // ferait retaper un montant qu'on a sous les yeux — et retaper, c'est se
    // tromper.
    this.montant.set(this.ouvert()?.solde ?? null);
    this.reference.set('');
    this.prepare.set(null);
    this.erreurDetail.set(null);
    this.formReglement.set(true);
  }

  protected fermerFormulaire(): void {
    this.formReglement.set(false);
    this.prepare.set(null);
    this.montant.set(null);
    this.reference.set('');
  }

  /**
   * Premier temps : inscrire l'intention.
   *
   * <p>Rien ne bouge dans le grand livre. Le règlement reste <b>PRÉVU</b>
   * jusqu'à ce qu'on confirme que l'argent est parti.</p>
   */
  protected preparer(): void {
    const m = this.ouvert();
    const montant = this.montant();
    if (!m || !montant || montant <= 0 || this.action()) {
      return;
    }
    this.action.set('preparation');
    this.erreurDetail.set(null);

    this.http
      .post<Reglement>(`/api/finance/marchands/${m.marchandId}/reglements`, {
        montant,
        moyen: this.moyen(),
      })
      .subscribe({
        next: (r) => {
          this.action.set(null);
          this.prepare.set(r);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurDetail.set(messageErreur(e, 'Le règlement n’a pas pu être préparé.'));
        },
      });
  }

  /**
   * Second temps : acter le versement.
   *
   * <p>La référence est <b>obligatoire</b> — c'est elle, et elle seule, qui
   * permet d'arbitrer un « je n'ai jamais été payé » six mois plus tard.</p>
   */
  protected confirmer(): void {
    const r = this.prepare();
    if (!r || !this.reference().trim() || this.action()) {
      return;
    }
    this.action.set('confirmation');
    this.erreurDetail.set(null);

    this.http
      .post<Reglement>(`/api/finance/marchands/reglements/${r.id}/confirmation`, {
        reference: this.reference().trim(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.fermerFormulaire();
          // Le solde a bougé : on relit la liste ET le détail. Le deviner
          // localement ferait diverger l'affichage de la somme réelle des
          // écritures — exactement ce que ce module refuse.
          const m = this.ouvert();
          this.charger();
          if (m) {
            this.ouvrir(m);
          }
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurDetail.set(messageErreur(e, 'Le versement n’a pas pu être confirmé.'));
        },
      });
  }

  protected annulerReglement(): void {
    const r = this.prepare();
    if (!r || this.action()) {
      return;
    }
    this.action.set('annulation');

    this.http
      .post(`/api/finance/marchands/reglements/${r.id}/annulation`, null)
      .subscribe({
        next: () => {
          this.action.set(null);
          this.fermerFormulaire();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurDetail.set(messageErreur(e, 'Le règlement n’a pas pu être annulé.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected reglable(): boolean {
    return (this.ouvert()?.solde ?? 0) > 0 && this.session.peut('REGLEMENT_MARCHAND_CREER');
  }

  protected libelleType(type: string): string {
    return libelleTypeEcriture(type);
  }

  protected explication(type: string): string {
    return explicationEcriture(type);
  }

  protected libelleReglement(statut: string): string {
    return libelleStatutReglement(statut);
  }

  protected badgeReglement(statut: string): string {
    return badgeStatutReglement(statut);
  }

  protected montantLisible(valeur: number, devise = 'XAF'): string {
    return montantLisible(valeur, devise);
  }

  /**
   * Une écriture négative sort de ce qu'on doit.
   *
   * <p>On se fie au <b>signe réel</b>, pas au type : un ajustement peut aller
   * dans les deux sens, et c'est justement son rôle.</p>
   */
  protected sortante(e: Ecriture): boolean {
    return e.montant < 0;
  }

  protected dateHeure(iso: string | null): string {
    if (!iso) {
      return '';
    }
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

