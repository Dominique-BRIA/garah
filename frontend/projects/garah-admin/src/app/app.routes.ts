import { Routes } from '@angular/router';
import { gardeAuthentification } from './garde-authentification';

export const routes: Routes = [
  {
    path: 'connexion',
    loadComponent: () => import('./connexion/connexion').then((m) => m.Connexion),
  },
  {
    path: '',
    // La coque porte le garde : chaque ecran enfant en herite, et on ne peut
    // pas oublier de le poser sur une route ajoutee plus tard.
    canActivate: [gardeAuthentification],
    loadComponent: () => import('./coque/coque').then((m) => m.Coque),
    children: [
      { path: '', loadComponent: () => import('./tableau-bord/tableau-bord').then((m) => m.TableauBord) },
      { path: 'produits', loadComponent: () => import('./produits/produits').then((m) => m.Produits) },
    ],
  },
  { path: '**', redirectTo: '' },
];
