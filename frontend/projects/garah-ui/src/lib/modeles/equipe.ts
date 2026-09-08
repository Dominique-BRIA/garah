import type { TypeUtilisateur } from './authentification';

/**
 * Les deux seuls types de compte qu'un administrateur peut creer.
 *
 * Un SUPER_ADMIN n'en fait pas partie : il n'en existe qu'un, pose au
 * demarrage. En laisser creer un second donnerait a un administrateur le
 * moyen de se hisser au-dessus de son propre niveau.
 *
 * Un CLIENT non plus : il s'inscrit lui-meme.
 */
export const TYPES_CREABLES: readonly {
  readonly code: TypeUtilisateur;
  readonly libelle: string;
  readonly explication: string;
}[] = [
  {
    code: 'RESPONSABLE',
    libelle: 'Responsable',
    explication:
      'Fait le travail au quotidien. Ses droits viennent des profils qu’on lui donne.',
  },
  {
    code: 'ADMIN',
    libelle: 'Administrateur',
    explication:
      'Gere les comptes et le catalogue. Recoit tout sauf la securite du systeme.',
  },
];

/**
 * Un membre de l'equipe.
 *
 * Reunit ce que la base separe : l'identite et la connexion d'un cote, le
 * matricule et l'emploi de l'autre. La distinction est juste en base — un
 * administrateur n'a pas de matricule — mais elle n'interesse pas l'ecran,
 * qui affiche une personne.
 */
export interface Membre {
  readonly id: number;
  readonly type: TypeUtilisateur;
  readonly nom: string;
  readonly prenom: string | null;
  readonly email: string;
  readonly telephone: string | null;
  readonly statut: 'ACTIF' | 'INACTIF' | 'SUSPENDU';
  readonly emailVerifie: boolean;
  /** Nul pour un administrateur : il n'est pas affecte a un poste. */
  readonly matricule: string | null;
  readonly dateEmbauche: string | null;
  /** Le nom du profil principal. Il n'existe aucun champ « titre » en base. */
  readonly titre: string | null;
  /**
   * L'adresse de la photo, DEJA SIGNEE, ou null.
   *
   * ⚠️ Sans ce champ, la liste d'equipe affichait un avatar ENGENDRE a partir
   * du nom pendant que la barre laterale montrait la vraie photo. Deux visages
   * differents pour la meme personne sur le meme ecran.
   */
  readonly urlPhoto: string | null;
  readonly profils: readonly ProfilMetierResume[];
  readonly dateCreation: string;
  readonly dateDerniereConnexion: string | null;
}

export interface ProfilMetierResume {
  readonly id: number;
  readonly nom: string;
  readonly description: string | null;
  readonly statut: 'ACTIF' | 'INACTIF';
  readonly principale: boolean | null;
}

/**
 * Un profil METIER : un paquet de permissions auquel on rattache des gens.
 *
 * A ne pas confondre avec `Profil`, qui est le compte de la personne
 * connectee. Ici il s'agit de « Gestionnaire de catalogue », pas de « moi ».
 *
 * La base l'appelle `categorie_responsable`. L'interface dit « profil » :
 * « categorie » designe deja les familles de produits, et employer le meme
 * mot pour deux choses sans rapport oblige a demander « categorie de quoi ? ».
 */
export interface ProfilMetier {
  readonly id: number;
  readonly nom: string;
  readonly description: string | null;
  readonly statut: 'ACTIF' | 'INACTIF';
  readonly principale: boolean | null;
  /** Combien de personnes le portent. Modifier le profil les touche toutes. */
  readonly nombreMembres: number;
  readonly permissions: readonly string[];
}

/** Une fonctionnalite attribuable, telle qu'on la propose a cocher. */
export interface Fonctionnalite {
  readonly code: string;
  readonly nom: string;
  readonly description: string | null;
  readonly module: string;
}
