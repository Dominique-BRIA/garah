import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ServiceSession } from 'garah-ui';

/**
 * Interdit les ecrans du back-office a qui n'est pas connecte.
 *
 * <p><b>Volontairement SYNCHRONE.</b> Il ne fait que lire un signal deja
 * renseigne : au moment ou il s'execute, la restauration a deja eu lieu.</p>
 *
 * <p>Le rechargement de page reste le cas a traiter — le jeton d'acces vit en
 * memoire (D-19) et disparait a chaque F5, alors que le cookie de
 * rafraichissement vit quatorze jours. Mais cette reprise se fait desormais
 * dans un <b>initialiseur d'application</b> ({@code app.config.ts}), avant
 * qu'Angular ne termine son demarrage.</p>
 *
 * <p>⚠️ <b>Pourquoi ce n'est plus ici.</b> Le garde appelait l'API lui-meme.
 * Or Angular avait deja remplace l'ecran d'attente d'{@code index.html} :
 * pendant tout l'appel — 6 secondes a chaud, jusqu'a deux minutes sur une
 * instance Render endormie — le routeur n'avait aucun composant a afficher et
 * l'ecran restait <b>blanc</b>. Le defaut n'apparaissait que par intermittence,
 * selon que l'instance dormait ou non.</p>
 *
 * <p>Les liens profonds continuent de fonctionner : ouvrir /produits dans un
 * nouvel onglet restaure la session avant que la route ne soit evaluee.</p>
 */
export const gardeAuthentification: CanActivateFn = () => {
  const session = inject(ServiceSession);
  const router = inject(Router);

  if (!session.connecte()) {
    return router.createUrlTree(['/connexion']);
  }

  // 🎯 DEUX PUBLICS, DEUX ROUTES — et le controle doit etre fait aux DEUX
  //    entrees.
  //
  //    Le formulaire de connexion refusait deja un compte client. Mais on
  //    entre aussi par la RESTAURATION : le jeton d'acces vit en memoire et
  //    disparait a chaque rechargement, alors que le cookie de
  //    rafraichissement vit quatorze jours. Ce chemin-la ne verifiait rien.
  //
  //    Et ce cookie est pose par l'API, pour l'API : la boutique et le
  //    back-office en partagent UN SEUL par navigateur. Se connecter en client
  //    sur la boutique remplace donc la session d'administration ouverte a
  //    cote — puis on revient sur le back-office, la session est restauree, et
  //    l'ecran s'ouvre au nom d'un client.
  //
  // ⚠️ Ce n'est pas ce qui protege les donnees : un client n'a aucune
  //    permission, et le serveur refusait deja chaque appel. Ce qu'on repare
  //    ici, c'est une application qui accueillait quelqu'un pour lui refuser
  //    ensuite chaque ecran, un par un.
  if (!session.estInterne()) {
    // On coupe la session cote back-office plutot que de la laisser trainer :
    // sinon chaque navigation rejouerait ce refus, et le bouton « retour »
    // ramenerait dans la coque.
    session.terminer();
    return router.createUrlTree(['/connexion'], {
      queryParams: { motif: 'compte-client' },
    });
  }

  return true;
};
