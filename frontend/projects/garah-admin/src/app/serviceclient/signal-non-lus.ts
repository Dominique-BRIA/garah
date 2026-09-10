import { Injectable, signal } from '@angular/core';

/**
 * Demande au menu de recompter les messages non lus — tout de suite.
 *
 * <h2>🎯 Pourquoi</h2>
 *
 * <p>La pastille du menu se recompte toutes les deux minutes : c'est un rappel
 * de fond. Mais quand un agent OUVRE une conversation, ses messages passent à
 * « lu » à l'instant, et la pastille doit le montrer à l'instant. Sans ce
 * signal, elle affichait encore l'ancien nombre pendant deux minutes — et on
 * croyait que la lecture n'avait servi à rien.</p>
 *
 * <p>Un simple compteur de demandes : le menu l'écoute et recompte à chaque
 * incrément. L'écran des conversations n'a pas à savoir comment le menu compte.</p>
 */
@Injectable({ providedIn: 'root' })
export class SignalNonLus {
  private readonly demandesInternes = signal(0);

  /** Le nombre de demandes, pour qu'un effet puisse s'y abonner. */
  readonly demandes = this.demandesInternes.asReadonly();

  demander(): void {
    this.demandesInternes.update((n) => n + 1);
  }
}
