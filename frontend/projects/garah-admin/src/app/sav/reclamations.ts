import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Icone,
  Page,
  Pagination,
  Reclamation,
  ReponseErreur,
  ResumeReclamation,
  STATUTS_RECLAMATION,
  ServiceSession,
  StatutReclamation,
  badgeStatutReclamation,
  libelleStatutReclamation,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/**
 * Les réclamations.
 *
 * <h2>Pourquoi cet écran existe</h2>
 *
 * <p>La réclamation est la <b>seule voie de recours</b> du client après
 * paiement (D-12) : il ne peut plus annuler lui-même, un humain examine. Sans
 * cet écran, la règle d'annulation resterait — mais plus rien ne la
 * compenserait.</p>
 *
 * <h2>Les plus anciennes en haut</h2>
 *
 * <p>🎯 C'est l'inverse de toutes les autres listes du projet, et c'est voulu.
 * Une réclamation qui traîne est un client qui s'énerve : c'est celle-là qu'il
 * faut voir en premier, pas la dernière arrivée.</p>
 *
 * <h2>Prendre en charge n'est pas une formalité</h2>
 *
 * <p>Sans propriétaire explicite, une réclamation n'appartient à personne — et
 * c'est exactement comme ça qu'un dossier reste sans réponse pendant trois
 * semaines, chacun croyant qu'un autre s'en occupe.</p>
 */
@Component({
  selector: 'ga-reclamations',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './reclamations.html',
  styleUrl: './reclamations.scss',
})
export class Reclamations {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeReclamation[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_RECLAMATION;
  protected readonly statut = signal<StatutReclamation | ''>('');
  protected readonly recherche = signal('');
  protected readonly filtreApplique = signal('');

  // --- Le dossier ouvert ---
  protected readonly ouverte = signal<Reclamation | null>(null);
  protected readonly chargementFiche = signal(false);
  protected readonly action = signal<string | null>(null);
  protected readonly erreurFiche = signal<string | null>(null);

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? libelleStatutReclamation(code) : null;
  });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const parametres = new URLSearchParams({
      page: String(this.page()),
      taille: String(TAILLE_PAGE),
    });
    if (this.statut()) {
      parametres.set('statut', this.statut());
    }
    if (this.filtreApplique()) {
      parametres.set('recherche', this.filtreApplique());
    }

    this.http.get<Page<ResumeReclamation>>(`/api/sav/reclamations?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Les réclamations n’ont pas pu être chargées.'));
      },
    });
  }

  protected filtrer(code: StatutReclamation | ''): void {
    this.statut.set(code);
    this.page.set(0);
    this.charger();
  }

  /**
   * Le texte saisi et le filtre appliqué sont deux signaux distincts.
   *
   * <p>Les fondre ferait changer le message « aucun résultat pour… » pendant
   * qu'on tape, alors que la liste montre encore l'ancien filtre.</p>
   */
  protected chercher(): void {
    this.filtreApplique.set(this.recherche().trim());
    this.page.set(0);
    this.charger();
  }

  protected effacerRecherche(): void {
    this.recherche.set('');
    this.filtreApplique.set('');
    this.page.set(0);
    this.charger();
  }

  protected allerA(page: number): void {
    this.page.set(page);
    this.charger();
  }

  // -------------------------------------------------------------------------
  // Le dossier
  // -------------------------------------------------------------------------

  /**
   * Ouvre le dossier complet.
   *
   * <p>La <b>description</b> n'est pas dans la liste : elle peut faire deux
   * mille caractères, et une page de vingt-cinq réclamations en transporterait
   * cinquante mille que personne ne lit avant d'avoir cliqué.</p>
   */
  protected ouvrir(r: ResumeReclamation): void {
    this.chargementFiche.set(true);
    this.erreurFiche.set(null);
    this.ouverte.set(null);

    this.http.get<Reclamation>(`/api/sav/reclamations/${r.id}`).subscribe({
      next: (d) => {
        this.chargementFiche.set(false);
        this.ouverte.set(d);
      },
      error: (e: unknown) => {
        this.chargementFiche.set(false);
        this.erreurFiche.set(message(e, 'Ce dossier n’a pas pu être ouvert.'));
      },
    });
  }

  protected fermer(): void {
    this.ouverte.set(null);
    this.erreurFiche.set(null);
    this.chargementFiche.set(false);
  }

  protected prendreEnCharge(): void {
    this.agir('prise-en-charge', 'charge', null);
  }

  /**
   * Tranche la réclamation.
   *
   * <p>⚠️ Une résolution favorable n'entraîne <b>aucun</b> remboursement
   * automatique. Rendre l'argent est une décision distincte, prise par
   * quelqu'un qui en a la permission. Lier les deux ferait qu'un agent de SAV
   * déclencherait des mouvements d'argent sans y avoir droit.</p>
   */
  protected resoudre(favorable: boolean): void {
    this.agir('resolution', favorable ? 'favorable' : 'defavorable', { favorable });
  }

  private agir(chemin: string, nom: string, corps: unknown): void {
    const dossier = this.ouverte();
    if (!dossier || this.action()) {
      return;
    }
    this.action.set(nom);
    this.erreurFiche.set(null);

    this.http
      .post<Reclamation>(`/api/sav/reclamations/${dossier.id}/${chemin}`, corps)
      .subscribe({
        next: (d) => {
          this.action.set(null);
          this.ouverte.set(d);
          // La liste porte le statut : elle doit suivre, sinon le badge
          // derrière le panneau contredit ce qu'on vient de faire.
          this.charger();
        },
        error: (e: unknown) => {
          this.action.set(null);
          this.erreurFiche.set(message(e, 'L’action n’a pas pu être enregistrée.'));
        },
      });
  }

  protected prenable(): boolean {
    return this.ouverte()?.statut === 'OUVERTE';
  }

  protected close(): boolean {
    const statut = this.ouverte()?.statut;
    return statut === 'RESOLUE' || statut === 'FERMEE';
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected libelle(statut: string): string {
    return libelleStatutReclamation(statut);
  }

  protected badge(statut: string): string {
    return badgeStatutReclamation(statut);
  }

  /** Vrai si ce statut attend un geste : la liste le marque d'un point. */
  protected agirSur(statut: string): boolean {
    return STATUTS_RECLAMATION.find((s) => s.code === statut)?.agir ?? false;
  }

  /**
   * Depuis combien de temps ce dossier attend.
   *
   * <p>Une date absolue ne dit rien à qui parcourt une liste. « Depuis 6 j »
   * se lit d'un coup d'œil, et c'est le seul chiffre qui compte ici.</p>
   */
  protected attente(iso: string): string {
    const jours = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
    if (jours <= 0) {
      return "Aujourd'hui";
    }
    return jours === 1 ? 'Depuis 1 j' : `Depuis ${jours} j`;
  }

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

function message(e: unknown, repli: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Le service ne répond pas. Réessayez dans un instant.';
    }
    const corps = e.error as ReponseErreur | null;
    if (corps?.message) {
      return corps.message;
    }
  }
  return repli;
}
