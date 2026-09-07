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
