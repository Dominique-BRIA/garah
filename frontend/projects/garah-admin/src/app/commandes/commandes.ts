import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Icone,
  messageErreur,
  montantLisible,
  Page,
  Pagination,
  ResumeCommande,
  ServiceSession,
  StatutCommande,
  STATUTS_COMMANDE,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Les commandes du back-office.
 *
 * <p>🎯 <b>L'écran répond à une seule question : qu'est-ce qui attend un
 * geste ?</b> Une liste triée par date sans filtre y répond mal — les
 * commandes annulées et retirées, qui n'appellent rien, occupent la même
 * place que celles à préparer.</p>
 */
@Component({
  selector: 'ga-commandes',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './commandes.html',
  styleUrl: './commandes.scss',
})
export class Commandes {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeCommande[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_COMMANDE;
  protected readonly statut = signal<StatutCommande | ''>('');
  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');

  /** Le libellé du filtre en cours, pour le dire dans le sous-titre. */
  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? (STATUTS_COMMANDE.find((s) => s.code === code)?.libelle ?? code) : null;
  });

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
    if (this.statut()) {
      parametres.set('statut', this.statut());
    }
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }

    this.http.get<Page<ResumeCommande>>(`/api/commandes?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, { repli: 'Les commandes n’ont pas pu être chargées.', sujet: 'les commandes' }));
      },
    });
  }

  /**
   * Change de filtre.
   *
   * <p>Retour à la première page : rester sur la page 4 d'un filtre qui n'en
   * compte plus qu'une afficherait une liste vide, et personne ne penserait à
   * regarder le numéro de page.</p>
   */
  protected filtrer(statut: StatutCommande | ''): void {
    this.statut.set(statut);
    this.page.set(0);
    this.charger();
  }

  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected changerPage(page: number): void {
    this.page.set(page);
    this.charger();
    window.scrollTo({ top: 0, behavior: 'smooth' });
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

  /**
   * Cette commande attend-elle un geste ?
   *
   * <p>Payée, en préparation, prête : quelqu'un doit agir. Annulée, retirée,
   * en attente de paiement : non — on attend le client ou plus rien.</p>
   */
  protected attend(statut: StatutCommande): boolean {
    return STATUTS_COMMANDE.find((s) => s.code === statut)?.attendUneAction ?? false;
  }

  protected montant(valeur: number, devise: string): string {
    return montantLisible(valeur, devise);
  }

  protected dateLisible(iso: string): string {
    return new Date(iso).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  }
}

