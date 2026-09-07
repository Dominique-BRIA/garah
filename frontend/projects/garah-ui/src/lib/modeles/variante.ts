import type { ValeurChoisie } from './attribut';

/**
 * Un palier de la grille tarifaire.
 *
 * `quantiteMax` nul signifie « et au-dela ». Le dernier palier reste
 * toujours ouvert : sans lui, une commande de mille unites n'aurait aucun
 * prix, et le panier echouerait au moment de valider.
 *
 * Les prix sont TTC : la TVA en est extraite, pas ajoutee.
 */
export interface PalierPrix {
  /**
   * L'identifiant de la ligne tarifaire.
   *
   * Il sert a DESIGNER un palier pour en changer le prix ou le retirer. Sans
   * lui, corriger une faute de frappe imposerait de refaire toute la grille.
   */
  readonly id: number;
  readonly quantiteMin: number;
  readonly quantiteMax: number | null;
  readonly prixUnitaire: number;
  readonly devise: string;
}

/** C'est la variante qui se vend, jamais le produit. */
export interface Variante {
  readonly id: number;
  readonly produitId: number;
  readonly sku: string;
  readonly libelle: string;
  readonly parDefaut: boolean;
  readonly statut: string;
  readonly paliers: readonly PalierPrix[];
  /**
   * Les valeurs d'attribut qui definissent cette declinaison.
   *
   * C'est ce qui permet a la vitrine d'afficher des SELECTEURS plutot qu'une
   * liste d'intitules : « Taille : 42, 43 » se deduit des valeurs, jamais du
   * texte libre.
   *
   * Vide sur les declinaisons creees avant le referentiel — elles restent
   * valides, elles n'ont simplement pas de selecteur.
   */
  readonly valeurs: readonly ValeurChoisie[];
}
