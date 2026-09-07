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
  /**
   * L'adresse de la photo, DEJA SIGNEE, ou null.
   *
   * Elle ne vient PAS du jeton : un JWT voyage a chaque requete et vit quinze
   * minutes, y mettre une adresse valable sept jours la ferait circuler bien
   * au-dela du necessaire. Elle arrive dans le CORPS de la reponse de
   * connexion, et est relue a chaque rafraichissement.
   *
   * null n'est pas un manque : <gu-avatar> engendre alors un avatar a partir
   * du nom.
   */
  readonly urlPhoto: string | null;
}

export type TypeUtilisateur = 'SUPER_ADMIN' | 'ADMIN' | 'RESPONSABLE' | 'CLIENT';

/**
 * Le rôle, écrit pour un humain.
 *
 * ⚠️ NE JAMAIS AFFICHER `type` TEL QUEL.
 *
 * `SUPER_ADMIN` est un code technique : il porte un tiret bas, il est en
 * majuscules, et il n'est pas français. Une personne qui gère un commerce n'a
 * pas à lire le vocabulaire interne du modèle de données.
 *
 * Le code, lui, ne change jamais — c'est lui qui circule dans le jeton et dans
 * les permissions. Seul le libellé se traduit, et il se traduit ICI, une fois,
 * plutôt que dans chaque écran qui l'affiche.
 */
export function libelleRole(type: TypeUtilisateur | string): string {
  switch (type) {
    case 'SUPER_ADMIN':
      return 'Super administrateur';
    case 'ADMIN':
      return 'Administrateur';
    case 'RESPONSABLE':
      return 'Responsable';
    case 'CLIENT':
      return 'Client';
    default:
      // Un rôle ajouté côté serveur et pas encore connu ici : on montre
      // quelque chose de lisible plutôt qu'un blanc ou le code brut.
      return 'Utilisateur';
  }
}

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
