import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Comptoir,
  FORMAT_CODE_RETRAIT,
  Icone,
  messageErreur,
  normaliserCodeRetrait,
  ServiceSession,
} from 'garah-ui';

/**
 * Le comptoir de retrait.
 *
 * <h2>Le geste, dans l'ordre où il se fait</h2>
 *
 * <p>Un client se présente au point de récupération et annonce un code. L'agent
 * le saisit, <b>voit ce qu'il désigne</b>, sort les colis du rayonnage, puis
 * confirme. Cet écran suit cet ordre-là, pas un autre.</p>
 *
 * <p>🎯 <b>La recherche est séparée de la confirmation, et c'est le cœur de
 * l'écran.</b> Un seul bouton « Confirmer » ferait de la remise un geste
 * aveugle : le code serait validé, mais rien ne garantirait que la bonne
 * marchandise est partie. Fusionner les deux étapes pour « gagner un clic »
 * détruirait la seule vérification qui protège du mauvais paquet.</p>
 *
 * <h2>Le code ne sort jamais de l'URL</h2>
 *
 * <p>Toutes les requêtes sont des {@code POST} avec le code dans le
 * <b>corps</b> — y compris la recherche, qui ne modifie pourtant rien. Une URL
 * se retrouve dans les journaux du serveur, dans l'historique du navigateur et
 * dans l'en-tête {@code Referer} : un secret n'a rien à y faire.</p>
 *
 * <p>Pour la même raison, le code n'est <b>jamais</b> placé dans la route de
 * cet écran. On peut recharger la page, la mettre en favori, la partager : il
 * n'y a rien dedans.</p>
 */
@Component({
  selector: 'ga-retraits',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './retraits.html',
  styleUrl: './retraits.scss',
})
export class Retraits {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly code = signal('');
  protected readonly trouve = signal<Comptoir | null>(null);

  protected readonly recherche = signal(false);
  protected readonly action = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);

  /** Le retrait qui vient d'être remis : le message de succès du comptoir. */
  protected readonly remis = signal<string | null>(null);

  /** Le formulaire de refus, ouvert ou non, et son motif. */
  protected readonly formRefus = signal(false);
  protected readonly motif = signal('');

  /**
   * Reformate à chaque frappe.
   *
   * <p>L'agent tape vite, souvent sans le tiret, parfois en minuscules.
   * Refuser « acde2345 » quand le code est « ACDE-2345 » ferait recommencer la
   * saisie pour rien — devant un client qui attend.</p>
   */
  protected saisir(valeur: string): void {
    this.code.set(normaliserCodeRetrait(valeur));

    // Toute nouvelle frappe invalide ce qui était affiché. Sans ça, l'agent
    // pourrait confirmer un retrait pendant qu'un AUTRE code est à l'écran.
    if (this.trouve()) {
      this.trouve.set(null);
      this.formRefus.set(false);
    }
    this.erreur.set(null);
    this.remis.set(null);
  }

  protected codeComplet(): boolean {
    return FORMAT_CODE_RETRAIT.test(this.code());
  }

  // -------------------------------------------------------------------------
  // 1. Chercher
  // -------------------------------------------------------------------------

  protected chercher(): void {
    if (!this.codeComplet() || this.recherche()) {
      return;
    }
    this.recherche.set(true);
    this.erreur.set(null);
    this.remis.set(null);

    this.http
      .post<Comptoir>('/api/expeditions/retraits/recherche', { codeRetrait: this.code() })
      .subscribe({
        next: (c) => {
          this.recherche.set(false);
          this.trouve.set(c);
        },
        error: (e: unknown) => {
          this.recherche.set(false);
          this.trouve.set(null);
          this.erreur.set(messageErreur(e, 'Ce code n’a pas pu être vérifié.'));
        },
      });
  }

  // -------------------------------------------------------------------------
  // 2. Remettre — ou refuser
  // -------------------------------------------------------------------------

  protected confirmer(): void {
    if (!this.trouve() || this.action()) {
      return;
    }
    this.action.set('confirmation');
    this.erreur.set(null);

    this.http
      .post('/api/expeditions/retraits/confirmation', { codeRetrait: this.code() })
      .subscribe({
        next: () => {
          const numero = this.trouve()?.expedition.numero ?? '';
          this.action.set(null);
          // On vide tout : le client suivant arrive, et un écran qui garde le
          // retrait précédent invite à confirmer deux fois le même.
          this.reinitialiser();
          this.remis.set(numero);
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(messageErreur(e, 'La remise n’a pas pu être confirmée.'));
        },
      });
  }

  protected ouvrirRefus(): void {
    this.motif.set('');
    this.erreur.set(null);
    this.formRefus.set(true);
  }

  protected annulerRefus(): void {
    this.formRefus.set(false);
    this.motif.set('');
  }

  /**
   * Un refus se motive, toujours.
   *
   * <p>Le motif est ce que le service client lira quand le client rappellera.
   * « Refusé » sans raison ne se défend devant personne — ni devant le client,
   * ni devant le marchand qui réclame sa marchandise.</p>
   */
  protected refuser(): void {
    if (!this.trouve() || this.action() || !this.motif().trim()) {
      return;
    }
    this.action.set('refus');
    this.erreur.set(null);

    this.http
      .post('/api/expeditions/retraits/refus', {
        codeRetrait: this.code(),
        motif: this.motif().trim(),
      })
      .subscribe({
        next: () => {
          this.action.set(null);
          this.reinitialiser();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreur.set(messageErreur(e, 'Le refus n’a pas pu être enregistré.'));
        },
      });
  }

  protected reinitialiser(): void {
    this.code.set('');
    this.trouve.set(null);
    this.formRefus.set(false);
    this.motif.set('');
    this.erreur.set(null);
    this.remis.set(null);
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected dateHeure(iso: string | null): string {
    if (!iso) {
      return '';
    }
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

