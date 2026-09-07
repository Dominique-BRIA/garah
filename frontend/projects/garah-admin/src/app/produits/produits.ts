import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone, Page, ResumeProduit, ServiceSession, montantLisible } from 'garah-ui';

@Component({
  selector: 'ga-produits',
  imports: [Icone, RouterLink],
  templateUrl: './produits.html',
  styleUrl: './produits.scss',
})
export class Produits {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly produits = signal<readonly ResumeProduit[]>([]);
  protected readonly total = signal(0);
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
        this.erreur.set(
          e instanceof HttpErrorResponse && e.status === 0
            ? "L'API est injoignable. Elle peut être en train de se réveiller."
            : 'Le catalogue n\'a pas pu être chargé.',
        );
      },
    });
  }

  /** La classe de badge correspondant au statut. */
  /** « 5 000 FCFA », ou un tiret si le produit n'a pas encore de prix. */
  protected montant(valeur: number | null, devise: string | null): string {
    return montantLisible(valeur, devise);
  }

  protected badge(statut: string): string {
    switch (statut) {
      case 'PUBLIE': return 'gu-badge--succes';
      case 'ARCHIVE': return 'gu-badge--danger';
      case 'MASQUE': return 'gu-badge--alerte';
      default: return 'gu-badge--neutre';
    }
  }
}
