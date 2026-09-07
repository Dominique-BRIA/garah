import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  BasculeVue,
  Icone,
  Page,
  ReponseErreur,
  ResumeProduit,
  ServiceSession,
  TypeVue,
  montantLisible,
} from 'garah-ui';

@Component({
  selector: 'ga-produits',
  imports: [Icone, RouterLink, BasculeVue],
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

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    // ⚠️ La route d'ADMINISTRATION, pas le catalogue public. Ce dernier ne
    // renvoie que les produits publiés : la liste de gestion cachait donc
    // exactement les brouillons sur lesquels il restait du travail.
    this.http.get<Page<ResumeProduit>>('/api/produits/administration?page=0&taille=24').subscribe({
      next: (page) => {
        this.produits.set(page.content);
        this.total.set(page.page.totalElements);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e));
      },
    });
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
