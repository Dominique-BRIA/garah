import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  BilanPeriode,
  Icone,
  PERIODES,
  PointJour,
  ReponseErreur,
  ServiceSession,
  montantLisible,
  tauxLisible,
} from 'garah-ui';

import { Rapport, versPdf, versTableur, versWord } from './rapport';

/**
 * Ce que le catalogue a produit sur une période.
 *
 * <h2>Ces chiffres ne se calculent pas rétroactivement</h2>
 *
 * <p>🎯 C'est la raison pour laquelle les tables de mesure existent depuis la
 * v1, avant le moindre écran : une vue non enregistrée en mars est perdue pour
 * toujours. Cet écran ne fait que <b>lire</b> ce qui a été collecté.</p>
 *
 * <h2>Lu dans les agrégats, jamais recalculé</h2>
 *
 * <p>Le détail des vues est purgé à 90 jours. Un bilan recalculé depuis le
 * détail serait juste sur les dernières semaines et faux au-delà, <b>sans que
 * rien ne le signale</b> — le pire des deux mondes. Le serveur additionne donc
 * les agrégats quotidiens, et cet écran ne fait aucune arithmétique de son
 * côté.</p>
 *
 * <h2>Un trou dans les données se DIT</h2>
 *
 * <p>Le serveur renvoie le nombre de jours réellement <b>couverts par des
 * agrégats</b>. S'il est inférieur à la période demandée, une nuit
 * d'agrégation a été manquée : l'écran l'annonce au lieu d'afficher un creux
 * inexpliqué dans la courbe et de laisser croire à une baisse d'activité.</p>
 */
@Component({
  selector: 'ga-statistiques',
  imports: [FormsModule, Icone, RouterLink],
  templateUrl: './statistiques.html',
  styleUrl: './statistiques.scss',
})
export class Statistiques {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(ServiceSession);

  protected readonly bilan = signal<BilanPeriode | null>(null);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly action = signal<string | null>(null);

  protected readonly periodes = PERIODES;

  /** Trente jours : la fenêtre qui correspond au cycle réel d'une commande. */
  protected readonly jours = signal(30);

  /**
   * Le nombre de jours attendus sur la période demandée.
   *
   * <p>Bornes comprises : du 1er au 30 fait trente jours, pas vingt-neuf.</p>
   */
  protected readonly joursAttendus = computed(() => this.jours());

  /**
   * Combien de journées d'agrégation manquent.
   *
   * <p>⚠️ Le jour <b>en cours</b> n'est pas encore agrégé — l'agrégation
   * tourne la nuit. Un écart de 1 est donc normal et ne doit rien signaler :
   * l'annoncer chaque jour ferait passer l'alerte pour du bruit, et personne
   * ne la lirait le jour où elle compte.</p>
   */
  protected readonly journeesManquantes = computed(() => {
    const b = this.bilan();
    if (!b) {
      return 0;
    }
    return Math.max(0, this.joursAttendus() - 1 - b.jours);
  });

  /** Le plus haut point de la courbe, pour mettre les barres à l'échelle. */
  protected readonly sommet = computed(() =>
    Math.max(1, ...this.bilan()?.parJour.map((p) => p.vues) ?? [1]),
  );

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    const au = new Date();
    const du = new Date();
    du.setDate(du.getDate() - (this.jours() - 1));

    const parametres = new URLSearchParams({
      du: iso(du),
      au: iso(au),
      limite: '10',
    });

    this.http.get<BilanPeriode>(`/api/statistiques/bilan?${parametres}`).subscribe({
      next: (b) => {
        this.bilan.set(b);
        this.chargement.set(false);
      },
      error: (e: unknown) => {
        this.chargement.set(false);
        this.erreur.set(message(e, 'Le bilan n’a pas pu être chargé.'));
      },
    });
  }

  protected changerPeriode(jours: number): void {
    this.jours.set(jours);
    this.charger();
  }

  /**
   * Relance l'agrégation d'hier.
   *
   * <p>Le traitement tourne chaque nuit. Ce bouton sert au <b>rattrapage</b> :
   * instance redémarrée pendant la nuit, journée oubliée après une panne.
   * L'opération est idempotente — rejouer la même date recalcule au lieu de
   * dupliquer, et c'est bien pour ça qu'on peut l'exposer.</p>
   */
  protected rattraper(): void {
    if (this.action()) {
      return;
    }
    this.action.set('agregation');
    this.erreur.set(null);

    const hier = new Date();
    hier.setDate(hier.getDate() - 1);

    this.http.post(`/api/statistiques/agregation/${iso(hier)}`, null).subscribe({
      next: () => {
        this.action.set(null);
        this.charger();
      },
      error: (e: unknown) => {
        this.action.set(null);
        this.erreur.set(message(e, 'L’agrégation n’a pas pu être relancée.'));
      },
    });
  }

  /**
   * Le bilan affiché, dans un fichier qu'un tableur ouvre.
   *
   * <p>🎯 <b>Tout est déjà dans le navigateur.</b> Le serveur n'est pas
   * rappelé : on écrit ce que l'écran montre. Un export qui refait sa propre
   * requête finit toujours par livrer des chiffres différents de ceux qu'on
   * regardait — et c'est le genre d'écart qu'on ne découvre qu'en réunion.</p>
   *
   * <p>Les trois blocs de l'écran s'y retrouvent dans l'ordre : les totaux,
   * le détail jour par jour, le classement. La période est écrite en tête,
   * parce qu'un tableau de chiffres sans ses dates ne veut rien dire.</p>
   */
  protected rapport(): Rapport | null {
    const b = this.bilan();
    if (!b) {
      return null;
    }

    return {
      titre: 'Bilan GARAH',
      periode: `Du ${this.dateLongue(b.du)} au ${this.dateLongue(b.au)}`,
      fichier: `garah-statistiques-${b.du}-au-${b.au}`,
      sections: [
        {
          titre: 'Totaux',
          // Deux colonnes, et le type « texte » sur la seconde : ce tableau
          // aligne des grandeurs qui n'ont pas la même unité — des francs,
          // des unités, des jours. Les formater ensemble n'aurait aucun sens.
          colonnes: [
            { titre: 'Indicateur', type: 'texte' },
            { titre: 'Valeur', type: 'texte' },
          ],
          lignes: [
            ['Chiffre d’affaires', this.montant(b.chiffreAffaires)],
            ['Commandes', String(b.commandes)],
            ['Articles vendus', String(b.quantiteVendue)],
            ['Retours', String(b.retours)],
            ['Fiches consultées', String(b.vues)],
            ['Visiteurs distincts', String(b.vuesUniques)],
            ['Jours couverts', `${b.jours} sur ${this.joursAttendus()}`],
          ],
        },
        {
          titre: 'Jour par jour',
          colonnes: [
            { titre: 'Jour', type: 'date' },
            { titre: 'Vues', type: 'nombre' },
            { titre: 'Commandes', type: 'nombre' },
            { titre: 'Chiffre d’affaires', type: 'montant' },
          ],
          // De vraies dates et de vrais nombres, pas leur écriture : dans un
          // tableur, c'est ce qui permet de trier, de totaliser et de tracer.
          lignes: b.parJour.map((p) => [
            new Date(p.jour),
            p.vues,
            p.commandes,
            p.chiffreAffaires,
          ]),
        },
        {
          titre: 'Ce qui rapporte le plus',
          colonnes: [
            { titre: 'Produit', type: 'texte' },
            { titre: 'Vues', type: 'nombre' },
            { titre: 'Commandes', type: 'nombre' },
            { titre: 'Quantité vendue', type: 'nombre' },
            { titre: 'Conversion', type: 'taux' },
            { titre: 'Chiffre d’affaires', type: 'montant' },
          ],
          lignes: b.meilleurs.map((l) => [
            l.nom,
            l.vues,
            l.commandes,
            l.quantiteVendue,
            l.tauxConversion,
            l.chiffreAffaires,
          ]),
        },
      ],
    };
  }

  /**
   * Écrit le bilan dans le format demandé.
   *
   * <p>Le bouton reste enfoncé le temps du travail : la bibliothèque doit
   * d'abord être téléchargée, et sur une connexion lente cela prend plusieurs
   * secondes. Sans ce retour, on clique trois fois et on obtient trois
   * fichiers.</p>
   */
  protected exporter(format: 'pdf' | 'tableur' | 'word'): void {
    const r = this.rapport();
    if (!r || this.action()) {
      return;
    }

    this.action.set(format);
    const ecrire = { pdf: versPdf, tableur: versTableur, word: versWord }[format];

    ecrire(r)
      .catch(() => this.erreur.set('Le fichier n’a pas pu être créé.'))
      .finally(() => this.action.set(null));
  }

  // -------------------------------------------------------------------------
  // Affichage
  // -------------------------------------------------------------------------

  protected montant(valeur: number): string {
    return montantLisible(valeur, 'XAF');
  }

  protected taux(valeur: number | null): string {
    return tauxLisible(valeur);
  }

  /**
   * La hauteur d'une barre, en pourcentage du sommet.
   *
   * <p>Un minimum visible pour les jours à zéro : une barre de hauteur nulle
   * est indistinguable d'un jour <b>absent</b>, et les deux ne veulent pas
   * dire la même chose.</p>
   */
  protected hauteur(p: PointJour): number {
    return Math.max(2, Math.round((p.vues / this.sommet()) * 100));
  }

  protected jourCourt(iso: string): string {
    return new Date(iso).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
  }

  protected jourLong(p: PointJour): string {
    const date = new Date(p.jour).toLocaleDateString('fr-FR', {
      weekday: 'long',
      day: '2-digit',
      month: 'long',
    });
    return `${date} — ${p.vues} vue(s), ${p.commandes} commande(s)`;
  }

  /**
   * Une date écrite en toutes lettres, pour l'en-tête d'un fichier.
   *
   * <p>À l'écran, « 10 août » suffit : la page est là, on sait quand on la
   * regarde. Un fichier, lui, se retrouve six mois plus tard dans un dossier —
   * il lui faut son année.</p>
   */
  protected dateLongue(jour: string): string {
    return new Date(jour).toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: 'long',
      year: 'numeric',
    });
  }

  protected periodeLisible(): string {
    const b = this.bilan();
    if (!b) {
      return '';
    }
    const du = new Date(b.du).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' });
    const au = new Date(b.au).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' });
    return `${du} → ${au}`;
  }
}

/** Le format que le serveur attend : une date, sans heure ni fuseau. */
function iso(date: Date): string {
  return date.toISOString().slice(0, 10);
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
