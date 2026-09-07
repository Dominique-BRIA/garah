import { TypeUtilisateur } from './authentification';

/**
 * Son propre compte, tel que le renvoie `GET /api/profil`.
 *
 * ⚠️ A ne pas confondre avec `UtilisateurConnecte`, qui vient du JETON.
 *
 *   UtilisateurConnecte   ce que le jeton porte   id, nom, type, langue
 *   Profil                ce que la base contient + email, telephone, dates
 *
 * L'e-mail et le telephone ne sont volontairement PAS dans le jeton : un JWT
 * est lisible par quiconque met la main dessus, et il voyage a chaque requete.
 * On n'y met que ce qui sert a autoriser.
 */
export interface Profil {
  readonly id: number;
  readonly nom: string;
  readonly prenom: string | null;
  /** L'identifiant de connexion. Non modifiable depuis cet ecran. */
  readonly email: string;
  readonly telephone: string | null;
  /** `fr`, `en` ou `sg` (D-08). */
  readonly langue: string;
  readonly type: TypeUtilisateur;
  /**
   * Faux tant que le lien de confirmation n'a pas ete ouvert (D-23).
   *
   * L'ecran DOIT le montrer : c'est ce qui bloque le passage de commande, et
   * sans indication visible personne ne comprend pourquoi.
   */
  readonly emailVerifie: boolean;
  readonly dateCreation: string;
  readonly dateDerniereConnexion: string | null;
}

/**
 * Ce qu'on envoie a `PATCH /api/profil`.
 *
 * Tous les champs sont facultatifs : un champ ABSENT n'est pas modifie, un
 * champ present mais vide EFFACE la valeur. C'est cette distinction qui permet
 * de retirer son numero de telephone — un formulaire qui enverrait toujours
 * tout ne le pourrait pas.
 */
export interface ModificationProfil {
  readonly nom?: string;
  readonly prenom?: string;
  readonly telephone?: string;
  readonly langue?: string;
}

/**
 * Ce qu'on envoie a `POST /api/profil/mot-de-passe`.
 *
 * ⚠️ Le mot de passe actuel est exige bien que l'appelant soit deja connecte :
 * un poste laisse deverrouille suffirait sinon a prendre le compte
 * definitivement.
 *
 * En cas de succes, l'API repond 204 et TOUTES les sessions sont coupees — y
 * compris celle-ci. L'appelant doit terminer la session lui-meme.
 */
export interface ChangementMotDePasse {
  readonly actuel: string;
  readonly nouveau: string;
}
