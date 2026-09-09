import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ActiviteMembre,
  Avatar,
  Icone,
  Membre,
  messageErreur,
  Page,
  ProfilMetier,
  ServiceSession,
} from 'garah-ui';

/**
 * Mon service — l'écran du chef.
 *
 * <h2>🎯 Il ne montre QUE ce qu'on dirige</h2>
 *
 * <p>Un chef n'administre pas l'équipe : il administre la sienne. L'écran de
 * l'équipe existe déjà pour l'Admin, et donner au chef une liste complète où
 * la moitié des boutons échouent serait pire que de ne rien donner.</p>
 *
 * <h2>⚠️ Les boutons n'apparaissent que si la permission est là</h2>
 *
 * <p>Un chef reçoit ce que l'Admin lui accorde, et rien de plus : suspendre,
 * modifier, réinitialiser un mot de passe se donnent séparément. Afficher un
 * bouton qu'on n'a pas le droit d'utiliser fait cliquer, échouer, et
 * recommencer.</p>
 *
 * <h2>⚠️ Le serveur revérifie tout</h2>
 *
 * <p>Ce que cet écran cache reste refusé côté serveur — rang et portée. Un
 * masquage n'est pas une sécurité : c'est une politesse.</p>
 */
@Component({
  selector: 'gu-mon-service',
  imports: [Icone, Avatar, FormsModule],
  templateUrl: './mon-service.html',
  styleUrl: './mon-service.scss',
})
export class MonService {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  /** Les identifiants des services que je dirige. */
  protected readonly services = signal<readonly number[]>([]);

  /** Les profils correspondants, pour leur nom. */
  protected readonly profils = signal<readonly ProfilMetier[]>([]);

  protected readonly membres = signal<readonly Membre[]>([]);
  protected readonly serviceChoisi = signal<number | null>(null);

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<number | null>(null);

  // --- Les deux panneaux, et ce qu'ils partagent -----------------------------
  //
  // Un seul est ouvert à la fois : ce sont deux gestes distincts sur la même
  // personne, et les mêler ferait réinitialiser un mot de passe en croyant
  // corriger un prénom.
  protected readonly edition = signal<Membre | null>(null);
  protected readonly reinitialisation = signal<Membre | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreurPanneau = signal<string | null>(null);

  /** Ce qui vient de se passer, dit à l'écran plutôt que disparu en silence. */
  protected readonly succes = signal<string | null>(null);

  protected readonly nom = signal('');
  protected readonly prenom = signal('');
  protected readonly telephone = signal('');
  protected readonly dateEmbauche = signal('');

  protected readonly motDePasse = signal('');
  /** Le mot de passe se transmet : on doit pouvoir le relire pour le dicter. */
  protected readonly mdpVisible = signal(false);

  // --- L'activité d'un membre ------------------------------------------------
  //
  // ⚠️ Ce n'est PAS le journal d'audit. Celui-ci porte les clichés des objets
  //    modifiés — potentiellement n'importe quelle donnée du système — et
  //    reste réservé au module sécurité. Un chef reçoit le geste, l'objet visé
  //    et l'heure. La réduction se fait sur le serveur, pas ici.
  protected readonly activiteDe = signal<Membre | null>(null);
  protected readonly activite = signal<readonly ActiviteMembre[]>([]);
  protected readonly chargementActivite = signal(false);

  /** Les services que je dirige, nommés. */
  protected readonly mesServices = computed<readonly ProfilMetier[]>(() => {
    const miens = this.services();
    return this.profils().filter((p) => miens.includes(p.id));
  });

  protected readonly serviceCourant = computed<ProfilMetier | null>(() => {
    const id = this.serviceChoisi();
    return id === null ? null : (this.mesServices().find((p) => p.id === id) ?? null);
  });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    // ⚠️ « Quels services je dirige » ne demande AUCUNE permission : la
    //    réponse est vide pour qui n'en dirige aucun, et c'est la seule
    //    information qu'elle donne. La garder sous permission obligerait à
    //    donner un droit à toute l'équipe pour apprendre qu'on n'a rien.
    this.http.get<number[]>('/api/services/miens').subscribe({
      next: (ids) => {
        this.services.set(ids);
        if (ids.length === 0) {
          this.chargement.set(false);
          return;
        }
        this.chargerLesProfils(ids);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Vos services n’ont pas pu être chargés.'));
      },
    });
  }

  private chargerLesProfils(miens: readonly number[]): void {
    this.http.get<ProfilMetier[]>('/api/profils').subscribe({
      next: (tous) => {
        this.profils.set(tous);
        this.chargement.set(false);
        // Un seul service : on l'ouvre. Faire choisir entre une seule option
        // est un clic pour rien.
        this.choisir(miens[0]);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Vos services n’ont pas pu être chargés.'));
      },
    });
  }

  protected choisir(serviceId: number): void {
    this.serviceChoisi.set(serviceId);
    this.membres.set([]);
    this.erreur.set(null);
    this.succes.set(null);

    this.http.get<Membre[]>(`/api/services/${serviceId}/membres`).subscribe({
      next: (m) => this.membres.set(m),
      error: (e: unknown) =>
        this.erreur.set(messageErreur(e, 'Les membres n’ont pas pu être chargés.')),
    });
  }

  // ---------------------------------------------------------------------------
  // Les gestes sur un membre
  // ---------------------------------------------------------------------------

  protected basculerActivation(membre: Membre): void {
    const actif = membre.statut === 'ACTIF';
    const url = `/api/services/membres/${membre.id}/activation`;

    this.action.set(membre.id);
    this.erreur.set(null);

    const requete = actif
      ? this.http.delete<Membre>(url)
      : this.http.post<Membre>(url, null);

    requete.subscribe({
      next: () => {
        this.action.set(null);
        // On recharge la liste plutôt que la seule ligne : le serveur peut
        // avoir refusé pour une raison qu'on n'affiche pas, et une liste
        // fraîche dit la vérité.
        const service = this.serviceChoisi();
        if (service !== null) {
          this.choisir(service);
        }
      },
      error: (e: unknown) => {
        this.action.set(null);
        // Le serveur nomme la raison — hors de mon service, rang trop élevé.
        // Son message est plus juste que celui qu'on inventerait.
        this.erreur.set(messageErreur(e, 'Ce geste n’a pas pu être appliqué.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Modifier l'identité
  // -------------------------------------------------------------------------

  /**
   * Ouvre la fiche d'un membre.
   *
   * <p>⚠️ L'adresse e-mail n'y figure pas comme champ : c'est l'identifiant de
   * connexion, et la changer déconnecterait la personne sans le lui dire. Elle
   * est affichée pour qu'on sache de qui il s'agit, pas pour être touchée.</p>
   */
  protected ouvrirEdition(membre: Membre): void {
    this.fermerPanneaux();
    this.nom.set(membre.nom);
    this.prenom.set(membre.prenom ?? '');
    this.telephone.set(membre.telephone ?? '');
    this.dateEmbauche.set(membre.dateEmbauche ?? '');
    this.edition.set(membre);
  }

  protected enregistrerIdentite(): void {
    const membre = this.edition();
    if (!membre || this.enregistrement()) {
      return;
    }

    // 🎯 Ce qui manque se dit AVANT le clic — ici juste après, faute de place,
    //    mais toujours en nommant le champ plutôt qu'en rendant le bouton
    //    inerte sans un mot.
    if (!this.nom().trim()) {
      this.erreurPanneau.set('Le nom est obligatoire.');
      return;
    }

    this.enregistrement.set(true);
    this.erreurPanneau.set(null);

    this.http
      .patch<Membre>(`/api/services/membres/${membre.id}`, {
        nom: this.nom().trim(),
        prenom: this.prenom().trim(),
        telephone: this.telephone().trim(),
        dateEmbauche: this.dateEmbauche() || null,
      })
      .subscribe({
        next: () => {
          this.enregistrement.set(false);
          this.fermerPanneaux();
          // ⚠️ Le rechargement remet le bandeau à zéro : le message vient
          //    APRÈS, sinon il disparaîtrait dans la milliseconde.
          this.rafraichir();
          this.succes.set(`La fiche de ${this.nom().trim()} est enregistrée.`);
        },
        error: (e: unknown) => this.echoue(e, 'La fiche n’a pas pu être enregistrée.'),
      });
  }

  // -------------------------------------------------------------------------
  // Redonner un mot de passe
  // -------------------------------------------------------------------------

  protected ouvrirMotDePasse(membre: Membre): void {
    this.fermerPanneaux();
    this.motDePasse.set('');
    // Visible d'emblée : ce mot de passe doit être dicté ou recopié. Le masquer
    // ferait taper à l'aveugle un texte qu'on va de toute façon communiquer.
    this.mdpVisible.set(true);
    this.reinitialisation.set(membre);
  }

  protected enregistrerMotDePasse(): void {
    const membre = this.reinitialisation();
    if (!membre || this.enregistrement()) {
      return;
    }

    if (this.motDePasse().length < 6) {
      this.erreurPanneau.set(
        `Six caractères au moins — ${this.motDePasse().length} saisi(s).`,
      );
      return;
    }

    this.enregistrement.set(true);
    this.erreurPanneau.set(null);

    this.http
      .post<void>(`/api/services/membres/${membre.id}/mot-de-passe`, {
        motDePasse: this.motDePasse(),
      })
      .subscribe({
        next: () => {
          this.enregistrement.set(false);
          const qui = `${membre.prenom ?? ''} ${membre.nom}`.trim();
          this.fermerPanneaux();
          // ⚠️ On rappelle le geste qui reste à faire : un mot de passe changé
          //    et non communiqué met simplement la personne dehors.
          this.succes.set(
            `Mot de passe remplacé. Communiquez-le à ${qui} : c’est le seul moment où il est lisible.`,
          );
        },
        error: (e: unknown) => this.echoue(e, 'Le mot de passe n’a pas pu être remplacé.'),
      });
  }

  // -------------------------------------------------------------------------

  // -------------------------------------------------------------------------
  // Voir ce qu'un membre a fait
  // -------------------------------------------------------------------------

  protected ouvrirActivite(membre: Membre): void {
    this.fermerPanneaux();
    this.activite.set([]);
    this.chargementActivite.set(true);
    this.activiteDe.set(membre);

    this.http
      .get<Page<ActiviteMembre>>(`/api/services/membres/${membre.id}/activite`)
      .subscribe({
        next: (p) => {
          this.activite.set(p.content);
          this.chargementActivite.set(false);
        },
        error: (e: unknown) => {
          this.chargementActivite.set(false);
          this.erreurPanneau.set(messageErreur(e, 'L’activité n’a pas pu être chargée.'));
        },
      });
  }

  /**
   * Le code du geste, rendu lisible.
   *
   * <p>Pas de table de libellés : il faudrait la tenir à jour à chaque geste
   * ajouté, sous peine d'afficher un code brut le jour où on l'oublie.</p>
   */
  protected libelleGeste(code: string): string {
    const mots = code.toLowerCase().replace(/_/g, ' ');
    return mots.charAt(0).toUpperCase() + mots.slice(1);
  }

  protected quand(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  // -------------------------------------------------------------------------

  protected fermerPanneaux(): void {
    this.edition.set(null);
    this.reinitialisation.set(null);
    this.activiteDe.set(null);
    this.erreurPanneau.set(null);
    this.enregistrement.set(false);
    this.motDePasse.set('');
  }

  private rafraichir(): void {
    const service = this.serviceChoisi();
    if (service !== null) {
      this.choisir(service);
    }
  }

  private echoue(e: unknown, repli: string): void {
    this.enregistrement.set(false);
    // Le serveur nomme la raison — hors de mon service, rang trop élevé. Son
    // message est plus juste que celui qu'on inventerait.
    this.erreurPanneau.set(messageErreur(e, repli));
  }

  /** Vrai si c'est moi : on ne s'applique pas ces gestes à soi-même. */
  protected estMoi(membre: Membre): boolean {
    return membre.id === this.session.utilisateur()?.id;
  }
}
