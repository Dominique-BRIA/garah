// =============================================================================
// Le service client : conversations et negociation de prix
// =============================================================================
// ⚠️ La negociation n'a pas d'ecran a elle. Une proposition de prix vit DANS
// une conversation, et l'exposer ailleurs lui ferait perdre son contexte —
// c'est aussi ce qui garantit qu'on ne propose pas un prix sans qu'un echange
// l'ait precede.
// =============================================================================

export type StatutConversation = 'INFORMATION' | 'WAITING' | 'ASSIGNED' | 'CLOSED';

/**
 * Ce que chaque statut veut dire, et ce qu'il APPELLE comme geste.
 *
 *   INFORMATION ──le client repond──▶ WAITING ──prise──▶ ASSIGNED ──cloture──▶ CLOSED
 *        │                              ▲                   │
 *        │                              └── retrait Admin ──┘
 *        └──────────── un conseiller ecrit ──────────────▶ ASSIGNED
 *
 * INFORMATION : ouverte par le SYSTEME (un colis parti). Personne n'attend
 * rien, et elle n'entre donc PAS dans la file — elle y entre des que le
 * client repond.
 */
export const STATUTS_CONVERSATION: readonly {
  readonly code: StatutConversation;
  readonly libelle: string;
  readonly badge: string;
  /** Vrai si ce statut attend un geste de quelqu'un. */
  readonly agir: boolean;
}[] = [
  { code: 'WAITING', libelle: 'En attente', badge: 'gu-badge--alerte', agir: true },
  { code: 'ASSIGNED', libelle: 'En cours', badge: 'gu-badge--info', agir: true },
  { code: 'CLOSED', libelle: 'Fermée', badge: 'gu-badge--neutre', agir: false },
  { code: 'INFORMATION', libelle: 'Information', badge: 'gu-badge--neutre', agir: false },
];

/**
 * Une conversation telle qu'une liste l'affiche : SANS ses messages.
 *
 * Une page de vingt-cinq conversations ne transporte pas leurs quatre cents
 * messages. Mais elle porte `nonLus` — le seul chiffre qui reponde a la
 * question que se pose un agent en ouvrant la liste : « laquelle attend ma
 * reponse ? ».
 */
export interface ResumeConversation {
  readonly id: number;
  readonly clientId: number;
  readonly clientCode: string | null;
  /** Nul si le compte a disparu. La conversation, elle, reste. */
  readonly clientNom: string | null;
  /**
   * Qui a pris la conversation. ⚠️ Le champ s'appelait `responsableId` : depuis
   * V33 un ADMIN ou un SUPER_ADMIN peut prendre, et le nom désignait un type
   * d'acteur là où quatre sont possibles.
   */
  readonly prisPar: number | null;
  /** Le NOM de celui qui a pris. Nul si le compte a disparu. */
  readonly prisParNom: string | null;
  /**
   * Qui a clos, et non pas seulement quand.
   *
   * ⚠️ Nul pour les conversations closes AVANT V33 : la base ne l'a jamais su.
   * L'écran doit donc savoir dire « on ne sait pas » — et non afficher un vide
   * qui se lit comme « personne ».
   */
  readonly closPar: number | null;
  readonly closParNom: string | null;
  readonly sujet: string;
  readonly statut: StatutConversation;
  readonly nombreMessages: number;
  /** Ce qui n'a jamais ete ouvert. C'est LUI qui trie la file. */
  readonly nonLus: number;
  /** Nul si personne n'a encore ecrit : le sujet suffit a ouvrir un dossier. */
  readonly dernierMessageLe: string | null;
  readonly dateCreation: string;
  readonly dateAffectation: string | null;
  readonly dateCloture: string | null;
}

/** Une conversation et son fil complet. */
export interface Conversation {
  readonly id: number;
  readonly clientId: number;
  /**
   * Qui a pris la conversation. ⚠️ Le champ s'appelait `responsableId` : depuis
   * V33 un ADMIN ou un SUPER_ADMIN peut prendre, et le nom désignait un type
   * d'acteur là où quatre sont possibles.
   */
  readonly prisPar: number | null;
  /** Le NOM de celui qui a pris. Nul si le compte a disparu. */
  readonly prisParNom: string | null;
  /**
   * Qui a clos, et non pas seulement quand.
   *
   * ⚠️ Nul pour les conversations closes AVANT V33 : la base ne l'a jamais su.
   * L'écran doit donc savoir dire « on ne sait pas » — et non afficher un vide
   * qui se lit comme « personne ».
   */
  readonly closPar: number | null;
  readonly closParNom: string | null;
  readonly sujet: string;
  readonly statut: StatutConversation;
  readonly dateCreation: string;
  readonly dateAffectation: string | null;
  readonly dateCloture: string | null;
  readonly messages: readonly MessageConversation[];
}

export interface MessageConversation {
  readonly id: number;
  readonly conversationId: number;
  /** ⚠️ NUL quand c'est le SYSTEME qui ecrit (un colis parti) — V34. */
  readonly expediteurId: number | null;
  readonly contenu: string;
  readonly lu: boolean;
  readonly dateEnvoi: string;
}

// -----------------------------------------------------------------------------
// La negociation
// -----------------------------------------------------------------------------

export type SensProposition = 'CLIENT' | 'RESPONSABLE';

export type StatutProposition =
  | 'PROPOSEE'
  | 'ACCEPTEE'
  | 'REFUSEE'
  | 'EXPIREE'
  | 'CONSOMMEE';

export const STATUTS_PROPOSITION: readonly {
  readonly code: StatutProposition;
  readonly libelle: string;
  readonly badge: string;
}[] = [
  { code: 'PROPOSEE', libelle: 'En attente', badge: 'gu-badge--alerte' },
  { code: 'ACCEPTEE', libelle: 'Acceptée', badge: 'gu-badge--succes' },
  { code: 'REFUSEE', libelle: 'Refusée', badge: 'gu-badge--danger' },
  { code: 'EXPIREE', libelle: 'Expirée', badge: 'gu-badge--neutre' },
  { code: 'CONSOMMEE', libelle: 'Utilisée', badge: 'gu-badge--neutre' },
];

/**
 * Une proposition de prix.
 *
 * ⚠️ Le SENS n'est jamais choisi par l'ecran : le serveur le deduit du type
 * porte par le jeton. Un client qui pourrait ecrire « RESPONSABLE »
 * contournerait le seul garde-fou de la negociation — le controle qui empeche
 * une proposition vendeur de depasser le tarif public.
 */
export interface Proposition {
  readonly id: number;
  readonly conversationId: number;
  readonly varianteId: number;
  readonly propositionParenteId: number | null;
  readonly quantite: number;
  readonly prixUnitairePropose: number;
  readonly auteurId: number;
  readonly sens: SensProposition;
  readonly statut: StatutProposition;
  /** Vrai si elle peut encore devenir une commande : acceptee ET non expiree. */
  readonly utilisable: boolean;
  readonly dateCreation: string;
  readonly dateExpiration: string;
}

export interface EvaluationConversation {
  readonly id: number;
  readonly conversationId: number;
  readonly note: number;
  readonly commentaire: string | null;
  readonly dateEvaluation: string;
}

// -----------------------------------------------------------------------------

export function libelleStatutConversation(statut: string): string {
  return STATUTS_CONVERSATION.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutConversation(statut: string): string {
  return STATUTS_CONVERSATION.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}

export function libelleStatutProposition(statut: string): string {
  return STATUTS_PROPOSITION.find((s) => s.code === statut)?.libelle ?? statut;
}

export function badgeStatutProposition(statut: string): string {
  return STATUTS_PROPOSITION.find((s) => s.code === statut)?.badge ?? 'gu-badge--neutre';
}
