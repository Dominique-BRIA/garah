import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Avatar,
  Icone,
  Membre,
  messageErreur,
  ProfilMetier,
  ServiceSession,
  TYPES_CREABLES,
  TypeUtilisateur,
} from 'garah-ui';

/**
 * Les comptes internes : qui travaille ici, et avec quels droits.
 *
 * <h2>La chaîne, en une phrase par maillon</h2>
 *
 * <pre>
 * SUPER_ADMIN   décide de ce que le système sait faire
 * ADMIN         décide de qui fait quoi
 * RESPONSABLE   fait le travail, selon ses profils
 * </pre>
 *
 * <p>Un responsable ne reçoit pas ses droits un par un : il porte des
 * <b>profils</b>, et chaque profil est un paquet de permissions. C'est ce qui
 * permet de répondre, six mois plus tard, à « pourquoi Paul peut-il publier un
 * produit ? » — parce qu'il est gestionnaire de catalogue, pas parce que
 * quelqu'un a coché une case un mardi.</p>
 */
@Component({
  selector: 'ga-equipe',
  imports: [FormsModule, Icone, Avatar, RouterLink],
  templateUrl: './equipe.html',
  styleUrl: './equipe.scss',
})
export class Equipe {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly membres = signal<readonly Membre[]>([]);
  protected readonly profils = signal<readonly ProfilMetier[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly typesCreables = TYPES_CREABLES;

  /** Seuls les profils actifs se proposent : on n'affecte pas à un profil retiré. */
  protected readonly profilsActifs = computed(() =>
    this.profils().filter((p) => p.statut === 'ACTIF'),
  );

  // --- Le formulaire, en création ou en modification ---
  protected readonly formulaireOuvert = signal(false);
  protected readonly enEdition = signal<Membre | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  protected readonly type = signal<TypeUtilisateur>('RESPONSABLE');
  protected readonly nom = signal('');
  protected readonly prenom = signal('');
  protected readonly email = signal('');
  protected readonly telephone = signal('');
  protected readonly motDePasse = signal('');
  /** Le mot de passe se transmet : on doit pouvoir le relire pour le dicter. */
  protected readonly mdpVisible = signal(false);
  protected readonly dateEmbauche = signal('');
  protected readonly profilsChoisis = signal<readonly number[]>([]);
  protected readonly profilPrincipal = signal<number | null>(null);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    let restants = 2;
    const fini = () => {
      if (--restants === 0) {
        this.chargement.set(false);
      }
    };

    this.http.get<Membre[]>('/api/equipe').subscribe({
      next: (liste) => {
        this.membres.set(liste);
        fini();
      },
      error: (e: unknown) => {
        this.erreur.set(messageErreur(e, 'L’équipe n’a pas pu être chargée.'));
        fini();
      },
    });

    // Les profils servent au formulaire. Leur absence n'empêche pas de lire
    // la liste : on ne bloque pas l'écran entier pour un morceau manquant.
    this.http.get<ProfilMetier[]>('/api/profils').subscribe({
      next: (liste) => {
        this.profils.set(liste);
        fini();
      },
      error: () => {
        this.profils.set([]);
        fini();
      },
    });
  }

  // -------------------------------------------------------------------------
  // Le formulaire
  // -------------------------------------------------------------------------

  protected ouvrirCreation(): void {
    this.enEdition.set(null);
    this.type.set('RESPONSABLE');
    this.nom.set('');
    this.prenom.set('');
    this.email.set('');
    this.telephone.set('');
    this.motDePasse.set('');
    this.mdpVisible.set(false);
    this.dateEmbauche.set('');
    this.profilsChoisis.set([]);
    this.profilPrincipal.set(null);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected ouvrirEdition(membre: Membre): void {
    this.enEdition.set(membre);
    this.type.set(membre.type);
    this.nom.set(membre.nom);
    this.prenom.set(membre.prenom ?? '');
    this.email.set(membre.email);
    this.telephone.set(membre.telephone ?? '');
    this.motDePasse.set('');
    this.mdpVisible.set(false);
    this.dateEmbauche.set(membre.dateEmbauche ?? '');
    this.profilsChoisis.set(membre.profils.map((p) => p.id));
    this.profilPrincipal.set(membre.profils.find((p) => p.principale)?.id ?? null);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected fermer(): void {
    this.formulaireOuvert.set(false);
    this.enEdition.set(null);
  }

  protected basculerProfil(id: number): void {
    this.profilsChoisis.update((liste) =>
      liste.includes(id) ? liste.filter((x) => x !== id) : [...liste, id],
    );

    // Le principal doit rester parmi les profils cochés. Décocher celui qui
    // portait le titre laisserait un titre sans profil derrière.
    if (!this.profilsChoisis().includes(this.profilPrincipal() ?? -1)) {
      this.profilPrincipal.set(this.profilsChoisis()[0] ?? null);
    }
  }

  protected estChoisi(id: number): boolean {
    return this.profilsChoisis().includes(id);
  }

  /**
   * Ce qui empêche d'enregistrer, dit en une phrase — ou {@code null}.
   *
   * <p>🎯 <b>Un refus muet est pire qu'un refus.</b> Ces conditions
   * désactivaient le bouton, sans un mot : un mot de passe de huit
   * caractères le rendait inerte, on cliquait, rien ne se passait, et on
   * concluait que la création de comptes était cassée.</p>
   *
   * <p>L'ordre compte : on annonce ce qui manque en premier dans le
   * formulaire, sinon on renvoie quelqu'un vers le bas de l'écran alors
   * qu'un champ du haut est vide.</p>
   */
  private blocage(): string | null {
    if (!this.nom().trim()) {
      return 'Le nom est obligatoire.';
    }
    if (!this.enEdition()) {
      if (!this.email().trim()) {
        return 'L’adresse e-mail est obligatoire : elle sert à se connecter.';
      }
      if (this.motDePasse().length < 6) {
        return `Le mot de passe provisoire doit compter six caractères au moins — ${this.motDePasse().length} saisi(s).`;
      }
    }
    if (this.type() === 'RESPONSABLE' && this.profilsChoisis().length === 0) {
      return 'Un responsable a besoin d’au moins un profil : sans profil, il n’a aucun droit.';
    }
    return null;
  }

  /**
   * Enregistre — en une ou deux requêtes selon le cas.
   *
   * <p>À la modification d'un responsable, l'identité et les profils sont
   * deux routes distinctes, gardées par la même permission mais portant des
   * conséquences différentes : corriger un nom n'est pas redistribuer des
   * droits. L'écran les envoie l'une après l'autre.</p>
   */
  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }

    const empeche = this.blocage();
    if (empeche) {
      this.erreurFormulaire.set(empeche);
      return;
    }

    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);

    const existant = this.enEdition();
    const identite = {
      nom: this.nom().trim(),
      prenom: this.prenom().trim(),
      telephone: this.telephone().trim(),
      dateEmbauche: this.dateEmbauche() || null,
    };

    if (!existant) {
      this.http
        .post<Membre>('/api/equipe', {
          ...identite,
          type: this.type(),
          email: this.email().trim(),
          motDePasse: this.motDePasse(),
          profilIds: this.type() === 'RESPONSABLE' ? this.profilsChoisis() : [],
          profilPrincipalId: this.type() === 'RESPONSABLE' ? this.profilPrincipal() : null,
        })
        .subscribe({
          next: () => this.termine(),
          error: (e: unknown) => this.echoue(e, 'Le compte n’a pas pu être créé.'),
        });
      return;
    }

    this.http.put<Membre>(`/api/equipe/${existant.id}`, identite).subscribe({
      next: () => {
        if (existant.type !== 'RESPONSABLE') {
          this.termine();
          return;
        }
        this.http
          .put<Membre>(`/api/equipe/${existant.id}/profils`, {
            profilIds: this.profilsChoisis(),
            profilPrincipalId: this.profilPrincipal(),
          })
          .subscribe({
            next: () => this.termine(),
            error: (e: unknown) => this.echoue(e, 'Les profils n’ont pas pu être enregistrés.'),
          });
      },
      error: (e: unknown) => this.echoue(e, 'Le compte n’a pas pu être enregistré.'),
    });
  }

  private termine(): void {
    this.enregistrement.set(false);
    this.fermer();
    this.charger();
  }

  private echoue(e: unknown, repli: string): void {
    this.enregistrement.set(false);
    this.erreurFormulaire.set(messageErreur(e, repli));
  }

  // -------------------------------------------------------------------------
  // Activation
  // -------------------------------------------------------------------------

  /**
   * Active ou désactive un compte.
   *
   * <p>Il n'y a pas de suppression : le journal de sécurité, les commandes
   * traitées et les conversations portent l'identifiant de cette personne.</p>
   */
  protected basculerActivation(membre: Membre): void {
    const actif = membre.statut === 'ACTIF';
    const url = `/api/equipe/${membre.id}/activation`;
    const requete = actif
      ? this.http.delete<Membre>(url)
      : this.http.post<Membre>(url, null);

    requete.subscribe({
      next: () => this.charger(),
      error: (e: unknown) => this.erreur.set(messageErreur(e, 'L’opération a échoué.')),
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected libelleType(type: TypeUtilisateur): string {
    switch (type) {
      case 'SUPER_ADMIN':
        return 'Super administrateur';
      case 'ADMIN':
        return 'Administrateur';
      case 'RESPONSABLE':
        return 'Responsable';
      default:
        return 'Client';
    }
  }

  protected badgeType(type: TypeUtilisateur): string {
    switch (type) {
      case 'SUPER_ADMIN':
        return 'gu-badge--danger';
      case 'ADMIN':
        return 'gu-badge--info';
      default:
        return 'gu-badge--neutre';
    }
  }

  protected nomComplet(membre: Membre): string {
    return membre.prenom ? `${membre.prenom} ${membre.nom}` : membre.nom;
  }

  /**
   * Le compte d'amorçage ne se touche pas.
   *
   * <p>Le serveur refuse de le désactiver — plus personne ne pourrait rendre
   * la main. On retire donc le bouton plutôt que d'offrir une action qui ne
   * peut qu'échouer.</p>
   */
  protected modifiable(membre: Membre): boolean {
    return membre.type !== 'SUPER_ADMIN';
  }
}

