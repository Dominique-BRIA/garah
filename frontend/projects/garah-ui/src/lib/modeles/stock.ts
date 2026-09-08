/**
 * L'etat d'un stock.
 *
 * Trois compteurs, jamais un seul :
 *
 *   DISPONIBLE   vendable tout de suite
 *   RESERVE      promis a une commande en attente de paiement
 *   ENDOMMAGE    physiquement la, mais invendable
 *
 * Le total est leur somme — ce qui est reellement dans l'entrepot. Un stock
 * qui n'exposerait qu'un nombre laisserait croire qu'une reservation a fait
 * disparaitre la marchandise.
 */
export interface EtatStock {
  readonly varianteId: number;
  /** Vient du catalogue. Nul sur les routes qui repondent sur une seule variante. */
  readonly sku: string | null;
  readonly libelle: string | null;
  readonly produitId: number | null;
  readonly produitNom: string | null;
  readonly disponible: number;
  readonly reserve: number;
  readonly endommage: number;
  readonly total: number;
  readonly seuilAlerte: number;
  readonly sousLeSeuil: boolean;
}

/**
 * Un mouvement de stock.
 *
 * C'est ce qui repond a « pourquoi n'en reste-t-il que trois ? ». La quantite
 * courante est une photo de l'instant ; les mouvements sont les faits dates
 * qui l'expliquent — et eux ne s'effacent jamais.
 */
export interface MouvementStock {
  readonly id: number;
  readonly type: TypeMouvement;
  readonly compteur: CompteurStock;
  readonly quantite: number;
  readonly quantiteAvant: number;
  readonly quantiteApres: number;
  readonly origineType: OrigineMouvement | null;
  readonly origineId: number | null;
  /**
   * Qui a enregistre ce mouvement — un UTILISATEUR, quel que soit son type.
   *
   * Nul quand il vient du systeme : une reservation posee par une commande
   * n'a pas d'auteur humain.
   */
  readonly auteurId: number | null;
  readonly commentaire: string | null;
  readonly dateOperation: string;
}

export type TypeMouvement =
  | 'ENTREE'
  | 'SORTIE'
  | 'RESERVATION'
  | 'LIBERATION'
  | 'RETOUR'
  | 'CASSE'
  | 'AJUSTEMENT';

export type CompteurStock = 'DISPONIBLE' | 'RESERVEE' | 'ENDOMMAGEE';

export type OrigineMouvement = 'COMMANDE' | 'RETOUR' | 'EXPEDITION' | 'INVENTAIRE' | 'MANUEL';

/** Le libelle d'un type de mouvement, en langage d'entrepot. */
export function libelleMouvement(type: string): string {
  switch (type) {
    case 'ENTREE':
      return 'Réception';
    case 'SORTIE':
      return 'Sortie';
    case 'RESERVATION':
      return 'Réservation';
    case 'LIBERATION':
      return 'Libération';
    case 'RETOUR':
      return 'Retour client';
    case 'CASSE':
      return 'Casse';
    case 'AJUSTEMENT':
      return 'Ajustement d’inventaire';
    default:
      return type;
  }
}

/** D'ou vient le mouvement. Dit CE QUI l'a provoque, pas qui. */
export function libelleOrigine(origine: string | null): string {
  switch (origine) {
    case 'COMMANDE':
      return 'commande';
    case 'RETOUR':
      return 'retour';
    case 'EXPEDITION':
      return 'expédition';
    case 'INVENTAIRE':
      return 'inventaire';
    case 'MANUEL':
      return 'saisie manuelle';
    default:
      return '';
  }
}

/** Le compteur touche par le mouvement. */
export function libelleCompteur(compteur: string): string {
  switch (compteur) {
    case 'DISPONIBLE':
      return 'disponible';
    case 'RESERVEE':
      return 'réservé';
    case 'ENDOMMAGEE':
      return 'endommagé';
    default:
      return compteur;
  }
}
