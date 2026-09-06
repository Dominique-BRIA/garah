import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { ServiceTheme } from '../theme/service-theme';

/**
 * Le logotype GARAH, qui suit le thème.
 *
 * <pre>
 * &lt;gu-logo /&gt;                    taille par défaut
 * &lt;gu-logo hauteur="3rem" /&gt;     dans un en-tête
 * </pre>
 *
 * <p>Deux fichiers, un par thème. C'est nécessaire parce qu'ils sont opaques :
 * un logo sur fond blanc posé sur le thème sombre découpe un rectangle clair
 * au milieu de l'interface.</p>
 *
 * <p>⚠️ Le choix se fait sur le thème <b>effectif</b>, pas sur le réglage
 * choisi. Un utilisateur en « suivre le système » n'a ni {@code clair} ni
 * {@code sombre} enregistré : lire la préférence brute donnerait toujours le
 * même logo, et il jurerait avec le fond une fois sur deux.</p>
 *
 * <p>⚠️ <b>Un {@code <picture>} avec {@code media="(prefers-color-scheme: dark)"}
 * ne conviendrait pas</b>, alors que c'est la solution qu'on trouve partout.
 * Il ne connaît que le réglage du <i>système</i> : la bascule manuelle de
 * l'application lui échapperait complètement, et le logo resterait figé sur le
 * thème du système pendant que tout le reste change autour de lui.</p>
 *
 * <p>C'est le même piège que les trois états du thème, sous une autre forme :
 * ce qui suit {@code prefers-color-scheme} ignore le choix explicite.</p>
 */
@Component({
  selector: 'gu-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <img
      [src]="source()"
      [alt]="alt()"
      [style.height]="hauteur()"
      decoding="async"
    />
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
      }

      img {
        /* La hauteur est pilotée ; la largeur suit le rapport d'origine.
           Sans ça, un logo non carré serait écrasé. */
        width: auto;
        max-width: 100%;
        display: block;
      }
    `,
  ],
})
export class Logo {
  private readonly theme = inject(ServiceTheme);

  readonly hauteur = input('2.25rem');
  readonly alt = input('GARAH');

  /**
   * Le chemin dépend du thème RÉELLEMENT rendu.
   *
   * <p>Les fichiers sont copiés par le build de la librairie vers
   * {@code assets/marque/} de chaque application.</p>
   */
  protected readonly source = computed(() =>
    this.theme.sombreEffectif()
      ? 'assets/marque/logo-sombre.jpg'
      : 'assets/marque/logo-clair.jpg',
  );
}
