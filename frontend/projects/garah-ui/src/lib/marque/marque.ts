import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { MARQUE_COMPLETE, MARQUE_EPAISSIE } from './traces';

/**
 * La marque GARAH : le téléphone qui devient chariot.
 *
 * <pre>
 * &lt;gu-marque /&gt;                        la marque, immobile
 * &lt;gu-marque taille="4rem" /&gt;          dans un en-tête
 * &lt;gu-marque animee="entree" /&gt;        elle apparaît, une fois
 * &lt;gu-marque animee="boucle" /&gt;        elle respire — pour une attente
 * &lt;gu-marque mono /&gt;                   la couleur du texte, pas celle de la marque
 * </pre>
 *
 * <h2>Ce que la forme raconte</h2>
 *
 * <p>Un <b>seul ruban</b> : le flanc droit du téléphone descend, tourne, et
 * devient le bord haut du panier. On achète depuis son téléphone, et la
 * marchandise part. Les deux formes ne sont pas voisines — l'une devient
 * l'autre.</p>
 *
 * <h2>⚠️ Le tracé n'est PAS écrit ici</h2>
 *
 * <p>Il vient de {@code ./traces.ts}, engendré par
 * {@code marque/engendrer-marque.mjs} qui relève le contour du fichier livré
 * par le graphiste. Recopier une forme à la main la fait dériver de quelques
 * pour cent — et l'on se retrouve avec deux marques sans savoir laquelle fait
 * foi. C'est arrivé, et c'est pour cela que le relevé existe.</p>
 *
 * <h2>⚠️ DEUX TRACÉS, choisis par la taille</h2>
 *
 * <p>Sous 44 px, les dix trous du panier font des carrés d'un pixel : ils se
 * remplissent d'anticrénelage et le dessin devient une tache. Le composant
 * bascule alors sur le tracé <b>épaissi</b> — qui n'est pas un autre dessin,
 * mais le même, relevé après dilatation du masque.</p>
 *
 * <p>C'est la barre latérale qui l'exige : elle affiche la marque à 26 px.</p>
 *
 * <h2>Sur la couleur</h2>
 *
 * <p>Un aplat, et il vient de {@code var(--marque)} — la seule constante de
 * {@code _jetons.scss}. L'emblème garde donc sa couleur sur les trois
 * frontends et dans les deux thèmes, pendant que {@code --primary} varie
 * d'une application à l'autre.</p>
 *
 * <p>⚠️ <b>La couleur est posée en CSS, jamais en attribut.</b> Une variable
 * CSS n'est pas substituée dans un attribut de présentation :
 * {@code fill="var(--marque)"} donnerait simplement du noir. C'est une erreur
 * qui ne se voit pas à la relecture — l'attribut a l'air parfaitement bon.</p>
 */
@Component({
  selector: 'gu-marque',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // La taille est portée par l'hôte, pas par le <svg> : c'est l'hôte qui
  // occupe la place dans la mise en page. La poser sur le <svg> laisserait un
  // élément de taille nulle autour d'un dessin visible — et tout ce qui
  // l'entoure se calerait sur le mauvais rectangle.
  host: {
    '[style.width]': 'taille()',
    '[style.height]': 'taille()',
  },
  template: `
    <svg
      [attr.viewBox]="trace().boite"
      [class]="classes()"
      [attr.aria-hidden]="etiquette() ? null : 'true'"
      [attr.role]="etiquette() ? 'img' : null"
      [attr.aria-label]="etiquette() || null"
      focusable="false"
    >
      <path class="dessin" fill-rule="evenodd" [attr.d]="trace().trace" />
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }

      svg {
        width: 100%;
        height: 100%;
        display: block;
        overflow: visible;
      }

      .dessin {
        fill: var(--marque);
      }

      /* Sur un aplat de couleur, ou en impression : la marque prend la couleur
         du texte qui l'entoure. C'est la version qui compte à long terme —
         une marque qui ne survit pas à l'aplat ne survit ni au tampon, ni à
         la broderie. */
      svg.mono .dessin {
        fill: currentColor;
      }

      /* ── L'entrée : la marque monte et se pose ────────────────────────────
         ⚠️ Elle ne se TRACE pas. L'ancienne calebasse était faite de deux
            traits qu'on pouvait dessiner l'un après l'autre ; ce dessin-ci
            est une surface pleine percée de trous, et un stroke-dasharray
            n'a aucun sens dessus. Une animation qui ment sur la nature de
            la forme se remarque même sans qu'on sache pourquoi. */
      svg.entree {
        animation: gu-marque-entrer 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
      }

      @keyframes gu-marque-entrer {
        from {
          opacity: 0;
          transform: translateY(14%) scale(0.88);
        }
        to {
          opacity: 1;
          transform: none;
        }
      }

      /* ── La boucle : elle respire, pour dire une attente ──────────────────
         Le palier en haut est ce qui distingue une respiration d'un
         va-et-vient mécanique. */
      svg.boucle {
        animation: gu-marque-respirer 2s ease-in-out infinite;
        transform-origin: center;
      }

      @keyframes gu-marque-respirer {
        0% {
          opacity: 0.55;
          transform: scale(0.94);
        }
        45%,
        60% {
          opacity: 1;
          transform: none;
        }
        100% {
          opacity: 0.55;
          transform: scale(0.94);
        }
      }

      /* ⚠️ Le réglage système « réduire les animations » n'est pas un confort :
         pour une partie des utilisateurs, le mouvement déclenche des nausées.
         La marque doit alors apparaître ENTIÈRE, pas figée à mi-course. */
      @media (prefers-reduced-motion: reduce) {
        svg.entree,
        svg.boucle {
          animation: none;
          opacity: 1;
          transform: none;
        }
      }
    `,
  ],
})
export class Marque {
  /** La taille du carré. La marque est dessinée sur une grille carrée. */
  readonly taille = input('2.5rem');

  /** `non`, `entree` (une fois) ou `boucle` (une attente). */
  readonly animee = input<'non' | 'entree' | 'boucle'>('non');

  /** La couleur du texte plutôt que celle de la marque : aplat, impression. */
  readonly mono = input(false, { transform: booleen });

  /**
   * À renseigner uniquement quand la marque est SEULE.
   *
   * <p>Posée à côté du mot « GARAH », elle est décorative : l'annoncer ferait
   * entendre « GARAH GARAH ». C'est la même règle que {@code gu-icone}.</p>
   */
  readonly etiquette = input<string | null>(null);

  /**
   * Le tracé qui convient à cette taille.
   *
   * <p>⚠️ Le seuil est à 44 px, et il est mesuré sur la taille DEMANDÉE, pas
   * sur la taille rendue. Un conteneur qui rétrécirait la marque en dessous
   * la ferait empâter sans que le composant le sache — c'est le prix d'un
   * choix fait à la construction plutôt qu'à l'affichage, et c'est le bon
   * compromis : observer la taille réelle coûterait un ResizeObserver par
   * marque, sur un dessin qui ne bouge jamais.</p>
   */
  protected readonly trace = computed(() =>
    enPixels(this.taille()) < 44 ? MARQUE_EPAISSIE : MARQUE_COMPLETE,
  );

  protected readonly classes = computed(() =>
    this.mono() ? `${this.animee()} mono` : this.animee(),
  );
}

/**
 * Une taille CSS en pixels, approximativement.
 *
 * <p>Assez juste pour choisir entre deux tracés. Une valeur exotique — un
 * pourcentage, un {@code calc()} — retombe sur le tracé épaissi : c'est le
 * plus sûr des deux, il reste lisible partout.</p>
 */
function enPixels(taille: string): number {
  const valeur = Number.parseFloat(taille);
  if (Number.isNaN(valeur)) {
    return 0;
  }
  if (taille.includes('rem') || taille.includes('em')) {
    return valeur * 16;
  }
  if (taille.includes('px')) {
    return valeur;
  }
  return 0;
}

/** `<gu-marque mono />` doit valoir vrai, comme un attribut HTML natif. */
function booleen(valeur: boolean | string): boolean {
  return valeur !== false && valeur !== 'false';
}
