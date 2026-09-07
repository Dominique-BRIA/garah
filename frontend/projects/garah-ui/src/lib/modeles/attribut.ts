/**
 * Une dimension de declinaison : Taille, Couleur, Conditionnement.
 *
 * C'est ce qu'Amazon appelle un « variation theme » : l'axe selon lequel un
 * produit se decline.
 *
 * Le referentiel est PARTAGE par tout le catalogue. « Taille » est defini une
 * fois et reutilise par tous les vetements — sinon chaque produit inventerait
 * ses propres valeurs (« M », « m », « Medium », « Moyen ») et aucun filtre
 * transversal ne serait possible.
 */
export interface Attribut {
  readonly id: number;
  /** Engendre depuis le nom, immuable. C'est lui qu'un import designe. */
  readonly code: string;
  readonly nom: string;
  readonly typeAffichage: TypeAffichage;
  readonly valeurs: readonly ValeurAttribut[];
}

/**
 * LISTE : une deroulante. PASTILLE : un carre de couleur.
 *
 * Une couleur ecrite en toutes lettres oblige le client a imaginer « Bleu
 * ciel ». La pastille la montre.
 */
export type TypeAffichage = 'LISTE' | 'PASTILLE';

export interface ValeurAttribut {
  readonly id: number;
  readonly code: string;
  readonly libelle: string;
  /** La couleur d'une pastille, en hexadecimal. Nulle sur une liste. */
  readonly valeurAffichage: string | null;
  /**
   * 38 avant 39 avant 40.
   *
   * Trie alphabetiquement, on obtient « 10, 38, 9 » : une taille se lit dans
   * l'ordre des tailles, pas dans celui du dictionnaire.
   */
  readonly ordre: number;
}

/** Les deux affichages proposes a la creation d'une dimension. */
export const TYPES_AFFICHAGE: readonly {
  readonly code: TypeAffichage;
  readonly libelle: string;
  readonly explication: string;
}[] = [
  {
    code: 'LISTE',
    libelle: 'Liste',
    explication: 'Pour les tailles, les capacites, les conditionnements.',
  },
  {
    code: 'PASTILLE',
    libelle: 'Pastille de couleur',
    explication: 'Pour les couleurs : le client voit la teinte, il ne l’imagine pas.',
  },
];

/**
 * Une valeur retenue par une declinaison, telle que l'API la renvoie.
 *
 * Porte de quoi l'afficher sans relire le referentiel : le nom de sa
 * dimension et, le cas echeant, sa couleur.
 */
export interface ValeurChoisie {
  readonly valeurId: number;
  readonly attributId: number;
  readonly attribut: string;
  readonly libelle: string;
  readonly valeurAffichage: string | null;
}
