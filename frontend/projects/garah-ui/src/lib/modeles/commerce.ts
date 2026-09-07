/**
 * Le cycle de vie d'une commande.
 *
 *   EN_ATTENTE_PAIEMENT ──▶ PAYEE ──▶ EN_PREPARATION ──▶ PRETE
 *            │               │                             │
 *            ▼               ▼                             ▼
 *         ANNULEE         ANNULEE                      EXPEDIEE
 *     (client, delai)  (admin, remboursement)              │
 *                                                          ▼
 *                                                     DISPONIBLE ──▶ RETIREE
 *
 * Le cycle est LINEAIRE parce qu'il n'y a pas d'especes : rien n'est prepare
 * ni achemine avant d'etre paye.
 */
export type StatutCommande =
  | 'EN_ATTENTE_PAIEMENT'
  | 'PAYEE'
  | 'EN_PREPARATION'
  | 'PRETE'
  | 'EXPEDIEE'
  | 'DISPONIBLE'
  | 'RETIREE'
  | 'ANNULEE';

/**
 * Ce que chaque statut veut dire, et ce qu'il APPELLE comme geste.
 *
 * Le libelle seul ne suffit pas : « PRETE » ne dit pas si quelqu'un doit
 * agir. C'est pourtant la seule question que se pose celui qui ouvre la
 * liste le matin.
 */
export const STATUTS_COMMANDE: readonly {
  readonly code: StatutCommande;
  readonly libelle: string;
  readonly badge: string;
  /** Vrai si la commande attend un geste de l'equipe. */
  readonly attendUneAction: boolean;
}[] = [
  {
    code: 'EN_ATTENTE_PAIEMENT',
    libelle: 'En attente de paiement',
    badge: 'gu-badge--alerte',
    attendUneAction: false,
  },
  { code: 'PAYEE', libelle: 'Payée', badge: 'gu-badge--info', attendUneAction: true },
  {
    code: 'EN_PREPARATION',
    libelle: 'En préparation',
    badge: 'gu-badge--info',
    attendUneAction: true,
  },
  { code: 'PRETE', libelle: 'Prête', badge: 'gu-badge--info', attendUneAction: true },
  { code: 'EXPEDIEE', libelle: 'Expédiée', badge: 'gu-badge--neutre', attendUneAction: false },
  {
    code: 'DISPONIBLE',
    libelle: 'Disponible au retrait',
    badge: 'gu-badge--succes',
    attendUneAction: false,
  },
  { code: 'RETIREE', libelle: 'Retirée', badge: 'gu-badge--succes', attendUneAction: false },
  { code: 'ANNULEE', libelle: 'Annulée', badge: 'gu-badge--danger', attendUneAction: false },
];

/**
 * Les transitions autorisees, copie de celles du serveur.
 *
 * ⚠️ C'est une DUPLICATION assumee, et il faut savoir pourquoi. Le serveur
 * reste seul juge : il refuse toute transition interdite. Mais un ecran qui
 * proposerait « Expedier » sur une commande impayee ferait cliquer pour rien,
 * et l'erreur arriverait apres coup. On dit ce qui est possible AVANT le clic.
 *
 * Le jour ou le serveur change sa machine a etats, cette table doit suivre.
 */
export const TRANSITIONS_COMMANDE: Readonly<Record<StatutCommande, readonly StatutCommande[]>> = {
  EN_ATTENTE_PAIEMENT: ['PAYEE', 'ANNULEE'],
  PAYEE: ['EN_PREPARATION', 'ANNULEE'],
  EN_PREPARATION: ['PRETE'],
  PRETE: ['EXPEDIEE'],
  EXPEDIEE: ['DISPONIBLE'],
  DISPONIBLE: ['RETIREE'],
  RETIREE: [],
  ANNULEE: [],
};

/** Une commande telle qu'une liste l'affiche : sans ses lignes. */
export interface ResumeCommande {
  readonly id: number;
  readonly numero: string;
  readonly statut: StatutCommande;
  readonly clientId: number;
  readonly clientCode: string | null;
  readonly clientNom: string | null;
  readonly nombreLignes: number;
  readonly montantTotal: number;
  readonly devise: string;
  readonly dateCreation: string;
}

/** Une commande complete, avec ce qui a ete FIGE a l'achat. */
export interface DetailCommande {
  readonly id: number;
  readonly numero: string;
  readonly statut: StatutCommande;
  readonly pointRecuperationId: number | null;
  readonly langue: string;
  readonly montantArticles: number;
  readonly montantFrais: number;
  readonly montantRemise: number;
  readonly montantTotal: number;
  readonly montantTva: number;
  readonly devise: string;
  readonly dateCreation: string;
  readonly lignes: readonly LigneCommande[];
}

/**
 * Une ligne, telle qu'elle a ete figee a la commande.
 *
 * `designation` et `prixUnitaire` viennent de la ligne, pas du catalogue :
 * c'est ce qui rend une facture de mars encore juste en septembre.
 */
export interface LigneCommande {
  readonly varianteId: number;
  readonly designation: string;
  readonly quantite: number;
  readonly prixUnitaire: number;
  readonly montantLigne: number;
  readonly montantTva: number;
}

// -----------------------------------------------------------------------------
// Paiements
// -----------------------------------------------------------------------------

export type StatutPaiement =
  | 'INITIE'
  | 'EN_ATTENTE'
  | 'CONFIRME'
  | 'ECHOUE'
  | 'ANNULE'
  | 'REMBOURSE';

export type TypePaiement = 'ENCAISSEMENT' | 'REMBOURSEMENT';

export type MoyenPaiement = 'MTN_MOMO' | 'ORANGE_MONEY' | 'ESPECES' | 'VIREMENT';

export const STATUTS_PAIEMENT: readonly {
  readonly code: StatutPaiement;
  readonly libelle: string;
  readonly badge: string;
}[] = [
  { code: 'INITIE', libelle: 'Initié', badge: 'gu-badge--neutre' },
  { code: 'EN_ATTENTE', libelle: 'En attente du client', badge: 'gu-badge--alerte' },
  { code: 'CONFIRME', libelle: 'Confirmé', badge: 'gu-badge--succes' },
  { code: 'ECHOUE', libelle: 'Échoué', badge: 'gu-badge--danger' },
  { code: 'ANNULE', libelle: 'Annulé', badge: 'gu-badge--neutre' },
  { code: 'REMBOURSE', libelle: 'Remboursé', badge: 'gu-badge--info' },
];

/** Le libelle d'un moyen de paiement, tel qu'on le nomme au Cameroun. */
export function libelleMoyen(moyen: string): string {
  switch (moyen) {
    case 'MTN_MOMO':
      return 'MTN MoMo';
    case 'ORANGE_MONEY':
      return 'Orange Money';
    case 'ESPECES':
      return 'Espèces';
    case 'VIREMENT':
      return 'Virement';
    default:
      return moyen;
  }
}

export interface Paiement {
  readonly id: number;
  readonly commandeId: number;
  /** Renseigne dans les listes du back-office, nul ailleurs. */
  readonly commandeNumero: string | null;
  readonly type: TypePaiement;
  readonly montant: number;
  readonly devise: string;
  readonly moyen: MoyenPaiement;
  readonly statut: StatutPaiement;
  readonly referenceTransaction: string | null;
  readonly dateInitiation: string;
  readonly dateConfirmation: string | null;
}
