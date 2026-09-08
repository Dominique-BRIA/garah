import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { Avatar, Icone, Membre, messageErreur, ProfilMetier, ServiceSession } from 'garah-ui';

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
  imports: [Icone, Avatar],
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

  /** Vrai si c'est moi : on ne s'applique pas ces gestes à soi-même. */
  protected estMoi(membre: Membre): boolean {
    return membre.id === this.session.utilisateur()?.id;
  }
}
