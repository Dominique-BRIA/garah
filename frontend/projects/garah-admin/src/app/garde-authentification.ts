import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ServiceSession } from 'garah-ui';
import { map, of } from 'rxjs';

/**
 * Interdit les ecrans du back-office a qui n'est pas connecte.
 *
 * <p>⚠️ <b>Le rechargement de page est le cas a traiter, pas la connexion.</b></p>
 *
 * <p>Le jeton d'acces vit EN MEMOIRE (D-19) : il disparait a chaque F5. Un
 * garde qui se contenterait de lire `session.connecte()` renverrait donc
 * l'utilisateur vers l'ecran de connexion a chaque rechargement — alors que
 * son cookie de rafraichissement est parfaitement valide et vit quatorze
 * jours.</p>
 *
 * <p>On tente donc une restauration avant de refuser. C'est aussi ce qui rend
 * un lien profond partageable : ouvrir /produits dans un nouvel onglet
 * fonctionne.</p>
 */
export const gardeAuthentification: CanActivateFn = () => {
  const session = inject(ServiceSession);
  const router = inject(Router);

  if (session.connecte()) {
    return of(true);
  }

  return session.restaurer().pipe(
    map((reprise) => reprise || router.createUrlTree(['/connexion'])),
  );
};
