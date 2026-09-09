import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Icone, Marque, ReponseErreur, ServiceSession, ServiceTheme } from 'garah-ui';

@Component({
  selector: 'ga-connexion',
  imports: [FormsModule, Icone, Marque],
  templateUrl: './connexion.html',
  styleUrl: './connexion.scss',
})
export class Connexion {
  private readonly session = inject(ServiceSession);
  private readonly router = inject(Router);
  protected readonly theme = inject(ServiceTheme);

  /**
   * Pourquoi on se retrouve ici.
   *
   * <p>Changer son mot de passe coupe toutes les sessions, celle-ci comprise
   * (D-19). Sans ce message, on est éjecté sur l'écran de connexion une
   * seconde après avoir cliqué « Changer » — et la protection ressemble à une
   * panne.</p>
   */
  protected readonly avis = signal<string | null>(
    Connexion.avisPour(inject(ActivatedRoute).snapshot.queryParamMap.get('motif')),
  );

  /**
   * Ce qui explique la présence sur cet écran.
   *
   * <p>Se retrouver devant un formulaire de connexion sans un mot
   * d'explication ressemble à une panne, ou à une session perdue sans raison.
   * Chaque renvoi ici en donne donc une.</p>
   */
  private static avisPour(motif: string | null): string | null {
    switch (motif) {
      case 'mot-de-passe-change':
        return 'Votre mot de passe a été modifié et toutes vos sessions ont été fermées. '
          + 'Connectez-vous avec le nouveau mot de passe.';

      // ⚠️ On dit CE QUI S'EST PASSÉ, pas seulement que c'est refusé.
      //
      //    La boutique et le back-office partagent une session par navigateur.
      //    Se connecter en client d'un côté remplace donc la session
      //    d'administration ouverte de l'autre — sans rien casser, mais sans
      //    rien dire non plus. Le comportement est correct et parfaitement
      //    incompréhensible tant qu'on ne l'explique pas.
      case 'compte-client':
        return 'Vous étiez connecté avec un compte client — probablement depuis la boutique, '
          + 'qui partage la même session que cet écran. Le back-office est réservé aux comptes '
          + 'de l’administration : connectez-vous avec le vôtre.';

      default:
        return null;
    }
  }

  protected readonly email = signal('');
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected soumettre(): void {
    if (this.enCours()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);

    this.session.connecter(this.email().trim(), this.motDePasse()).subscribe({
      next: () => {
        this.enCours.set(false);

        // ⚠️ Un CLIENT n'a rien à faire dans le back-office.
        //
        // Le backend ne l'interdit pas explicitement : il n'a simplement
        // aucune permission, donc chaque écran répondrait 403. Le laisser
        // entrer produirait une application vide et incompréhensible plutôt
        // qu'un refus clair.
        //
        // ⚠️ La MÊME règle est posée dans le garde de route. Ce n'est pas une
        //    répétition inutile : on entre ici par le formulaire, et là-bas
        //    par la restauration de session au démarrage. Elle n'était écrite
        //    qu'ici, et c'est par l'autre porte qu'un compte client est entré.
        //    Les deux lisent maintenant `estInterne`, à un seul endroit.
        if (!this.session.estInterne()) {
          this.session.deconnecter().subscribe();
          this.erreur.set(
            "Ce compte est un compte client. Le back-office est réservé à l'administration.",
          );
          return;
        }

        void this.router.navigate(['/']);
      },
      error: (e: unknown) => {
        this.enCours.set(false);
        this.erreur.set(this.lireErreur(e));
      },
    });
  }

  /**
   * Traduit une erreur HTTP en phrase utile.
   *
   * <p>Le backend renvoie toujours la même forme — un `code` stable et un
   * `message` humain. On affiche le message : il est déjà écrit pour être lu,
   * et le réécrire ici ferait diverger deux formulations de la même règle.</p>
   *
   * <p>Le cas `status === 0` mérite son propre message : ce n'est pas une
   * erreur de l'API, c'est le navigateur qui n'a pas pu l'atteindre — réseau
   * coupé, CORS mal configuré, ou API endormie. Dire « identifiants
   * incorrects » enverrait l'utilisateur chercher au mauvais endroit.</p>
   */
  private lireErreur(e: unknown): string {
    if (!(e instanceof HttpErrorResponse)) {
      return 'Une erreur inattendue est survenue.';
    }
    if (e.status === 0) {
      return 'Service momentanément indisponible. Vérifiez votre connexion et réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    return corps?.message ?? 'La connexion a échoué.';
  }
}
