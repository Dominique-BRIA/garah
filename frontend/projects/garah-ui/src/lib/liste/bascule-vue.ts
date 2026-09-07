import { ChangeDetectionStrategy, Component, OnInit, input, model } from '@angular/core';

import { Icone } from '../icones/icone';

/** Les deux façons de regarder une liste. */
export type TypeVue = 'tableau' | 'cartes';

/**
 * Tableau ou cartes : le choix de celui qui regarde.
 *
 * <pre>
 * &lt;gu-bascule-vue [(vue)]="vue" cle="marchands" /&gt;
 * </pre>
 *
 * <h2>Pourquoi deux affichages plutôt qu'un bon</h2>
 *
 * <p>Ce ne sont pas deux goûts, ce sont deux tâches. Le <b>tableau</b> sert à
 * comparer : les colonnes s'alignent, l'œil descend une seule d'entre elles et
 * repère l'intrus. Les <b>cartes</b> servent à identifier : chaque objet est un
 * bloc avec son avatar et ses coordonnées groupées, ce qu'un tableau éclate sur
 * toute une largeur.</p>
 *
 * <p>Et sur un téléphone, un tableau de sept colonnes défile latéralement quoi
 * qu'on fasse : les cartes y sont le seul affichage réellement lisible.</p>
 *
 * <h2>⚠️ Le choix est mémorisé, et il le doit</h2>
 *
 * <p>Sans mémoire, quelqu'un qui préfère les cartes rebascule à chaque
 * navigation. La préférence part dans {@code localStorage} : elle est propre à
 * ce navigateur et ne regarde ni le serveur ni les autres appareils — c'est
 * exactement le genre de confort qui n'a rien à faire en base.</p>
 *
 * <p>Chaque écran fournit sa propre {@code cle} : on peut vouloir les marchands
 * en cartes et les produits en tableau. Une clé partagée les lierait sans que
 * personne ne comprenne pourquoi.</p>
 */
@Component({
  selector: 'gu-bascule-vue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icone],
  template: `
    <div class="bascule" role="group" aria-label="Type d'affichage">
      <!-- aria-pressed : le lecteur d'écran annonce lequel est ACTIF. Sans
           lui, les deux boutons se ressemblent et rien ne dit ce qu'on voit. -->
      <button
        type="button"
        [class.actif]="vue() === 'cartes'"
        [attr.aria-pressed]="vue() === 'cartes'"
        (click)="choisir('cartes')"
        aria-label="Afficher en cartes"
        title="Afficher en cartes"
      >
        <gu-icone nom="layer-group" />
      </button>

      <button
        type="button"
        [class.actif]="vue() === 'tableau'"
        [attr.aria-pressed]="vue() === 'tableau'"
        (click)="choisir('tableau')"
        aria-label="Afficher en tableau"
        title="Afficher en tableau"
      >
        <gu-icone nom="bars" />
      </button>
    </div>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
      }

      .bascule {
        display: inline-flex;
        gap: 0.2rem;
        padding: 0.2rem;
        border-radius: var(--radius-sm);
        background: var(--surface-douce);
        border: 1px solid var(--champ-bordure);
      }

      button {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 2rem;
        height: 2rem;
        border: none;
        border-radius: calc(var(--radius-sm) - 2px);
        background: transparent;
        color: var(--texte-attenue);
        cursor: pointer;
        font-size: 0.85rem;
        font-family: inherit;
        transition: background 0.2s ease, color 0.2s ease;
      }

      button:hover {
        color: var(--texte);
      }

      button:focus-visible {
        outline: 2px solid var(--primary);
        outline-offset: 1px;
      }

      /* L'actif est REMPLI, pas seulement coloré : sur un écran de téléphone
         en plein soleil, une différence de teinte seule ne se voit pas. */
      button.actif {
        background: var(--surface);
        color: var(--primary);
        box-shadow: 0 1px 3px rgb(0 0 0 / 0.12);
      }
    `,
  ],
})
export class BasculeVue implements OnInit {
  /** Liaison double : `[(vue)]="vue"`. */
  readonly vue = model<TypeVue>('tableau');

  /**
   * La clé de mémorisation, propre à l'écran. Absente : rien n'est retenu.
   *
   * <p>Préfixée à l'écriture pour ne pas entrer en collision avec les autres
   * choses que l'application range dans {@code localStorage}.</p>
   */
  readonly cle = input<string | null>(null);

  /**
   * ⚠️ {@code ngOnInit} et NON le constructeur.
   *
   * <p>Les entrées ne sont pas encore renseignées quand le constructeur
   * s'exécute : {@code cle()} y vaudrait {@code null} quel que soit ce que
   * l'appelant a écrit dans le gabarit, et la préférence ne serait jamais
   * relue. Le bogue est silencieux — la bascule fonctionne, elle oublie
   * simplement toujours.</p>
   */
  ngOnInit(): void {
    const memorisee = this.lire();
    if (memorisee) {
      this.vue.set(memorisee);
    }
  }

  protected choisir(vue: TypeVue): void {
    this.vue.set(vue);
    this.ecrire(vue);
  }

  private lire(): TypeVue | null {
    const cle = this.cle();
    if (!cle) {
      return null;
    }
    try {
      const valeur = localStorage.getItem(`garah.vue.${cle}`);
      return valeur === 'cartes' || valeur === 'tableau' ? valeur : null;
    } catch {
      // Navigation privée, cookies bloqués : on retombe sur le défaut.
      // Un confort d'affichage ne doit jamais faire échouer un écran.
      return null;
    }
  }

  private ecrire(vue: TypeVue): void {
    const cle = this.cle();
    if (!cle) {
      return;
    }
    try {
      localStorage.setItem(`garah.vue.${cle}`, vue);
    } catch {
      // Sans conséquence : le choix vaut pour cette page, il ne survivra pas.
    }
  }
}
