// =============================================================================
// Les clients, vus du back-office
// =============================================================================
// ⚠️ « Deux publics, deux routes ». Ces modeles servent les ecrans INTERNES.
// Un client gere son compte par /api/profil — une route qui ne connait que
// lui, jamais un identifiant en parametre.
// =============================================================================

export type StatutClient = 'ACTIF' | 'INACTIF' | 'BLOQUE';

/**
 * Les trois etats, et ils ne disent PAS la meme chose.
 *
 * INACTIF est une decision administrative — compte en sommeil, doublon,
 * fermeture demandee. BLOQUE est une decision de SECURITE, prise sur alerte.
 * Les confondre a l'ecran ferait passer pour fraudeur un client simplement
 * desactive.
 */
export const STATUTS_CLIENT: readonly {
  readonly code: StatutClient;
  readonly libelle: string;
  readonly badge: string;
  readonly explication: string;
}[] = [
  {
    code: 'ACTIF',
    libelle: 'Actif',
    badge: 'gu-badge--succes',
    explication: 'Peut commander normalement.',
  },
  {
    code: 'INACTIF',
    libelle: 'Suspendu',
    badge: 'gu-badge--neutre',
    explication: 'Compte fermé administrativement. Réactivable.',
  },
  {
    code: 'BLOQUE',
    libelle: 'Bloqué',
    badge: 'gu-badge--danger',
    explication: 'Blocage de sécurité, décidé sur alerte.',
  },
];

/** Un client tel qu'une liste l'affiche. Aucun secret n'y transite. */
export interface ResumeClient {
  readonly id: number;
  readonly code: string;
  readonly nom: string;
  readonly email: string;
  readonly telephone: string | null;
  /** Faux = le client ne recoit AUCUN courriel. Explique la moitie des litiges. */
  readonly emailVerifie: boolean;
  readonly statut: StatutClient;
  readonly dateInscription: string;
}

/** Un client, tel que le back-office l'ouvre. */
export interface FicheClient {
  readonly id: number;
  readonly code: string;
  readonly utilisateurId: number;
  readonly nom: string;
  readonly prenom: string | null;
  readonly email: string;
  readonly telephone: string | null;
  readonly emailVerifie: boolean;
  readonly deuxFacteursActif: boolean;
  readonly langue: string;
  readonly statut: StatutClient;
  readonly dateInscription: string;
  /** Nul = ne s'est JAMAIS connecte. Une information, pas un trou. */
  readonly dateDerniereConnexion: string | null;
}

// -----------------------------------------------------------------------------
// La surveillance
// -----------------------------------------------------------------------------

export type NiveauRisque = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const NIVEAUX_RISQUE: readonly {
  readonly code: NiveauRisque;
  readonly libelle: string;
  readonly badge: string;
}[] = [
  { code: 'LOW', libelle: 'Faible', badge: 'gu-badge--succes' },
  { code: 'MEDIUM', libelle: 'Moyen', badge: 'gu-badge--alerte' },
  { code: 'HIGH', libelle: 'Élevé', badge: 'gu-badge--danger' },
  { code: 'CRITICAL', libelle: 'Critique', badge: 'gu-badge--danger' },
];

/**
 * Un signal qui a pese dans le score.
 *
 * 🎯 C'est LUI qui rend le score utilisable. « HIGH » tout seul ne permet
 * aucune decision : on ne sait ni pourquoi, ni quoi verifier.
 */
export interface SignalRisque {
  readonly code: string;
  readonly libelle: string;
  readonly valeur: number;
  readonly poids: number;
}

/**
 * Une evaluation de risque, avec ses raisons.
 *
 * ⚠️ La surveillance OBSERVE, elle ne decide pas. Aucun score ne bloque un
 * compte tout seul : un humain tranche. Bloquer automatiquement reviendrait a
 * refuser des clients legitimes sans recours et sans explication.
 */
export interface EvaluationRisque {
  readonly score: number;
  readonly niveau: NiveauRisque;
  readonly version: string;
  readonly signaux: readonly SignalRisque[];
}

export type GraviteAlerte = 'INFO' | 'AVERTISSEMENT' | 'CRITIQUE';

export interface Alerte {
  readonly id: number;
  readonly clientId: number | null;
  readonly evenementSecuriteId: number | null;
  readonly type: string;
  readonly gravite: string;
  readonly statut: string;
  readonly traitePar: number | null;
  /** Toujours motivee : une alerte ecartee sans raison ressemble a un oubli. */
  readonly decision: string | null;
  readonly dateCreation: string;
  readonly dateTraitement: string | null;
}

// -----------------------------------------------------------------------------

export function libelleStatutClient(statut: string): string {
  return STATUTS_CLIENT.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutClient(statut: string): string {
  return STATUTS_CLIENT.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

export function libelleNiveauRisque(niveau: string): string {
  return NIVEAUX_RISQUE.find((n) => n.code === niveau)?.libelle ?? niveau;
}

export function badgeNiveauRisque(niveau: string): string {
  return NIVEAUX_RISQUE.find((n) => n.code === niveau)?.badge ?? 'gu-badge--neutre';
}
