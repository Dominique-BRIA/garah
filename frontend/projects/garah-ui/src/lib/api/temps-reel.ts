import { Injectable, inject } from '@angular/core';

import { ConfigurationApi } from './configuration-api';
import { ServiceSession } from './service-session';

/**
 * Le caractère qui termine une trame STOMP : l octet NUL.
 *
 * ⚠️ Écrit en échappement, jamais en clair. Un octet nul posé tel quel dans un
 *    fichier source se confond à l œil avec un espace et survit mal aux outils
 *    de format — et le jour où il devient un vrai espace, plus AUCUNE trame
 *    n est reconnue, sans le moindre message d erreur.
 */
const FIN = '\u0000';

/** Les attentes successives avant de retenter, en millisecondes. */
const REPRISES = [1_000, 2_000, 5_000, 10_000, 30_000];

/**
 * Le temps réel : STOMP sur WebSocket, écrit à la main.
 *
 * <h2>🎯 Ce que ça remplace</h2>
 *
 * <p>Un message envoyé n'apparaissait chez l'autre qu'au rechargement de la
 * page. Chacun rafraîchissait pour savoir si l'autre avait parlé — ce qui, sur
 * une conversation vive, revient à recharger toutes les vingt secondes.</p>
 *
 * <h2>⚠️ Pourquoi pas une bibliothèque STOMP</h2>
 *
 * <p>Le protocole tient en quarante lignes : une commande, des en-têtes, une
 * ligne vide, un corps, un octet nul. Un paquet pèserait plus que le code qui
 * l'utilise, et il faudrait le télécharger à chaque passage de CI.</p>
 *
 * <p>⚠️ Ce client ne fait QUE recevoir. On écrit toujours en HTTP, qui sait
 * dire « refusé » et pourquoi — un POST rejeté affiche un message, une trame
 * STOMP perdue ne dit rien à personne. Le serveur est réglé dans le même
 * esprit : rien n'écoute sur <code>/app</code>.</p>
 *
 * <h2>⚠️ Le jeton voyage dans la trame CONNECT, pas dans l'URL</h2>
 *
 * <p>L'API WebSocket du navigateur ne permet pas d'ajouter un en-tête à
 * l'ouverture. Mettre le jeton dans l'URL le ferait finir dans les journaux du
 * serveur et du mandataire ; un cookie rouvrirait le CSRF fermé ailleurs. La
 * trame CONNECT est un message applicatif : ni l'un, ni l'autre.</p>
 */
@Injectable({ providedIn: 'root' })
export class ServiceTempsReel {
  private readonly config = inject(ConfigurationApi);
  private readonly session = inject(ServiceSession);

  private prise: WebSocket | null = null;
  private connecte = false;
  private compteur = 0;
  private essais = 0;
  private minuterie: ReturnType<typeof setTimeout> | null = null;

  /** Les abonnements vivants, par identifiant de souscription. */
  private readonly abonnes = new Map<string, Abonnement>();

  /**
   * Écoute une destination. Rend la fonction qui coupe l'écoute.
   *
   * <p>La connexion s'ouvre au PREMIER abonnement et se ferme au dernier : un
   * écran qui n'écoute rien ne tient pas de prise ouverte.</p>
   */
  abonner<T>(destination: string, surMessage: (charge: T) => void): () => void {
    const id = `sub-${this.compteur++}`;
    this.abonnes.set(id, { destination, surMessage: surMessage as (c: unknown) => void });

    if (this.connecte) {
      this.souscrire(id, destination);
    } else {
      this.ouvrir();
    }

    return () => {
      this.abonnes.delete(id);
      if (this.connecte) {
        this.envoyer('UNSUBSCRIBE', { id });
      }
      if (this.abonnes.size === 0) {
        this.fermer();
      }
    };
  }

  // ---------------------------------------------------------------------------
  // La prise
  // ---------------------------------------------------------------------------

  private ouvrir(): void {
    if (this.prise || this.abonnes.size === 0) {
      return;
    }

    // ⚠️ Sans session, on n'ouvre RIEN. Le serveur accepterait la connexion —
    //    il ne refuse pas brutalement, pour ne pas faire boucler le client —
    //    mais aucune file personnelle ne lui serait adressée. Une prise
    //    ouverte qui ne recevra jamais rien est pire qu'aucune prise : elle a
    //    l'air de marcher.
    const jeton = this.session.jetonAcces();
    if (!jeton) {
      return;
    }

    const adresse = this.config.baseUrl.replace(/^http/, 'ws') + '/ws';
    let prise: WebSocket;
    try {
      prise = new WebSocket(adresse);
    } catch {
      this.reprendre();
      return;
    }
    this.prise = prise;

    prise.onopen = () => {
      // `accept-version` est obligatoire. `heart-beat:0,0` : ni l'un ni
      // l'autre n'en envoie — le courtier en mémoire de Spring n'en émet pas
      // sans ordonnanceur, et un client qui en attendrait couperait à tort.
      this.envoyer('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
        Authorization: `Bearer ${jeton}`,
      });
    };

    prise.onmessage = (evenement) => this.recevoir(String(evenement.data));

    prise.onclose = () => {
      this.connecte = false;
      this.prise = null;
      // Une fermeture n'est pas une erreur : le réseau mobile coupe. On
      // retente tant qu'un écran écoute.
      this.reprendre();
    };

    // onerror précède toujours onclose : on laisse onclose faire le travail,
    // sinon on programmerait DEUX reprises pour une seule coupure.
    prise.onerror = () => {};
  }

  private fermer(): void {
    if (this.minuterie !== null) {
      clearTimeout(this.minuterie);
      this.minuterie = null;
    }
    this.connecte = false;
    const prise = this.prise;
    this.prise = null;
    if (prise) {
      // On retire onclose AVANT : sinon la fermeture volontaire déclencherait
      // une reprise, et la prise se rouvrirait toute seule.
      prise.onclose = null;
      try {
        prise.close();
      } catch {
        /* déjà fermée */
      }
    }
  }

  /**
   * Retente, de plus en plus espacé.
   *
   * <p>⚠️ Un intervalle FIXE est ce qu'il ne faut pas faire. Quand le serveur
   * redémarre, tous les postes ouverts retentent en même temps et le noient au
   * moment où il est le plus fragile.</p>
   */
  private reprendre(): void {
    if (this.abonnes.size === 0 || this.minuterie !== null) {
      return;
    }
    const attente = REPRISES[Math.min(this.essais, REPRISES.length - 1)];
    this.essais++;
    this.minuterie = setTimeout(() => {
      this.minuterie = null;
      this.ouvrir();
    }, attente);
  }

  // ---------------------------------------------------------------------------
  // Le protocole
  // ---------------------------------------------------------------------------

  private envoyer(commande: string, entetes: Record<string, string>, corps = ''): void {
    if (!this.prise || this.prise.readyState !== WebSocket.OPEN) {
      return;
    }
    const lignes = Object.entries(entetes).map(([c, v]) => `${c}:${v}`);
    this.prise.send(`${commande}\n${lignes.join('\n')}\n\n${corps}${FIN}`);
  }

  private souscrire(id: string, destination: string): void {
    this.envoyer('SUBSCRIBE', { id, destination });
  }

  private recevoir(donnees: string): void {
    // Une trame WebSocket peut en contenir plusieurs.
    for (const brute of donnees.split(FIN)) {
      const trame = brute.replace(/^\n+/, '');
      if (!trame) {
        continue;
      }

      const separation = trame.indexOf('\n\n');
      const tete = separation === -1 ? trame : trame.slice(0, separation);
      const corps = separation === -1 ? '' : trame.slice(separation + 2);

      const lignes = tete.split('\n');
      const commande = lignes[0];

      if (commande === 'CONNECTED') {
        this.connecte = true;
        this.essais = 0;
        // On reprend TOUS les abonnements : après une coupure, le serveur ne
        // se souvient de rien. Sans cela l'écran resterait muet en paraissant
        // connecté.
        for (const [id, a] of this.abonnes) {
          this.souscrire(id, a.destination);
        }
        continue;
      }

      if (commande !== 'MESSAGE') {
        // ERROR, RECEIPT : rien à faire. Une ERROR ferme la prise côté
        // serveur, et onclose programmera la reprise.
        continue;
      }

      const entetes = new Map<string, string>();
      for (const ligne of lignes.slice(1)) {
        const i = ligne.indexOf(':');
        if (i > 0) {
          entetes.set(ligne.slice(0, i), ligne.slice(i + 1));
        }
      }

      const abonne = this.abonnes.get(entetes.get('subscription') ?? '');
      if (!abonne) {
        continue;
      }

      try {
        abonne.surMessage(JSON.parse(corps));
      } catch {
        // ⚠️ Un corps illisible ne doit pas tuer la boucle : les trames
        //    suivantes, elles, sont peut-être bonnes.
      }
    }
  }
}

interface Abonnement {
  readonly destination: string;
  readonly surMessage: (charge: unknown) => void;
}
