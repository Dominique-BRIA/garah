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
    inject(ActivatedRoute).snapshot.queryParamMap.get('motif') === 'mot-de-passe-change'
      ? 'Votre mot de passe a été modifié et toutes vos sessions ont été fermées. '
        + 'Connectez-vous avec le nouveau mot de passe.'
      : null,
  );

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
      next: (utilisateur) => {
        this.enCours.set(false);

        // ⚠️ Un CLIENT n'a rien à faire dans le back-office.
        //
        // Le backend ne l'interdit pas explicitement : il n'a simplement
        // aucune permission, donc chaque écran répondrait 403. Le laisser
        // entrer produirait une application vide et incompréhensible plutôt
        // qu'un refus clair.
        if (utilisateur.type === 'CLIENT') {
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
