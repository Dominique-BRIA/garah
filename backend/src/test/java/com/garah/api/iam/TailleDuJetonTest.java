package com.garah.api.iam;

import com.garah.api.iam.domaine.ServiceAuthentification;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.securite.ServiceJeton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le jeton doit tenir dans un en-tête HTTP.
 *
 * <h2>🎯 Ce que ce test empêche de revenir</h2>
 *
 * <p>Le jeton d'un super-administrateur énumère <b>toutes</b> les permissions
 * actives — 197 à ce jour, et une de plus à chaque fonctionnalité ajoutée. Il
 * pesait 6 915 octets, pour une limite Tomcat de 8 Ko en-têtes compris.</p>
 *
 * <p>Le symptôme n'a rien d'un dépassement de taille. Le journal des actions
 * se chargeait ; on choisissait un filtre, l'URL grandissait de vingt-cinq
 * caractères, et Tomcat répondait <b>400</b> avec sa propre page d'erreur —
 * produite avant la chaîne de filtres, donc <b>sans en-tête CORS</b>. Le
 * navigateur jetait la réponse et rendait un statut 0. L'écran affichait « Le
 * service ne répond pas » pendant que le service répondait parfaitement.</p>
 *
 * <p>Trois faux coupables ont été poursuivis avant celui-là. Un test qui
 * mesure coûte quelques millisecondes ; le diagnostic a coûté une heure.</p>
 */
@SpringBootTest
@DisplayName("Taille du jeton d'accès")
class TailleDuJetonTest {

    /**
     * La limite configurée est de 16 Ko, en-têtes compris.
     *
     * <p>Le navigateur en consomme environ 1 Ko de son côté — Host,
     * User-Agent, Accept, Origin, Referer, Sec-Fetch-*. On garde donc une
     * marge large : franchir ce seuil ne casse rien tout de suite, mais dit
     * qu'il est temps de cesser d'énumérer (voir D-34).</p>
     */
    private static final int SEUIL_OCTETS = 12_000;

    @Autowired ServiceJeton jetons;
    @Autowired ServiceAuthentification authentification;

    @Test
    @DisplayName("⚠️ celui du super-administrateur tient dans l'en-tête")
    void celuiDuSuperAdminTientDansLEnTete() {
        // Le pire cas par construction : ce type reçoit TOUTES les
        // permissions actives. Mesurer un responsable ne prouverait rien —
        // il en porte trois.
        Utilisateur patron = new Utilisateur(
                TypeUtilisateur.SUPER_ADMIN, "Mesure", "mesure.jeton@garah.cm", "x");

        Set<String> droits = authentification.permissionsDe(patron);
        int taille = jetons.creer(patron, droits).length();

        assertThat(droits)
                .as("le pire cas doit bien être le pire : toutes les permissions")
                .hasSizeGreaterThan(100);

        assertThat(taille)
                .as("""
                        Le jeton pèse %d octets pour %d permissions. Au-delà de \
                        %d, l'en-tête Authorization plus ceux du navigateur \
                        approchent la limite du serveur — et un dépassement ne \
                        se lit pas comme un dépassement : il se lit comme un \
                        écran qui dit « le service ne répond pas ». Il est \
                        temps de cesser d'énumérer les droits dans le jeton.\
                        """.formatted(taille, droits.size(), SEUIL_OCTETS))
                .isLessThan(SEUIL_OCTETS);
    }
}
