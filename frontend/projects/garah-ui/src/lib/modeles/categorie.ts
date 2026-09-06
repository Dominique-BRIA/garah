/**
 * Une categorie, avec ses enfants.
 *
 * L'API renvoie l'arbre complet d'un coup : un catalogue compte quelques
 * dizaines de categories, pas des milliers.
 */
export interface Categorie {
  readonly id: number;
  readonly parentId: number | null;
  readonly nom: string;
  readonly slug: string;
  readonly ordre: number;
  readonly statut: 'ACTIVE' | 'INACTIVE';
  readonly enfants: readonly Categorie[];
}
