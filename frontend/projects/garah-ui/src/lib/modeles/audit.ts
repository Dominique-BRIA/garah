/**
 * Une ligne du journal des actions internes.
 *
 * ⚠️ `ancienneValeur` et `nouvelleValeur` sont des cliches JSON des objets
 *    modifies. Ils peuvent porter n'importe quelle donnee du systeme — le prix
 *    d'achat d'un marchand, l'adresse d'un client. C'est pour cela que la
 *    lecture est reservee au module SECURITE, donc au seul super-administrateur.
 *
 * ⚠️ `utilisateurId` peut etre nul alors que `acteurNom` ne l'est jamais. Le
 *    nom est RECOPIE au moment de l'action, le lien seulement pointe : un
 *    compte retire laisse la trace intacte et le lien vide. Un ecran qui
 *    afficherait le lien plutot que le nom perdrait exactement les lignes qui
 *    interessent.
 */
export interface LigneAudit {
  readonly id: number;
  readonly utilisateurId: number | null;
  readonly acteurNom: string;
  readonly acteurEmail: string | null;
  /** Le code du geste — `PRIX_MODIFIER`, `REGLEMENT_CONFIRMER`… */
  readonly action: string;
  /** Le genre d'objet touche — `tarification`, `commande`… */
  readonly entite: string;
  readonly entiteId: number | null;
  /** JSON, ou nul pour une creation. */
  readonly ancienneValeur: string | null;
  /** JSON, ou nul pour une suppression. */
  readonly nouvelleValeur: string | null;
  readonly adresseIp: string | null;
  readonly dateHeure: string;
}

/**
 * Ce qu'un membre a fait, tel qu'un CHEF DE SERVICE le voit.
 *
 * ⚠️ CE N'EST PAS `LigneAudit`, et la difference n'est pas cosmetique. Celle-la
 *    porte les cliches des objets modifies — donc potentiellement n'importe
 *    quelle donnee du systeme. La rendre a un chef de service ouvrirait la
 *    lecture de tout GARAH a quiconque dirige une equipe.
 *
 *    Le serveur ne renvoie que ces cinq champs : ce n'est pas l'ecran qui
 *    masque le reste.
 */
export interface ActiviteMembre {
  readonly id: number;
  readonly action: string;
  readonly entite: string;
  readonly entiteId: number | null;
  readonly dateHeure: string;
}

/**
 * Ce que le journal contient REELLEMENT.
 *
 * Peuplees a la main, les listes deroulantes proposeraient des filtres qui ne
 * rendent rien — et laisseraient croire, le jour ou une action nouvelle
 * apparait, qu'elle n'est pas tracee.
 */
export interface FiltresAudit {
  readonly actions: readonly string[];
  readonly entites: readonly string[];
}
