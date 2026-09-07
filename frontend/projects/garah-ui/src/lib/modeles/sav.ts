// =============================================================================
// Le service apres-vente : reclamations et retours
// =============================================================================
// La reclamation est la SEULE voie de recours du client apres paiement (D-12) :
// il ne peut plus annuler lui-meme, un humain examine. Ces ecrans sont donc la
// contrepartie de la regle d'annulation — sans eux, elle serait brutale.
// =============================================================================

export type StatutReclamation = 'OUVERTE' | 'EN_COURS' | 'RESOLUE' | 'FERMEE';

/**
 * Ce que chaque statut veut dire, et ce qu'il APPELLE comme geste.
 *
 * Le libelle seul ne suffit pas : « OUVERTE » ne dit pas si quelqu'un doit
 * agir. C'est pourtant la seule question que se pose celui qui ouvre la liste
 * le matin.
 */
export const STATUTS_RECLAMATION: readonly {
  readonly code: StatutReclamation;
  readonly libelle: string;
  readonly badge: string;
  /** Vrai si ce statut attend un geste de quelqu'un. */
  readonly agir: boolean;
}[] = [
  { code: 'OUVERTE', libelle: 'Ouverte', badge: 'gu-badge--alerte', agir: true },
  { code: 'EN_COURS', libelle: 'En cours', badge: 'gu-badge--info', agir: true },
  { code: 'RESOLUE', libelle: 'Résolue', badge: 'gu-badge--succes', agir: false },
  { code: 'FERMEE', libelle: 'Fermée', badge: 'gu-badge--neutre', agir: false },
];

/** Une reclamation telle qu'une liste l'affiche : SANS sa description. */
export interface ResumeReclamation {
  readonly id: number;
  readonly numero: string;
  readonly statut: StatutReclamation;
  readonly motif: string;
  readonly clientId: number;
  readonly clientCode: string | null;
  /** Nul si le client a disparu. La reclamation, elle, reste. */
  readonly clientNom: string | null;
  readonly commandeId: number | null;
  /** Nul si la reclamation ne porte sur aucune commande — c'est permis. */
  readonly commandeNumero: string | null;
  readonly responsableId: number | null;
  readonly dateCreation: string;
  readonly dateResolution: string | null;
}

/** Une reclamation complete, description comprise. */
export interface Reclamation {
  readonly id: number;
  readonly numero: string;
  readonly clientId: number;
  readonly commandeId: number | null;
  readonly responsableId: number | null;
  readonly motif: string;
  readonly description: string | null;
  readonly statut: StatutReclamation;
  readonly dateCreation: string;
  readonly dateResolution: string | null;
}

// -----------------------------------------------------------------------------
// Les retours
// -----------------------------------------------------------------------------

export type StatutRetour =
  | 'DEMANDE'
  | 'ACCEPTE'
  | 'REFUSE'
  | 'RECEPTIONNE'
  | 'VALIDE'
  | 'CLOTURE';

/**
 * Le cycle de vie d'un retour.
 *
 *   DEMANDE ──▶ ACCEPTE ──▶ RECEPTIONNE ──▶ VALIDE ──▶ CLOTURE
 *      │                          │
 *      └──▶ REFUSE                └── controle physique des articles
 *
 * ⚠️ Le remboursement ne part qu'a VALIDE, donc APRES que quelqu'un a ouvert
 * le colis et constate l'etat reel des articles. Jamais a DEMANDE.
 */
export const STATUTS_RETOUR: readonly {
  readonly code: StatutRetour;
  readonly libelle: string;
  readonly badge: string;
  /** Ce que ce statut attend de celui qui lit la liste. */
  readonly attendu: string;
}[] = [
  {
    code: 'DEMANDE',
    libelle: 'Demandé',
    badge: 'gu-badge--alerte',
    attendu: 'Décider si le retour est accepté.',
  },
  {
    code: 'ACCEPTE',
    libelle: 'Accepté',
    badge: 'gu-badge--info',
    attendu: 'Attendre que la marchandise revienne.',
  },
  {
    code: 'RECEPTIONNE',
    libelle: 'Réceptionné',
    badge: 'gu-badge--info',
    attendu: 'Contrôler les articles, puis rembourser.',
  },
  {
    code: 'VALIDE',
    libelle: 'Validé',
    badge: 'gu-badge--succes',
    attendu: 'Le client a été remboursé. Reste à clore.',
  },
  { code: 'CLOTURE', libelle: 'Clôturé', badge: 'gu-badge--neutre', attendu: '' },
  { code: 'REFUSE', libelle: 'Refusé', badge: 'gu-badge--danger', attendu: '' },
];

/**
 * Les transitions autorisees, DUPLIQUEES depuis le serveur — sciemment.
 *
 * ⚠️ Le serveur reste seul juge. Cette table ne sert qu'a ne pas proposer un
 * bouton qui echouera. Si la machine a etats du serveur change, cette table
 * doit suivre — sinon l'ecran propose un geste refuse.
 */
export const TRANSITIONS_RETOUR: Readonly<Record<StatutRetour, readonly StatutRetour[]>> = {
  DEMANDE: ['ACCEPTE', 'REFUSE'],
  ACCEPTE: ['RECEPTIONNE', 'REFUSE'],
  RECEPTIONNE: ['VALIDE'],
  VALIDE: ['CLOTURE'],
  REFUSE: [],
  CLOTURE: [],
};

/**
 * L'etat d'un article retourne — il decide du mouvement de stock.
 *
 * Sans cette distinction, on revendrait une marchandise abimee.
 */
export type EtatArticle = 'NEUF' | 'ABIME' | 'INUTILISABLE';

export const ETATS_ARTICLE: readonly {
  readonly code: EtatArticle;
  readonly libelle: string;
  readonly explication: string;
}[] = [
  { code: 'NEUF', libelle: 'Neuf', explication: 'Redevient vendable.' },
  { code: 'ABIME', libelle: 'Abîmé', explication: 'Ne sera pas revendu.' },
  { code: 'INUTILISABLE', libelle: 'Inutilisable', explication: 'Ne sera pas revendu.' },
];

/** Un retour tel qu'une liste l'affiche. */
export interface ResumeRetour {
  readonly id: number;
  readonly numero: string;
  readonly statut: StatutRetour;
  readonly motif: string;
  readonly clientId: number;
  readonly clientCode: string | null;
  readonly clientNom: string | null;
  readonly commandeId: number;
  readonly commandeNumero: string | null;
  readonly reclamationId: number | null;
  /** Ce que le client ANNONCE renvoyer. */
  readonly nombreArticles: number;
  /** Ce qui a ete RENDU. Zero tant que le retour n'est pas valide. */
  readonly montantRembourse: number;
  readonly dateCreation: string;
  readonly dateReception: string | null;
}

export interface Retour {
  readonly id: number;
  readonly numero: string;
  readonly commandeId: number;
  readonly clientId: number;
  readonly reclamationId: number | null;
  readonly motif: string;
  readonly statut: StatutRetour;
  readonly dateCreation: string;
  readonly dateReception: string | null;
  readonly lignes: readonly LigneRetour[];
}

export interface LigneRetour {
  readonly id: number;
  readonly ligneCommandeId: number;
  readonly quantite: number;
  readonly etatArticle: EtatArticle | null;
  readonly montantRembourse: number;
}

// -----------------------------------------------------------------------------

export function libelleStatutReclamation(statut: string): string {
  return STATUTS_RECLAMATION.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutReclamation(statut: string): string {
  return STATUTS_RECLAMATION.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

export function libelleStatutRetour(statut: string): string {
  return STATUTS_RETOUR.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutRetour(statut: string): string {
  return STATUTS_RETOUR.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

export function libelleEtatArticle(etat: string): string {
  return ETATS_ARTICLE.find((e) => e.code === etat)?.libelle ?? etat;
}
