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
  /** Engendre par le serveur : MAR-00042. Jamais saisi. */
  readonly code: string;
  readonly nom: string;
  readonly type: TypeMarchand;
  /** Code ISO 3166-1 alpha-2. Determine si la marchandise franchit une frontiere. */
  readonly pays: string;
  readonly telephone: string | null;
  readonly email: string | null;
  /**
   * URL du logo, deja signee, ou null.
   *
   * Null n'est pas un manque : <gu-avatar> engendre une pastille a partir des
   * initiales, qui ne coute rien et suit le theme.
   */
  readonly urlLogo: string | null;
  readonly statut: StatutMarchand;
  readonly dateCreation: string;
}

/**
 * Les pays desservis.
 *
 * On stocke un CODE et on affiche un libelle. « Cameroun », « Cameroon » et
 * « cameroun » seraient trois saisies du meme pays et rendraient faux tout
 * regroupement.
 *
 * La liste est courte a dessein : ce sont les pays de l'axe Douala-Bangui et
 * leurs voisins immediats. En proposer deux cents ferait defiler l'utilisateur
 * pour rien.
 */
export const PAYS_DESSERVIS: ReadonlyArray<{ code: string; libelle: string }> = [
  { code: 'CM', libelle: 'Cameroun' },
  { code: 'CF', libelle: 'République centrafricaine' },
  { code: 'TD', libelle: 'Tchad' },
  { code: 'GA', libelle: 'Gabon' },
  { code: 'CG', libelle: 'Congo' },
  { code: 'GQ', libelle: 'Guinée équatoriale' },
  { code: 'NG', libelle: 'Nigéria' },
  { code: 'CN', libelle: 'Chine' },
  { code: 'FR', libelle: 'France' },
  { code: 'TR', libelle: 'Turquie' },
  { code: 'AE', libelle: 'Émirats arabes unis' },
];

/** Le libelle d'un code, ou le code lui-meme s'il est inconnu. */
export function libellePays(code: string): string {
  return PAYS_DESSERVIS.find((p) => p.code === code)?.libelle ?? code;
}
