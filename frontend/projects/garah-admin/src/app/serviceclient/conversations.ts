import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  badgeStatutConversation,
  badgeStatutProposition,
  Conversation,
  Icone,
  libelleStatutConversation,
  libelleStatutProposition,
  messageErreur,
  montantLisible,
  Page,
  Pagination,
  Proposition,
  ResumeConversation,
  ServiceSession,
  StatutConversation,
  STATUTS_CONVERSATION,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Le service client : la file d'attente et les conversations en cours.
 *
 * <h2>La file est PARTAGÉE, et c'est tout le sujet</h2>
 *
 * <p>Plusieurs agents voient la même liste de conversations en attente. Le
 * premier qui clique gagne ; les autres reçoivent « un autre responsable a
 * déjà pris cette conversation » — un {@code 409}, pas une erreur de leur
 * part. C'est le monde qui a changé entre l'affichage et le clic.</p>
 *
 * <p>D'où le rechargement systématique après une prise ratée : rester sur une
 * liste périmée ferait recliquer sur des dossiers déjà partis.</p>
 *
 * <h2>Le chiffre qui trie est celui des non lus</h2>
 *
 * <p>🎯 Un agent qui ouvre cet écran se pose une seule question : <b>laquelle
 * attend ma réponse ?</b> Le total des messages n'y répond pas — une
 * conversation de quarante messages tous lus n'attend rien.</p>
 *
 * <h2>Les propositions de prix vivent DANS la conversation</h2>
 *
 * <p>Elles n'ont pas d'écran à elles. Les en sortir leur ferait perdre leur
 * contexte, et supprimerait la garantie qu'un échange précède toujours un
 * prix négocié.</p>
 */
@Component({
  selector: 'ga-conversations',
  imports: [FormsModule, Icone, Pagination],
  templateUrl: './conversations.html',
  styleUrl: './conversations.scss',
})
export class Conversations {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeConversation[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_CONVERSATION;
  protected readonly statut = signal<StatutConversation | ''>('');

  /** « Mes dossiers » plutôt que « tous » : le filtre le plus utilisé. */
  protected readonly miennes = signal(false);

  // --- Le fil ouvert ---
  protected readonly ouverte = signal<Conversation | null>(null);
  protected readonly propositions = signal<readonly Proposition[]>([]);
  protected readonly chargementFil = signal(false);
  protected readonly action = signal<string | null>(null);
  protected readonly erreurFil = signal<string | null>(null);
  protected readonly reponse = signal('');

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? libelleStatutConversation(code) : null;
  });

  /**
   * Le filtre initial, lu dans l'URL.
   *
   * <p>La carte « Conversations en attente » du tableau de bord compte la
   * file. Y mener sans porter le filtre ferait chercher les trois dossiers
   * libres au milieu de ceux de toute l'équipe.</p>
   *
   * <p>Lu <b>une fois</b>, au démarrage, et non par abonnement : les clics sur
   * les onglets ne réécrivent pas l'URL, donc rien ne pousserait de nouvelle
   * valeur — et s'abonner ferait croire le contraire à qui lit ce code.</p>
   */
  readonly statutInitial = input<string | undefined>(undefined, { alias: 'statut' });

  constructor() {
    queueMicrotask(() => {
      const depuisUrl = this.statutInitial();
      if (depuisUrl && STATUTS_CONVERSATION.some((s) => s.code === depuisUrl)) {
        this.statut.set(depuisUrl as StatutConversation);
      }
      this.charger();
    });
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
    if (this.miennes()) {
      // Le responsable est lu dans le jeton, jamais envoyé ici : un
      // `?responsableId=` laisserait lire la file d'un collègue.
      parametres.set('miennes', 'true');
    }

    this.http.get<Page<ResumeConversation>>(`/api/conversations?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'Les conversations n’ont pas pu être chargées.'));
      },
    });
  }

  protected filtrer(code: StatutConversation | ''): void {
    this.statut.set(code);
    this.page.set(0);
    this.charger();
  }

  protected basculerMiennes(): void {
    this.miennes.update((v) => !v);
    this.page.set(0);
    this.charger();
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.charger();
  }

  // -------------------------------------------------------------------------
  // Le fil
  // -------------------------------------------------------------------------

  protected ouvrir(c: ResumeConversation): void {
    this.chargementFil.set(true);
    this.erreurFil.set(null);
    this.ouverte.set(null);
    this.propositions.set([]);
    this.reponse.set('');

    this.http.get<Conversation>(`/api/conversations/${c.id}`).subscribe({
      next: (fil) => {
        this.chargementFil.set(false);
        this.ouverte.set(fil);
        this.chargerPropositions(c.id);
      },
      error: (e: unknown) => {
        this.chargementFil.set(false);
        this.erreurFil.set(messageErreur(e, 'Cette conversation n’a pas pu être ouverte.'));
      },
    });
  }

  /**
   * Chargées à part, et sans bloquer.
   *
   * <p>Le fil reste lisible même si les propositions manquent : on n'efface
   * pas ce qui s'affiche déjà pour un morceau absent.</p>
   */
  private chargerPropositions(id: number): void {
    this.http.get<Proposition[]>(`/api/conversations/${id}/propositions`).subscribe({
      next: (p) => this.propositions.set(p),
      error: () => this.propositions.set([]),
    });
  }

  protected fermerFil(): void {
    this.ouverte.set(null);
    this.propositions.set([]);
    this.erreurFil.set(null);
    this.chargementFil.set(false);
  }

  // -------------------------------------------------------------------------
  // Les gestes de l'agent
  // -------------------------------------------------------------------------

  /**
   * « Je prends ce dossier. »
   *
   * <p>Peut échouer légitimement : quelqu'un a cliqué avant. On recharge alors
   * la liste — rester sur une liste périmée ferait recliquer sur des dossiers
   * déjà partis.</p>
   */
  protected prendre(c: ResumeConversation, evenement: Event): void {
    evenement.stopPropagation();
    if (this.action()) {
      return;
    }
    this.action.set(`prise-${c.id}`);
    this.erreur.set(null);

    this.http.post<Conversation>(`/api/conversations/${c.id}/affectation`, null).subscribe({
      next: () => {
        this.action.set(null);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(messageErreur(e, 'Cette conversation n’a pas pu être prise.'));
        this.charger();
      },
    });
  }

  protected repondre(): void {
    const fil = this.ouverte();
    if (!fil || !this.reponse().trim() || this.action()) {
      return;
    }
    this.action.set('reponse');
    this.erreurFil.set(null);

    this.http
      .post(`/api/conversations/${fil.id}/messages`, { contenu: this.reponse().trim() })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.reponse.set('');
          // On relit le fil entier plutôt que d'y ajouter le message localement :
          // le compteur de non lus et la date du dernier message sont calculés
          // par le serveur, et deviner leur nouvelle valeur les ferait diverger.
          this.rafraichirFil(fil.id);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurFil.set(messageErreur(e, 'La réponse n’a pas pu être envoyée.'));
        },
      });
  }

  protected clore(): void {
    const fil = this.ouverte();
    if (!fil || this.action()) {
      return;
    }
    this.action.set('cloture');
    this.erreurFil.set(null);

    this.http.post(`/api/conversations/${fil.id}/fermeture`, null).subscribe({
      next: () => {
        this.action.set(null);
        this.rafraichirFil(fil.id);
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreurFil.set(messageErreur(e, 'La conversation n’a pas pu être fermée.'));
      },
    });
  }

  private rafraichirFil(id: number): void {
    this.http.get<Conversation>(`/api/conversations/${id}`).subscribe({
      next: (fil) => {
        this.ouverte.set(fil);
        this.chargerPropositions(id);
        this.charger();
      },
      error: () => this.charger(),
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected prenable(c: ResumeConversation): boolean {
    return c.statut === 'WAITING' && this.session.peut('CONVERSATION_PRENDRE');
  }

  protected closable(): boolean {
    return this.ouverte()?.statut !== 'CLOSED' && this.session.peut('CONVERSATION_FERMER');
  }

  /** Le message vient-il de l'équipe ? On aligne les bulles là-dessus. */
  protected deLEquipe(expediteurId: number): boolean {
    return expediteurId !== this.ouverte()?.clientId;
  }

  protected libelle(statut: string): string {
    return libelleStatutConversation(statut);
  }

  protected badge(statut: string): string {
    return badgeStatutConversation(statut);
  }

  protected libelleProposition(statut: string): string {
    return libelleStatutProposition(statut);
  }

  protected badgeProposition(statut: string): string {
    return badgeStatutProposition(statut);
  }

  protected agirSur(statut: string): boolean {
    return STATUTS_CONVERSATION.find((s) => s.code === statut)?.agir ?? false;
  }

  protected montant(valeur: number): string {
    return montantLisible(valeur, 'XAF');
  }

  /**
   * Depuis combien de temps ce dossier attend.
   *
   * <p>Une date absolue ne dit rien à qui parcourt une file. « Depuis 3 j » se
   * lit d'un coup d'œil, et c'est le seul chiffre qui compte ici.</p>
   */
  protected attente(iso: string): string {
    const heures = Math.floor((Date.now() - new Date(iso).getTime()) / 3_600_000);
    if (heures < 1) {
      return "À l'instant";
    }
    if (heures < 24) {
      return `Depuis ${heures} h`;
    }
    const jours = Math.floor(heures / 24);
    return jours === 1 ? 'Depuis 1 j' : `Depuis ${jours} j`;
  }

  protected dateHeure(iso: string | null): string {
    if (!iso) {
      return '';
    }
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

