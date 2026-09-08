import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone, ServiceSession } from 'garah-ui';
import { Observable, catchError, of } from 'rxjs';

/** Un indicateur affiché en carte. */
interface Indicateur {
  readonly cle: string;
  readonly libelle: string;
  readonly icone: string;
  /** La teinte de la carte — pastille et bouton. Voir les classes `.teinte--*`. */
  readonly teinte: string;
  /** Le code de `cas_utilisation` requis. Sans lui, la carte n'est pas affichée. */
  readonly permission: string;
  /** Où mène « Voir la liste ». Vide = pas encore d'écran. */
  readonly lien?: string;
  /**
   * Les paramètres de l'URL, s'il en faut.
   *
   * <p>⚠️ Ils ne se mettent <b>pas</b> dans {@link #lien}. Un `?` écrit dans
   * une chaîne passée à `routerLink` est encodé comme un morceau de chemin :
   * la navigation partirait vers `/reclamations%3Fstatut=OUVERTE`, qui
   * n'existe pas.</p>
   */
  readonly parametres?: Readonly<Record<string, string>>;
  /** L'appel qui donne le nombre. */
  readonly source: () => Observable<number | null>;
}

@Component({
  selector: 'ga-tableau-bord',
  imports: [RouterLink, Icone],
  templateUrl: './tableau-bord.html',
  styleUrl: './tableau-bord.scss',
})
export class TableauBord {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly chargement = signal(false);

  /** Les valeurs, par clé. `undefined` = pas encore lu, `null` = échec. */
  private readonly valeurs = signal<Record<string, number | null | undefined>>({});

  /**
   * Les indicateurs du back-office.
   *
   * <p>Les trois premiers décrivent l'<b>état</b> du catalogue ; les trois
   * suivants, ce qui <b>attend une action</b>. C'est la distinction qui compte
   * pour quelqu'un qui ouvre son back-office le matin : un nombre de produits
   * ne demande rien, une réclamation à traiter si.</p>
   */
  private readonly definitions: readonly Indicateur[] = [
    {
      cle: 'produits',
      libelle: 'Produits',
      icone: 'box-open',
      teinte: 'indigo',
      permission: 'PRODUIT_CONSULTER',
      lien: '/produits',
      // ⚠️ La route d'ADMINISTRATION, pas le catalogue public. Ce dernier ne
      // renvoie que les produits publiés : la carte annonçait « 1 produit »
      // alors que la liste juste à côté en montrait quatre, dont trois
      // brouillons. Deux chiffres différents pour la même chose font douter
      // des deux.
      // ⚠️ La route de COMPTAGE, pas la liste tronquée à un élément.
      //
      // `/administration?taille=1` enrichit la page qu'elle rend : marchand,
      // prix d'appel, disponibilité. Quatre allers-retours vers Neon pour un
      // chiffre dont on jetait tout le reste — invisible en local, très
      // visible depuis Douala avec six cartes qui chargent ensemble.
      source: () => this.compterDirect('/api/produits/administration/nombre'),
    },
    {
      cle: 'marchands',
      libelle: 'Marchands',
      icone: 'store',
      teinte: 'bleu',
      permission: 'MARCHAND_CONSULTER',
      lien: '/marchands',
      source: () => this.compterDirect('/api/marchands/nombre'),
    },
    {
      cle: 'categories',
      libelle: 'Catégories',
      icone: 'sitemap',
      teinte: 'violet',
      permission: 'CATEGORIE_PRODUIT_GERER',
      lien: '/categories',
      // ⚠️ /nombre, et non la longueur de /api/categories.
      //
      // Cette route rend un ARBRE : sa longueur, c'est le nombre de
      // racines. Trois familles et quarante sous-categories affichaient
      // « 3 » — un chiffre faux, pas seulement cher.
      source: () => this.compterDirect('/api/categories/nombre'),
    },
    {
      cle: 'stock',
      libelle: 'Stock en alerte',
      icone: 'triangle-exclamation',
      teinte: 'orange',
      permission: 'STOCK_CONSULTER',
      source: () => this.compterDirect('/api/stock/alertes/nombre'),
    },
    {
      cle: 'conversations',
      libelle: 'Conversations en attente',
      icone: 'comments',
      teinte: 'vert',
      permission: 'CONVERSATION_CONSULTER',
      // Même raisonnement que la carte des réclamations : le lien porte le
      // filtre que la carte vient de compter. La file d'attente, ce sont les
      // conversations WAITING — mener à « toutes » ferait chercher les trois
      // dossiers libres au milieu de ceux de toute l'équipe.
      lien: '/conversations',
      parametres: { statut: 'WAITING' },
      source: () => this.compterDirect('/api/conversations/file-attente/nombre'),
    },
    {
      cle: 'reclamations',
      libelle: 'Réclamations à traiter',
      icone: 'life-ring',
      teinte: 'rouge',
      permission: 'RECLAMATION_CONSULTER',
      // Le lien mène au filtre « Ouverte », pas à la liste complète : c'est
      // exactement ce que la carte vient de compter. Ouvrir sur « toutes »
      // ferait chercher, dans une liste de deux cents lignes, les quatre que
      // le chiffre annonçait.
      lien: '/reclamations',
      parametres: { statut: 'OUVERTE' },
      source: () => this.compterDirect('/api/sav/reclamations/a-traiter/nombre'),
    },
  ];

  /**
   * Seulement ce que l'utilisateur a le droit de voir.
   *
   * <p>⚠️ Ce filtre n'est pas cosmétique : sans lui, un responsable sans
   * {@code STOCK_CONSULTER} déclencherait un appel qui répond 403 à chaque
   * ouverture du tableau de bord. La carte afficherait un tiret, la console
   * une erreur, et personne ne saurait si c'est une panne ou un droit
   * manquant.</p>
   */
  protected readonly cartes = computed(() =>
    this.definitions.filter((i) => this.session.peut(i.permission)),
  );

  protected readonly aDesCartes = computed(() => this.cartes().length > 0);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    const attendus = this.cartes();
    let restants = attendus.length;

    if (restants === 0) {
      this.chargement.set(false);
      return;
    }

    for (const indicateur of attendus) {
      // 🎯 Chaque indicateur est lu INDÉPENDAMMENT.
      //
      // La tentation serait un forkJoin : un seul abonnement, un seul état.
      // Mais forkJoin échoue en bloc — un service momentanément indisponible
      // viderait le tableau de bord entier, alors que cinq indicateurs sur six
      // sont parfaitement lisibles.
      //
      // Ici, une carte en échec affiche un tiret ; les autres affichent leur
      // valeur.
      indicateur.source().subscribe((valeur) => {
        this.valeurs.update((v) => ({ ...v, [indicateur.cle]: valeur }));
        if (--restants === 0) {
          this.chargement.set(false);
        }
      });
    }
  }

  protected valeur(cle: string): number | null | undefined {
    return this.valeurs()[cle];
  }

  /**
   * La SEULE forme de lecture du tableau de bord : une requête, un nombre.
   *
   * <p>Les cartes ont longtemps lu des listes ou des pages pour n'en garder
   * que la taille — six cartes, six réponses complètes traversant la
   * Méditerranée pour six chiffres. Invisible en local, très visible depuis
   * Douala.</p>
   *
   * <p>⚠️ Toute carte ajoutée ici a besoin d'une route de comptage côté API.
   * Recompter une liste dans le navigateur est le raccourci qui a produit le
   * « 3 catégories » d'un catalogue qui en comptait quarante.</p>
   */
  private compterDirect(url: string): Observable<number | null> {
    return this.http.get<number>(url).pipe(catchError(() => of(null)));
  }
}
