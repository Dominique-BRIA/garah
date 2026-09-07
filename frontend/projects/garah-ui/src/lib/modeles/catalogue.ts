/** Un produit tel que le catalogue le liste. */
export interface ResumeProduit {
  readonly id: number;
  readonly slug: string;
  readonly nom: string;
  readonly reference: string;
  readonly statut: StatutProduit;
  /**
   * ⚠️ L'URL, jamais la cle d'objet.
   *
   * Le bucket est prive : les URL sont SIGNEES et expirent au bout de sept
   * jours (D-21). Le frontend ne peut donc pas fabriquer d'adresse lui-meme —
   * il doit utiliser celle-ci telle quelle.
   */
  readonly urlPhotoPrincipale: string | null;
  readonly marchandNom: string | null;
  readonly categorieNom: string | null;
  /** Le plus bas de la grille. Nul tant qu'aucun prix n'est pose. */
  readonly prixMin: number | null;
  readonly devise: string | null;
  /**
   * La quantite disponible, SOMMEE sur toutes les declinaisons.
   *
   * Portee par le produit et non par la variante : une declinaison epuisee et
   * une autre disponible font un produit toujours vendable.
   *
   * Zero quand aucune ligne de stock n existe encore — le cas d un brouillon
   * qu on vient de creer.
   */
  readonly quantiteDisponible: number;
}

/**
 * Le filtre de disponibilite de la liste d administration.
 *
 * ⚠️ Il s applique EN BASE, pas apres reception de la page. Trier une page
 * deja recue donnerait « 3 produits sur 24 » ici et « 7 sur 24 » a la page
 * suivante, avec un total qui ne correspondrait a rien.
 */
export type FiltreDisponibilite = 'TOUS' | 'EN_STOCK' | 'FAIBLE' | 'RUPTURE';

/** Les libelles affiches, dans l ordre du menu. */
export const FILTRES_DISPONIBILITE: readonly {
  readonly code: FiltreDisponibilite;
  readonly libelle: string;
}[] = [
  { code: 'TOUS', libelle: 'Toutes les disponibilités' },
  { code: 'EN_STOCK', libelle: 'En stock' },
  { code: 'FAIBLE', libelle: 'Stock faible' },
  { code: 'RUPTURE', libelle: 'En rupture' },
];

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

/**
 * Un montant lisible : « 5 000 FCFA ».
 *
 * Sans separateur de milliers, 5000 et 50000 ne se distinguent qu'en comptant
 * les chiffres — dans une liste faite pour comparer des prix, c'est exactement
 * ce qu'il ne faut pas demander a l'oeil.
 *
 * ⚠️ Le test est `== null` et non `=== null`, volontairement : il attrape AUSSI
 * `undefined`. Un champ absent de la reponse JSON arrive en `undefined`, et un
 * `!== null` le laissait passer — c'est ainsi que la liste affichait « FCFA »
 * tout seul, sans montant devant.
 *
 * @param devise le code renvoye par l'API. XAF s'ecrit FCFA : c'est le nom que
 *               tout le monde emploie ici, et personne ne lit « XAF » sur un
 *               prix.
 */
export function montantLisible(
  valeur: number | null | undefined,
  devise?: string | null,
  absent = '—',
): string {
  if (valeur == null) {
    return absent;
  }
  const nombre = new Intl.NumberFormat('fr-FR').format(valeur);
  return `${nombre} ${devise === 'XAF' || !devise ? 'FCFA' : devise}`;
}

/**
 * Un produit que l API a REFUSE de supprimer, et pourquoi.
 *
 * Le `code` est stable et sert de cle de traduction ; le `message` est humain
 * et peut changer sans rien casser.
 */
export interface Refus {
  readonly id: number;
  readonly code: string;
  readonly message: string;
}

/**
 * Le resultat d une suppression multiple.
 *
 * 🎯 LA REUSSITE EST PARTIELLE, PAR CONCEPTION.
 *
 * Sur dix produits coches, deux peuvent avoir deja ete vendus : l API supprime
 * les huit autres et nomme les deux qui restent. L ecran DOIT montrer les
 * deux listes — annoncer « echec » masquerait huit suppressions bien reelles,
 * annoncer « succes » mentirait sur deux.
 */
export interface ResultatSuppression {
  readonly supprimes: readonly number[];
  readonly refuses: readonly Refus[];
}
