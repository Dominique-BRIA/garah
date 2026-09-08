import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  EtapeSuivi,
  Icone,
  Marque,
  ServiceTheme,
  Suivi,
  TYPES_EVENEMENT,
  libelleEvenement,
} from 'garah-ui';

/**
 * Le suivi public d'un colis.
 *
 * <h2>La seule page de l'application qu'on atteint sans compte</h2>
 *
 * <p>Elle est <b>hors de la coque</b> : pas de menu, pas de garde
 * d'authentification, pas de session. Un client qui a reçu un numéro par SMS
 * ouvre le lien et lit son trajet — rien d'autre à faire.</p>
 *
 * <h2>Ce qu'elle n'affiche pas, et pourquoi</h2>
 *
 * <p>⚠️ Un numéro de suivi circule par SMS, par WhatsApp, sur un bordereau
 * photographié : il ne prouve <b>rien</b> sur l'identité de celui qui le
 * présente. Le serveur ne renvoie donc que le trajet — où, quand — et cet
 * écran n'a aucun moyen d'en afficher davantage : le contenu du colis, le
 * destinataire et l'agent qui a scanné ne sont même pas dans la réponse.</p>
 *
 * <p>Ajouter ici un appel à une autre route « pour enrichir l'affichage »
 * transformerait un numéro qui traîne en fuite de données.</p>
 *
 * <h2>Le numéro EST dans l'URL, et c'est voulu</h2>
 *
 * <p>Contrairement au code de retrait, qui n'y va jamais. La différence tient
 * en une phrase : un numéro de suivi <b>se partage</b> — il sert à envoyer un
 * lien —, un code de retrait <b>ouvre la marchandise</b>.</p>
 */
@Component({
  selector: 'ga-suivi',
  imports: [FormsModule, Icone, Marque],
  templateUrl: './suivi.html',
  styleUrl: './suivi.scss',
})
export class SuiviColis {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  protected readonly theme = inject(ServiceTheme);

  /** Lié depuis la route. Vide sur `/suivi` : on affiche alors le champ seul. */
  readonly numero = input<string>('');

  protected readonly saisie = signal('');
  protected readonly suivi = signal<Suivi | null>(null);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    // Le numéro vient de l'URL : arriver par un lien partagé doit lancer la
    // recherche tout seul. Sans ça, le client verrait un champ vide alors
    // qu'il a cliqué sur un lien qui contenait déjà sa réponse.
    effect(() => {
      const n = this.numero().trim();
      if (n) {
        this.saisie.set(n);
        this.chercher(n);
      }
    });
  }

  /** Le formulaire pose le numéro dans l'URL ; l'effet ci-dessus fait le reste. */
  protected soumettre(): void {
    const n = this.saisie().trim();
    if (n) {
      this.router.navigate(['/suivi', n]);
    }
  }

  private chercher(numeroSuivi: string): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.http.get<Suivi>(`/api/expeditions/suivi/${encodeURIComponent(numeroSuivi)}`).subscribe({
      next: (s) => {
        this.chargement.set(false);
        this.suivi.set(s);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.suivi.set(null);
        this.erreur.set(message(e));
      },
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  /**
   * Les étapes de la plus récente à la plus ancienne.
   *
   * <p>Le serveur les range par ordre chronologique — c'est le bon ordre pour
   * un journal. Ici on cherche « où est mon colis <b>maintenant</b> » : la
   * réponse doit être la première ligne, pas la dernière.</p>
   */
  protected recentesDabord(): readonly EtapeSuivi[] {
    return [...(this.suivi()?.etapes ?? [])].reverse();
  }

  protected libelle(type: string): string {
    return libelleEvenement(type);
  }

  /** Ce que l'étape veut dire, en clair, pour quelqu'un qui n'est pas du métier. */
  protected explication(type: string): string {
    return TYPES_EVENEMENT.find((t) => t.code === type)?.explication ?? '';
  }

  protected estAnomalie(type: string): boolean {
    return type === 'ANOMALIE';
  }

  /** Nul si le lieu a été supprimé : l'étape reste, elle a bien eu lieu. */
  protected ou(etape: EtapeSuivi): string {
    if (!etape.lieu && !etape.ville) {
      return 'Lieu non précisé';
    }
    return etape.ville ? `${etape.lieu ?? ''} · ${etape.ville}`.trim() : (etape.lieu ?? '');
  }

  protected dateHeure(iso: string): string {
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

/**
 * Un 404 dit « ce numéro n'existe pas », pas « erreur technique ».
 *
 * <p>C'est le cas le plus fréquent sur cette page : un chiffre mal recopié
 * depuis un SMS. Le message doit inviter à vérifier la saisie, pas laisser
 * croire que le service est en panne.</p>
 *
 * <h2>⚠️ Pourquoi cet écran garde sa propre version</h2>
 *
 * <p>Les vingt-trois autres écrans du back-office sont passés au lecteur
 * partagé {@code messageErreur}. Celui-ci non, et ce n'est pas un oubli :
 * c'est la <b>seule page publique</b>. Elle s'atteint sans compte, depuis un
 * lien reçu par SMS.</p>
 *
 * <p>Le lecteur partagé fait remonter ce que l'API répond. Ici, on ne veut
 * précisément <b>pas</b> cela : rien de ce que le serveur raconte n'a de sens
 * pour un client qui suit son colis, et le lui montrer révélerait au passage
 * des détails de fonctionnement à quiconque connaît l'adresse.</p>
 */
function message(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    if (e.status === 404) {
      return 'Aucun colis ne porte ce numéro. Vérifiez les caractères saisis.';
    }
  }
  return 'Le suivi n’a pas pu être affiché.';
}
