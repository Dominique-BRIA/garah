import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icone, ServiceSession, messageErreur } from 'garah-ui';

interface Fil {
  readonly id: number;
  readonly interlocuteurId: number;
  readonly interlocuteurNom: string;
  readonly nonLus: number;
  readonly dateDernier: string;
}

interface MessageInterne {
  readonly id: number;
  readonly filId: number;
  readonly expediteurId: number;
  readonly contenu: string;
  readonly dateLecture: string | null;
  readonly dateEnvoi: string;
}

interface Collegue {
  readonly utilisateurId: number;
  readonly nom: string;
}

/** Vingt secondes : assez pour ne rien manquer, assez peu pour ne pas peser. */
const RYTHME_MS = 20_000;

/**
 * La messagerie interne : se parler entre collègues.
 *
 * <h2>🎯 L'API existait depuis V30, aucun écran ne la consommait</h2>
 *
 * <p>Les routes, les blocages, et jusqu'à la <b>diffusion WebSocket</b>
 * étaient livrés. Rien ne les appelait : une fonctionnalité entière, testée
 * côté serveur, que personne ne pouvait utiliser.</p>
 *
 * <h2>⚠️ Cet écran INTERROGE, il n'écoute pas encore</h2>
 *
 * <p>Le serveur pousse déjà chaque message vers son destinataire —
 * {@code DiffuseurMessagerie}, sur {@code /utilisateur/{id}}. Le consommer
 * demanderait un client STOMP, donc une dépendance de plus à télécharger. Elle
 * n'est pas là, et la connexion de ce poste se compte.</p>
 *
 * <p>On interroge donc toutes les vingt secondes. C'est le comportement des
 * autres écrans, ce n'est pas un régression — et le jour où la bibliothèque
 * arrivera, {@code rafraichir()} est l'unique endroit à remplacer.</p>
 *
 * <h2>Ce qui trie la liste, c'est ce qui attend une réponse</h2>
 *
 * <p>Le serveur rend les fils par date du dernier message. On remonte
 * <b>d'abord</b> ceux qui portent des non-lus : quelqu'un qui ouvre cet écran
 * se demande « qui attend ma réponse ? », pas « qui a parlé en dernier ».</p>
 */
@Component({
  selector: 'ga-messagerie',
  imports: [FormsModule, Icone],
  templateUrl: './messagerie.html',
  styleUrl: './messagerie.scss',
})
export class Messagerie implements OnDestroy {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly fils = signal<readonly Fil[]>([]);
  protected readonly messages = signal<readonly MessageInterne[]>([]);
  protected readonly collegues = signal<readonly Collegue[]>([]);

  protected readonly ouvert = signal<Fil | null>(null);
  protected readonly nouveau = signal(false);
  protected readonly destinataire = signal<number | null>(null);

  protected readonly chargement = signal(true);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly texte = signal('');

  private minuteur?: ReturnType<typeof setInterval>;

  /**
   * Les fils, ceux qui attendent une réponse en tête.
   *
   * <p>⚠️ On copie avant de trier : {@code sort} modifie le tableau reçu, et
   * celui-ci vient d'un signal — le muter ferait diverger l'affichage de sa
   * source sans que rien ne le signale.</p>
   */
  protected readonly filsTries = computed(() =>
    [...this.fils()].sort((a, b) => {
      if ((a.nonLus > 0) !== (b.nonLus > 0)) {
        return a.nonLus > 0 ? -1 : 1;
      }
      return b.dateDernier.localeCompare(a.dateDernier);
    }),
  );

  protected readonly totalNonLus = computed(() =>
    this.fils().reduce((somme, f) => somme + f.nonLus, 0),
  );

  constructor() {
    this.charger();
    this.minuteur = setInterval(() => this.rafraichir(), RYTHME_MS);

    // Ouvrir un fil le marque lu côté serveur : on recharge la liste pour que
    // la pastille disparaisse sans attendre le prochain tour.
    effect(() => {
      const fil = this.ouvert();
      if (fil) {
        this.chargerLeFil(fil.id);
      }
    });
  }

  ngOnDestroy(): void {
    // ⚠️ Sans cela, le minuteur survit à l'écran : on quitte la messagerie et
    //    l'application continue d'interroger le serveur, indéfiniment.
    clearInterval(this.minuteur);
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Fil[]>('/api/messagerie/fils').subscribe({
      next: (f) => {
        this.fils.set(f);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(messageErreur(e, 'La messagerie n’a pas pu être chargée.'));
      },
    });
  }

  /**
   * Le rappel silencieux.
   *
   * <p>⚠️ Il n'affiche <b>ni chargement ni erreur</b> : un rafraîchissement de
   * fond qui fait clignoter l'écran toutes les vingt secondes, ou qui affiche
   * une erreur réseau pendant qu'on écrit, est pire que pas de
   * rafraîchissement du tout.</p>
   */
  private rafraichir(): void {
    this.http.get<Fil[]>('/api/messagerie/fils').subscribe({
      next: (f) => this.fils.set(f),
      error: () => undefined,
    });

    const fil = this.ouvert();
    if (fil) {
      this.chargerLeFil(fil.id, true);
    }
  }

  private chargerLeFil(id: number, silencieux = false): void {
    this.http.get<MessageInterne[]>(`/api/messagerie/fils/${id}`).subscribe({
      next: (m) => this.messages.set(m),
      error: (e: unknown) => {
        if (!silencieux) {
          this.erreur.set(messageErreur(e, 'Ce fil n’a pas pu être ouvert.'));
        }
      },
    });
  }

  protected ouvrir(fil: Fil): void {
    this.nouveau.set(false);
    this.messages.set([]);
    this.ouvert.set(fil);
  }

  protected commencer(): void {
    this.ouvert.set(null);
    this.messages.set([]);
    this.nouveau.set(true);

    // Chargés à la demande : la liste des collègues joignables ne sert qu'ici,
    // et la charger au démarrage ferait payer un appel à qui ne l'ouvre jamais.
    this.http.get<Collegue[]>('/api/messagerie/joignables').subscribe({
      next: (c) => this.collegues.set(c),
      error: (e: unknown) =>
        this.erreur.set(messageErreur(e, 'La liste des collègues est indisponible.')),
    });
  }

  protected envoyer(): void {
    const contenu = this.texte().trim();
    const destinataireId = this.ouvert()?.interlocuteurId ?? this.destinataire();

    if (!contenu || destinataireId === null || this.envoi()) {
      return;
    }

    this.envoi.set(true);
    this.erreur.set(null);

    this.http
      .post<MessageInterne>('/api/messagerie/messages', { destinataireId, contenu })
      .subscribe({
        next: (m) => {
          this.envoi.set(false);
          this.texte.set('');

          // ⚠️ On ajoute le message rendu par le SERVEUR, pas le texte saisi :
          //    lui seul porte son identifiant, sa date et son fil — et c'est
          //    ce qui le range du bon côté au prochain rendu.
          this.messages.update((liste) => [...liste, m]);

          // Un premier message crée le fil : sans ce rechargement, il
          // n'apparaîtrait dans la liste qu'au tour suivant.
          this.charger();
          if (this.nouveau()) {
            this.nouveau.set(false);
          }
        },
        error: (e: unknown) => {
          this.envoi.set(false);
          this.erreur.set(messageErreur(e, 'Le message n’a pas pu être envoyé.'));
        },
      });
  }

  protected estDeMoi(m: MessageInterne): boolean {
    return m.expediteurId === this.session.utilisateur()?.id;
  }

  /** « 14:05 » aujourd'hui, « 09/09 » sinon : dans un fil, l'heure suffit. */
  protected quand(iso: string): string {
    const d = new Date(iso);
    const maintenant = new Date();
    const deux = (n: number) => String(n).padStart(2, '0');

    return d.toDateString() === maintenant.toDateString()
      ? `${deux(d.getHours())}:${deux(d.getMinutes())}`
      : `${deux(d.getDate())}/${deux(d.getMonth() + 1)}`;
  }
}
