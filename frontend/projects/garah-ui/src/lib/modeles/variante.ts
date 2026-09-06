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
}
