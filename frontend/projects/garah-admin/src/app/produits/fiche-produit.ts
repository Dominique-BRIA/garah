import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  Categorie,
  DetailProduit,
  Icone,
  Manques,
  OptionCategorie,
  PalierPrix,
  ReponseErreur,
  ServiceSession,
  Variante,
  aplatirCategories,
} from 'garah-ui';

/**
 * Le formulaire actuellement ouvert.
 *
 * <p>🎯 <b>Un seul à la fois, et c'est tout l'intérêt du type.</b> Avec un
 * booléen par formulaire, rien n'empêche d'en avoir trois ouverts en même
 * temps — donc trois boutons « Enregistrer » à l'écran, dont on ne sait plus
 * lequel valide quoi. Ici, ouvrir un formulaire ferme le précédent par
 * construction : le signal ne peut contenir qu'une valeur.</p>
 */
type Formulaire =
  | { readonly quoi: 'produit' }
  | { readonly quoi: 'variante-creation' }
  | { readonly quoi: 'variante-edition'; readonly varianteId: number }
  | { readonly quoi: 'palier-creation'; readonly varianteId: number }
  | { readonly quoi: 'palier-prix'; readonly varianteId: number; readonly palierId: number };

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
  protected readonly categories = signal<readonly OptionCategorie[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly erreurForm = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly formulaire = signal<Formulaire | null>(null);

  // --- Champs de la fiche ---
  protected readonly nom = signal('');
  protected readonly description = signal('');
  protected readonly categorieId = signal<number | null>(null);

  // --- Champs d'une déclinaison ---
  protected readonly sku = signal('');
  protected readonly libelle = signal('');

  // --- Champs d'un palier ---
  protected readonly quantiteMin = signal(1);
  protected readonly quantiteMax = signal<number | null>(null);
  protected readonly prix = signal<number | null>(null);

  constructor() {
    // `input.required` n'est pas lisible dans le constructeur : on charge au
    // premier rendu, quand la valeur est posée.
    queueMicrotask(() => this.charger());
  }

  /**
   * Ce qui manque pour publier (I-12).
   *
   * <p>🎯 <b>On le dit AVANT le clic, pas après l'erreur.</b> Le backend
   * refuse la publication tant qu'il manque une déclinaison active, un prix ou
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

  // -------------------------------------------------------------------------
  // Chargement
  // -------------------------------------------------------------------------

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
        // La fiche reste lisible sans ses déclinaisons : on n'efface pas ce
        // qui s'affiche déjà pour un morceau manquant.
        this.variantes.set([]);
        this.chargement.set(false);
      },
    });
  }

  /**
   * Remplace une déclinaison dans la liste.
   *
   * <p>Les routes de modification renvoient la déclinaison rechargée, grille
   * comprise. La reposer sur place évite un aller-retour de plus — et surtout
   * évite que toute la page ne clignote après un changement de prix.</p>
   */
  private remplacer(mise: Variante): void {
    this.variantes.update((liste) => liste.map((v) => (v.id === mise.id ? mise : v)));
  }

  // -------------------------------------------------------------------------
  // Ouverture des formulaires
  // -------------------------------------------------------------------------

  protected ouvrirProduit(): void {
    const p = this.produit();
    if (!p) {
      return;
    }
    this.nom.set(p.nom);
    this.description.set(p.description ?? '');
    this.categorieId.set(p.categorie?.id ?? null);
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'produit' });

    // Chargées à la première ouverture seulement : la plupart des visites de
    // cette page ne modifient pas la catégorie.
    if (this.categories().length === 0) {
      this.http.get<Categorie[]>('/api/categories').subscribe({
        next: (arbre) => this.categories.set(aplatirCategories(arbre)),
        error: () => this.categories.set([]),
      });
    }
  }

  protected ouvrirCreationVariante(): void {
    this.sku.set('');
    this.libelle.set('');
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'variante-creation' });
  }

  protected ouvrirEditionVariante(v: Variante): void {
    this.sku.set(v.sku);
    this.libelle.set(v.libelle);
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'variante-edition', varianteId: v.id });
  }

  /**
   * Ouvre le formulaire d'un nouveau palier, <b>déjà positionné</b>.
   *
   * <p>🎯 La grille reprend là où elle s'arrête. Les paliers ne peuvent pas se
   * chevaucher : « 1 à 6 » suivi de « 6 à 10 » se recouvrent sur 6, et le
   * serveur refuse. Proposer 7 d'office évite l'erreur — et montre au passage
   * comment une grille s'enchaîne, ce qu'aucune phrase d'aide n'explique aussi
   * bien.</p>
   */
  protected ouvrirCreationPalier(v: Variante): void {
    const bornes = v.paliers.map((p) => p.quantiteMax ?? 0);
    const fin = bornes.length > 0 ? Math.max(...bornes) : 0;

    this.quantiteMin.set(fin + 1);
    this.quantiteMax.set(null);
    this.prix.set(null);
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'palier-creation', varianteId: v.id });
  }

  protected ouvrirPrix(v: Variante, palier: PalierPrix): void {
    this.prix.set(palier.prixUnitaire);
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'palier-prix', varianteId: v.id, palierId: palier.id });
  }

  protected fermer(): void {
    this.formulaire.set(null);
    this.erreurForm.set(null);
  }

  /** Ce formulaire-là est-il ouvert ? */
  protected ouvert(quoi: Formulaire['quoi'], varianteId?: number, palierId?: number): boolean {
    const f = this.formulaire();
    if (!f || f.quoi !== quoi) {
      return false;
    }
    if (varianteId !== undefined && 'varianteId' in f && f.varianteId !== varianteId) {
      return false;
    }
    return !(palierId !== undefined && 'palierId' in f && f.palierId !== palierId);
  }

  // -------------------------------------------------------------------------
  // La fiche
  // -------------------------------------------------------------------------

  protected enregistrerProduit(): void {
    if (this.action()) {
      return;
    }
    this.action.set('produit');
    this.erreurForm.set(null);

    this.http
      .put<DetailProduit>(`/api/produits/${this.id()}`, {
        nom: this.nom().trim(),
        // Chaîne vide → `null` : « pas de description » et « description
        // vidée » doivent se ranger au même endroit en base.
        description: this.description().trim() || null,
        categorieId: this.categorieId(),
      })
      .subscribe({
        next: (p) => {
          this.action.set(null);
          this.produit.set(p);
          this.fermer();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La fiche n’a pas pu être enregistrée.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // Les déclinaisons
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
          this.fermer();
          this.chargerVariantes();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La déclinaison n’a pas pu être ajoutée.'));
        },
      });
  }

  protected enregistrerVariante(varianteId: number): void {
    if (this.action()) {
      return;
    }
    this.action.set('variante');
    this.erreurForm.set(null);

    this.http
      .put<Variante>(`/api/produits/${this.id()}/variantes/${varianteId}`, {
        sku: this.sku().trim(),
        libelle: this.libelle().trim(),
      })
      .subscribe({
        next: (v) => {
          this.action.set(null);
          this.remplacer(v);
          this.fermer();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'La déclinaison n’a pas pu être enregistrée.'));
        },
      });
  }

  protected basculerVariante(v: Variante): void {
    if (this.action()) {
      return;
    }
    const active = v.statut === 'ACTIVE';
    this.action.set('variante');
    this.erreur.set(null);

    const url = `/api/produits/${this.id()}/variantes/${v.id}/activation`;
    const requete = active
      ? this.http.delete<Variante>(url)
      : this.http.post<Variante>(url, null);

    requete.subscribe({
      next: (mise) => {
        this.action.set(null);
        this.remplacer(mise);
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(message(e, 'Le statut de la déclinaison n’a pas pu être changé.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Les prix
  // -------------------------------------------------------------------------

  protected definirPalier(varianteId: number): void {
    if (this.action()) {
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
        next: (v) => {
          this.action.set(null);
          this.remplacer(v);
          this.fermer();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'Le prix n’a pas pu être enregistré.'));
        },
      });
  }

  protected enregistrerPrix(varianteId: number, palierId: number): void {
    if (this.action()) {
      return;
    }
    this.action.set('palier');
    this.erreurForm.set(null);

    this.http
      .put<Variante>(
        `/api/produits/${this.id()}/variantes/${varianteId}/paliers/${palierId}`,
        { prixUnitaire: this.prix() },
      )
      .subscribe({
        next: (v) => {
          this.action.set(null);
          this.remplacer(v);
          this.fermer();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(message(e, 'Le prix n’a pas pu être modifié.'));
        },
      });
  }

  protected supprimerPalier(varianteId: number, palierId: number): void {
    if (this.action()) {
      return;
    }
    this.action.set('palier');
    this.erreur.set(null);

    this.http
      .delete<Variante>(`/api/produits/${this.id()}/variantes/${varianteId}/paliers/${palierId}`)
      .subscribe({
        next: (v) => {
          this.action.set(null);
          this.remplacer(v);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(message(e, 'Le prix n’a pas pu être retiré.'));
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

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

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

  /**
   * Un montant lisible : « 5 000 FCFA ».
   *
   * <p>Sans séparateur de milliers, 5000 et 50000 ne se distinguent qu'en
   * comptant les chiffres — dans une grille faite pour comparer des prix,
   * c'est exactement ce qu'il ne faut pas demander à l'œil.</p>
   */
  protected montant(valeur: number, devise: string): string {
    const nombre = new Intl.NumberFormat('fr-FR').format(valeur);
    return `${nombre} ${devise === 'XAF' ? 'FCFA' : devise}`;
  }

  /**
   * La grille monte-t-elle déjà jusqu'à l'infini ?
   *
   * <p>Un palier sans borne haute couvre toutes les quantités au-dessus de
   * lui : aucun autre ne peut plus s'y ajouter sans le chevaucher. On retire
   * donc le bouton, plutôt que d'ouvrir un formulaire qui ne peut qu'échouer.</p>
   */
  protected grilleFermee(v: Variante): boolean {
    return v.paliers.some((p) => p.quantiteMax === null);
  }

  protected decalage(niveau: number): string {
    return '— '.repeat(niveau);
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
