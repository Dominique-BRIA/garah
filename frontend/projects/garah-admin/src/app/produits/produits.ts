import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Page, ResumeProduit } from 'garah-ui';

@Component({
  selector: 'ga-produits',
  imports: [DecimalPipe],
  templateUrl: './produits.html',
  styleUrl: './produits.scss',
})
export class Produits {
  private readonly http = inject(HttpClient);

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

    this.http.get<Page<ResumeProduit>>('/api/produits?page=0&taille=24').subscribe({
      next: (page) => {
        this.produits.set(page.content);
        this.total.set(page.totalElements);
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
  protected badge(statut: string): string {
    switch (statut) {
      case 'PUBLIE': return 'gu-badge--succes';
      case 'ARCHIVE': return 'gu-badge--danger';
      case 'MASQUE': return 'gu-badge--alerte';
      default: return 'gu-badge--neutre';
    }
  }
}
