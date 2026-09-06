/** Un produit tel que le catalogue le liste. */
export interface ResumeProduit {
  readonly id: number;
  readonly slug: string;
  readonly nom: string;
  readonly reference: string;
  readonly statut: StatutProduit;
  readonly marchandNom: string;
  readonly categorieNom: string;
  /**
   * ⚠️ L'URL, jamais la clé d'objet.
   *
   * Le bucket est privé : les URL sont SIGNÉES et expirent au bout de sept
   * jours (D-21). Le frontend ne peut donc pas fabriquer d'adresse lui-même —
   * il doit utiliser celle-ci telle quelle.
   */
  readonly urlPhotoPrincipale: string | null;
  readonly prixMin: number | null;
}

export type StatutProduit = 'BROUILLON' | 'PUBLIE' | 'MASQUE' | 'ARCHIVE';
