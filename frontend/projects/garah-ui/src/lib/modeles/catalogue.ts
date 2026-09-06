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
   * ⚠️ L'URL, jamais la cle d'objet.
   *
   * Le bucket est prive : les URL sont SIGNEES et expirent au bout de sept
   * jours (D-21). Le frontend ne peut donc pas fabriquer d'adresse lui-meme —
   * il doit utiliser celle-ci telle quelle.
   */
  readonly urlPhotoPrincipale: string | null;
  readonly prixMin: number | null;
}

export type StatutProduit = 'BROUILLON' | 'PUBLIE' | 'MASQUE' | 'ARCHIVE';

/** La fiche complete, telle que la voit le back-office. */
export interface DetailProduit {
  readonly id: number;
  readonly reference: string;
  readonly nom: string;
  readonly slug: string;
  readonly description: string | null;
  readonly statut: StatutProduit;
  readonly tauxTva: number;
  readonly marchandId: number;
  readonly categorie: CategorieProduitResumee | null;
  readonly variantes: readonly VarianteResumee[];
  readonly medias: readonly MediaProduit[];
}

export interface CategorieProduitResumee {
  readonly id: number;
  readonly nom: string;
  readonly slug: string;
}

export interface VarianteResumee {
  readonly id: number;
  readonly sku: string;
  readonly libelle: string;
  readonly parDefaut: boolean;
  readonly statut: string;
}

export interface MediaProduit {
  readonly id: number;
  readonly type: 'PHOTO' | 'VIDEO';
  readonly cleObjet: string;
  /** Deja signee. A utiliser telle quelle — voir ResumeProduit. */
  readonly url: string;
  readonly principal: boolean;
  readonly ordre: number;
}

/**
 * Ce qui manque a un produit pour etre publiable (I-12).
 *
 * Le backend refuse la publication tant que les trois ne sont pas reunis. On
 * le dit AVANT que l'utilisateur ne clique, plutot que de lui renvoyer une
 * erreur apres coup.
 */
export interface Manques {
  readonly variante: boolean;
  readonly prix: boolean;
  readonly photo: boolean;
}
