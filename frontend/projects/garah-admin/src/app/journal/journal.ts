import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  FiltresAudit,
  Icone,
  LigneAudit,
  messageErreur,
  Page,
  Pagination,
} from 'garah-ui';

const TAILLE_PAGE = 50;

/**
 * Le journal des actions internes.
 *
 * <h2>🎯 La question à laquelle cet écran répond</h2>
 *
 * <p>« Qui a annulé cette commande ? », « qui a mis ce taux à 30 % ? », « qui
 * a remboursé ce client ? ». Elles se posent toutes des semaines après le
 * geste, quand la table concernée ne porte plus que son état courant.</p>
 *
 * <h2>⚠️ Trois journaux, et celui-ci n'est pas les deux autres</h2>
 *
 * <pre>
 * ce que fait un CLIENT          → son parcours, mesuré à part
 * les faits d'AUTHENTIFICATION   → le journal de sécurité
 * les actions INTERNES           → ici
 * </pre>
 *
 * <p>Les fondre en un seul rendrait les trois inexploitables : les quelques
 * gestes internes d'une journée disparaîtraient sous le trafic de la
 * boutique.</p>
 *
 * <h2>⚠️ Ce que l'écran affiche est sensible</h2>
 *
 * <p>Les clichés « avant / après » contiennent l'état des objets modifiés :
 * potentiellement n'importe quelle donnée du système. La route est réservée
 * au module sécurité, donc au seul super-administrateur — et un chef de
 * service, lui, reçoit une vue allégée qui ne les porte pas.</p>
 */
@Component({
  selector: 'ga-journal',
  imports: [FormsModule, Icone, Pagination],
  templateUrl: './journal.html',
  styleUrl: './journal.scss',
})
export class Journal {
  private readonly http = inject(HttpClient);

  protected readonly lignes = signal<readonly LigneAudit[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  /** Les valeurs réellement présentes — on ne propose pas un filtre vide. */
  protected readonly actions = signal<readonly string[]>([]);
  protected readonly entites = signal<readonly string[]>([]);

  protected readonly action = signal('');
  protected readonly entite = signal('');

  /** La ligne dépliée : les clichés ne s'affichent qu'à la demande. */
  protected readonly ouverte = signal<number | null>(null);

  constructor() {
    this.chargerLesFiltres();
    this.charger();
  }

  private chargerLesFiltres(): void {
    // Leur absence n'empêche pas de lire le journal : on ne bloque pas
    // l'écran entier pour des listes déroulantes.
    this.http.get<FiltresAudit>('/api/surveillance/audit/filtres').subscribe({
      next: (f) => {
        this.actions.set(f.actions);
        this.entites.set(f.entites);
      },
      error: () => {
        this.actions.set([]);
        this.entites.set([]);
      },
    });
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    let parametres = new HttpParams()
      .set('page', this.page())
      .set('taille', this.taille);

    // ⚠️ On n'envoie pas un filtre vide : le serveur chercherait les actions
    //    dont le code est « », c'est-à-dire aucune, et l'écran dirait « rien à
    //    afficher » sur un journal plein.
    if (this.action()) {
      parametres = parametres.set('action', this.action());
    }
    if (this.entite()) {
      parametres = parametres.set('entite', this.entite());
    }

    this.http
      .get<Page<LigneAudit>>('/api/surveillance/audit', { params: parametres })
      .subscribe({
        next: (p) => {
          this.lignes.set(p.content);
          this.total.set(p.page.totalElements);
          this.totalPages.set(p.page.totalPages);
          this.chargement.set(false);
        },
        error: (e: unknown) => {
          this.chargement.set(false);
          this.erreur.set(messageErreur(e, 'Le journal n’a pas pu être chargé.'));
        },
      });
  }

  protected filtrer(): void {
    // Retour à la première page : rester en page 4 après avoir filtré
    // afficherait un écran vide alors qu'il y a des résultats.
    this.page.set(0);
    this.ouverte.set(null);
    this.charger();
  }

  protected effacer(): void {
    this.action.set('');
    this.entite.set('');
    this.filtrer();
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.ouverte.set(null);
    this.charger();
  }

  protected basculer(ligne: LigneAudit): void {
    this.ouverte.set(this.ouverte() === ligne.id ? null : ligne.id);
  }

  protected aDesDetails(ligne: LigneAudit): boolean {
    return !!(ligne.ancienneValeur || ligne.nouvelleValeur);
  }

  // ---------------------------------------------------------------------------
  // Ce qui a changé
  // ---------------------------------------------------------------------------

  /**
   * Les champs qui ont réellement changé, et eux seuls.
   *
   * <h2>🎯 Le journal affichait deux blocs de JSON côte à côte</h2>
   *
   * <p>Deux objets de dix champs, identiques à un près, posés l'un à côté de
   * l'autre. Il fallait les lire ligne à ligne pour trouver la différence — et
   * pour quelqu'un qui n'écrit pas de logiciel, <b>accolades comprises</b>,
   * il n'y avait rien à lire du tout.</p>
   *
   * <p>C'est d'autant plus grave ici qu'un journal d'audit sert précisément
   * dans les moments tendus : un litige, un écart de caisse, un soupçon. Le
   * moment où l'on peut le moins se permettre de déchiffrer.</p>
   *
   * <p>On ne rend donc que les champs qui <b>diffèrent</b>. Quand rien ne
   * diffère, on le dit — c'est une information, et elle se produit : une
   * modification enregistrée alors que rien n'a bougé.</p>
   *
   * <p>⚠️ La comparaison se fait sur la forme JSON des valeurs et non sur
   * {@code ===} : deux tableaux de mêmes éléments sont deux objets distincts
   * en mémoire, et tout aurait été signalé comme modifié.</p>
   */
  protected changements(ligne: LigneAudit): readonly Changement[] {
    const avant = objet(ligne.ancienneValeur);
    const apres = objet(ligne.nouvelleValeur);

    // L'ordre suit APRÈS d'abord : c'est l'état qui vaut aujourd'hui, et donc
    // celui dont l'ordre des champs a un sens pour qui lit.
    const cles = [...new Set([...Object.keys(apres), ...Object.keys(avant)])];

    return cles
      .filter((c) => JSON.stringify(avant[c]) !== JSON.stringify(apres[c]))
      .map((c) => ({
        champ: champLisible(c),
        avant: valeurLisible(avant[c]),
        apres: valeurLisible(apres[c]),
        nature: !(c in avant) ? 'ajoute' : !(c in apres) ? 'retire' : 'modifie',
      }));
  }

  /** Le geste a bien un cliché, mais aucun champ n'y a bougé. */
  protected sansChangement(ligne: LigneAudit): boolean {
    return this.aDesDetails(ligne) && this.changements(ligne).length === 0;
  }

  // ---------------------------------------------------------------------------
  // Affichage
  // ---------------------------------------------------------------------------

  /**
   * Le code du geste, rendu lisible.
   *
   * <p>{@code REGLEMENT_CONFIRMER} devient « Règlement confirmer ». Ce n'est
   * pas une traduction — il n'y a pas de table de libellés, et en inventer une
   * demanderait de la tenir à jour à chaque geste ajouté, sous peine
   * d'afficher un code brut le jour où on l'oublie.</p>
   */
  protected libelle(code: string): string {
    const mots = code.toLowerCase().replace(/_/g, ' ');
    return mots.charAt(0).toUpperCase() + mots.slice(1);
  }

  protected date(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

/** Un champ qui a bougé entre les deux clichés. */
export interface Changement {
  /** Le nom du champ, écrit pour être lu. */
  readonly champ: string;
  readonly avant: string;
  readonly apres: string;
  /** `ajoute` : absent avant. `retire` : absent après. */
  readonly nature: 'ajoute' | 'retire' | 'modifie';
}

/** Le cliché, en objet. Un JSON illisible ne fait pas échouer l'écran. */
function objet(json: string | null): Record<string, unknown> {
  if (!json) {
    return {};
  }
  try {
    const v: unknown = JSON.parse(json);
    return v && typeof v === 'object' && !Array.isArray(v)
      ? (v as Record<string, unknown>)
      : { valeur: v };
  } catch {
    // Le journal est immuable : on n'y touche pas, même pour le réparer.
    return { valeur: json };
  }
}

/**
 * Les noms de champs qui ne se devinent pas.
 *
 * <p>⚠️ Volontairement COURTE. Elle ne couvre que ce qu'un découpage
 * automatique écrirait mal — les accents, et les quelques mots dont le nom
 * technique ne dit pas la chose. Tout le reste passe par {@link champLisible},
 * qui sépare les mots et met une majuscule.</p>
 *
 * <p>Une table exhaustive devrait être tenue à jour à chaque champ ajouté,
 * sous peine d'afficher un code brut le jour où on l'oublie. Ici, l'oubli
 * donne « Date embauche » au lieu de « Date d'embauche » : lisible, juste un
 * peu sec.</p>
 */
const NOMS: Readonly<Record<string, string>> = {
  prenom: 'Prénom',
  telephone: 'Téléphone',
  email: 'Adresse e-mail',
  dateEmbauche: 'Date d’embauche',
  date_embauche: 'Date d’embauche',
  motDePasse: 'Mot de passe',
  prixUnitaire: 'Prix unitaire',
  quantiteMin: 'À partir de',
  quantiteMax: 'Jusqu’à',
  profils: 'Profils',
  principal: 'Profil principal',
  chef: 'Chef de service',
  matricule: 'Matricule',
  statut: 'Statut',
  categorie: 'Catégorie',
  quantite: 'Quantité',
  seuilAlerte: 'Seuil d’alerte',
  commentaire: 'Commentaire',
  reference: 'Référence',
};

/** Le nom d'un champ, écrit pour être lu. */
function champLisible(cle: string): string {
  const connu = NOMS[cle];
  if (connu) {
    return connu;
  }
  // `dateDerniereConnexion` et `date_derniere_connexion` donnent la même chose.
  const mots = cle
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .toLowerCase()
    .trim();
  return mots.charAt(0).toUpperCase() + mots.slice(1);
}

/**
 * Une valeur, écrite pour être lue.
 *
 * <p>⚠️ Les identifiants restent des identifiants — on écrit « n° 3 » et non
 * le nom du profil. Le résoudre demanderait d'aller le chercher, et le nom
 * d'AUJOURD'HUI serait affiché sur un geste d'il y a six mois. Un journal
 * d'audit doit montrer ce qui a été enregistré, pas ce qui est vrai
 * maintenant.</p>
 */
function valeurLisible(valeur: unknown): string {
  if (valeur === null || valeur === undefined || valeur === '') {
    return '—';
  }
  if (typeof valeur === 'boolean') {
    return valeur ? 'Oui' : 'Non';
  }
  if (Array.isArray(valeur)) {
    return valeur.length === 0 ? 'aucun' : valeur.map(valeurLisible).join(', ');
  }
  if (typeof valeur === 'object') {
    return JSON.stringify(valeur);
  }

  const texte = String(valeur);

  // Une liste d'identifiants arrive parfois sous forme de CHAÎNE — « [1,2] ».
  // Laissée telle quelle, elle affiche ses crochets à l'écran.
  if (/^\[.*\]$/.test(texte)) {
    try {
      const liste: unknown = JSON.parse(texte);
      if (Array.isArray(liste)) {
        return liste.length === 0
          ? 'aucun'
          : liste.map((n) => `n° ${n}`).join(', ');
      }
    } catch {
      /* pas une liste : on garde le texte */
    }
  }

  // Une date ISO se lit mal. Le test est volontairement strict : « 2026-09 »
  // pourrait être un numéro de contrat, et le muer en date serait un mensonge.
  if (/^\d{4}-\d{2}-\d{2}(T|$)/.test(texte)) {
    const d = new Date(texte);
    if (!Number.isNaN(d.getTime())) {
      return texte.includes('T')
        ? d.toLocaleString('fr-FR', {
            day: '2-digit', month: '2-digit', year: 'numeric',
            hour: '2-digit', minute: '2-digit',
          })
        : d.toLocaleDateString('fr-FR');
    }
  }

  return texte;
}
