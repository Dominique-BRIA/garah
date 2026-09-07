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

/** Une categorie aplatie, avec sa profondeur, pour une liste deroulante. */
export interface OptionCategorie {
  readonly id: number;
  readonly nom: string;
  readonly niveau: number;
}

/**
 * Deplie l'arbre en une liste ordonnee, chaque noeud portant sa profondeur.
 *
 * Un `<select>` ne sait pas afficher un arbre : on decale les libelles pour
 * que la hierarchie reste lisible dans une liste plate.
 */
export function aplatirCategories(
  noeuds: readonly Categorie[],
  niveau = 0,
): OptionCategorie[] {
  return noeuds.flatMap((c) => [
    { id: c.id, nom: c.nom, niveau },
    ...aplatirCategories(c.enfants, niveau + 1),
  ]);
}
