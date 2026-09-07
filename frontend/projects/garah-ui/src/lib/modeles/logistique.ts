/**
 * Les trois roles d'un lieu dans la chaine Douala → Bertoua → Bangui.
 *
 *   ENTREPOT            la ou la marchandise est stockee
 *   POINT_TRANSIT       une etape sur la route
 *   POINT_RECUPERATION  la ou le client vient chercher sa commande
 */
export type TypeLieu = 'ENTREPOT' | 'POINT_TRANSIT' | 'POINT_RECUPERATION';

export const TYPES_LIEU: readonly {
  readonly code: TypeLieu;
  readonly libelle: string;
  readonly explication: string;
}[] = [
  {
    code: 'POINT_RECUPERATION',
    libelle: 'Point de récupération',
    explication:
      'Le client vient y chercher sa commande. Il porte des frais d’acheminement.',
  },
  {
    code: 'POINT_TRANSIT',
    libelle: 'Point de transit',
    explication: 'Une étape de la route. Interne — le client ne le voit jamais.',
  },
  {
    code: 'ENTREPOT',
    libelle: 'Entrepôt',
    explication: 'Là où la marchandise est stockée avant de partir.',
  },
];

/**
 * Un lieu.
 *
 * ⚠️ Les FRAIS n'ont de sens que sur un point de recuperation : c'est ce que
 * coute l'acheminement jusqu'a lui, et c'est ce qui sera FIGE sur la commande.
 * Ailleurs ils valent zero — les poser laisserait croire qu'ils s'additionnent
 * le long de la route, ce qui n'est pas le modele.
 */
export interface Lieu {
  readonly id: number;
  readonly type: TypeLieu;
  readonly nom: string;
  /** Code ISO a deux lettres : CM, CF, TD. Jamais un nom en clair. */
  readonly pays: string;
  readonly ville: string;
  readonly adresse: string | null;
  readonly telephone: string | null;
  readonly horaires: string | null;
  readonly fraisAcheminement: number;
  readonly statut: 'ACTIF' | 'INACTIF';
}

/** Le libelle d'un type de lieu. */
export function libelleTypeLieu(type: string): string {
  return TYPES_LIEU.find((t) => t.code === type)?.libelle ?? type;
}

// -----------------------------------------------------------------------------
// Expeditions
// -----------------------------------------------------------------------------

/**
 * Le cycle de vie d'une expedition.
 *
 *   CREEE ─▶ PREPAREE ─▶ EN_TRANSIT ─▶ DISPONIBLE ─▶ REMISE
 *                             │
 *                             ▼
 *                          BLOQUEE
 *
 * ⚠️ Ce statut est une PROJECTION, recalculee depuis les evenements des colis.
 * Ce n'est pas lui la verite — ce sont les evenements. Un statut se lit d'un
 * coup d'oeil ; le parcours dit ce qui s'est reellement passe, et quand.
 */
export type StatutExpedition =
  | 'CREEE'
  | 'PREPAREE'
  | 'EN_TRANSIT'
  | 'BLOQUEE'
  | 'DISPONIBLE'
  | 'REMISE'
  | 'ANNULEE';

export const STATUTS_EXPEDITION: readonly {
  readonly code: StatutExpedition;
  readonly libelle: string;
  readonly badge: string;
  /** Vrai si quelqu'un doit agir. */
  readonly attendUneAction: boolean;
}[] = [
  { code: 'CREEE', libelle: 'Créée', badge: 'gu-badge--neutre', attendUneAction: true },
  { code: 'PREPAREE', libelle: 'Préparée', badge: 'gu-badge--info', attendUneAction: true },
  { code: 'EN_TRANSIT', libelle: 'En transit', badge: 'gu-badge--info', attendUneAction: false },
  { code: 'BLOQUEE', libelle: 'Bloquée', badge: 'gu-badge--danger', attendUneAction: true },
  {
    code: 'DISPONIBLE',
    libelle: 'Disponible au retrait',
    badge: 'gu-badge--succes',
    attendUneAction: true,
  },
  { code: 'REMISE', libelle: 'Remise', badge: 'gu-badge--succes', attendUneAction: false },
  { code: 'ANNULEE', libelle: 'Annulée', badge: 'gu-badge--neutre', attendUneAction: false },
];

export type StatutColis =
  | 'CREE'
  | 'EN_TRANSIT'
  | 'BLOQUE'
  | 'DISPONIBLE'
  | 'REMIS'
  | 'PERDU';

/**
 * Les faits qu'on enregistre le long de la route.
 *
 * ⚠️ CONTROLE ne change AUCUN statut : c'est une verification, pas un
 * mouvement. Le colis reste ou il etait.
 */
export type TypeEvenement =
  | 'DEPART'
  | 'ARRIVEE'
  | 'RECEPTION'
  | 'CONTROLE'
  | 'ANOMALIE'
  | 'REMISE';

export const TYPES_EVENEMENT: readonly {
  readonly code: TypeEvenement;
  readonly libelle: string;
  readonly explication: string;
}[] = [
  { code: 'DEPART', libelle: 'Départ', explication: 'Le colis quitte ce lieu.' },
  { code: 'ARRIVEE', libelle: 'Arrivée', explication: 'Le colis arrive à ce lieu.' },
  {
    code: 'RECEPTION',
    libelle: 'Réception',
    explication: 'Pris en charge et vérifié à l’arrivée.',
  },
  {
    code: 'CONTROLE',
    libelle: 'Contrôle',
    explication: 'Une vérification. Ne déplace pas le colis.',
  },
  {
    code: 'ANOMALIE',
    libelle: 'Anomalie',
    explication: 'Bloque le colis. La description est obligatoire.',
  },
  { code: 'REMISE', libelle: 'Remise', explication: 'Le client a récupéré sa marchandise.' },
];

/** Une expedition telle qu'une liste l'affiche : sans ses colis. */
export interface ResumeExpedition {
  readonly id: number;
  readonly numero: string;
  readonly commandeId: number;
  /** Nul si la commande a disparu. L'expedition, elle, reste. */
  readonly commandeNumero: string | null;
  readonly statut: StatutExpedition;
  readonly pointNom: string | null;
  readonly pointVille: string | null;
  readonly nombreColis: number;
  readonly dateCreation: string;
  readonly dateExpedition: string | null;
}

export interface Expedition {
  readonly id: number;
  readonly numero: string;
  readonly commandeId: number;
  readonly marchandId: number | null;
  readonly lieuDepartId: number | null;
  readonly pointRecuperationId: number | null;
  readonly statut: StatutExpedition;
  readonly dateCreation: string;
  readonly dateExpedition: string | null;
  readonly colis: readonly Colis[];
}

export interface Colis {
  readonly id: number;
  readonly numeroSuivi: string;
  readonly poidsKg: number | null;
  readonly statut: StatutColis;
}

/**
 * Un colis et son PARCOURS date.
 *
 * C'est ce qui distingue un suivi d'un statut. « EN_TRANSIT » ne dit pas ou ;
 * « receptionne a Bertoua le 12/03 a 14 h » le dit — et reste vrai meme quand
 * le colis est reparti.
 */
export interface ParcoursColis {
  readonly id: number;
  readonly numeroSuivi: string;
  readonly poidsKg: number | null;
  readonly statut: StatutColis;
  readonly evenements: readonly EvenementExpedition[];
}

export interface EvenementExpedition {
  readonly id: number;
  readonly colisId: number;
  readonly lieuId: number;
  /** Nul sur la route PUBLIQUE : qui a scanne ne regarde pas le destinataire. */
  readonly responsableId: number | null;
  readonly type: TypeEvenement;
  readonly observation: string | null;
  readonly dateHeure: string;
}

/** Le code que le client presente au comptoir. */
export interface Retrait {
  readonly id: number;
  readonly expeditionId: number;
  readonly clientId: number;
  readonly codeRetrait: string;
  readonly statut: string;
  readonly dateRetrait: string | null;
}

export function libelleStatutExpedition(statut: string): string {
  return STATUTS_EXPEDITION.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutExpedition(statut: string): string {
  return STATUTS_EXPEDITION.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

export function libelleEvenement(type: string): string {
  return TYPES_EVENEMENT.find((t) => t.code === type)?.libelle ?? type;
}
