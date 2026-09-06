export type TypeMarchand = 'INTERNE' | 'EXTERNE';
export type StatutMarchand = 'ACTIF' | 'INACTIF';

/**
 * Un marchand : GARAH elle-meme, ou un partenaire qui vend via la plateforme.
 *
 * La distinction INTERNE / EXTERNE n'est pas decorative : elle decide de la
 * commission. Une vente interne n'engendre aucune dette envers un tiers.
 */
export interface Marchand {
  readonly id: number;
  readonly code: string;
  readonly nom: string;
  readonly type: TypeMarchand;
  readonly telephone: string | null;
  readonly email: string | null;
  readonly statut: StatutMarchand;
  readonly dateCreation: string;
}

/** Ni le code ni le type : ils sont figes apres creation. */
export interface ModificationMarchand {
  readonly nom: string;
  readonly telephone?: string;
  readonly email?: string;
}
