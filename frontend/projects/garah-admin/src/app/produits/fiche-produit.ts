import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  DetailProduit,
  Icone,
  Manques,
  ReponseErreur,
  ServiceSession,
  Variante,
} from 'garah-ui';

@Component({
  selector: 'ga-fiche-produit',
  imports: [FormsModule, RouterLink, Icone],
  templateUrl: './fiche-produit.html',
  styleUrl: './fiche-produit.scss',
})
export class FicheProduit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly session = inject(ServiceSession);

  /** Lié depuis la route par `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  protected readonly produit = signal<DetailProduit | null>(null);
  protected readonly variantes = signal<readonly Variante[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  // --- Ajout d'une variante ---
  protected readonly formVariante = signal(false);
  protected readonly sku = signal('');
  protected readonly libelle = signal('');

  // --- Ajout d'un palier ---
  protected readonly formPalier = signal<number | null>(null);
  protected readonly quantiteMin = signal(1);
  protected readonly quantiteMax = signal<number | null>(null);
  protected readonly prix = signal<number | null>(null);

  protected readonly erreurForm = signal<string | null>(null);

  constructor() {
    // `input.required` n'est pas lisible dans le constructeur : on charge au
    // premier rendu, quand la valeur est posée.
    queueMicrotask(() => this.charger());
  }

  /**
   * Ce qui manque pour publier (I-12).
   *
   * <p>🎯 <b>On le dit AVANT le clic, pas après l'erreur.</b> Le backend
   * refuse la publication tant qu'il manque une variante active, un prix ou
   * une photo. Laisser l'utilisateur cliquer pour découvrir laquelle des trois
   * fait défaut, c'est lui faire deviner ce qu'on sait déjà.</p>
   */
  protected readonly manques = computed<Manques>(() => {
    const p = this.produit();
    const v = this.variantes();

    return {
      variante: !v.some((x) => x.statut === 'ACTIVE'),
      prix: !v.some((x) => x.paliers.length > 0),
      photo: !p || p.medias.length === 0,
    };
  });

  protected readonly publiable = computed(() => {
    const m = this.manques();
    return !m.variante && !m.prix && !m.photo;
  });

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<DetailProduit>(`/api/produits/administration/${this.id()}`).subscribe({
      next: (p) => {
        this.produit.set(p);
        this.chargerVariantes();
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Cette fiche produit n’a pas pu être chargée.'));
      },
    });
  }

  private chargerVariantes(): void {
    this.http.get<Variante[]>(`/api/produits/${this.id()}/variantes`).subscribe({
      next: (v) => {
        this.variantes.set(v);
        this.chargement.set(false);
      },
      error: () => {
        // La fiche reste lisible sans ses variantes : on n'efface pas ce qui
        // s'affiche déjà pour un morceau manquant.
        this.variantes.set([]);
        this.chargement.set(false);
      },
    });
  }

  // -------------------------------------------------------------------------
  // Variantes et prix
  // -------------------------------------------------------------------------

  protected ajouterVariante(): void {
    if (this.action()) {
      return;
    }
    this.action.set('variante');
    this.erreurForm.set(null);

    this.http
      .post<Variante>(`/api/produits/${this.id()}/variantes`, {
        sku: this.sku().trim(),
        libelle: this.libelle().trim(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.formVariante.set(false);
          this.sku.set('');
          this.libelle.set('');
          this.chargerVariantes();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La variante n’a pas pu être ajoutée.'));
        },
      });
  }

  protected ouvrirPalier(varianteId: number): void {
    this.formPalier.set(varianteId);
    this.quantiteMin.set(1);
    this.quantiteMax.set(null);
    this.prix.set(null);
    this.erreurForm.set(null);
  }

  protected definirPalier(): void {
    const varianteId = this.formPalier();
    if (varianteId === null || this.action()) {
      return;
    }
    this.action.set('palier');
    this.erreurForm.set(null);

    this.http
      .put<Variante>(`/api/produits/${this.id()}/variantes/${varianteId}/paliers`, {
        quantiteMin: this.quantiteMin(),
        // `null` explicite, pas une clé absente : c'est ce qui signifie
        // « et au-delà », et le dernier palier d'une grille doit rester ouvert.
        quantiteMax: this.quantiteMax(),
        prixUnitaire: this.prix(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.formPalier.set(null);
          this.chargerVariantes();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'Le prix n’a pas pu être enregistré.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Photos
  // -------------------------------------------------------------------------

  protected televerser(evenement: Event): void {
    const champ = evenement.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    if (!fichier || this.action()) {
      return;
    }

    this.action.set('photo');
    this.erreur.set(null);

    const corps = new FormData();
    corps.append('fichier', fichier);
    // La première photo devient la principale : un produit sans photo
    // principale n'a rien à afficher en liste.
    corps.append('principal', String((this.produit()?.medias.length ?? 0) === 0));

    // ⚠️ Aucun Content-Type posé à la main. Le navigateur doit écrire lui-même
    // `multipart/form-data; boundary=…` — le fixer écraserait la frontière et
    // le serveur ne saurait plus découper les parties.
    this.http.post(`/api/produits/${this.id()}/medias`, corps).subscribe({
      next: () => {
        this.action.set(null);
        // Le champ est vidé : sans cela, redéposer LE MÊME fichier ne
        // déclenche aucun événement, et l'utilisateur croit à une panne.
        champ.value = '';
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        champ.value = '';
        this.erreur.set(message(e, 'La photo n’a pas pu être envoyée.'));
      },
    });
  }

  protected supprimerMedia(mediaId: number): void {
    if (this.action()) {
      return;
    }
    this.action.set('photo');

    this.http.delete(`/api/produits/${this.id()}/medias/${mediaId}`).subscribe({
      next: () => {
        this.action.set(null);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(message(e, 'La photo n’a pas pu être supprimée.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Cycle de vie
  // -------------------------------------------------------------------------

  protected publier(): void {
    this.changerEtat('publication', 'post');
  }

  protected depublier(): void {
    this.changerEtat('publication', 'delete');
  }

  protected archiver(): void {
    this.changerEtat('archivage', 'post');
  }

  private changerEtat(chemin: string, methode: 'post' | 'delete'): void {
    if (this.action()) {
      return;
    }
    this.action.set('etat');
    this.erreur.set(null);

    const url = `/api/produits/${this.id()}/${chemin}`;
    const requete =
      methode === 'post'
        ? this.http.post<DetailProduit>(url, null)
        : this.http.delete<DetailProduit>(url);

    requete.subscribe({
      next: (p) => {
        this.action.set(null);
        this.produit.set(p);
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(message(e, 'Le statut n’a pas pu être changé.'));
      },
    });
  }

  protected retour(): void {
    void this.router.navigate(['/produits']);
  }

  protected badge(statut: string): string {
    switch (statut) {
      case 'PUBLIE':
        return 'gu-badge--succes';
      case 'ARCHIVE':
        return 'gu-badge--danger';
      case 'MASQUE':
        return 'gu-badge--alerte';
      default:
        return 'gu-badge--neutre';
    }
  }
}

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Service momentanément indisponible. Réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
