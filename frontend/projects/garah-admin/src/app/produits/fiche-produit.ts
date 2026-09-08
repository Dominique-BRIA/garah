import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  aplatirCategories,
  Attribut,
  Categorie,
  DetailProduit,
  EtatStock,
  Icone,
  Manques,
  messageErreur,
  montantLisible,
  OptionCategorie,
  PalierPrix,
  ServiceSession,
  Variante,
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
  /*
   * La creation par GRILLE : on choisit des valeurs de dimensions, le serveur
   * compose toutes les combinaisons. C'est le chemin recommande — SKU et
   * intitules sont alors coherents par construction, la ou quatre saisies
   * manuelles sont quatre occasions de diverger.
   */
  | { readonly quoi: 'variante-grille' }
  | { readonly quoi: 'variante-edition'; readonly varianteId: number }
  | { readonly quoi: 'palier-creation'; readonly varianteId: number }
  | { readonly quoi: 'palier-prix'; readonly varianteId: number; readonly palierId: number }
  /*
   * Les trois gestes de stock. Ils se ressemblent — un nombre et un texte —
   * et ne veulent PAS dire la meme chose :
   *   reception   ce qui vient d'arriver, a AJOUTER
   *   inventaire  ce qu'on a REELLEMENT compte
   *   seuil       a partir de quand prevenir
   * Les confondre ferait passer un inventaire de 8 pour une livraison de 8.
   */
  | { readonly quoi: 'stock-reception'; readonly varianteId: number }
  | { readonly quoi: 'stock-inventaire'; readonly varianteId: number }
  | { readonly quoi: 'stock-seuil'; readonly varianteId: number };

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

  // --- La création par grille ---
  //
  // Le référentiel des dimensions, chargé à la première ouverture seulement :
  // la plupart des visites de cette page ne créent aucune déclinaison.
  protected readonly attributs = signal<readonly Attribut[]>([]);
  protected readonly chargementAttributs = signal(false);
  protected readonly valeursChoisies = signal<ReadonlySet<number>>(new Set());

  // --- Champs d'un palier ---
  protected readonly quantiteMin = signal(1);
  protected readonly quantiteMax = signal<number | null>(null);
  protected readonly prix = signal<number | null>(null);

  // --- Le stock de chaque déclinaison ---
  protected readonly stocks = signal<readonly EtatStock[]>([]);
  protected readonly quantiteStock = signal<number | null>(null);
  protected readonly motifStock = signal('');

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
        this.chargerStock();
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Cette fiche produit n’a pas pu être chargée.'));
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
   * Le stock de chaque déclinaison.
   *
   * <p>🎯 C'est en regardant un produit qu'on se demande combien il en reste.
   * Obliger à quitter la fiche, ouvrir l'inventaire et y retrouver la
   * déclinaison, c'est séparer deux questions qu'on se pose ensemble.</p>
   *
   * <p>Chargé sans bloquer : la fiche reste utilisable si le stock manque, et
   * un compte sans {@code STOCK_CONSULTER} ne déclenche même pas l'appel — il
   * répondrait 403 à chaque ouverture.</p>
   */
  protected chargerStock(): void {
    if (!this.session.peut('STOCK_CONSULTER')) {
      return;
    }

    this.http.get<EtatStock[]>(`/api/stock/produits/${this.id()}`).subscribe({
      next: (etats) => this.stocks.set(etats),
      error: () => this.stocks.set([]),
    });
  }

  /** Le stock d'une déclinaison, ou `null` s'il n'a pas pu être lu. */
  protected stockDe(varianteId: number): EtatStock | null {
    return this.stocks().find((s) => s.varianteId === varianteId) ?? null;
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

  // -------------------------------------------------------------------------
  // La création par grille
  // -------------------------------------------------------------------------

  /**
   * Ouvre le choix des dimensions.
   *
   * <p>Le référentiel est chargé à la première ouverture seulement : la
   * plupart des visites de cette page ne créent aucune déclinaison.</p>
   */
  protected ouvrirGrille(): void {
    this.valeursChoisies.set(new Set());
    this.erreurForm.set(null);
    this.formulaire.set({ quoi: 'variante-grille' });

    if (this.attributs().length === 0) {
      this.chargementAttributs.set(true);
      this.http.get<Attribut[]>('/api/attributs').subscribe({
        next: (a) => {
          this.attributs.set(a);
          this.chargementAttributs.set(false);
        },
        error: () => {
          this.attributs.set([]);
          this.chargementAttributs.set(false);
        },
      });
    }
  }

  protected basculerValeur(id: number): void {
    this.valeursChoisies.update((courant) => {
      const suivant = new Set(courant);
      if (!suivant.delete(id)) {
        suivant.add(id);
      }
      return suivant;
    });
  }

  protected valeurChoisie(id: number): boolean {
    return this.valeursChoisies().has(id);
  }

  /**
   * Combien de déclinaisons la grille produira.
   *
   * <p>Le produit des dimensions retenues : deux tailles et deux couleurs font
   * quatre déclinaisons. Le dire <b>avant</b> le clic évite d'en créer
   * cinquante par inadvertance — un catalogue se salit beaucoup plus vite
   * qu'il ne se nettoie.</p>
   */
  protected readonly nbCombinaisons = computed(() => {
    const dimensions = this.dimensionsRetenues();
    return dimensions.length === 0
      ? 0
      : dimensions.reduce((total, valeurs) => total * valeurs.length, 1);
  });

  /**
   * Les valeurs cochées, groupées par dimension.
   *
   * <p>⚠️ L'ordre des dimensions suit celui du référentiel, et il fixe l'ordre
   * dans le SKU : {@code CH-2026-42-BLANC} plutôt que
   * {@code CH-2026-BLANC-42}. Les deux sont valides ; mélanger les deux dans
   * un même catalogue le rend illisible.</p>
   */
  private readonly dimensionsRetenues = computed<number[][]>(() => {
    const choisies = this.valeursChoisies();

    return this.attributs()
      .map((a) => a.valeurs.filter((v) => choisies.has(v.id)).map((v) => v.id))
      .filter((valeurs) => valeurs.length > 0);
  });

  protected creerGrille(): void {
    if (this.action() || this.nbCombinaisons() === 0) {
      return;
    }
    this.action.set('variante');
    this.erreurForm.set(null);

    this.http
      .post<Variante[]>(`/api/produits/${this.id()}/variantes/grille`, {
        dimensions: this.dimensionsRetenues(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.fermer();
          this.chargerVariantes();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(messageErreur(e, 'Les déclinaisons n’ont pas pu être créées.'));
        },
      });
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

  /**
   * Ouvre l'un des trois gestes de stock.
   *
   * <p>⚠️ Ils prennent tous un nombre, et ce nombre ne veut pas dire la même
   * chose :</p>
   * <ul>
   *   <li><b>réception</b> — ce qui vient d'arriver, à <b>ajouter</b> ;</li>
   *   <li><b>inventaire</b> — ce qu'on a <b>réellement compté</b> ;</li>
   *   <li><b>seuil</b> — à partir de quand prévenir.</li>
   * </ul>
   *
   * <p>Les deux premiers pré-remplissent différemment, et c'est délibéré : une
   * réception part vide (on saisit ce qu'on reçoit), un inventaire part du
   * disponible courant (on corrige ce qui est affiché).</p>
   */
  protected ouvrirStock(
    quoi: 'stock-reception' | 'stock-inventaire' | 'stock-seuil',
    v: Variante,
  ): void {
    const etat = this.stockDe(v.id);

    this.quantiteStock.set(
      quoi === 'stock-reception' ? null
        : quoi === 'stock-inventaire' ? (etat?.disponible ?? 0)
        : (etat?.seuilAlerte ?? 0),
    );
    this.motifStock.set('');
    this.erreurForm.set(null);
    this.formulaire.set({ quoi, varianteId: v.id });
  }

  protected enregistrerStock(varianteId: number): void {
    const f = this.formulaire();
    if (!f || this.action() || !f.quoi.startsWith('stock-')) {
      return;
    }

    this.action.set('stock');
    this.erreurForm.set(null);

    const appel =
      f.quoi === 'stock-reception'
        ? this.http.post<EtatStock>(`/api/stock/${varianteId}/entrees`, {
            quantite: this.quantiteStock(),
            commentaire: this.motifStock().trim(),
          })
        : f.quoi === 'stock-inventaire'
          ? this.http.post<EtatStock>(`/api/stock/${varianteId}/ajustements`, {
              quantiteReelle: this.quantiteStock(),
              motif: this.motifStock().trim(),
            })
          : this.http.put<EtatStock>(`/api/stock/${varianteId}/seuil`, {
              seuilAlerte: this.quantiteStock(),
            });

    appel.subscribe({
      next: () => {
        this.action.set(null);
        this.fermer();
        // On recharge tout le stock du produit plutôt que de reposer la seule
        // réponse : elle ne porte pas la désignation, qui vient du catalogue.
        this.chargerStock();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreurForm.set(messageErreur(e, 'Le stock n’a pas pu être enregistré.'));
      },
    });
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
          this.erreurForm.set(messageErreur(e, 'La fiche n’a pas pu être enregistrée.'));
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
        this.chargerStock();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurForm.set(messageErreur(e, 'La déclinaison n’a pas pu être ajoutée.'));
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
          this.erreurForm.set(messageErreur(e, 'La déclinaison n’a pas pu être enregistrée.'));
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
        this.erreur.set(messageErreur(e, 'Le statut de la déclinaison n’a pas pu être changé.'));
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
          this.erreurForm.set(messageErreur(e, 'Le prix n’a pas pu être enregistré.'));
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
          this.erreurForm.set(messageErreur(e, 'Le prix n’a pas pu être modifié.'));
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
          this.erreur.set(messageErreur(e, 'Le prix n’a pas pu être retiré.'));
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
        this.erreur.set(messageErreur(e, 'La photo n’a pas pu être envoyée.'));
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
        this.erreur.set(messageErreur(e, 'La photo n’a pas pu être supprimée.'));
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
        this.erreur.set(messageErreur(e, 'Le statut n’a pas pu être changé.'));
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

  /** « 5 000 FCFA ». Le formatage vit dans `garah-ui`, pour ne pas dériver
   *  d'un écran à l'autre. */
  protected montant(valeur: number, devise: string): string {
    return montantLisible(valeur, devise);
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

