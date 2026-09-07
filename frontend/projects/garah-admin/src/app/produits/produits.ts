import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  BasculeVue,
  Icone,
  Page,
  Pagination,
  ReponseErreur,
  ResumeProduit,
  ServiceSession,
  TypeVue,
  montantLisible,
} from 'garah-ui';

/**
 * Vingt-quatre par page.
 *
 * <p>Trois rangées de huit en vue « cartes » sur un écran large, et une
 * réponse qui reste légère sur une connexion mobile.</p>
 */
const TAILLE_PAGE = 24;

@Component({
  selector: 'ga-produits',
  imports: [FormsModule, Icone, RouterLink, BasculeVue, Pagination],
  templateUrl: './produits.html',
  styleUrl: './produits.scss',
})
export class Produits {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly produits = signal<readonly ResumeProduit[]>([]);
  protected readonly total = signal(0);

  /**
   * Tableau ou cartes.
   *
   * <p>Les cartes par défaut ici, contrairement aux marchands : un catalogue
   * se reconnaît à ses photos, et un tableau les réduit à une vignette de
   * 2 rem au bout d'une ligne. C'est aussi l'affichage qui ressemble le plus
   * à ce que verra le client sur la vitrine.</p>
   */
  protected readonly vue = signal<TypeVue>('cartes');
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly taille = TAILLE_PAGE;

  /** Le texte saisi. Il ne part au serveur qu'à la validation. */
  protected readonly recherche = signal('');

  /**
   * Ce qui filtre RÉELLEMENT la liste affichée.
   *
   * <p>Distinct de {@link recherche} : sans cette seconde valeur, vider le
   * champ de saisie modifierait aussitôt le message « aucun résultat pour… »
   * alors que la liste, elle, montrerait toujours l'ancien filtre.</p>
   */
  protected readonly filtreApplique = signal('');

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const q = this.filtreApplique();
    const parametres = new URLSearchParams({
      page: String(this.page()),
      taille: String(TAILLE_PAGE),
    });
    if (q) {
      parametres.set('recherche', q);
    }

    // ⚠️ La route d'ADMINISTRATION, pas le catalogue public. Ce dernier ne
    // renvoie que les produits publiés : la liste de gestion cachait donc
    // exactement les brouillons sur lesquels il restait du travail.
    this.http
      .get<Page<ResumeProduit>>(`/api/produits/administration?${parametres}`)
      .subscribe({
        next: (page) => {
          this.produits.set(page.content);
          this.total.set(page.page.totalElements);
          this.totalPages.set(page.page.totalPages);
          this.chargement.set(false);
        },
        error: (e: unknown) => {
          this.chargement.set(false);
          this.erreur.set(message(e));
        },
      });
  }

  /**
   * Lance la recherche.
   *
   * <p>Retour à la première page : rester sur la page 4 d'un résultat qui n'en
   * compte plus qu'une afficherait une liste vide, et personne ne penserait à
   * regarder le numéro de page.</p>
   */
  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected effacer(): void {
    this.recherche.set('');
    this.chercher();
  }

  protected changerPage(page: number): void {
    this.page.set(page);
    this.charger();
    // La liste change entièrement sous les yeux : sans ce retour en haut, on
    // se retrouve au milieu de la page suivante sans avoir vu son début.
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /** « 5 000 FCFA », ou un tiret si le produit n'a pas encore de prix. */
  protected montant(valeur: number | null, devise: string | null): string {
    return montantLisible(valeur, devise);
  }

  /** La classe de badge correspondant au statut. */
  protected badge(statut: string): string {
    switch (statut) {
      case 'PUBLIE': return 'gu-badge--succes';
      case 'ARCHIVE': return 'gu-badge--danger';
      case 'MASQUE': return 'gu-badge--alerte';
      default: return 'gu-badge--neutre';
    }
  }
}

/**
 * Ce qui a réellement échoué, et non « ça n'a pas marché ».
 *
 * <p>🎯 Un même message pour tous les échecs cache exactement ce qu'on a besoin
 * de savoir. « Le catalogue n'a pas pu être chargé » couvrait un droit
 * manquant, un serveur endormi et une panne réelle — trois situations dont
 * <b>aucune</b> ne se traite de la même façon. Le serveur, lui, dit lequel des
 * trois c'est : on le répète plutôt que de l'écraser.</p>
 */
function message(e: unknown): string {
  if (!(e instanceof HttpErrorResponse)) {
    return 'Le catalogue n’a pas pu être chargé.';
  }

  // Statut 0 : la requête n'a jamais abouti. Ni CORS, ni réseau, ni serveur —
  // le navigateur ne le dit pas, et l'instance gratuite met environ trois
  // minutes à se réveiller.
  if (e.status === 0) {
    return 'Le service ne répond pas. Il peut être en train de se réveiller : réessayez dans deux minutes.';
  }
  if (e.status === 403) {
    return 'Votre compte n’a pas le droit de consulter le catalogue.';
  }
  if (e.status >= 500) {
    return 'Le service a rencontré une erreur. Réessayez dans un instant.';
  }

  const corps = e.error as ReponseErreur | null;
  return corps?.message ?? 'Le catalogue n’a pas pu être chargé.';
}
