import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TRACES } from './traces';

/**
 * Une icône.
 *
 * <pre>
 * &lt;gu-icone nom="box-open" /&gt;
 * &lt;gu-icone nom="circle-notch" tourne /&gt;
 * &lt;gu-icone nom="bars" etiquette="Ouvrir le menu" /&gt;
 * </pre>
 *
 * <h2>Sur l'accessibilité</h2>
 *
 * <p>Une icône est <b>décorative par défaut</b> : elle porte
 * {@code aria-hidden}, et un lecteur d'écran l'ignore. C'est le bon
 * comportement dans l'écrasante majorité des cas — l'icône accompagne un
 * libellé qui, lui, est lu.</p>
 *
 * <p>Quand l'icône est <b>seule</b> — un bouton hamburger, une croix de
 * fermeture — elle devient la seule information disponible. Fournir
 * {@code etiquette} la rend alors annonçable. Sans cela, l'utilisateur au
 * lecteur d'écran entend « bouton » et rien d'autre.</p>
 *
 * <p>⚠️ {@code fill="currentColor"} : l'icône prend la couleur du texte
 * environnant. C'est ce qui la fait suivre le thème clair/sombre sans une
 * seule règle CSS supplémentaire — et ce qu'une image ne saurait pas faire.</p>
 */
@Component({
  selector: 'gu-icone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.viewBox]="boite()"
      [attr.aria-hidden]="etiquette() ? null : 'true'"
      [attr.role]="etiquette() ? 'img' : null"
      [attr.aria-label]="etiquette() || null"
      [class.tourne]="tourne()"
      fill="currentColor"
      focusable="false"
    >
      <path [attr.d]="trace()" />
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        /* Se cale sur la taille du texte plutôt que sur une valeur fixe :
           une icône dans un titre doit grandir avec lui. */
        width: 1em;
        height: 1em;
        flex-shrink: 0;
      }

      svg {
        width: 100%;
        height: 100%;
        display: block;
      }

      .tourne {
        animation: gu-rotation 1.1s linear infinite;
      }

      @keyframes gu-rotation {
        to {
          transform: rotate(360deg);
        }
      }

      /* Le réglage système « réduire les animations » n'est pas un confort :
         les mouvements répétés déclenchent des malaises chez certaines
         personnes. On garde l'icône, on arrête la rotation. */
      @media (prefers-reduced-motion: reduce) {
        .tourne {
          animation: none;
        }
      }
    `,
  ],
})
export class Icone {
  readonly nom = input.required<string>();

  /** Fait tourner l'icône — pour un indicateur de chargement. */
  readonly tourne = input(false, { transform: booleen });

  /** À fournir uniquement quand l'icône est seule et porte le sens. */
  readonly etiquette = input<string>('');

  protected readonly boite = computed(() => TRACES[this.nom()]?.boite ?? '0 0 512 512');

  /**
   * Un nom inconnu ne dessine rien, et ne casse rien.
   *
   * <p>Une faute de frappe produit une icône vide, pas une exception qui
   * abattrait l'écran entier. La console avertit en développement.</p>
   */
  protected readonly trace = computed(() => {
    const connue = TRACES[this.nom()];
    if (!connue && typeof ngDevMode !== 'undefined' && ngDevMode) {
      console.warn(`Icône inconnue : « ${this.nom()} ». Voir garah-ui/icones/traces.ts`);
    }
    return connue?.trace ?? '';
  });
}

/** Accepte `<gu-icone tourne />` autant que `[tourne]="expression"`. */
function booleen(valeur: boolean | string): boolean {
  return valeur !== false && valeur !== 'false';
}

declare const ngDevMode: boolean | undefined;
