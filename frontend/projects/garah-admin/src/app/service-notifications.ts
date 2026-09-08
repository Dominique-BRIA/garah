import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ServiceSession } from 'garah-ui';

import { cleVapid, firebase } from '../environments/firebase';

/** Ce que le navigateur nous laisse faire, en un mot. */
export type EtatNotifications = 'indisponible' | 'a-demander' | 'refuse' | 'actif';

/**
 * Les notifications du back-office.
 *
 * <h2>🎯 Ce qu'elles annoncent ici n'est pas ce qu'elles annoncent au client</h2>
 *
 * <ul>
 *   <li><b>un client attend</b> — conversation ouverte sans propriétaire.
 *       C'est un signal d'<b>équipe</b>, adressé à quiconque a la permission de
 *       la prendre ;</li>
 *   <li><b>le dossier que je suis a bougé</b> — adressé à une <b>personne</b> ;</li>
 *   <li><b>un collègue m'a écrit</b>.</li>
 * </ul>
 *
 * <p>Le serveur décide de qui reçoit quoi : le navigateur ne fait que
 * s'abonner. Filtrer ici reviendrait à télécharger des messages pour les jeter.</p>
 *
 * <h2>🎯 On ne demande JAMAIS la permission au chargement</h2>
 *
 * <p>Une demande qui surgit à l'ouverture d'un back-office est refusée d'un
 * réflexe — et un refus est <b>définitif</b> : le navigateur ne repose plus la
 * question, et l'agent doit aller la débloquer dans ses réglages, ce que
 * personne ne fait. On attend un geste explicite.</p>
 *
 * <h2>⚠️ Firebase est chargé À LA DEMANDE</h2>
 *
 * <p>Un {@code import} statique le mettrait dans le paquet initial — près de
 * 80 ko pour une fonctionnalité que tous n'activeront pas. L'import dynamique
 * le garde dans un morceau à part, chargé au premier clic.</p>
 */
@Injectable({ providedIn: 'root' })
export class ServiceNotifications {
  private readonly http = inject(HttpClient);
  private readonly session = inject(ServiceSession);

  private readonly permission = signal<NotificationPermission | null>(lirePermission());
  private readonly abonne = signal(false);
  private readonly enCours = signal(false);

  /** Le jeton déclaré au serveur, pour savoir lequel retirer. */
  private jeton: string | null = null;

  readonly occupe = this.enCours.asReadonly();

  readonly etat = computed<EtatNotifications>(() => {
    const p = this.permission();
    if (p === null) {
      return 'indisponible';
    }
    if (p === 'denied') {
      return 'refuse';
    }
    return p === 'granted' && this.abonne() ? 'actif' : 'a-demander';
  });

  constructor() {
    // ⚠️ Le jeton appartient au COMPTE, pas au navigateur.
    //
    //    Un poste de back-office est partagé entre équipes qui se relaient.
    //    Garder l'abonnement après une déconnexion enverrait au suivant les
    //    signaux du précédent — y compris des dossiers qu'il n'a pas à voir.
    effect(() => {
      if (!this.session.connecte()) {
        this.retirer();
      }
    });
  }

  /**
   * Active les notifications, sur un geste explicite.
   *
   * <p>Rend un message d'explication en cas d'échec, ou {@code null} si tout
   * s'est bien passé. Un bouton qui ne fait rien et ne dit rien se clique trois
   * fois avant qu'on renonce.</p>
   */
  async activer(): Promise<string | null> {
    if (this.enCours()) {
      return null;
    }
    if (!('Notification' in window) || !('serviceWorker' in navigator)) {
      return 'Ce navigateur ne gère pas les notifications.';
    }
    if (!this.session.connecte()) {
      return 'Connectez-vous pour recevoir des notifications.';
    }

    this.enCours.set(true);
    try {
      const accord = await Notification.requestPermission();
      this.permission.set(accord);

      if (accord !== 'granted') {
        // ⚠️ Un refus est DÉFINITIF côté navigateur : on le dit, plutôt que de
        //    laisser rappuyer sur un bouton qui ne redemandera jamais rien.
        return 'Notifications refusées. Vous pouvez les réactiver dans les '
          + 'réglages de votre navigateur, à la ligne de ce site.';
      }

      const { initializeApp } = await import('firebase/app');
      const { getMessaging, getToken, isSupported } = await import('firebase/messaging');

      if (!(await isSupported())) {
        return 'Ce navigateur ne gère pas encore les notifications web.';
      }

      const messagerie = getMessaging(initializeApp(firebase));
      const jeton = await getToken(messagerie, { vapidKey: cleVapid });
      if (!jeton) {
        return 'L’abonnement n’a pas abouti. Réessayez dans un instant.';
      }

      this.declarer(jeton);
      this.abonne.set(true);
      return null;
    } catch {
      // En développement, l'agent de service n'est pas servi à la racine et
      // l'enregistrement échoue. Le dire, plutôt que de laisser croire à un
      // défaut de l'application.
      return 'Les notifications n’ont pas pu être activées sur ce poste.';
    } finally {
      this.enCours.set(false);
    }
  }

  private declarer(jeton: string): void {
    this.http
      .put<void>('/api/notifications/appareils', { jeton, plateforme: 'WEB' })
      .subscribe({
        next: () => (this.jeton = jeton),
        // Réessayé au prochain clic : le serveur n'enverra rien d'ici là,
        // mais rien n'est cassé pour autant.
        error: () => undefined,
      });
  }

  private retirer(): void {
    const jeton = this.jeton;
    this.jeton = null;
    this.abonne.set(false);
    if (!jeton) {
      return;
    }
    this.http.delete<void>(`/api/notifications/appareils/${jeton}`).subscribe({
      // Le serveur nettoie de lui-même : un envoi vers un jeton mort échoue,
      // et l'échec vaut désinscription.
      error: () => undefined,
    });
  }
}

function lirePermission(): NotificationPermission | null {
  // ⚠️ Le test doit se faire sans y toucher : lire `Notification.permission`
  //    sur un navigateur qui ne connaît pas l'API lève, et l'exception
  //    remonterait jusqu'au démarrage de l'application.
  return typeof Notification === 'undefined' ? null : Notification.permission;
}
