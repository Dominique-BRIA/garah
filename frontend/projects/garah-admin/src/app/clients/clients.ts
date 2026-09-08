import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  badgeNiveauRisque,
  badgeStatutClient,
  EvaluationRisque,
  FicheClient,
  Icone,
  libelleNiveauRisque,
  libelleStatutClient,
  messageErreur,
  Page,
  Pagination,
  ResumeClient,
  ServiceSession,
  StatutClient,
  STATUTS_CLIENT,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Les clients.
 *
 * <h2>Deux publics, deux routes</h2>
 *
 * <p>🎯 Cet écran est <b>entièrement</b> réservé aux comptes internes. Un
 * client gère le sien depuis son profil — une route qui ne connaît que lui,
 * jamais un identifiant en paramètre. Les fusionner derrière un
 * « si responsable » ferait qu'un oubli de condition ouvre toutes les fiches à
 * n'importe quel compte connecté.</p>
 *
 * <h2>Le score de risque doit s'expliquer</h2>
 *
 * <p>« HIGH » tout seul ne permet aucune décision : on ne sait ni pourquoi, ni
 * quoi vérifier. L'écran affiche donc <b>les signaux</b>, et le score n'est
 * qu'un résumé de cette liste.</p>
 *
 * <p>⚠️ La surveillance <b>observe</b>, elle ne décide pas. Aucun score ne
 * suspend un compte : c'est un humain qui tranche, avec le bouton d'à côté.
 * Automatiser ce lien refuserait des clients légitimes sans recours.</p>
 *
 * <h2>Consulter ne laisse pas de trace de score</h2>
 *
 * <p>L'évaluation se lit en {@code GET}, qui <b>n'archive rien</b>. Consulter
 * dix fois le même dossier produirait sinon dix scores identiques, et la
 * courbe d'évolution deviendrait illisible. Archiver est un geste explicite.</p>
 */
@Component({
  selector: 'ga-clients',
  imports: [FormsModule, Icone, Pagination],
  templateUrl: './clients.html',
  styleUrl: './clients.scss',
})
export class Clients {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeClient[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_CLIENT;
  protected readonly statut = signal<StatutClient | ''>('');
  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');

  // --- La fiche ouverte ---
  protected readonly fiche = signal<FicheClient | null>(null);
  protected readonly risque = signal<EvaluationRisque | null>(null);
  protected readonly chargementFiche = signal(false);
  protected readonly erreurFiche = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  // --- Le formulaire de correction ---
  protected readonly formEdition = signal(false);
  protected readonly telephone = signal('');
  protected readonly langue = signal('fr');

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? libelleStatutClient(code) : null;
  });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const parametres = new URLSearchParams({
      page: String(this.page()),
      taille: String(TAILLE_PAGE),
    });
    if (this.statut()) {
      parametres.set('statut', this.statut());
    }
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }

    this.http.get<Page<ResumeClient>>(`/api/clients?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Les clients n’ont pas pu être chargés.'));
      },
    });
  }

  protected filtrer(code: StatutClient | ''): void {
    this.statut.set(code);
    this.page.set(0);
    this.charger();
  }

  /**
   * Le texte saisi et le filtre appliqué sont deux signaux distincts.
   *
   * <p>Les fondre ferait changer le message « aucun résultat pour… » pendant
   * qu'on tape, alors que la liste montre encore l'ancien filtre.</p>
   */
  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected effacerRecherche(): void {
    this.recherche.set('');
    this.filtreApplique.set('');
    this.page.set(0);
    this.charger();
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.charger();
  }

  // -------------------------------------------------------------------------
  // La fiche
  // -------------------------------------------------------------------------

  protected ouvrir(c: ResumeClient): void {
    this.chargementFiche.set(true);
    this.erreurFiche.set(null);
    this.fiche.set(null);
    this.risque.set(null);
    this.formEdition.set(false);

    this.http.get<FicheClient>(`/api/clients/${c.id}`).subscribe({
      next: (f) => {
        this.chargementFiche.set(false);
        this.fiche.set(f);
        this.chargerRisque(c.id);
      },
      error: (e: unknown) => {
        this.chargementFiche.set(false);
        this.erreurFiche.set(messageErreur(e, 'Cette fiche n’a pas pu être ouverte.'));
      },
    });
  }

  /**
   * L'évaluation, en lecture seule.
   *
   * <p>Chargée sans bloquer : la fiche reste lisible sans le score, et tous
   * les agents n'ont pas la permission de le voir.</p>
   */
  private chargerRisque(clientId: number): void {
    if (!this.session.peut('SURVEILLANCE_CONSULTER_RISQUE')) {
      return;
    }

    this.http
      .get<EvaluationRisque>(`/api/surveillance/clients/${clientId}/risque`)
      .subscribe({
        next: (r) => this.risque.set(r),
        error: () => this.risque.set(null),
      });
  }

  protected fermer(): void {
    this.fiche.set(null);
    this.risque.set(null);
    this.erreurFiche.set(null);
    this.formEdition.set(false);
    this.chargementFiche.set(false);
  }

  // -------------------------------------------------------------------------
  // Les gestes
  // -------------------------------------------------------------------------

  protected ouvrirEdition(): void {
    const f = this.fiche();
    if (!f) {
      return;
    }
    this.telephone.set(f.telephone ?? '');
    this.langue.set(f.langue);
    this.erreurFiche.set(null);
    this.formEdition.set(true);
  }

  protected enregistrer(): void {
    const f = this.fiche();
    if (!f || this.action()) {
      return;
    }
    this.action.set('edition');
    this.erreurFiche.set(null);

    this.http
      .put<FicheClient>(`/api/clients/${f.id}`, {
        telephone: this.telephone().trim(),
        langue: this.langue(),
      })
      .subscribe({
        next: (maj) => {
          this.action.set(null);
          this.formEdition.set(false);
          this.fiche.set(maj);
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurFiche.set(messageErreur(e, 'Les coordonnées n’ont pas pu être enregistrées.'));
        },
      });
  }

  /**
   * Suspend ou réactive.
   *
   * <p>Un geste <b>humain</b>, jamais déclenché par un score. La surveillance
   * observe ; elle ne décide pas.</p>
   */
  protected basculerActivation(): void {
    const f = this.fiche();
    if (!f || this.action()) {
      return;
    }
    const actif = f.statut !== 'ACTIF';
    this.action.set('activation');
    this.erreurFiche.set(null);

    this.http.put<FicheClient>(`/api/clients/${f.id}/activation`, { actif }).subscribe({
      next: (maj) => {
        this.action.set(null);
        this.fiche.set(maj);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreurFiche.set(messageErreur(e, 'Le statut n’a pas pu être changé.'));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected suspendable(): boolean {
    return this.fiche()?.statut === 'ACTIF' && this.session.peut('CLIENT_DESACTIVER');
  }

  protected reactivable(): boolean {
    return this.fiche()?.statut !== 'ACTIF' && this.session.peut('CLIENT_ACTIVER');
  }

  /** Un blocage de sécurité ne se lève pas d'ici : il vient d'une alerte. */
  protected bloque(): boolean {
    return this.fiche()?.statut === 'BLOQUE';
  }

  protected libelle(statut: string): string {
    return libelleStatutClient(statut);
  }

  protected badge(statut: string): string {
    return badgeStatutClient(statut);
  }

  protected libelleRisque(niveau: string): string {
    return libelleNiveauRisque(niveau);
  }

  protected badgeRisque(niveau: string): string {
    return badgeNiveauRisque(niveau);
  }

  /**
   * Le poids d'un signal, en pourcentage lisible.
   *
   * <p>Un poids de « 0.35 » ne dit rien à un agent ; « 35 % » se compare d'un
   * coup d'œil aux autres lignes.</p>
   */
  protected poids(valeur: number): string {
    return `${Math.round(valeur * 100)} %`;
  }

  protected date(iso: string | null): string {
    if (!iso) {
      return '';
    }
    return new Date(iso).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  }

  protected dateHeure(iso: string | null): string {
    if (!iso) {
      return '';
    }
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

