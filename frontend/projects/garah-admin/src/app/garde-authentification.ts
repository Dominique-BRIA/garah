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

  return session.connecte() || router.createUrlTree(['/connexion']);
};
