import { HttpErrorResponse } from '@angular/common/http';

import { ReponseErreur } from './authentification';

// =============================================================================
// Ce qu'on dit a quelqu'un quand l'API refuse
// =============================================================================
// 🎯 LE BACKEND ENVOIE LE DETAIL CHAMP PAR CHAMP. On le jetait.
//
// Sur une erreur de validation, `ReponseErreur.champs` porte une phrase PAR
// champ fautif — « Le mot de passe doit compter au moins 6 caracteres. » — et
// `message` ne porte que la phrase generique « Certains champs sont
// invalides. ». Les ecrans n'affichaient que la seconde.
//
// Resultat, devant un formulaire refuse : on savait que quelque chose n'allait
// pas, jamais quoi. On corrigeait au hasard, champ par champ, jusqu'a ce que
// ca passe — ou on concluait que l'ecran etait casse.
//
// ⚠️ Cette fonction a vecu en VINGT-QUATRE copies dans le back-office, aucune
// ne lisant `champs`. Elle vit ici desormais.
// =============================================================================

/** De quoi parle l'ecran, pour la phrase des droits manquants : « les commandes ». */
export interface ContexteErreur {
  /** Ce qu'on affichait ou tentait. Sert de repli et de sujet au message 403. */
  readonly repli: string;
  /** Le sujet, au 403 : « consulter <sujet> ». Sans lui, phrase generique. */
  readonly sujet?: string;
}

/**
 * L'erreur, dite a quelqu'un qui ne lira jamais la console.
 *
 * <p>Dans l'ordre, du plus precis au plus general :</p>
 *
 * <ol>
 *   <li>le detail champ par champ, s'il y en a — c'est le seul cas ou l'on
 *       peut dire quoi corriger ;</li>
 *   <li>le message metier renvoye par l'API ;</li>
 *   <li>une phrase selon le statut ;</li>
 *   <li>le repli fourni par l'ecran.</li>
 * </ol>
 */
export function messageErreur(e: unknown, contexte: ContexteErreur | string): string {
  const { repli, sujet } = typeof contexte === 'string' ? { repli: contexte, sujet: undefined } : contexte;

  if (!(e instanceof HttpErrorResponse)) {
    return repli;
  }

  // ⚠️ Statut 0 : la requete n'a JAMAIS abouti. Le navigateur ne dit pas si
  // c'est le reseau, CORS ou le serveur — et depuis une connexion mobile
  // camerounaise, c'est le plus souvent le reseau.
  if (e.status === 0) {
    return 'Le service ne répond pas. Réessayez dans un instant.';
  }

  const corps = e.error as ReponseErreur | null;

  // Le detail champ par champ d'abord : c'est la seule reponse qui dise QUOI
  // corriger. TOUS les champs fautifs, pas le premier — sinon on corrige, on
  // renvoie, on decouvre le suivant, et on recommence.
  const phrases = Object.values(corps?.champs ?? {}).filter((p) => !!p);
  if (phrases.length > 0) {
    return phrases.join(' ');
  }

  // ⚠️ Les droits et la panne serveur passent AVANT `corps.message`. Sur un
  // 403, l'API dit « Acces refuse » ; la phrase de l'ecran nomme ce a quoi
  // l'acces manque, et c'est la seule des deux avec laquelle on peut aller
  // voir un administrateur.
  if (e.status === 403) {
    return sujet
      ? `Votre compte n’a pas le droit de consulter ${sujet}.`
      : 'Votre compte n’a pas le droit de faire cela.';
  }

  if (e.status >= 500) {
    return 'Le service a rencontré une erreur. Réessayez dans un instant.';
  }

  if (corps?.message) {
    return corps.message;
  }

  return repli;
}
