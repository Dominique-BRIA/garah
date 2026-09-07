import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

/**
 * L'hôte de DiceBear et la version de son API.
 *
 * <p>Extraits en constantes pour une raison précise : DiceBear
 * <b>s'auto-héberge</b>. Le jour où l'on ne veut plus dépendre d'un service
 * tiers — ou le jour où il devient payant, lent, ou inatteignable depuis le
 * Cameroun comme l'est déjà Render (D-22) — c'est cette seule ligne qui
 * change, et rien d'autre dans le projet.</p>
 *
 * <p>⚠️ La version est <b>épinglée</b>. {@code 7.x} est celle du projet de
 * référence, éprouvée. Ne pas écrire « la dernière » : une collection
 * renommée entre deux versions majeures ne casse pas bruyamment, elle renvoie
 * une image vide — et l'écran se remplit de pastilles blanches sans qu'aucune
 * erreur n'apparaisse.</p>
 */
const DICEBEAR = 'https://api.dicebear.com/7.x';

/** Les collections utilisées. Les mêmes que le projet de référence. */
export type CollectionAvatar = 'adventurer' | 'avataaars' | 'initials' | 'shapes' | 'identicon';

/**
 * Une pastille d'identité : la photo, sinon un avatar engendré, sinon les
 * initiales.
 *
 * <pre>
 * &lt;gu-avatar nom="Bria Togbé" /&gt;
 * &lt;gu-avatar nom="Ets Ngono" [url]="marchand.urlLogo" taille="3rem" /&gt;
 * &lt;gu-avatar nom="Ets Ngono" collection="shapes" /&gt;
 * </pre>
 *
 * <h2>Trois niveaux, et chacun rattrape le précédent</h2>
 *
 * <pre>
 * 1. la vraie photo      si elle existe          &lt;- toujours prioritaire
 * 2. l'avatar DiceBear   engendré depuis le nom  &lt;- demande le réseau
 * 3. les initiales       deux lettres, une couleur &lt;- coûte zéro octet
 * </pre>
 *
 * <p>🎯 <b>Les initiales sont affichées AVANT l'image, pas à sa place.</b></p>
 *
 * <p>C'est le point qui fait tenir l'ensemble. Un avatar DiceBear est une
 * requête vers un service tiers ; sur une connexion mobile camerounaise, une
 * liste de quarante marchands en fait quarante. Si l'on attendait l'image, la
 * liste s'afficherait avec quarante trous, puis se remplirait par à-coups.</p>
 *
 * <p>Ici les initiales sont peintes immédiatement, l'image les recouvre quand
 * elle arrive, et <b>si elle n'arrive jamais</b> — hors ligne, service en
 * panne, réseau qui filtre — elles restent. L'écran est correct dans les trois
 * cas, et à aucun moment il n'est vide.</p>
 *
 * <p>{@code loading="lazy"} complète le dispositif : les avatars sous la ligne
 * de flottaison ne sont même pas demandés.</p>
 *
 * <h2>⚠️ Ce que ça envoie dehors</h2>
 *
 * <p>Le <b>nom</b> part chez DiceBear, puisqu'il sert de graine. Ce n'est pas
 * anodin même si ce n'est pas sensible : un nom de marchand et un nom
 * d'administrateur transitent par un tiers à chaque affichage non mis en
 * cache. Ne jamais y mettre autre chose — pas d'e-mail, pas de téléphone, pas
 * d'identifiant interne.</p>
 */
@Component({
  selector: 'gu-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="pastille" [style.background]="fond()">
      <!-- Les initiales, dessous. Retirées seulement quand une image a
           RÉELLEMENT fini de charger : les collections DiceBear ont un fond
           transparent, et les laisser derrière ferait lire les deux
           superposées. -->
      @if (!chargee()) {
        <!-- aria-hidden : le nom est presque toujours écrit juste à côté.
             L'annoncer une seconde fois sous forme d'initiales n'apporterait
             rien et alourdirait la lecture au lecteur d'écran. -->
        <span class="initiales" aria-hidden="true">{{ initiales() }}</span>
      }

      @if (source(); as adresse) {
        <img
          [src]="adresse"
          [alt]="nom()"
          (load)="chargee.set(true)"
          (error)="surEchec()"
          loading="lazy"
          decoding="async"
        />
      }
    </span>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        flex-shrink: 0;
      }

      .pastille {
        position: relative;
        width: var(--gu-avatar-taille, 2.5rem);
        height: var(--gu-avatar-taille, 2.5rem);
        border-radius: 50%;
        display: grid;
        place-items: center;
        /* Sans cela, un avatar carré déborderait du cercle par les coins. */
        overflow: hidden;
      }

      img {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
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
        line-height: 1;
      }
    `,
  ],
  host: {
    '[style.--gu-avatar-taille]': 'taille()',
  },
})
export class Avatar {
  readonly nom = input.required<string>();

  /** L'URL de la vraie photo. Absente ou en échec : on descend d'un niveau. */
  readonly url = input<string | null>(null);

  readonly taille = input('2.5rem');

  /**
   * Le style de l'avatar engendré.
   *
   * <p>{@code adventurer} pour des personnes, {@code shapes} pour une
   * entreprise — un personnage de dessin animé pour une société de transport
   * fait un drôle d'effet dans un back-office.</p>
   */
  readonly collection = input<CollectionAvatar>('adventurer');

  /**
   * ⚠️ Le repli n'est pas seulement pour l'absence d'URL.
   *
   * <p>Les URL de médias sont <b>signées et expirent</b> au bout de sept jours
   * (D-21). Une page laissée ouverte assez longtemps, ou une adresse mise en
   * cache, finit par pointer vers un lien mort — et sans ce repli, l'écran se
   * remplirait d'icônes d'image brisée.</p>
   */
  private readonly echecPhoto = signal(false);

  /** DiceBear injoignable : hors ligne, en panne, ou filtré par l'opérateur. */
  private readonly echecEngendre = signal(false);

  /** Vrai une fois l'image réellement peinte, jamais avant. */
  protected readonly chargee = signal(false);

  protected readonly initiales = computed(() => initialesDe(this.nom()));

  /**
   * La photo si elle existe, sinon l'avatar engendré, sinon rien.
   *
   * <p>Renvoyer {@code null} au bout de la cascade est ce qui retire le
   * {@code <img>} du gabarit : sans cela, le navigateur garderait l'icône
   * d'image brisée par-dessus les initiales.</p>
   */
  protected readonly source = computed(() => {
    const photo = this.url();
    if (photo && !this.echecPhoto()) {
      return photo;
    }
    return this.echecEngendre() ? null : this.engendree();
  });

  /**
   * Descend d'un cran dans la cascade.
   *
   * <p>⚠️ Il faut savoir <b>laquelle</b> des deux images vient d'échouer. Un
   * drapeau unique ferait sauter directement aux initiales quand une photo
   * expire — alors que l'avatar engendré, lui, serait parfaitement
   * disponible.</p>
   *
   * <p>{@code chargee} est remis à faux : l'image suivante n'est pas encore
   * peinte, et les initiales doivent réapparaître en attendant.</p>
   */
  protected surEchec(): void {
    if (this.url() && !this.echecPhoto()) {
      this.echecPhoto.set(true);
    } else {
      this.echecEngendre.set(true);
    }
    this.chargee.set(false);
  }

  /**
   * L'avatar DiceBear, dérivé du nom.
   *
   * <p>⚠️ {@code encodeURIComponent} n'est pas une précaution de style :
   * « BRIA ophelie » contient une espace, et beaucoup de noms d'ici portent
   * des accents. Sans encodage, la graine est tronquée au premier caractère
   * douteux — deux marchands différents reçoivent alors le même avatar, et on
   * cherche longtemps pourquoi.</p>
   */
  private readonly engendree = computed(() => {
    const graine = (this.nom() ?? '').trim();
    if (!graine) {
      return null;
    }
    return `${DICEBEAR}/${this.collection()}/svg?seed=${encodeURIComponent(graine)}`;
  });

  /**
   * La couleur est <b>dérivée du nom</b>, jamais tirée au hasard.
   *
   * <p>C'est ce qui rend l'avatar reconnaissable : la même personne garde sa
   * couleur d'un écran à l'autre et d'une session à l'autre. Une couleur
   * aléatoire changerait à chaque rendu et ne servirait plus à rien.</p>
   *
   * <p>Elle reste visible derrière les collections à fond transparent : le
   * fond coloré fait partie de l'avatar, pas seulement du repli.</p>
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
