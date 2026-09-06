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
      { path: 'marchands', loadComponent: () => import('./marchands/marchands').then((m) => m.Marchands) },
      { path: 'categories', loadComponent: () => import('./categories/categories').then((m) => m.Categories) },
      { path: 'produits', loadComponent: () => import('./produits/produits').then((m) => m.Produits) },
      { path: 'produits/nouveau', loadComponent: () => import('./produits/nouveau-produit').then((m) => m.NouveauProduit) },
      // ⚠️ APRES 'produits/nouveau'. Place avant, ':id' capturerait le mot
      //    « nouveau » et tenterait de charger un produit d'identifiant
      //    « nouveau » — 404 au lieu du formulaire.
      { path: 'produits/:id', loadComponent: () => import('./produits/fiche-produit').then((m) => m.FicheProduit) },
    ],
  },
  { path: '**', redirectTo: '' },
];
