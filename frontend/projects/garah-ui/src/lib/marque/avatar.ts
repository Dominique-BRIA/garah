import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

/**
 * Une pastille d'identité : la photo si elle existe, les initiales sinon.
 *
 * <pre>
 * &lt;gu-avatar nom="Bria Togbé" /&gt;
 * &lt;gu-avatar nom="Ets Ngono" [url]="marchand.urlLogo" taille="3rem" /&gt;
 * </pre>
 *
 * <h2>Pourquoi l'avatar par défaut est ENGENDRÉ, et non stocké</h2>
 *
 * <p>La tentation serait de fabriquer une image à l'inscription et de la
 * déposer sur le stockage d'objets. Ce serait payer trois fois :</p>
 *
 * <ul>
 *   <li>un fichier par utilisateur, à stocker et à servir indéfiniment ;</li>
 *   <li>un aller-retour réseau à chaque affichage — sur une connexion mobile
 *       camerounaise, quarante avatars dans une liste font quarante
 *       requêtes ;</li>
 *   <li>une image <b>figée</b>, qui ne suivrait ni le thème ni un changement
 *       de nom.</li>
 * </ul>
 *
 * <p>Ici, l'avatar est deux lettres et une couleur. Il coûte zéro octet, se
 * calcule instantanément, et disparaît dès qu'une vraie photo est déposée.</p>
 */
@Component({
  selector: 'gu-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (url() && !echec()) {
      <img
        [src]="url()"
        [alt]="nom()"
        (error)="echec.set(true)"
        loading="lazy"
        decoding="async"
      />
    } @else {
      <!-- aria-hidden : le nom est presque toujours écrit juste à côté.
           L'annoncer une seconde fois sous forme d'initiales n'apporterait
           rien et alourdirait la lecture au lecteur d'écran. -->
      <span class="initiales" [style.background]="fond()" aria-hidden="true">
        {{ initiales() }}
      </span>
    }
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        flex-shrink: 0;
      }

      img,
      .initiales {
        width: var(--gu-avatar-taille, 2.5rem);
        height: var(--gu-avatar-taille, 2.5rem);
        border-radius: 50%;
        display: grid;
        place-items: center;
        /* object-fit : une photo rectangulaire est recadrée au centre plutôt
           qu'écrasée. Sans cela, tout visage non carré est déformé. */
        object-fit: cover;
      }

      .initiales {
        color: #fff;
        font-weight: 700;
        /* La taille du texte suit celle de la pastille : les initiales
           restent proportionnées à 2rem comme à 6rem. */
        font-size: calc(var(--gu-avatar-taille, 2.5rem) * 0.38);
        letter-spacing: 0.02em;
        user-select: none;
      }
    `,
  ],
  host: {
    '[style.--gu-avatar-taille]': 'taille()',
  },
})
export class Avatar {
  readonly nom = input.required<string>();

  /** L'URL de la photo. Absente ou en échec : on retombe sur les initiales. */
  readonly url = input<string | null>(null);

  readonly taille = input('2.5rem');

  /**
   * ⚠️ Le repli n'est pas seulement pour l'absence d'URL.
   *
   * <p>Les URL de médias sont <b>signées et expirent</b> au bout de sept jours
   * (D-21). Une page laissée ouverte assez longtemps, ou une adresse mise en
   * cache, finit par pointer vers un lien mort — et sans ce repli, l'écran se
   * remplirait d'icônes d'image brisée.</p>
   */
  protected readonly echec = signal(false);

  protected readonly initiales = computed(() => initialesDe(this.nom()));

  /**
   * La couleur est <b>dérivée du nom</b>, jamais tirée au hasard.
   *
   * <p>C'est ce qui rend l'avatar reconnaissable : la même personne garde sa
   * couleur d'un écran à l'autre et d'une session à l'autre. Une couleur
   * aléatoire changerait à chaque rendu et ne servirait plus à rien.</p>
   */
  protected readonly fond = computed(() => degradeDe(this.nom()));
}

/**
 * Deux lettres, tirées du premier et du dernier mot.
 *
 * <p>« Bria Togbé » donne BT, « Ets Ngono Distribution » donne EN — le mot du
 * milieu est ignoré, parce que trois lettres deviennent illisibles dans une
 * pastille de 2,5 rem.</p>
 */
function initialesDe(nom: string): string {
  const mots = (nom ?? '').trim().split(/\s+/).filter(Boolean);

  if (mots.length === 0) {
    return '?';
  }
  if (mots.length === 1) {
    // Un seul mot : ses deux premières lettres. « Ngono » donne NG, ce qui
    // distingue mieux que le seul N.
    return mots[0].slice(0, 2).toUpperCase();
  }
  return (mots[0][0] + mots[mots.length - 1][0]).toUpperCase();
}

/**
 * Une couleur stable, dérivée du nom.
 *
 * <p>Le condensé n'a aucune prétention cryptographique : il ne sert qu'à
 * répartir les noms sur la roue chromatique de façon reproductible.</p>
 *
 * <p>La saturation et la luminosité sont <b>fixes</b>. Les laisser varier
 * produirait des pastilles très claires sur lesquelles le texte blanc
 * deviendrait illisible — le contraste ne doit pas dépendre du nom qu'on
 * porte.</p>
 */
function degradeDe(nom: string): string {
  let condense = 0;
  for (const caractere of nom ?? '') {
    condense = (condense * 31 + caractere.charCodeAt(0)) % 360;
  }

  const teinte = condense;
  // 40° d'écart : assez pour que le dégradé se voie, assez peu pour qu'il
  // reste la même couleur plutôt que deux.
  const teinteFin = (condense + 40) % 360;

  return `linear-gradient(135deg, hsl(${teinte} 62% 48%), hsl(${teinteFin} 62% 38%))`;
}
