import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpTestingController } from '@angular/common/http/testing';
import { ConfigurationApi, ServiceSession } from 'garah-ui';

/**
 * Le back-office démarre, et sa session lit le bon contrat.
 *
 * <h2>🎯 Les premiers tests de cette application</h2>
 *
 * <p>Elle n'en avait <b>aucun</b>. C'est celle dont la maison se sert tous les
 * jours, et celle qui a servi de référence pour trouver que les deux autres se
 * trompaient de contrat (D-36) — elle-même n'était vérifiée par rien.</p>
 *
 * <h2>⚠️ Ce que le premier test prouve vraiment</h2>
 *
 * <p>{@code zone.js} était embarqué dans le paquet livré alors que
 * l'application tourne <b>sans zone</b> : Angular le signalait à chaque
 * démarrage (NG0914), et cela pesait <b>34 Ko</b> sur chaque chargement, depuis
 * une connexion mobile camerounaise.</p>
 *
 * <p>Le retirer se vérifie mal : la compilation réussit dans les deux cas, et
 * un défaut de détection de changement ne se voit qu'à l'écran. Instancier les
 * services de l'application dans un contexte lui aussi sans zone est ce qui
 * s'en approche le plus sans navigateur.</p>
 */
describe('Démarrage du back-office', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        // L'adresse de l'API : le service la reclame a l'instanciation.
        { provide: ConfigurationApi, useValue: { baseUrl: '' } },
      ],
    });
  });

  it('⚠️ la session s’instancie sans zone.js', () => {
    // Si quelque chose dépendait encore de Zone, l'injection lèverait ici.
    expect(TestBed.inject(ServiceSession)).toBeTruthy();
  });

  it('une session neuve n’est pas connectée', () => {
    const session = TestBed.inject(ServiceSession);

    expect(session.connecte()).toBe(false);
    expect(session.jetonAcces()).toBeNull();
  });

  it('⚠️ elle n’accorde aucune permission tant qu’on n’est pas connecté', () => {
    const session = TestBed.inject(ServiceSession);

    // Le menu s'appuie dessus pour masquer des entrées. Un `peut()` optimiste
    // afficherait vingt écrans dont quinze répondent « accès refusé ».
    expect(session.peut('AUDIT_CONSULTER')).toBe(false);
    expect(session.peut('PRODUIT_PUBLIER')).toBe(false);
  });

  describe('le contrat de connexion', () => {
    // Recopié d'un appel réel à l'API, le 09/09/2026.
    const reponseReelle = {
      jeton: 'eyJhbGciOiJIUzI1NiJ9.charge-utile.signature',
      typeJeton: 'Bearer',
      expireDansSecondes: 900,
      utilisateur: {
        id: 1,
        nom: 'Super Administrateur',
        type: 'SUPER_ADMIN',
        langue: 'fr',
        urlPhoto: null,
      },
      permissions: ['AUDIT_CONSULTER', 'PRODUIT_PUBLIER'],
    };

    function connecter(reponse: object = reponseReelle): ServiceSession {
      const session = TestBed.inject(ServiceSession);
      session.connecter('admin@garah.cm', 'MotDePasse123').subscribe({
        error: () => undefined,
      });
      TestBed.inject(HttpTestingController)
        .expectOne((r) => r.url.endsWith('/api/auth/connexion'))
        .flush(reponse);
      return session;
    }

    it('⚠️ lit le jeton et le compte aux noms que le serveur emploie', () => {
      const session = connecter();

      // La boutique et le mobile cherchaient `jetonAcces` et `utilisateurId` :
      // deux champs qui n'existent pas. Cette application-ci lisait juste —
      // c'est pourquoi le défaut est resté invisible (D-36).
      expect(session.jetonAcces()).toBe(reponseReelle.jeton);
      expect(session.connecte()).toBe(true);
      expect(session.utilisateur()?.nom).toBe('Super Administrateur');
    });

    it('⚠️ les permissions viennent du CORPS, jamais du jeton', () => {
      const session = connecter();

      // C'est ce qui a permis de RETIRER les 197 permissions du jeton d'un
      // administrateur (D-35) sans toucher une ligne d'interface : le menu
      // n'a jamais lu le jeton.
      expect(session.peut('AUDIT_CONSULTER')).toBe(true);
      expect(session.peut('STOCK_SUPPRIMER')).toBe(false);
    });
  });
});
