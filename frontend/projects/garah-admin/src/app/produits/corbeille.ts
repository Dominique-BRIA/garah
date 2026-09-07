import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone, Page, Pagination, ReponseErreur, ServiceSession, VueCorbeille } from 'garah-ui';

const TAILLE_PAGE = 24;

/**
 * La corbeille des produits.
 *
 * <h2>Pourquoi un écran à part, et pas un onglet de la liste</h2>
 *
 * <p>Ce ne sont pas les mêmes objets ni les mêmes gestes. La liste sert à
 * <b>travailler</b> le catalogue — publier, modifier, mettre en stock. La
 * corbeille ne propose que deux issues, et l'une des deux est irréversible.
 * Les mêler dans un onglet ferait cohabiter « modifier le prix » et « effacer
 * pour toujours » à un clic d'écart.</p>
 *
 * <p>C'est aussi pourquoi elle est <b>vide la plupart du temps</b> : on n'y
 * vient que pour rattraper une erreur, ou pour faire le ménage.</p>
 */
@Component({
  selector: 'ga-corbeille',
  imports: [Icone, RouterLink, Pagination],
  templateUrl: './corbeille.html',
  styleUrl: './corbeille.scss',
})
export class Corbeille {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly lignes = signal<readonly VueCorbeille[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  /** L'identifiant en cours de traitement, pour ne bloquer que SA ligne. */
  protected readonly enCours = signal<number | null>(null);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http
      .get<Page<VueCorbeille>>(
        `/api/produits/corbeille?page=${this.page()}&taille=${TAILLE_PAGE}`,
      )
      .subscribe({
        next: (page) => {
          this.lignes.set(page.content);
          this.total.set(page.page.totalElements);
          this.totalPages.set(page.page.totalPages);
          this.chargement.set(false);
        },
        error: (e: unknown) => {
          this.chargement.set(false);
          this.erreur.set(this.message(e, 'La corbeille n’a pas pu être chargée.'));
        },
      });
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.charger();
  }

  /** Rend le produit à la liste, avec son statut d'avant. */
  protected restaurer(ligne: VueCorbeille): void {
    this.agir(ligne.id, `/api/produits/corbeille/${ligne.id}/restauration`, 'POST',
      'La restauration a échoué.');
  }

  /**
   * Efface pour de bon.
   *
   * <p>⚠️ La confirmation nomme le produit et dit ce qui part <b>avec</b> lui.
   * « Êtes-vous sûr ? » ne fait réfléchir personne ; « Chemise Oxford, ses
   * déclinaisons, ses prix et ses photos » si.</p>
   */
  protected effacer(ligne: VueCorbeille): void {
    const confirme = confirm(
      `Effacer définitivement « ${ligne.nom} » ?\n\n`
      + 'Ses déclinaisons, ses prix et ses photos partiront avec lui.\n'
      + 'Cette action ne peut pas être annulée.',
    );
    if (!confirme) {
      return;
    }
    this.agir(ligne.id, `/api/produits/corbeille/${ligne.id}`, 'DELETE',
      'L’effacement a échoué.');
  }

  private agir(id: number, url: string, methode: 'POST' | 'DELETE', repli: string): void {
    if (this.enCours() !== null) {
      return;
    }
    this.enCours.set(id);
    this.erreur.set(null);

    const appel = methode === 'POST'
      ? this.http.post<void>(url, null)
      : this.http.delete<void>(url);

    appel.subscribe({
      next: () => {
        this.enCours.set(null);
        // 🎯 On RECHARGE au lieu de retirer la ligne localement.
        //
        // Retirer la ligne à la main laisserait le compteur et le nombre de
        // pages inchangés : « 4 produits » au-dessus d'une liste qui en montre
        // trois. Sur la dernière page, vider la dernière ligne laisserait même
        // une page vide sans moyen d'en sortir.
        this.reculerSiPageVidee();
        this.charger();
      },
      error: (e: unknown) => {
        this.enCours.set(null);
        this.erreur.set(this.message(e, repli));
      },
    });
  }

  /** Si on vient de vider la dernière ligne d'une page, on remonte d'une page. */
  private reculerSiPageVidee(): void {
    if (this.lignes().length === 1 && this.page() > 0) {
      this.page.update((p) => p - 1);
    }
  }

  /**
   * « il y a 3 jours » plutôt qu'une date brute.
   *
   * <p>La corbeille sert à rattraper un geste qu'on a soi-même fait : on se
   * souvient de « tout à l'heure », pas du 6 septembre à 14 h 32.</p>
   */
  protected depuis(iso: string): string {
    const instant = new Date(iso).getTime();
    if (Number.isNaN(instant)) {
      return '—';
    }
    const minutes = Math.floor((Date.now() - instant) / 60000);

    if (minutes < 1) return 'à l’instant';
    if (minutes < 60) return `il y a ${minutes} min`;

    const heures = Math.floor(minutes / 60);
    if (heures < 24) return `il y a ${heures} h`;

    const jours = Math.floor(heures / 24);
    return jours === 1 ? 'hier' : `il y a ${jours} jours`;
  }

  private message(e: unknown, repli: string): string {
    if (e instanceof HttpErrorResponse) {
      const corps = e.error as ReponseErreur | null;
      if (corps?.message) {
        return corps.message;
      }
      if (e.status === 0) {
        return 'Le serveur est injoignable. Vérifiez votre connexion.';
      }
    }
    return repli;
  }
}
