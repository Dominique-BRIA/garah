import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Avatar, Icone, Marque, ServiceSession, ServiceTheme, libelleRole } from 'garah-ui';

interface Entree {
  readonly libelle: string;
  readonly chemin: string;
  readonly icone: string;
  /** Le code de `cas_utilisation` qui donne accès. Vide = toujours visible. */
  readonly permission?: string;
}

/** Un paquet d'écrans qui répondent à la même question. */
interface Groupe {
  readonly titre: string;
  readonly entrees: readonly Entree[];
}

@Component({
  selector: 'ga-coque',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icone, Avatar, Marque],
  templateUrl: './coque.html',
  styleUrl: './coque.scss',
})
export class Coque {
  private readonly router = inject(Router);
  protected readonly session = inject(ServiceSession);
  protected readonly theme = inject(ServiceTheme);

  protected readonly menuOuvert = signal(false);

  /** `SUPER_ADMIN` n'a rien à faire à l'écran : on affiche le libellé. */
  protected readonly role = libelleRole;

  /**
   * Le menu, rangé dans l'ordre de la VIE D'UNE VENTE.
   *
   * <pre>
   * on regarde où on en est        →  Pilotage
   * on prépare ce qu'on vend       →  Le catalogue
   * ça se vend                     →  Les ventes
   * ça part                        →  L'acheminement
   * ça se passe mal                →  Après-vente
   * derrière tout ça, des gens     →  Les gens
   * et une trace de ce qu'ils font →  La trace
   * </pre>
   *
   * <h2>⚠️ Pourquoi des groupes, et pas seulement un ordre</h2>
   *
   * <p>Vingt entrées à la file forment un mur, quel que soit leur ordre : on
   * les relit toutes pour en trouver une. Les intertitres donnent des points
   * d'accroche — on cherche d'abord la bonne <b>question</b>, puis l'écran
   * dedans.</p>
   *
   * <p>C'est aussi ce qui rend les icônes lisibles : « Clients » et « Équipe »
   * portent le même bonhomme, et personne ne s'y trompe une fois qu'ils sont
   * côte à côte sous « Les gens ».</p>
   *
   * <h2>⚠️ Confort, jamais sécurité</h2>
   *
   * <p>Masquer une entrée empêche le clic, pas l'appel : quelqu'un qui tape
   * l'URL atteint la route. C'est le backend qui refuse, avec les mêmes codes
   * de {@code cas_utilisation}.</p>
   *
   * <p>Les cacher reste utile — un back-office qui montre vingt écrans dont
   * quinze répondent « accès refusé » est illisible.</p>
   */
  protected readonly groupes: readonly Groupe[] = [
    {
      titre: 'Pilotage',
      entrees: [
        { libelle: 'Tableau de bord', chemin: '/', icone: 'layer-group' },
        { libelle: 'Statistiques', chemin: '/statistiques', icone: 'chart-pie', permission: 'STATISTIQUE_GENERALE_CONSULTER' },
      ],
    },
    {
      // Ce qu'on vend, et d'où ça vient. Le marchand est ICI et non parmi les
      // gens : on le choisit en créant un produit, pas en gérant des comptes.
      titre: 'Le catalogue',
      entrees: [
        { libelle: 'Produits', chemin: '/produits', icone: 'box-open', permission: 'PRODUIT_CONSULTER' },
        { libelle: 'Categories', chemin: '/categories', icone: 'sitemap', permission: 'CATEGORIE_PRODUIT_GERER' },
        // Le referentiel des dimensions de declinaison. Garde par
        // PRODUIT_CONSULTER et non ATTRIBUT_GERER : composer une declinaison
        // suppose de LIRE les dimensions, meme sans droit d'en inventer une.
        { libelle: 'Dimensions', chemin: '/attributs', icone: 'tags', permission: 'PRODUIT_CONSULTER' },
        { libelle: 'Stock', chemin: '/stock', icone: 'warehouse', permission: 'STOCK_CONSULTER' },
        { libelle: 'Marchands', chemin: '/marchands', icone: 'store', permission: 'MARCHAND_CONSULTER' },
      ],
    },
    {
      // Une vente produit trois choses : une commande, un encaissement, et une
      // dette envers le marchand. Les trois se suivent.
      titre: 'Les ventes',
      entrees: [
        { libelle: 'Commandes', chemin: '/commandes', icone: 'cart-shopping', permission: 'COMMANDE_CONSULTER_DETAILS' },
        { libelle: 'Paiements', chemin: '/paiements', icone: 'money-bill-wave', permission: 'PAIEMENT_CONSULTER' },
        // « Marchands a payer » et non « Finance » : le libelle dit le geste,
        // pas le service. C'est ce qu'on cherche dans un menu en debut de mois.
        { libelle: 'Marchands a payer', chemin: '/finance', icone: 'money-bill-wave', permission: 'DETTE_MARCHAND_CONSULTER' },
      ],
    },
    {
      // Le colis d'abord, le referentiel ensuite : on vient ici pour suivre
      // une expedition dix fois pour une fois qu'on ajoute un point de transit.
      titre: 'L’acheminement',
      entrees: [
        { libelle: 'Expeditions', chemin: '/expeditions', icone: 'truck-fast', permission: 'EXPEDITION_CONSULTER' },
        { libelle: 'Retraits', chemin: '/retraits', icone: 'check', permission: 'RETRAIT_CONFIRMER' },
        { libelle: 'Lieux', chemin: '/lieux', icone: 'earth-africa', permission: 'POINT_RECUPERATION_CONSULTER' },
        { libelle: 'Itineraires', chemin: '/itineraires', icone: 'arrow-right', permission: 'ITINERAIRE_CONSULTER' },
      ],
    },
    {
      titre: 'Après-vente',
      entrees: [
        { libelle: 'Reclamations', chemin: '/reclamations', icone: 'life-ring', permission: 'RECLAMATION_CONSULTER' },
        { libelle: 'Retours', chemin: '/retours', icone: 'arrows-rotate', permission: 'RETOUR_CONSULTER' },
        { libelle: 'Service client', chemin: '/conversations', icone: 'comments', permission: 'CONVERSATION_CONSULTER' },
      ],
    },
    {
      titre: 'Les gens',
      entrees: [
        { libelle: 'Clients', chemin: '/clients', icone: 'users', permission: 'CLIENT_CONSULTER' },
        // « equipe » et non « utilisateurs » : les clients sont aussi des
        // utilisateurs, et ils ne se gerent pas ici.
        { libelle: 'Equipe', chemin: '/equipe', icone: 'users', permission: 'RESPONSABLE_CONSULTER' },
        { libelle: 'Mon service', chemin: '/mon-service', icone: 'users', permission: 'SERVICE_MEMBRE_CONSULTER' },
      ],
    },
    {
      // Seul de son groupe, et c'est voulu : il ne se range sous aucune des
      // questions precedentes — il porte sur TOUTES.
      titre: 'La trace',
      entrees: [
        { libelle: 'Journal des actions', chemin: '/journal', icone: 'bars', permission: 'AUDIT_CONSULTER' },
      ],
    },
  ];

  /**
   * Les groupes qui ont au moins une entrée visible.
   *
   * <p>⚠️ Un groupe dont toutes les entrées sont masquées disparaît AVEC son
   * intertitre. Le laisser afficherait « Les ventes » suivi de rien — et l'on
   * chercherait ce qui a disparu.</p>
   */
  protected visibles(): readonly Groupe[] {
    return this.groupes
      .map((g) => ({
        titre: g.titre,
        entrees: g.entrees.filter((e) => !e.permission || this.session.peut(e.permission)),
      }))
      .filter((g) => g.entrees.length > 0);
  }

  protected seDeconnecter(): void {
    this.session.deconnecter().subscribe(() => this.router.navigate(['/connexion']));
  }

  protected fermerLeMenu(): void {
    this.menuOuvert.set(false);
  }
}
