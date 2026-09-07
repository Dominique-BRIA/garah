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
      // Son propre compte. Aucune permission : tout le monde a un profil, y
      // compris un compte qui n'a le droit de consulter aucun écran.
      { path: 'profil', loadComponent: () => import('./profil/profil').then((m) => m.ProfilEcran) },
      { path: 'marchands', loadComponent: () => import('./marchands/marchands').then((m) => m.Marchands) },
      { path: 'categories', loadComponent: () => import('./categories/categories').then((m) => m.Categories) },
      { path: 'produits', loadComponent: () => import('./produits/produits').then((m) => m.Produits) },
      { path: 'produits/nouveau', loadComponent: () => import('./produits/nouveau-produit').then((m) => m.NouveauProduit) },
      // ⚠️ APRES 'produits/nouveau'. Place avant, ':id' capturerait le mot
      //    « nouveau » et tenterait de charger un produit d'identifiant
      //    « nouveau » — 404 au lieu du formulaire.
      { path: 'produits/:id', loadComponent: () => import('./produits/fiche-produit').then((m) => m.FicheProduit) },
      { path: 'commandes', loadComponent: () => import('./commandes/commandes').then((m) => m.Commandes) },
      { path: 'commandes/:id', loadComponent: () => import('./commandes/fiche-commande').then((m) => m.FicheCommande) },
      // Les paiements vivent dans le meme dossier que les commandes : ils n'ont
      // aucun sens separes. « Ou est passe l'argent de cette commande ? » est
      // une seule question, pas deux.
      { path: 'paiements', loadComponent: () => import('./commandes/paiements').then((m) => m.Paiements) },
      { path: 'lieux', loadComponent: () => import('./lieux/lieux').then((m) => m.Lieux) },
      { path: 'attributs', loadComponent: () => import('./attributs/attributs').then((m) => m.Attributs) },
      { path: 'stock', loadComponent: () => import('./stock/stock').then((m) => m.Stock) },
      // Les comptes internes. « equipe » et non « utilisateurs » : les clients
      // sont aussi des utilisateurs, et ils ne se gerent pas ici.
      { path: 'equipe', loadComponent: () => import('./equipe/equipe').then((m) => m.Equipe) },
      { path: 'profils', loadComponent: () => import('./equipe/profils').then((m) => m.Profils) },
    ],
  },
  { path: '**', redirectTo: '' },
];
