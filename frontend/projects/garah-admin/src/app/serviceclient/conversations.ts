import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  badgeStatutConversation,
  badgeStatutProposition,
  Conversation,
  MessageConversation,
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
  ServiceTempsReel,
  StatutConversation,
  STATUTS_CONVERSATION,
} from 'garah-ui';

/**
 * La file personnelle des messages de conversation.
 *
 * ⚠️ Le prefixe /utilisateur est resolu par Spring vers la session de
 *    l abonne. Une destination partagee aurait livre chaque message a tous
 *    les connectes — y compris aux clients de la boutique, qui ouvrent le
 *    meme WebSocket.
 */
const DESTINATION_CONVERSATIONS = '/utilisateur/file/conversations';

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
  private readonly tempsReel = inject(ServiceTempsReel);
  private readonly destruction = inject(DestroyRef);
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

  /**
   * L'étape de la demande de clôture : 0 fermée, 1 la conséquence, 2 le
   * dernier mot.
   *
   * <p>⚠️ DEUX étapes, et la seconde apporte un fait neuf — le nombre de
   * messages jamais ouverts. Deux écrans identiques n'apprendraient qu'à
   * cliquer deux fois.</p>
   */
  protected readonly etapeCloture = signal(0);
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

    // ⚠️ On COUPE a la destruction. Sans cela, chaque passage sur cet ecran
    //    laisserait un abonnement de plus derriere lui, et un message
    //    arrivant en declencherait autant de rechargements de la liste.
    this.destruction.onDestroy(
      this.tempsReel.abonner<MessageConversation>(
        DESTINATION_CONVERSATIONS,
        (m) => this.surMessageRecu(m),
      ),
    );
  }


  /**
   * L'arrivee d'un message, en direct.
   *
   * <h2>🎯 Il fallait recharger pour voir arriver une reponse</h2>
   *
   * <p>Le client ecrivait, l'agent ne voyait rien tant qu'il n'avait pas
   * recharge. Sur une conversation vive, cela revenait a rafraichir toutes les
   * vingt secondes pour savoir si l'autre avait parle.</p>
   *
   * <p>⚠️ LE MESSAGE EST AJOUTE, LE FIL N'EST PAS RELU. Redemander le fil
   * entier a chaque phrase ferait une requete par message — exactement ce que
   * le temps reel est cense eviter. La trame porte deja le message complet.</p>
   *
   * <p>⚠️ On se protege du DOUBLON. Le serveur pousse aux deux bouts, y
   * compris a l'expediteur : quelqu'un peut avoir le meme dossier ouvert sur
   * son telephone et ici. L'identifiant tranche.</p>
   */
  private surMessageRecu(message: MessageConversation): void {
    // La liste change de toute facon : compteur de non lus, date du dernier
    // message. Ces deux chiffres sont calcules par le serveur, et les deviner
    // ici les ferait diverger.
    this.charger();

    const fil = this.ouverte();
    if (!fil || message.conversationId !== fil.id) {
      // Le message concerne un AUTRE dossier. La liste vient d'etre relue,
      // c'est tout ce qu'il faut : ouvrir le fil de force arracherait l'agent
      // a celui qu'il est en train de lire.
      return;
    }

    if (fil.messages.some((m) => m.id === message.id)) {
      return;
    }

    this.ouverte.set({ ...fil, messages: [...fil.messages, message] });
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
    // ⚠️ Sans cela, une demande de clôture laissée ouverte se reporterait sur
    //    la conversation SUIVANTE — et on clorait la mauvaise.
    this.etapeCloture.set(0);
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
    this.etapeCloture.set(0);
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

  /**
   * Clôt la conversation, après DEUX confirmations.
   *
   * <h2>🎯 Pourquoi deux, et pas une</h2>
   *
   * <p>Clore est <b>sans retour</b> : {@code WAITING → ASSIGNED → CLOSED}, et
   * aucune route ne rouvre. Le client ne peut plus répondre dans ce fil — il
   * doit en ouvrir un nouveau, en réexpliquant tout.</p>
   *
   * <h2>⚠️ Le second rappel APPREND quelque chose</h2>
   *
   * <p>Deux boîtes identiques n'apprennent qu'à cliquer deux fois. Celle-ci
   * dit ce que la première ne pouvait pas dire : <b>combien de messages du
   * client n'ont jamais été ouverts</b>. Clore sur un message non lu est
   * précisément l'erreur qu'on veut attraper — le client a écrit, personne
   * n'a lu, et on ferme la porte.</p>
   *
   * <p>Quand tout a été lu, le second rappel le dit aussi : c'est une
   * information, pas une formalité. On confirme en sachant que rien n'attend.</p>
   *
   * <p>⚠️ {@code confirm} est laid, mais il BLOQUE — c'est la convention du
   * projet pour ce qui ne se rattrape pas. À remplacer par une boîte de
   * dialogue maison, jamais par rien.</p>
   */
  /**
   * Demande confirmation, DANS l'interface.
   *
   * <h2>🎯 Pourquoi ce n'est plus `confirm()`</h2>
   *
   * <p>La boîte grise du navigateur bloquait, ce qui était sa qualité. Mais
   * elle a un défaut qu'on ne découvre qu'en production : après quelques
   * ouvertures, le navigateur propose « empêcher cette page de créer d'autres
   * boîtes de dialogue ». Une fois la case cochée, <b>{@code confirm()} rend
   * `false` sans rien afficher</b> — et le bouton « Clore » devient
   * silencieusement mort. On clique, rien ne se passe, et rien ne l'explique.</p>
   *
   * <p>Le commentaire qui accompagnait ces `confirm()` disait déjà : « à
   * remplacer par une boîte de dialogue maison, jamais par rien ».</p>
   */
  protected clore(): void {
    const fil = this.ouverte();
    if (!fil || this.action()) {
      return;
    }
    this.etapeCloture.set(1);
  }

  /** Le nom du client, pour nommer la personne dans la demande. */
  protected clientDuFil(): string {
    const fil = this.ouverte();
    if (!fil) {
      return 'ce client';
    }
    // ⚠️ Le nom vient de la LISTE : le détail ne porte que l'identifiant.
    return this.liste().find((c) => c.id === fil.id)?.clientNom ?? 'ce client';
  }

  /**
   * Les messages du client jamais ouverts.
   *
   * <p>⚠️ C'est le SEUL fait que la première étape ne pouvait pas dire, et
   * c'est ce qui justifie une seconde. Clore sur un message non lu est
   * précisément l'erreur à attraper : le client a écrit, personne n'a lu, et
   * on ferme la porte.</p>
   */
  protected nonLusDuFil(): number {
    const fil = this.ouverte();
    if (!fil) {
      return 0;
    }
    return fil.messages.filter((m) => !m.lu && !this.deLEquipe(m.expediteurId)).length;
  }

  protected annulerCloture(): void {
    this.etapeCloture.set(0);
  }

  protected etapeSuivante(): void {
    this.etapeCloture.set(2);
  }

  /** Le geste, une fois les deux étapes franchies. */
  protected confirmerCloture(): void {
    const fil = this.ouverte();
    if (!fil || this.action()) {
      return;
    }
    this.etapeCloture.set(0);
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
    // ⚠️ Ni close, ni INFORMATION : une annonce du systeme n'a ete prise par
    //    personne, et il n'y a rien a y clore — le serveur le refuserait.
    const statut = this.ouverte()?.statut;
    return statut !== 'CLOSED' && statut !== 'INFORMATION'
      && this.session.peut('CONVERSATION_FERMER');
  }

  /**
   * Le message vient-il de notre cote ? On aligne les bulles la-dessus.
   *
   * Une annonce du systeme (expediteur nul) est rangee de notre cote : c'est
   * GARAH qui parle au client, pas le client qui parle.
   */
  protected deLEquipe(expediteurId: number | null): boolean {
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

