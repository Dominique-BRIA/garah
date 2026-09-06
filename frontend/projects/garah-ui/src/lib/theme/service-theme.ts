import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'clair' | 'sombre' | 'systeme';

const CLE = 'garah.theme';

/**
 * La bascule clair / sombre.
 *
 * <h2>Trois etats, pas deux</h2>
 *
 * <pre>
 * clair    l'utilisateur a choisi le clair, MEME si son systeme est sombre
 * sombre   l'utilisateur a choisi le sombre
 * systeme  on suit le reglage du systeme d'exploitation
 * </pre>
 *
 * <p>C'est la troisieme valeur qu'on oublie presque toujours. Sans elle, un
 * utilisateur qui force le clair sur un systeme en sombre obtient quand meme
 * le sombre, et le bouton parait casse. `_jetons.scss` porte la garde
 * correspondante : `:root:not([data-theme='light'])` dans la regle media.</p>
 *
 * <p>⚠️ `localStorage` peut LEVER, pas seulement renvoyer vide : navigation
 * privee, cookies bloques, iframe restreinte. Chaque acces est donc garde —
 * une preference d'affichage ne doit jamais empecher l'application de
 * demarrer.</p>
 */
@Injectable({ providedIn: 'root' })
export class ServiceTheme {
  private readonly _theme = signal<Theme>(this.lire());
  readonly theme = this._theme.asReadonly();

  constructor() {
    effect(() => this.appliquer(this._theme()));
  }

  definir(theme: Theme): void {
    this._theme.set(theme);
    try {
      localStorage.setItem(CLE, theme);
    } catch {
      // Preference non retenue d'une session a l'autre. Sans consequence.
    }
  }

  /** Bascule clair ↔ sombre en partant de ce qui est REELLEMENT affiche. */
  basculer(): void {
    this.definir(this.sombreEffectif() ? 'clair' : 'sombre');
  }

  /** Le theme reellement rendu, une fois « systeme » resolu. */
  sombreEffectif(): boolean {
    const choisi = this._theme();
    if (choisi !== 'systeme') {
      return choisi === 'sombre';
    }
    return typeof matchMedia === 'function'
      && matchMedia('(prefers-color-scheme: dark)').matches;
  }

  private appliquer(theme: Theme): void {
    const racine = document.documentElement;
    if (theme === 'systeme') {
      // On RETIRE l'attribut : c'est son absence qui rend la main a la regle
      // media. Poser data-theme="systeme" ne correspondrait a aucun selecteur
      // et laisserait le theme clair par defaut.
      racine.removeAttribute('data-theme');
    } else {
      racine.setAttribute('data-theme', theme === 'sombre' ? 'dark' : 'light');
    }
  }

  private lire(): Theme {
    try {
      const enregistre = localStorage.getItem(CLE);
      if (enregistre === 'clair' || enregistre === 'sombre' || enregistre === 'systeme') {
        return enregistre;
      }
    } catch {
      // Acces refuse : on suit le systeme, ce qui est le meilleur defaut.
    }
    return 'systeme';
  }
}
