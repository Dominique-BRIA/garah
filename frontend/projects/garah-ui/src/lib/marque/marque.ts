import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * La marque GARAH : la calebasse, réduite à deux formes.
 *
 * <pre>
 * &lt;gu-marque /&gt;                        la marque, immobile
 * &lt;gu-marque taille="4rem" /&gt;          dans un en-tête
 * &lt;gu-marque animee="entree" /&gt;        elle se trace et se remplit, une fois
 * &lt;gu-marque animee="boucle" /&gt;        elle respire — pour une attente
 * &lt;gu-marque mono /&gt;                   la couleur du texte, pas celle de la marque
 * </pre>
 *
 * <h2>Ce que la forme raconte</h2>
 *
 * <p>Les deux visuels de marque montrent une <b>calebasse</b> qui porte une
 * voiture, une paire de baskets et un pagne — ce que le commerce rapporte.
 * L'idée est bonne, mais elle vit dans deux photographies de 1408 × 768 :
 * inexploitable en favicon, inexploitable dans une barre latérale.</p>
 *
 * <p>Ici la calebasse est ramenée à sa panse pleine et à l'anse qui la
 * referme, en laissant une ouverture à droite. On y lit un récipient d'abord,
 * un <b>G</b> ensuite — et c'est le bon ordre : la marque doit dire le métier
 * avant de dire le nom, puisque le nom est déjà écrit à côté.</p>
 *
 * <p>{@link Logo} reste la <b>bannière</b> photographique, pour une vitrine où
 * une photo de produits a un rôle à jouer. Ceci est la <b>marque</b>, pour
 * tout le reste : onglet, barre latérale, tampon, en-tête d'e-mail.</p>
 *
 * <h2>Sur la couleur</h2>
 *
 * <p>Un aplat, pas un dégradé, et il vient de {@code var(--marque)} — la seule
 * constante de {@code _jetons.scss}. L'emblème garde donc sa couleur sur les
 * trois frontends et dans les deux thèmes, pendant que {@code --primary} varie
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
      viewBox="0 0 48 48"
      [class]="classes()"
      [attr.aria-hidden]="etiquette() ? null : 'true'"
      [attr.role]="etiquette() ? 'img' : null"
      [attr.aria-label]="etiquette() || null"
      focusable="false"
    >
      <!-- Le niveau de remplissage. Immobile hors animation : le rectangle
           couvre alors toute la panse, et le découpage ne se voit pas. -->
      <clipPath [attr.id]="idNiveau">
        <rect class="niveau" x="8" y="24" width="34" height="18" />
      </clipPath>

      <!-- L'anse. Bout franc et non arrondi : un bout rond dépasserait sous la
           ligne du bord, à gauche, et ferait une verrue que personne ne sait
           nommer mais que tout le monde voit. -->
      <path class="anse" d="M10 25A15 15 0 0 1 37.29 16.4" />

      <!-- La panse. -->
      <path
        class="panse"
        d="M10 25A15 15 0 0 0 40 25Z"
        [attr.clip-path]="'url(#' + idNiveau + ')'"
      />
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
      }

      .anse {
        fill: none;
        stroke: var(--marque);
        stroke-width: 5;
        stroke-linecap: butt;
      }

      .panse {
        fill: var(--marque);
      }

      /* Sur un aplat de couleur, ou en impression : la marque prend la couleur
         du texte qui l'entoure. C'est la version qui compte à long terme —
         une marque qui ne survit pas à l'aplat ne survit ni au tampon, ni à
         la broderie. */
      svg.mono .anse {
        stroke: currentColor;
      }

      svg.mono .panse {
        fill: currentColor;
      }

      /* ── L'entrée : l'anse se trace, puis la panse se remplit ──────────────
         Les deux se chevauchent volontairement (0,34 s < 0,62 s) : enchaînées
         bout à bout, on verrait deux gestes ; superposées, on en voit un. */
      svg.entree .anse {
        /* 38 = la longueur de l'arc : 145° d'un cercle de rayon 15.
           Une valeur trop courte laisse un bout non tracé à la fin. */
        stroke-dasharray: 38;
        animation: gu-marque-tracer 0.62s cubic-bezier(0.65, 0, 0.35, 1) both;
      }

      svg.entree .niveau {
        animation: gu-marque-remplir 0.68s 0.34s cubic-bezier(0.22, 1, 0.36, 1) both;
      }

      @keyframes gu-marque-tracer {
        from {
          stroke-dashoffset: 38;
        }
        to {
          stroke-dashoffset: 0;
        }
      }

      @keyframes gu-marque-remplir {
        from {
          transform: translateY(17px);
        }
        to {
          transform: none;
        }
      }

      /* ── La boucle : elle se remplit et se vide, pour dire une attente ─────
         Le niveau monte, tient un instant en haut, puis redescend. Le palier
         est ce qui distingue une respiration d'un va-et-vient mécanique. */
      svg.boucle .niveau {
        animation: gu-marque-respirer 2s ease-in-out infinite;
      }

      @keyframes gu-marque-respirer {
        0% {
          transform: translateY(17px);
        }
        45%,
        60% {
          transform: none;
        }
        100% {
          transform: translateY(17px);
        }
      }

      /* ⚠️ Le réglage système « réduire les animations » n'est pas un confort :
         pour une partie des utilisateurs, le mouvement déclenche des nausées.
         La marque doit alors apparaître ENTIÈRE, pas figée à mi-remplissage. */
      @media (prefers-reduced-motion: reduce) {
        svg .anse,
        svg .niveau {
          animation: none;
        }

        svg .anse {
          stroke-dashoffset: 0;
        }

        svg .niveau {
          transform: none;
        }
      }
    `,
  ],
})
export class Marque {
  /**
   * Un identifiant de découpe par instance.
   *
   * <p>⚠️ Deux marques sur la même page partageraient sinon le même
   * {@code id}. Le second l'emporterait, et la première pointerait vers une
   * découpe qui n'est plus la sienne — un bogue qui ne se voit QUE lorsqu'on
   * affiche deux marques de tailles différentes, donc jamais pendant qu'on
   * l'écrit.</p>
   *
   * <p>L'encapsulation d'Angular ne protège pas d'un doublon : elle réécrit
   * les sélecteurs CSS, pas les identifiants d'un document SVG.</p>
   */
  private static suivant = 0;
  protected readonly idNiveau = `gu-marque-niveau-${++Marque.suivant}`;

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

  protected readonly classes = computed(() =>
    this.mono() ? `${this.animee()} mono` : this.animee(),
  );
}

/** `<gu-marque mono />` doit valoir vrai, comme un attribut HTML natif. */
function booleen(valeur: boolean | string): boolean {
  return valeur !== false && valeur !== 'false';
}
