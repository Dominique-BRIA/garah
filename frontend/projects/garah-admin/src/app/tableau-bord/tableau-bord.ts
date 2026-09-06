import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ServiceSession } from 'garah-ui';

/**
 * Le tableau de bord.
 *
 * <p>⚠️ <b>Rien de technique n'apparaît à l'écran.</b> Ni la pile employée, ni
 * l'architecture, ni les invariants du modèle. La personne qui ouvre ce
 * back-office gère un commerce ; ces informations ne l'aident pas, et
 * renseignent qui n'a rien à y faire.</p>
 *
 * <p>Les chiffres viendront des statistiques réelles. En attendant, on annonce
 * ce qui est disponible en langage métier plutôt que d'afficher des cases
 * vides.</p>
 */
@Component({
  selector: 'ga-tableau-bord',
  imports: [RouterLink],
  template: `
    <header class="entete">
      <h1>Bonjour {{ session.utilisateur()?.nom }}</h1>
      <p class="sous-titre">Vue d'ensemble de votre activité</p>
    </header>

    <div class="raccourcis">
      <a routerLink="/produits" class="gu-carte raccourci">
        <i class="fa-solid fa-box-open" aria-hidden="true"></i>
        <span class="raccourci__titre">Catalogue</span>
        <span class="raccourci__aide">Consulter et publier les produits</span>
      </a>
    </div>
  `,
  styles: [`
    .entete { margin-bottom: 1.75rem; }
    h1 { font-size: 1.5rem; }
    .sous-titre { color: var(--texte-attenue); font-size: 0.88rem; margin-top: 0.2rem; }

    .raccourcis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
      gap: 1rem;
    }

    .raccourci {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      text-decoration: none;
      color: inherit;

      i { font-size: 1.35rem; color: var(--primary); margin-bottom: 0.4rem; }
      &:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
    }

    .raccourci__titre { font-weight: 700; font-size: 0.98rem; }
    .raccourci__aide { color: var(--texte-attenue); font-size: 0.82rem; }
  `],
})
export class TableauBord {
  protected readonly session = inject(ServiceSession);
}
