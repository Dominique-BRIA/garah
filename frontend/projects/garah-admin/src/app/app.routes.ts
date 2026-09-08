import { Routes } from '@angular/router';
import { gardeAuthentification } from './garde-authentification';

export const routes: Routes = [
  {
    path: 'connexion',
    loadComponent: () => import('./connexion/connexion').then((m) => m.Connexion),
  },
  // Le suivi public. HORS de la coque, et donc hors du garde : c'est la seule
  // page qu'on atteint sans compte. Un client recoit un numero par SMS, ouvre
  // le lien, lit son trajet.
  //
  // Deux routes plutot qu'une : '/suivi' seul affiche le champ de recherche,
  // '/suivi/GRH...' lance la recherche tout seul — arriver par un lien partage
  // ne doit pas obliger a resaisir ce que le lien contenait deja.
  { path: 'suivi', loadComponent: () => import('./suivi/suivi').then((m) => m.SuiviColis) },
  { path: 'suivi/:numero', loadComponent: () => import('./suivi/suivi').then((m) => m.SuiviColis) },
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
      // ⚠️ AVANT 'produits/:id', pour la meme raison que 'produits/nouveau' :
      //    place apres, ':id' capturerait le mot « corbeille ».
      { path: 'produits/corbeille', loadComponent: () => import('./produits/corbeille').then((m) => m.Corbeille) },
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
      { path: 'expeditions', loadComponent: () => import('./expeditions/expeditions').then((m) => m.Expeditions) },
      // A la RACINE, et pas sous 'expeditions/retraits'. Deux raisons :
      //   - place sous 'expeditions', il faudrait le declarer AVANT ':id',
      //     sans quoi ':id' capturerait le mot « retraits » ;
      //   - le menu surlignerait « Expeditions » ET « Retraits » en meme
      //     temps, la surbrillance n'etant pas exacte.
      // Le comptoir est de toute facon un geste a lui seul, pas une sous-vue
      // de la liste des expeditions.
      { path: 'retraits', loadComponent: () => import('./expeditions/retraits').then((m) => m.Retraits) },
      { path: 'expeditions/:id', loadComponent: () => import('./expeditions/fiche-expedition').then((m) => m.FicheExpedition) },
      // Le SAV. Deux ecrans distincts et non deux onglets d'un meme : une
      // reclamation est une PLAINTE qu'un humain tranche, un retour est une
      // MARCHANDISE qui revient. Les gestes, les permissions et les gens ne
      // sont pas les memes.
      { path: 'reclamations', loadComponent: () => import('./sav/reclamations').then((m) => m.Reclamations) },
      { path: 'retours', loadComponent: () => import('./sav/retours').then((m) => m.Retours) },
      // Le service client. Les propositions de prix n'ont pas de route a
      // elles : elles vivent DANS une conversation, et les en sortir leur
      // ferait perdre leur contexte.
      { path: 'conversations', loadComponent: () => import('./serviceclient/conversations').then((m) => m.Conversations) },
      // La finance marchand. « /finance » et non « /marchands/soldes » : la
      // question posee ici est « qui doit-on payer ? », pas « qui sont nos
      // marchands ? » — et ce ne sont pas les memes gens qui la posent.
      { path: 'finance', loadComponent: () => import('./finance/finance').then((m) => m.Finance) },
      // Les clients. « /clients » et non « /utilisateurs » : les comptes
      // internes se gerent sous /equipe, et les deux ne se melangent pas.
      { path: 'clients', loadComponent: () => import('./clients/clients').then((m) => m.Clients) },
      { path: 'statistiques', loadComponent: () => import('./statistiques/statistiques').then((m) => m.Statistiques) },
      { path: 'lieux', loadComponent: () => import('./lieux/lieux').then((m) => m.Lieux) },
      // Les itineraires vivent dans le meme dossier que les lieux : un trajet
      // n'est rien d'autre qu'une suite de lieux, et on ne peut en definir un
      // qu'apres les avoir crees.
      { path: 'itineraires', loadComponent: () => import('./lieux/itineraires').then((m) => m.Itineraires) },
      { path: 'attributs', loadComponent: () => import('./attributs/attributs').then((m) => m.Attributs) },
      { path: 'stock', loadComponent: () => import('./stock/stock').then((m) => m.Stock) },
      // Les comptes internes. « equipe » et non « utilisateurs » : les clients
      // sont aussi des utilisateurs, et ils ne se gerent pas ici.
      { path: 'equipe', loadComponent: () => import('./equipe/equipe').then((m) => m.Equipe) },
      { path: 'profils', loadComponent: () => import('./equipe/profils').then((m) => m.Profils) },
      // L ecran du CHEF, distinct de celui de l Admin : il ne montre que ce
      // qu on dirige. Aucun garde de permission sur la route — la reponse est
      // simplement vide pour qui ne dirige rien.
      { path: 'mon-service', loadComponent: () => import('./equipe/mon-service').then((m) => m.MonService) },
    ],
  },
  { path: '**', redirectTo: '' },
];
