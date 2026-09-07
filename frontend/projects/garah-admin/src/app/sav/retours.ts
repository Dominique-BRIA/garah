import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Icone,
  MOYENS_PAIEMENT,
  MoyenPaiement,
  Page,
  Pagination,
  ReponseErreur,
  ResumeRetour,
  Retour,
  STATUTS_RETOUR,
  ServiceSession,
  StatutRetour,
  TRANSITIONS_RETOUR,
  badgeStatutRetour,
  libelleEtatArticle,
  libelleMoyen,
  libelleStatutRetour,
  montantLisible,
} from 'garah-ui';

const TAILLE_PAGE = 25;

/** Quelle permission autorise quelle transition, et sous quel libellé. */
const GESTES: Readonly<
  Record<string, { readonly chemin: string; readonly libelle: string; readonly permission: string }>
> = {
  ACCEPTE: { chemin: 'acceptation', libelle: 'Accepter le retour', permission: 'RETOUR_ACCEPTER' },
  REFUSE: { chemin: 'refus', libelle: 'Refuser', permission: 'RETOUR_REFUSER' },
  RECEPTIONNE: {
    chemin: 'reception',
    libelle: 'Marquer reçu',
    permission: 'RETOUR_RECEPTIONNER',
  },
  VALIDE: { chemin: 'validation', libelle: 'Rembourser', permission: 'RETOUR_VALIDER' },
  CLOTURE: { chemin: 'cloture', libelle: 'Clôturer', permission: 'RETOUR_CLOTURER' },
};

/**
 * Les retours de marchandise.
 *
 * <h2>Le cycle, et pourquoi il compte</h2>
 *
 * <pre>
 * DEMANDE ──▶ ACCEPTE ──▶ RECEPTIONNE ──▶ VALIDE ──▶ CLOTURE
 *    │                          │
 *    └──▶ REFUSE                └── on ouvre le colis et on regarde
 * </pre>
 *
 * <p>🎯 <b>Le remboursement ne part qu'à VALIDE</b>, donc après que quelqu'un
 * a vu la marchandise. Jamais à la demande. Raccourcir ce chemin — un bouton
 * « accepter et rembourser » — reviendrait à rendre l'argent sur parole.</p>
 *
 * <h2>Trois métiers, trois permissions, trois files</h2>
 *
 * <p>Accepter un retour, réceptionner un colis et décider de rendre l'argent
 * ne sont pas le même travail : ce sont trois permissions distinctes. D'où le
 * filtre par statut — sans lui, chacun parcourrait toute la liste pour trouver
 * ce qui l'attend.</p>
 *
 * <h2>Deux chiffres qui ne disent pas la même chose</h2>
 *
 * <p>Les <b>articles annoncés</b> sont ce que le client dit renvoyer ; le
 * <b>montant remboursé</b> est ce qui a été rendu, et il vaut zéro tant que le
 * colis n'a pas été ouvert. Les fondre en un seul « montant du retour » serait
 * un contresens.</p>
 */
@Component({
  selector: 'ga-retours',
  imports: [FormsModule, Icone, RouterLink, Pagination],
  templateUrl: './retours.html',
  styleUrl: './retours.scss',
})
export class Retours {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly liste = signal<readonly ResumeRetour[]>([]);
  protected readonly total = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly page = signal(0);
  protected readonly taille = TAILLE_PAGE;

  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly statuts = STATUTS_RETOUR;
  protected readonly statut = signal<StatutRetour | ''>('');

  // --- Le dossier ouvert ---
  protected readonly ouvert = signal<Retour | null>(null);
  protected readonly chargementFiche = signal(false);
  protected readonly action = signal<string | null>(null);
  protected readonly erreurFiche = signal<string | null>(null);

  /** Le remboursement demande par quel moyen l'argent repart. */
  protected readonly moyens = MOYENS_PAIEMENT;
  protected readonly formRemboursement = signal(false);
  protected readonly moyen = signal<MoyenPaiement>('MTN_MOMO');

  protected readonly libelleStatut = computed(() => {
    const code = this.statut();
    return code ? libelleStatutRetour(code) : null;
  });

  /** Ce que le statut courant attend de celui qui lit — affiché en clair. */
  protected readonly attendu = computed(
    () => STATUTS_RETOUR.find((s) => s.code === this.statut())?.attendu ?? '',
  );

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

    this.http.get<Page<ResumeRetour>>(`/api/sav/retours?${parametres}`).subscribe({
      next: (page) => {
        this.liste.set(page.content);
        this.total.set(page.page.totalElements);
        this.totalPages.set(page.page.totalPages);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Les retours n’ont pas pu être chargés.'));
      },
    });
  }

  protected filtrer(code: StatutRetour | ''): void {
    this.statut.set(code);
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

  protected ouvrir(r: ResumeRetour): void {
    this.chargementFiche.set(true);
    this.erreurFiche.set(null);
    this.formRemboursement.set(false);
    this.ouvert.set(null);

    this.http.get<Retour>(`/api/sav/retours/${r.id}`).subscribe({
      next: (d) => {
        this.chargementFiche.set(false);
        this.ouvert.set(d);
      },
      error: (e: unknown) => {
        this.chargementFiche.set(false);
        this.erreurFiche.set(message(e, 'Ce retour n’a pas pu être ouvert.'));
      },
    });
  }

  protected fermer(): void {
    this.ouvert.set(null);
    this.erreurFiche.set(null);
    this.chargementFiche.set(false);
    this.formRemboursement.set(false);
  }

  /**
   * Les gestes encore possibles, filtrés par ce que le compte a le droit de
   * faire.
   *
   * <p>Le serveur reste seul juge : cette table ne fait qu'éviter un bouton
   * qui échouerait. Si la machine à états du serveur change,
   * {@code TRANSITIONS_RETOUR} doit suivre.</p>
   */
  protected gestes(): readonly { code: StatutRetour; libelle: string }[] {
    const statut = this.ouvert()?.statut;
    if (!statut) {
      return [];
    }
    return TRANSITIONS_RETOUR[statut]
      .filter((vers) => this.session.peut(GESTES[vers].permission))
      .map((vers) => ({ code: vers, libelle: GESTES[vers].libelle }));
  }

  /**
   * Déclenche un geste.
   *
   * <p>La validation passe par un formulaire à part : c'est la seule
   * transition qui déplace de l'argent, et elle demande <b>par quel moyen</b>.
   * L'enchaîner sans rien demander ferait partir un remboursement au hasard.</p>
   */
  protected declencher(vers: StatutRetour): void {
    if (vers === 'VALIDE') {
      this.formRemboursement.set(true);
      return;
    }
    this.envoyer(GESTES[vers].chemin, vers, null);
  }

  protected rembourser(): void {
    this.envoyer('validation', 'VALIDE', { moyenRemboursement: this.moyen() });
  }

  private envoyer(chemin: string, nom: string, corps: unknown): void {
    const dossier = this.ouvert();
    if (!dossier || this.action()) {
      return;
    }
    this.action.set(nom);
    this.erreurFiche.set(null);

    this.http.post<Retour>(`/api/sav/retours/${dossier.id}/${chemin}`, corps).subscribe({
      next: () => {
        this.action.set(null);
        this.formRemboursement.set(false);
        // La réponse du serveur est un RÉSUMÉ : ses lignes sont vides. On
        // recharge la fiche plutôt que de l'écraser avec une version amputée —
        // sinon le détail disparaît juste après l'action.
        this.rafraichir(dossier.id);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreurFiche.set(message(e, 'L’action n’a pas pu être enregistrée.'));
      },
    });
  }

  private rafraichir(id: number): void {
    this.http.get<Retour>(`/api/sav/retours/${id}`).subscribe({
      next: (d) => this.ouvert.set(d),
      error: () => this.fermer(),
    });
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected libelle(statut: string): string {
    return libelleStatutRetour(statut);
  }

  protected badge(statut: string): string {
    return badgeStatutRetour(statut);
  }

  protected etat(code: string | null): string {
    return code ? libelleEtatArticle(code) : '—';
  }

  /** Un article abîmé ne redeviendra jamais vendable : la fiche le signale. */
  protected invendable(code: string | null): boolean {
    return code === 'ABIME' || code === 'INUTILISABLE';
  }

  protected montant(valeur: number): string {
    return montantLisible(valeur, 'XAF');
  }

  protected nomMoyen(code: string): string {
    return libelleMoyen(code);
  }

  protected attendSur(statut: string): boolean {
    return statut === 'DEMANDE' || statut === 'RECEPTIONNE';
  }

  protected totalRembourse(r: Retour): number {
    return r.lignes.reduce((somme, l) => somme + l.montantRembourse, 0);
  }

  protected dateHeure(iso: string | null): string {
    if (!iso) {
      return '—';
    }
    return new Date(iso).toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  protected attente(iso: string): string {
    const jours = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
    if (jours <= 0) {
      return "Aujourd'hui";
    }
    return jours === 1 ? 'Depuis 1 j' : `Depuis ${jours} j`;
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
