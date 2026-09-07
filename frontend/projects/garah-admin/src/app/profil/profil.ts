import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  Avatar,
  Icone,
  ModificationProfil,
  Profil,
  ReponseErreur,
  ServiceSession,
  libelleRole,
} from 'garah-ui';

/** Les langues du référentiel (D-08). Le code part à l'API, le libellé s'affiche. */
const LANGUES = [
  { code: 'fr', libelle: 'Français' },
  { code: 'en', libelle: 'English' },
  { code: 'sg', libelle: 'Sängö' },
] as const;

/**
 * Son propre compte : le consulter, le corriger, changer son mot de passe.
 *
 * <h2>Deux formulaires séparés, et ce n'est pas une facilité de mise en page</h2>
 *
 * <p>Modifier son nom et changer son mot de passe n'ont ni les mêmes
 * conséquences ni le même risque. Le second <b>coupe toutes les sessions</b> :
 * les réunir dans un seul bouton « Enregistrer » ferait déconnecter quelqu'un
 * qui voulait seulement corriger une faute dans son prénom.</p>
 *
 * <p>Ils appellent d'ailleurs deux routes distinctes — {@code PATCH /api/profil}
 * et {@code POST /api/profil/mot-de-passe} — parce que ce sont deux
 * opérations, pas deux moitiés d'une même.</p>
 */
@Component({
  selector: 'ga-profil',
  imports: [FormsModule, Icone, Avatar],
  templateUrl: './profil.html',
  styleUrl: './profil.scss',
})
export class ProfilEcran {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly session = inject(ServiceSession);

  protected readonly langues = LANGUES;

  /** `SUPER_ADMIN` n'a rien à faire à l'écran : on affiche le libellé. */
  protected readonly role = libelleRole;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly profil = signal<Profil | null>(null);

  // --- Le formulaire d'identité ---------------------------------------------
  protected readonly nom = signal('');
  protected readonly prenom = signal('');
  protected readonly telephone = signal('');
  protected readonly langue = signal('fr');

  protected readonly enregistrement = signal(false);
  protected readonly erreurFormulaire = signal<string | null>(null);
  protected readonly succes = signal(false);

  // --- Le formulaire de mot de passe ----------------------------------------
  protected readonly actuel = signal('');
  protected readonly nouveau = signal('');
  protected readonly confirmation = signal('');

  protected readonly changement = signal(false);
  protected readonly erreurMotDePasse = signal<string | null>(null);

  /**
   * Le nouveau mot de passe et sa confirmation diffèrent.
   *
   * <p>Vérifié ici et non par l'API : celle-ci ne reçoit qu'un seul nouveau
   * mot de passe, et n'a aucun moyen de savoir qu'on l'a saisi deux fois. La
   * confirmation existe pour attraper une faute de frappe — et une faute de
   * frappe sur un mot de passe qu'on ne relit jamais enferme dehors.</p>
   */
  protected readonly discordance = computed(
    () => this.confirmation().length > 0 && this.nouveau() !== this.confirmation(),
  );

  protected readonly peutChanger = computed(
    () =>
      !this.changement() &&
      this.actuel().length > 0 &&
      this.nouveau().length > 0 &&
      !this.discordance(),
  );

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Profil>('/api/profil').subscribe({
      next: (p) => {
        this.profil.set(p);
        this.remplirLeFormulaire(p);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.erreur.set(this.lireErreur(e, 'Impossible de charger votre profil.'));
        this.chargement.set(false);
      },
    });
  }

  private remplirLeFormulaire(p: Profil): void {
    this.nom.set(p.nom);
    this.prenom.set(p.prenom ?? '');
    this.telephone.set(p.telephone ?? '');
    this.langue.set(p.langue);
  }

  /**
   * Enregistre les informations d'identité.
   *
   * <p>⚠️ On envoie <b>tous</b> les champs du formulaire, y compris vides.
   * C'est voulu : côté API, un champ présent mais vide efface la valeur, et
   * c'est ainsi qu'on retire son numéro de téléphone. N'envoyer que ce qui a
   * changé rendrait la suppression impossible.</p>
   */
  protected enregistrer(): void {
    if (this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreurFormulaire.set(null);
    this.succes.set(false);

    const demande: ModificationProfil = {
      nom: this.nom().trim(),
      prenom: this.prenom().trim(),
      telephone: this.telephone().trim(),
      langue: this.langue(),
    };

    this.http.patch<Profil>('/api/profil', demande).subscribe({
      next: (p) => {
        this.profil.set(p);
        this.remplirLeFormulaire(p);
        this.enregistrement.set(false);
        this.succes.set(true);

        // La barre latérale lit l'utilisateur de la session, qui vient du
        // jeton : sans cette ligne, elle afficherait l'ancien nom pendant
        // quinze minutes et l'enregistrement paraîtrait n'avoir rien fait.
        this.session.actualiserUtilisateur({ nom: p.nom, langue: p.langue });
      },
      error: (e: unknown) => {
        this.enregistrement.set(false);
        this.erreurFormulaire.set(this.lireErreur(e, "L'enregistrement a échoué."));
      },
    });
  }

  /**
   * Change le mot de passe, puis <b>termine la session</b>.
   *
   * <p>🎯 La déconnexion n'est pas une réaction à une erreur : c'est le
   * comportement voulu. L'API coupe toutes les sessions, celle-ci comprise
   * (D-19). Attendre le premier 401 laisserait l'application fonctionner
   * encore quinze minutes sur le jeton d'accès en cours, puis déconnecter
   * sans rapport visible avec ce qu'on venait de faire.</p>
   *
   * <p>On part donc tout de suite, avec un message qui l'explique — l'écran de
   * connexion le reprend pour que personne ne croie à une panne.</p>
   */
  protected changerMotDePasse(): void {
    if (!this.peutChanger()) {
      return;
    }
    this.changement.set(true);
    this.erreurMotDePasse.set(null);

    this.http
      .post<void>('/api/profil/mot-de-passe', {
        actuel: this.actuel(),
        nouveau: this.nouveau(),
      })
      .subscribe({
        next: () => {
          this.session.deconnecter().subscribe(() =>
            void this.router.navigate(['/connexion'], {
              queryParams: { motif: 'mot-de-passe-change' },
            }),
          );
        },
        error: (e: unknown) => {
          this.changement.set(false);
          this.erreurMotDePasse.set(
            this.lireErreur(e, 'Le changement de mot de passe a échoué.'),
          );
        },
      });
  }

  /**
   * Une date en français, sans embarquer les données de locale d'Angular.
   *
   * <p>⚠️ Le pipe {@code | date} d'Angular formaterait en <b>anglais</b> :
   * aucune locale n'est enregistrée dans {@code app.config.ts}, et le défaut
   * est {@code en-US}. « 6 September 2026 » dans une interface française.</p>
   *
   * <p>La corriger demanderait {@code registerLocaleData(localeFr)} — une
   * quinzaine de kilo-octets de règles de formatage livrées à tous. Or le
   * navigateur en dispose déjà : {@code Intl.DateTimeFormat} coûte zéro octet
   * et connaît le français. C'est le même arbitrage que les icônes, où l'on a
   * renoncé à 360 Ko de police pour neuf tracés.</p>
   */
  protected dateLisible(iso: string): string {
    return this.formater(iso, { dateStyle: 'long' });
  }

  protected dateHeureLisible(iso: string): string {
    return this.formater(iso, { dateStyle: 'long', timeStyle: 'short' });
  }

  private formater(iso: string, options: Intl.DateTimeFormatOptions): string {
    const date = new Date(iso);
    // Une date illisible ne doit pas afficher « Invalid Date » en plein écran.
    return Number.isNaN(date.getTime())
      ? '—'
      : new Intl.DateTimeFormat('fr-FR', options).format(date);
  }

  /**
   * Le message de l'API, ou un repli.
   *
   * <p>L'API renvoie une forme unique : un {@code code} stable pour la machine
   * et un {@code message} humain. On affiche le message ; le code servira le
   * jour où l'interface sera traduite.</p>
   */
  private lireErreur(e: unknown, repli: string): string {
    if (e instanceof HttpErrorResponse) {
      const corps = e.error as ReponseErreur | null;
      if (corps?.message) {
        return corps.message;
      }
      if (e.status === 0) {
        return 'Le serveur est injoignable. Vérifiez votre connexion.';
      }
    }
    return repli;
  }
}
