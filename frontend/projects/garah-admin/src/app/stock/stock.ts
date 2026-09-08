import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  EtatStock,
  Icone,
  libelleCompteur,
  libelleMouvement,
  libelleOrigine,
  messageErreur,
  MouvementStock,
  Page,
  Pagination,
  ReponseErreur,
  ServiceSession,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * L'inventaire.
 *
 * <h2>Trois compteurs, jamais un seul</h2>
 *
 * <pre>
 * DISPONIBLE   vendable tout de suite
 * RESERVE      promis a une commande en attente de paiement
 * ENDOMMAGE    physiquement la, mais invendable
 * </pre>
 *
 * <p>N'afficher qu'un nombre laisserait croire qu'une réservation a fait
 * disparaître la marchandise. Le total dit ce qui est réellement dans
 * l'entrepôt ; le disponible, ce qu'on peut encore vendre.</p>
 *
 * <p>🎯 La liste est triée <b>ruptures en premier</b>, côté serveur. Un écran
 * de stock s'ouvre pour savoir ce qui manque.</p>
 */
@Component({
  selector: 'ga-stock',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './stock.html',
  styleUrl: './stock.scss',
})
export class Stock {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly EtatStock[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');
  protected readonly seulementAlertes = signal(false);

  // --- Le panneau d'une déclinaison ---
  protected readonly ouvert = signal<EtatStock | null>(null);
  protected readonly mouvements = signal<readonly MouvementStock[]>([]);
  protected readonly chargementMouvements = signal(false);
  protected readonly action = signal<string | null>(null);
  protected readonly erreurPanneau = signal<string | null>(null);

  /** Deux gestes distincts, deux formulaires : voir `ouvrirGeste`. */
  protected readonly geste = signal<'entree' | 'ajustement' | null>(null);
  protected readonly quantite = signal<number | null>(null);
  protected readonly commentaire = signal('');

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
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }
    if (this.seulementAlertes()) {
      parametres.set('sousLeSeuil', 'true');
    }

    this.http.get<Page<EtatStock>>(`/api/stock?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, { repli: 'Le stock n’a pas pu être chargé.', sujet: 'le stock' }));
      },
    });
  }

  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected basculerAlertes(): void {
    this.seulementAlertes.update((v) => !v);
    this.page.set(0);
    this.charger();
  }

  protected changerPage(page: number): void {
    this.page.set(page);
    this.charger();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // -------------------------------------------------------------------------
  // Le panneau d'une déclinaison
  // -------------------------------------------------------------------------

  protected ouvrir(etat: EtatStock): void {
    this.ouvert.set(etat);
    this.geste.set(null);
    this.erreurPanneau.set(null);
    this.mouvements.set([]);
    this.chargerMouvements(etat.varianteId);
  }

  protected fermer(): void {
    this.ouvert.set(null);
    this.geste.set(null);
    this.erreurPanneau.set(null);
  }

  private chargerMouvements(varianteId: number): void {
    if (!this.session.peut('STOCK_CONSULTER_HISTORIQUE')) {
      return;
    }
    this.chargementMouvements.set(true);

    this.http.get<MouvementStock[]>(`/api/stock/${varianteId}/mouvements`).subscribe({
      next: (m) => {
        this.mouvements.set(m);
        this.chargementMouvements.set(false);
      },
      error: () => {
        this.mouvements.set([]);
        this.chargementMouvements.set(false);
      },
    });
  }

  /**
   * Ouvre l'un des deux gestes.
   *
   * <p>⚠️ Ils ne se ressemblent qu'en apparence. Une <b>réception</b> ajoute
   * ce qui vient d'arriver ; un <b>ajustement</b> déclare ce qu'on a
   * réellement compté. Le premier prend une quantité à ajouter, le second la
   * quantité constatée — les confondre fait passer un inventaire de 8 pour
   * une livraison de 8.</p>
   */
  protected ouvrirGeste(geste: 'entree' | 'ajustement'): void {
    this.geste.set(geste);
    this.quantite.set(geste === 'ajustement' ? (this.ouvert()?.disponible ?? 0) : null);
    this.commentaire.set('');
    this.erreurPanneau.set(null);
  }

  protected enregistrerGeste(): void {
    const etat = this.ouvert();
    const geste = this.geste();
    if (!etat || !geste || this.action()) {
      return;
    }

    this.action.set(geste);
    this.erreurPanneau.set(null);

    const url = `/api/stock/${etat.varianteId}/${geste === 'entree' ? 'entrees' : 'ajustements'}`;
    const corps =
      geste === 'entree'
        ? { quantite: this.quantite(), commentaire: this.commentaire().trim() }
        : { quantiteReelle: this.quantite(), motif: this.commentaire().trim() };

    this.http.post<EtatStock>(url, corps).subscribe({
      next: (misAJour) => {
        this.action.set(null);
        this.geste.set(null);
        // Le panneau garde la désignation, que la réponse ne porte pas : elle
        // vient du catalogue, et cette route-là répond sur une seule variante.
        this.ouvert.set({ ...misAJour, sku: etat.sku, libelle: etat.libelle,
                          produitId: etat.produitId, produitNom: etat.produitNom });
        this.chargerMouvements(etat.varianteId);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreurPanneau.set(message2(e, 'Le mouvement n’a pas pu être enregistré.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected designation(etat: EtatStock): string {
    if (!etat.produitNom) {
      return `Déclinaison ${etat.varianteId}`;
    }
    return etat.libelle && etat.libelle !== etat.produitNom
      ? `${etat.produitNom} — ${etat.libelle}`
      : etat.produitNom;
  }

  protected typeMouvement(type: string): string {
    return libelleMouvement(type);
  }

  protected compteur(compteur: string): string {
    return libelleCompteur(compteur);
  }

  protected origine(origine: string | null): string {
    return libelleOrigine(origine);
  }

  protected dateHeure(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  /** Un mouvement qui ajoute, ou un qui retire ? */
  protected entrant(m: MouvementStock): boolean {
    return m.quantite > 0;
  }
}

function message2(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
