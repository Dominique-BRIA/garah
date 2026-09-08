import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Fonctionnalite,
  Icone,
  Membre,
  messageErreur,
  ProfilMetier,
  ServiceSession,
} from 'garah-ui';

/** Les fonctionnalités d'un même module, présentées ensemble. */
interface GroupeModule {
  readonly module: string;
  readonly fonctionnalites: readonly Fonctionnalite[];
}

/**
 * Les profils métier : les paquets de droits qu'on donne aux responsables.
 *
 * <p>Sans eux, il faudrait accorder les permissions une par une à chaque
 * personne — et personne ne saurait plus, six mois plus tard, pourquoi Paul
 * peut publier un produit et Marie non.</p>
 */
@Component({
  selector: 'ga-profils',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './profils.html',
  styleUrl: './profils.scss',
})
export class Profils {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ProfilMetier[]>([]);
  protected readonly fonctionnalites = signal<readonly Fonctionnalite[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  // --- Le formulaire ---
  protected readonly formulaireOuvert = signal(false);
  protected readonly enEdition = signal<ProfilMetier | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);

  // --- La nomination d'un chef ---------------------------------------------
  //
  // ⚠️ Les membres ne sont chargés QU'À L'OUVERTURE de la boîte, jamais avec
  //    la liste des profils. Les charger d'avance ferait une requête par
  //    profil affiché — vingt requêtes pour une nomination qui n'aura peut-être
  //    pas lieu.
  protected readonly nomination = signal<ProfilMetier | null>(null);
  protected readonly membres = signal<readonly Membre[]>([]);
  protected readonly chargementMembres = signal(false);
  protected readonly erreurNomination = signal<string | null>(null);

  protected readonly nom = signal('');
  protected readonly description = signal('');
  protected readonly choisies = signal<readonly string[]>([]);
  protected readonly filtre = signal('');
  protected readonly moduleOuvert = signal<string | null>(null);

  /**
   * Les fonctionnalités groupées par module.
   *
   * <p>Cent quatre-vingt-huit cases à cocher d'affilée ne se lisent pas. Le
   * module est le seul regroupement qui existe déjà en base — on s'en sert
   * plutôt que d'en inventer un second qui divergerait.</p>
   */
  protected readonly groupes = computed<GroupeModule[]>(() => {
    const recherche = this.filtre().trim().toLowerCase();

    const retenues = recherche
      ? this.fonctionnalites().filter(
          (f) =>
            f.nom.toLowerCase().includes(recherche) ||
            f.code.toLowerCase().includes(recherche) ||
            f.module.toLowerCase().includes(recherche),
        )
      : this.fonctionnalites();

    const parModule = new Map<string, Fonctionnalite[]>();
    for (const f of retenues) {
      const groupe = parModule.get(f.module) ?? [];
      groupe.push(f);
      parModule.set(f.module, groupe);
    }

    return [...parModule.entries()]
      .map(([module, fonctionnalites]) => ({ module, fonctionnalites }))
      .sort((a, b) => a.module.localeCompare(b.module));
  });

  constructor() {
    this.charger();
  }

  // ---------------------------------------------------------------------------
  // Nommer le chef d'un service
  // ---------------------------------------------------------------------------

  /**
   * Ouvre la nomination et charge les membres du service.
   *
   * ⚠️ On ne propose QUE les membres du service. Un chef qui n'en serait pas
   *    membre serait un supérieur sans équipe — et le serveur le refuserait de
   *    toute façon. Proposer toute l'équipe ferait choisir quelqu'un pour se
   *    voir refuser juste après, un refus qu'on peut éviter avant le clic.
   */
  protected ouvrirNomination(profil: ProfilMetier): void {
    this.nomination.set(profil);
    this.erreurNomination.set(null);
    this.membres.set([]);
    this.chargementMembres.set(true);

    this.http.get<Membre[]>(`/api/profils/${profil.id}/membres`).subscribe({
      next: (m) => {
        this.membres.set(m);
        this.chargementMembres.set(false);
      },
      error: (e: unknown) => {
        this.chargementMembres.set(false);
        this.erreurNomination.set(messageErreur(e, 'Les membres n’ont pas pu être chargés.'));
      },
    });
  }

  protected fermerNomination(): void {
    this.nomination.set(null);
    this.membres.set([]);
    this.erreurNomination.set(null);
  }

  /**
   * Nomme, ou démet.
   *
   * ⚠️ On recharge la LISTE ENTIÈRE après coup, et non la seule carte touchée.
   *    Nommer un chef en démet un autre — parfois sur la même carte, parfois
   *    ailleurs si la personne dirigeait déjà un autre service. Rafraîchir une
   *    seule ligne laisserait un second chef affiché quelque part.
   */
  protected nommer(membreId: number | null): void {
    const profil = this.nomination();
    if (!profil) {
      return;
    }
    this.erreurNomination.set(null);

    const requete = membreId === null
      ? this.http.delete<void>(`/api/services/${profil.id}/chef`)
      : this.http.put<void>(`/api/services/${profil.id}/chef/${membreId}`, null);

    requete.subscribe({
      next: () => {
        this.fermerNomination();
        this.charger();
      },
      error: (e: unknown) =>
        this.erreurNomination.set(messageErreur(e, 'La nomination a échoué.')),
    });
  }

  /** Celui qui dirige déjà ce service, vu depuis la liste des membres. */
  protected dirigeDeja(membre: Membre): boolean {
    const profil = this.nomination();
    return profil !== null
      && membre.profils.some((p) => p.id === profil.id && p.chef === true);
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

    this.http.get<ProfilMetier[]>('/api/profils').subscribe({
      next: (l) => {
        this.liste.set(l);
        fini();
      },
      error: (e: unknown) => {
        this.erreur.set(messageErreur(e, 'Les profils n’ont pas pu être chargés.'));
        fini();
      },
    });

    this.http.get<Fonctionnalite[]>('/api/profils/fonctionnalites').subscribe({
      next: (l) => {
        this.fonctionnalites.set(l);
        fini();
      },
      error: () => {
        this.fonctionnalites.set([]);
        fini();
      },
    });
  }

  // -------------------------------------------------------------------------
  // Le formulaire
  // -------------------------------------------------------------------------

  protected ouvrirCreation(): void {
    this.enEdition.set(null);
    this.nom.set('');
    this.description.set('');
    this.choisies.set([]);
    this.filtre.set('');
    this.moduleOuvert.set(null);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected ouvrirEdition(profil: ProfilMetier): void {
    this.enEdition.set(profil);
    this.nom.set(profil.nom);
    this.description.set(profil.description ?? '');
    this.choisies.set([...profil.permissions]);
    this.filtre.set('');
    this.moduleOuvert.set(null);
    this.erreurFormulaire.set(null);
    this.formulaireOuvert.set(true);
  }

  protected fermer(): void {
    this.formulaireOuvert.set(false);
    this.enEdition.set(null);
  }

  protected basculer(code: string): void {
    this.choisies.update((liste) =>
      liste.includes(code) ? liste.filter((c) => c !== code) : [...liste, code],
    );
  }

  protected estChoisie(code: string): boolean {
    return this.choisies().includes(code);
  }

  protected basculerModule(groupe: GroupeModule): void {
    const codes = groupe.fonctionnalites.map((f) => f.code);
    const toutes = codes.every((c) => this.choisies().includes(c));

    this.choisies.update((liste) =>
      toutes
        ? liste.filter((c) => !codes.includes(c))
        : [...new Set([...liste, ...codes])],
    );
  }

  protected compteDansModule(groupe: GroupeModule): number {
    return groupe.fonctionnalites.filter((f) => this.choisies().includes(f.code)).length;
  }

  protected deplier(module: string): void {
    this.moduleOuvert.update((ouvert) => (ouvert === module ? null : module));
  }

  protected estDeplie(module: string): boolean {
    // Une recherche en cours déplie tout : masquer un résultat trouvé serait
    // le contraire de ce qu'on demande en cherchant.
    return this.moduleOuvert() === module || this.filtre().trim().length > 0;
  }

  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);

    const corps = {
      nom: this.nom().trim(),
      description: this.description().trim() || null,
      permissions: this.choisies(),
    };

    const existant = this.enEdition();
    const requete = existant
      ? this.http.put<ProfilMetier>(`/api/profils/${existant.id}`, corps)
      : this.http.post<ProfilMetier>('/api/profils', corps);

    requete.subscribe({
      next: () => {
        this.enregistrement.set(false);
        this.fermer();
        this.charger();
      },
      error: (e: unknown) => {
        this.enregistrement.set(false);
        this.erreurFormulaire.set(messageErreur(e, 'Le profil n’a pas pu être enregistré.'));
      },
    });
  }

  /**
   * Désactive un profil, jamais ne le supprime.
   *
   * <p>Des responsables le portent. Désactiver n'enlève rien à ceux qui l'ont
   * déjà : cela empêche seulement d'y affecter quelqu'un de nouveau.</p>
   */
  protected basculerActivation(profil: ProfilMetier): void {
    const actif = profil.statut === 'ACTIF';
    const url = `/api/profils/${profil.id}/activation`;
    const requete = actif ? this.http.delete<ProfilMetier>(url) : this.http.post<ProfilMetier>(url, null);

    requete.subscribe({
      next: () => this.charger(),
      error: (e: unknown) => this.erreur.set(messageErreur(e, 'L’opération a échoué.')),
    });
  }
}

