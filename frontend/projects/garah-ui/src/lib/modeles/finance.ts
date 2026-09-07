// =============================================================================
// La finance marchand : soldes, grand livre, reglements, commissions
// =============================================================================
// 🎯 LE SOLDE N'EST STOCKE NULLE PART. Il est la somme des ecritures,
// recalculee a chaque lecture. Un solde stocke finit toujours par mentir : il
// suffit d'une ecriture ajoutee par un chemin qui a oublie de le mettre a
// jour, et l'ecart ne se voit qu'au moment de payer quelqu'un.
// =============================================================================

/** Ce qu'on doit a un marchand, tel qu'une liste l'affiche. */
export interface SoldeMarchand {
  readonly marchandId: number;
  readonly code: string;
  readonly nom: string;
  readonly statut: string;
  /** Zero pour un marchand sans vente. Pas « inconnu » : ecrire 0, pas un tiret. */
  readonly solde: number;
  readonly devise: string;
}

export type TypeEcriture =
  | 'VENTE'
  | 'COMMISSION'
  | 'RETOUR'
  | 'ANNUL_COMMISSION'
  | 'REGLEMENT'
  | 'AJUSTEMENT';

/**
 * Ce que chaque type d'ecriture veut dire, et dans quel sens il pousse.
 *
 * ⚠️ `sortant` decrit le sens pour LE MARCHAND : une commission sort de ce
 * qu'on lui doit. C'est ce qui permet de colorer une colonne de montants sans
 * relire le signe a chaque ligne — un « − » se confond avec un tiret.
 */
export const TYPES_ECRITURE: readonly {
  readonly code: TypeEcriture;
  readonly libelle: string;
  readonly explication: string;
  readonly sortant: boolean;
}[] = [
  {
    code: 'VENTE',
    libelle: 'Vente',
    explication: 'Ce qu’on doit au marchand pour cette vente.',
    sortant: false,
  },
  {
    code: 'COMMISSION',
    libelle: 'Commission',
    explication: 'Ce que GARAH prélève au passage.',
    sortant: true,
  },
  {
    code: 'RETOUR',
    libelle: 'Retour',
    explication: 'La vente est annulée : la marchandise est revenue.',
    sortant: true,
  },
  {
    code: 'ANNUL_COMMISSION',
    libelle: 'Commission rendue',
    explication: 'On ne garde pas de commission sur ce qui a été rendu.',
    sortant: false,
  },
  {
    code: 'REGLEMENT',
    libelle: 'Règlement',
    explication: 'Versement effectué : la dette diminue.',
    sortant: true,
  },
  {
    code: 'AJUSTEMENT',
    libelle: 'Ajustement',
    explication: 'Correction manuelle. Le signe est libre.',
    sortant: false,
  },
];

export interface Ecriture {
  readonly id: number;
  readonly marchandId: number;
  readonly type: TypeEcriture;
  readonly montant: number;
  readonly devise: string;
  readonly origineType: string;
  readonly origineId: number | null;
  readonly libelle: string;
  readonly dateEcriture: string;
}

// -----------------------------------------------------------------------------
// Les reglements
// -----------------------------------------------------------------------------

export type StatutReglement = 'PREVU' | 'PAYE' | 'ANNULE';

/**
 * ⚠️ PREPARER N'EST PAS PAYER. Un reglement PREVU n'ecrit rien dans le grand
 * livre : la dette ne baisse qu'au moment ou l'argent part reellement. Fondre
 * les deux etapes ferait qu'un virement rate laisserait quand meme une dette
 * soldee dans nos livres.
 */
export const STATUTS_REGLEMENT: readonly {
  readonly code: StatutReglement;
  readonly libelle: string;
  readonly badge: string;
}[] = [
  { code: 'PREVU', libelle: 'Prévu', badge: 'gu-badge--alerte' },
  { code: 'PAYE', libelle: 'Payé', badge: 'gu-badge--succes' },
  { code: 'ANNULE', libelle: 'Annulé', badge: 'gu-badge--neutre' },
];

export interface Reglement {
  readonly id: number;
  readonly numero: string;
  readonly marchandId: number;
  readonly montant: number;
  readonly moyen: string;
  /** La preuve du versement. C'est elle qui arbitre « je n'ai jamais ete paye ». */
  readonly reference: string | null;
  readonly statut: StatutReglement;
  readonly dateReglement: string | null;
}

// -----------------------------------------------------------------------------
// Les regles de commission
// -----------------------------------------------------------------------------

/**
 * Un taux de commission, avec sa portee et sa periode.
 *
 * `marchandNom` et `categorieNom` nuls veulent dire « TOUS » — une valeur
 * porteuse de sens, pas une donnee manquante. L'ecran doit ecrire « tous »,
 * jamais un tiret.
 */
export interface RegleCommission {
  readonly id: number;
  readonly marchandId: number | null;
  readonly marchandNom: string | null;
  readonly categorieProduitId: number | null;
  readonly categorieNom: string | null;
  readonly taux: number;
  readonly priorite: number;
  readonly dateDebut: string;
  readonly dateFin: string | null;
  /** Vrai si elle s'applique AUJOURD'HUI. Une regle future ou expiree reste listee. */
  readonly active: boolean;
}

/** Le taux qui s'appliquerait a une vente, aujourd'hui. */
export interface SimulationCommission {
  readonly taux: number;
  /** Nul si aucune regle ne couvre ce cas : le taux vaut alors zero. */
  readonly regleId: number | null;
}

// -----------------------------------------------------------------------------

export function libelleTypeEcriture(type: string): string {
  return TYPES_ECRITURE.find((t) => t.code === type)?.libelle ?? type;
}

export function explicationEcriture(type: string): string {
  return TYPES_ECRITURE.find((t) => t.code === type)?.explication ?? '';
}

export function libelleStatutReglement(statut: string): string {
  return STATUTS_REGLEMENT.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutReglement(statut: string): string {
  return STATUTS_REGLEMENT.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

/**
 * La portee d'une regle, en clair.
 *
 * « Tous les marchands · Textile » se lit ; « marchand null, categorie 12 »
 * ne se lit pas.
 */
export function porteeRegle(r: RegleCommission): string {
  const qui = r.marchandNom ?? 'Tous les marchands';
  const quoi = r.categorieNom ?? 'toutes catégories';
  return `${qui} · ${quoi}`;
}
