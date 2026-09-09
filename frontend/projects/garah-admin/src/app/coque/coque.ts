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
   * Le menu, filtré par permission.
   *
   * <p>⚠️ <b>Confort, jamais sécurité.</b> Masquer une entrée empêche le clic,
   * pas l'appel : quelqu'un qui tape l'URL atteint la route. C'est le backend
   * qui refuse, avec les mêmes codes de `cas_utilisation`.</p>
   *
   * <p>Les cacher reste utile — un back-office qui montre vingt écrans dont
   * quinze répondent « accès refusé » est illisible.</p>
   */
  protected readonly entrees: readonly Entree[] = [
    { libelle: 'Tableau de bord', chemin: '/', icone: 'chart-pie' },
    { libelle: 'Produits', chemin: '/produits', icone: 'box-open', permission: 'PRODUIT_CONSULTER' },
    { libelle: 'Marchands', chemin: '/marchands', icone: 'store', permission: 'MARCHAND_CONSULTER' },
    { libelle: 'Categories', chemin: '/categories', icone: 'sitemap', permission: 'CATEGORIE_PRODUIT_GERER' },
    // Le referentiel des dimensions de declinaison. Garde par PRODUIT_CONSULTER
    // et non ATTRIBUT_GERER : composer une declinaison suppose de LIRE les
    // dimensions, meme sans avoir le droit d'en inventer une.
    { libelle: 'Dimensions', chemin: '/attributs', icone: 'tags', permission: 'PRODUIT_CONSULTER' },
    { libelle: 'Commandes', chemin: '/commandes', icone: 'cart-shopping', permission: 'COMMANDE_CONSULTER_DETAILS' },
    { libelle: 'Expeditions', chemin: '/expeditions', icone: 'truck-fast', permission: 'EXPEDITION_CONSULTER' },
    // Le comptoir. Garde par RETRAIT_CONFIRMER et non EXPEDITION_CONSULTER :
    // c'est le geste de l'agent au point de recuperation, pas celui du
    // back-office qui regarde la chaine.
    { libelle: 'Retraits', chemin: '/retraits', icone: 'check', permission: 'RETRAIT_CONFIRMER' },
    // L ecran du CHEF. Garde par SERVICE_MEMBRE_CONSULTER : sans ce droit,
    // il n aurait rien a y voir. Un chef qui ne dirige rien ne l a pas non
    // plus — c est l Admin qui accorde la permission au profil.
    { libelle: 'Mon service', chemin: '/mon-service', icone: 'users', permission: 'SERVICE_MEMBRE_CONSULTER' },
    { libelle: 'Reclamations', chemin: '/reclamations', icone: 'life-ring', permission: 'RECLAMATION_CONSULTER' },
    { libelle: 'Retours', chemin: '/retours', icone: 'arrows-rotate', permission: 'RETOUR_CONSULTER' },
    { libelle: 'Service client', chemin: '/conversations', icone: 'comments', permission: 'CONVERSATION_CONSULTER' },
    { libelle: 'Lieux', chemin: '/lieux', icone: 'earth-africa', permission: 'POINT_RECUPERATION_CONSULTER' },
    { libelle: 'Itineraires', chemin: '/itineraires', icone: 'arrow-right', permission: 'ITINERAIRE_CONSULTER' },
    { libelle: 'Stock', chemin: '/stock', icone: 'warehouse', permission: 'STOCK_CONSULTER' },
    { libelle: 'Paiements', chemin: '/paiements', icone: 'money-bill-wave', permission: 'PAIEMENT_CONSULTER' },
    // « Marchands a payer » et non « Finance » : le libelle dit le geste, pas
    // le service. C'est ce qu'on cherche dans un menu en debut de mois.
    { libelle: 'Marchands a payer', chemin: '/finance', icone: 'money-bill-wave', permission: 'DETTE_MARCHAND_CONSULTER' },
    { libelle: 'Statistiques', chemin: '/statistiques', icone: 'chart-pie', permission: 'STATISTIQUE_GENERALE_CONSULTER' },
    { libelle: 'Clients', chemin: '/clients', icone: 'users', permission: 'CLIENT_CONSULTER' },
    { libelle: 'Equipe', chemin: '/equipe', icone: 'users', permission: 'RESPONSABLE_CONSULTER' },
    // En DERNIER, et garde par AUDIT_CONSULTER : cette permission appartient au
    // module SECURITE, donc au seul super-administrateur. Celui qui administre
    // les comptes ne doit pas pouvoir relire — ni un jour effacer — la trace de
    // ce qu il a fait.
    { libelle: 'Journal des actions', chemin: '/journal', icone: 'bars', permission: 'AUDIT_CONSULTER' },
  ];

  protected visibles(): readonly Entree[] {
    return this.entrees.filter((e) => !e.permission || this.session.peut(e.permission));
  }

  protected seDeconnecter(): void {
    this.session.deconnecter().subscribe(() => this.router.navigate(['/connexion']));
  }

  protected fermerLeMenu(): void {
    this.menuOuvert.set(false);
  }
}
