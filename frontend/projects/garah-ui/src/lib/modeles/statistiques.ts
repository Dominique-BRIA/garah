// =============================================================================
// Les statistiques
// =============================================================================
// 🎯 CES CHIFFRES NE SE CALCULENT PAS RETROACTIVEMENT. C'est pourquoi les
// tables de mesure existent depuis la v1, avant le moindre ecran : une vue non
// enregistree en mars est perdue pour toujours.
//
// Le bilan est lu dans les AGREGATS quotidiens, jamais recalcule depuis le
// detail — celui-ci est purge a 90 jours (D-15). Un bilan recalcule serait
// juste sur les dernieres semaines et faux au-dela, sans que rien ne le
// signale.
// =============================================================================

/** Un produit sur la periode. */
export interface LigneBilan {
  readonly produitId: number;
  readonly nom: string;
  readonly vues: number;
  readonly commandes: number;
  readonly quantiteVendue: number;
  readonly chiffreAffaires: number;
  /**
   * Part des vues qui ont abouti a une commande.
   *
   * Nul si le produit n'a recu AUCUNE vue : un « 0 % » accuserait une fiche
   * que personne n'a ouverte, alors que le probleme est ailleurs.
   */
  readonly tauxConversion: number | null;
}

/** Un jour de la periode — de quoi tracer une courbe sans trou. */
export interface PointJour {
  readonly jour: string;
  readonly vues: number;
  readonly commandes: number;
  readonly chiffreAffaires: number;
}

export interface BilanPeriode {
  readonly du: string;
  readonly au: string;
  /**
   * Le nombre de jours COUVERTS PAR DES AGREGATS, pas la longueur de la
   * periode demandee.
   *
   * ⚠️ S'ils different, une nuit d'agregation a ete manquee. L'ecran doit le
   * DIRE, au lieu d'afficher un creux inexplique dans la courbe.
   */
  readonly jours: number;
  readonly vues: number;
  readonly vuesUniques: number;
  /** Les commandes PAYÉES, au jour de leur paiement. */
  readonly commandes: number;
  /** Les commandes annulées, au jour de leur annulation. */
  readonly commandesAnnulees: number;
  readonly quantiteVendue: number;
  /**
   * L'argent réellement ENCAISSÉ, frais d'acheminement compris.
   * ⚠️ Plus la somme des commandes créées : impayées et annulées la gonflaient.
   */
  readonly chiffreAffaires: number;
  /** L'argent rendu, compté à part — jamais soustrait en silence. */
  readonly montantRembourse: number;
  readonly retours: number;
  readonly meilleurs: readonly LigneBilan[];
  readonly parJour: readonly PointJour[];
}

/** Un produit qui monte, sur la page d'accueil de la vitrine. */
export interface ProduitTendance {
  readonly produitId: number;
  readonly nom: string;
  readonly ventesRecentes: number;
  readonly ventesPrecedentes: number;
  readonly vuesRecentes: number;
  /** Rapport recentes/precedentes, borne a 2 quand on part de zero. */
  readonly croissance: number;
}

/**
 * Les periodes proposees par l'ecran.
 *
 * Sept jours par defaut serait trop court pour un commerce ou une commande
 * met plusieurs jours a se conclure ; trente jours est la fenetre qui
 * correspond au cycle reel.
 */
export const PERIODES: readonly {
  readonly jours: number;
  readonly libelle: string;
}[] = [
  { jours: 7, libelle: '7 jours' },
  { jours: 30, libelle: '30 jours' },
  { jours: 90, libelle: '90 jours' },
];

/**
 * Un taux de conversion lisible.
 *
 * Nul se dit « — » et non « 0 % » : les deux ne veulent pas dire la meme
 * chose.
 */
export function tauxLisible(taux: number | null): string {
  if (taux === null || taux === undefined) {
    return '—';
  }
  return `${(taux * 100).toFixed(1)} %`;
}
