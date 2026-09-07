import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  BasculeVue,
  Icone,
  Page,
  Pagination,
  ReponseErreur,
  ResumeProduit,
  ResultatSuppression,
  Refus,
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

  // --- La sélection multiple --------------------------------------------------
  //
  // Un Set d'identifiants, et non un drapeau posé sur chaque produit : la liste
  // est rechargée à chaque page et à chaque recherche, et des drapeaux portés
  // par les objets disparaîtraient avec eux.
  protected readonly selection = signal<ReadonlySet<number>>(new Set());

  protected readonly nbSelectionnes = computed(() => this.selection().size);

  /** Toutes les lignes visibles sont-elles cochées ? */
  protected readonly toutSelectionne = computed(() => {
    const liste = this.produits();
    const choisis = this.selection();
    return liste.length > 0 && liste.every((p) => choisis.has(p.id));
  });

  protected readonly suppressionEnCours = signal(false);

  /** Ce qui n'a pas pu être supprimé, et pourquoi. Vidé à la prochaine action. */
  protected readonly refus = signal<readonly Refus[]>([]);

  constructor() {
    this.charger();
  }

  protected estSelectionne(id: number): boolean {
    return this.selection().has(id);
  }

  protected basculerSelection(id: number): void {
    this.selection.update((courant) => {
      const suivant = new Set(courant);
      if (!suivant.delete(id)) {
        suivant.add(id);
      }
      return suivant;
    });
  }

  /**
   * Coche ou décoche toutes les lignes VISIBLES.
   *
   * <p>⚠️ Visibles, pas « toutes celles du catalogue ». Une case qui
   * sélectionnerait des produits qu'on n'a pas sous les yeux — ceux des pages
   * suivantes, ou ceux qu'un filtre écarte — ferait supprimer sans avoir
   * regardé. La sélection des autres pages est conservée, elle, parce que la
   * décocher à chaque changement de page serait tout aussi surprenant.</p>
   */
  protected basculerTout(): void {
    const visibles = this.produits().map((p) => p.id);
    const tout = this.toutSelectionne();

    this.selection.update((courant) => {
      const suivant = new Set(courant);
      for (const id of visibles) {
        if (tout) {
          suivant.delete(id);
        } else {
          suivant.add(id);
        }
      }
      return suivant;
    });
  }

  protected viderSelection(): void {
    this.selection.set(new Set());
    this.refus.set([]);
  }

  /**
   * Supprime la sélection, après confirmation.
   *
   * <p>🎯 <b>La réussite est PARTIELLE, et l'écran doit le dire.</b> Sur dix
   * produits cochés, deux peuvent avoir déjà été vendus : l'API supprime les
   * huit autres et nomme les deux qui restent. Afficher « échec » masquerait
   * huit suppressions bien réelles ; afficher « succès » mentirait sur deux.</p>
   */
  protected supprimerSelection(): void {
    const ids = [...this.selection()];
    if (ids.length === 0 || this.suppressionEnCours()) {
      return;
    }

    // ⚠️ `confirm` est laid, mais il BLOQUE. Une suppression définitive ne doit
    // pas pouvoir partir d'un clic distrait. À remplacer par une boîte de
    // dialogue maison, jamais par rien.
    // ⚠️ PAS `message` : ce nom est déjà celui de la fonction du module qui
    //    traduit une erreur HTTP. Une variable locale la masquerait, et
    //    l'appel `message(e)` du gestionnaire d'erreur ci-dessous tenterait
    //    d'appeler une chaîne de caractères.
    const question = ids.length === 1
      ? 'Supprimer définitivement ce produit ?'
      : `Supprimer définitivement ces ${ids.length} produits ?`;
    if (!confirm(question)) {
      return;
    }

    this.suppressionEnCours.set(true);
    this.refus.set([]);

    // `delete` avec un corps : Angular l'expose par l'option `body`, la seule
    // façon d'en envoyer un sur cette méthode.
    this.http
      .delete<ResultatSuppression>('/api/produits', { body: { ids } })
      .subscribe({
        next: (resultat) => {
          this.suppressionEnCours.set(false);
          this.refus.set(resultat.refuses);

          // Seuls les refusés restent cochés : la sélection devient la liste
          // de ce qu'il reste à traiter, au lieu d'être à reconstituer.
          this.selection.set(new Set(resultat.refuses.map((r) => r.id)));
          this.charger();
        },
        error: (e: unknown) => {
          this.suppressionEnCours.set(false);
          this.erreur.set(message(e));
        },
      });
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
