/**
 * Ce que renvoie `/api/auth/connexion`.
 *
 * <p>⚠️ Le jeton de <b>rafraîchissement</b> n'y figure pas, et ce n'est pas un
 * oubli : il arrive en cookie `HttpOnly`, hors de portée du JavaScript (D-19).
 * Le voir apparaître ici un jour signifierait que le backend a régressé.</p>
 */
export interface ReponseConnexion {
  readonly jeton: string;
  readonly typeJeton: string;
  /** 900 secondes. Court par nécessité : un JWT ne se révoque pas. */
  readonly expireDansSecondes: number;
  readonly utilisateur: UtilisateurConnecte;
  readonly permissions: readonly string[];
}

export interface UtilisateurConnecte {
  readonly id: number;
  readonly nom: string;
  readonly type: TypeUtilisateur;
  readonly langue: string;
}

export type TypeUtilisateur = 'SUPER_ADMIN' | 'ADMIN' | 'RESPONSABLE' | 'CLIENT';

/**
 * La forme unique de toute erreur de l'API.
 *
 * <p>Le `code` est stable et sert de clé de traduction ; le `message` est
 * humain et peut changer sans rien casser. C'est le même principe que
 * `cas_utilisation.code` côté backend : un code pour la machine, un libellé
 * pour l'humain.</p>
 */
export interface ReponseErreur {
  readonly code: string;
  readonly message: string;
  readonly horodatage: string;
  readonly chemin: string;
  /** Présent uniquement sur une erreur de validation. */
  readonly champs?: Readonly<Record<string, string>>;
}
