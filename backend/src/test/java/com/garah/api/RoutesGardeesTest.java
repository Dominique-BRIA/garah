package com.garah.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toute route nouvelle est gardée, ou inscrite ici <b>exprès</b>.
 *
 * <h2>🎯 Pourquoi ce test existe</h2>
 *
 * <p>Un audit a montré que {@code ADMIN_MODIFIER}, {@code ADMIN_ACTIVER} et
 * {@code ADMIN_DESACTIVER} existaient au référentiel depuis l'origine et
 * n'étaient vérifiés <b>nulle part</b> (D-38). Un responsable pouvait
 * réinitialiser le mot de passe d'un administrateur, donc prendre son
 * compte.</p>
 *
 * <p>Un audit trouve ce qui est cassé aujourd'hui. Il ne dit rien de la route
 * qu'on ajoutera dans trois mois, un vendredi soir. Ce test-là, si.</p>
 *
 * <h2>Comment il fonctionne</h2>
 *
 * <p>Toute méthode annotée d'un {@code @…Mapping} dans un
 * {@code @RestController} doit porter un {@code @PreAuthorize} — ou figurer
 * dans la liste ci-dessous, qui dit <b>pourquoi</b> elle s'en passe.</p>
 *
 * <p>⚠️ Ajouter une entrée à cette liste doit être un geste <b>délibéré</b>,
 * accompagné d'une raison. C'est tout l'intérêt : le test ne rend pas
 * l'oubli impossible, il rend l'oubli <b>visible</b>.</p>
 */
@DisplayName("Routes gardées")
class RoutesGardeesTest {

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * Les routes qui se passent volontairement d'un {@code @PreAuthorize}.
     *
     * <p>Trois familles, et rien d'autre :</p>
     *
     * <pre>
     * PUBLIC      la vitrine, la santé, l'authentification, le webhook
     * PROPRIETE   un CLIENT n'a aucune permission : son accès repose sur le
     *             fait que la donnée est LA SIENNE, vérifié dans le service
     * </pre>
     *
     * <p>⚠️ « Vérifié dans le service » n'est pas une formule de politesse :
     * l'isolation a été éprouvée contre l'API réelle — un client qui demande
     * la conversation d'un autre reçoit <b>404</b>, et non 403, ce qui ne
     * confirme même pas l'existence.</p>
     */
    private static final Set<String> SANS_GARDE_ASSUMEE = Set.of(
            // --- PUBLIC : la vitrine ---
            "ControleurProduit.parIds", "ControleurProduit.fichePublique",
            "ControleurProduit.ficheVitrine", "ControleurProduit.catalogue",
            "ControleurCategorie.arbre", "ControleurLieu.pointsRecuperation",
            "ControleurStatistiques.enregistrerVue", "ControleurStatistiques.tendance",
            "ControleurConfiguration.configuration", "ControleurSante.sante",
            "ControleurExpedition.suivi",

            // --- PUBLIC : s'identifier ---
            "ControleurAuthentification.inscription", "ControleurAuthentification.connexion",
            "ControleurAuthentification.rafraichir", "ControleurAuthentification.deconnexion",
            "ControleurAuthentification.moi", "ControleurAuthentification.confirmerParLien",
            "ControleurAuthentification.confirmer", "ControleurAuthentification.renvoyer",

            // --- PUBLIC : l'opérateur nous notifie ---
            // ⚠️ Elle ne retient que la référence et redemande l'état réel à
            //    Campay : forger une notification ne sert a rien.
            "ControleurPaiement.notificationCampay",

            // --- PROPRIETE : le panier ---
            "ControleurPanier.contenu", "ControleurPanier.ajouter",
            "ControleurPanier.definirQuantite", "ControleurPanier.retirer",
            "ControleurPanier.vider", "ControleurPanier.fusionner",

            // --- PROPRIETE : mon compte ---
            // ⚠️ Toutes lisent l'identifiant DANS LE JETON : elles ne peuvent
            //    designer que le compte de l'appelant. Un @PreAuthorize n'y
            //    ajouterait rien — un CLIENT n'a aucune permission a exiger.
            "ControleurProfil.lire", "ControleurProfil.modifier",
            "ControleurProfil.changerMotDePasse", "ControleurProfil.changerPhoto",
            "ControleurProfil.retirerPhoto", "ControleurService.mesServices",

            // --- PROPRIETE : mon appareil ---
            "ControleurNotification.declarer", "ControleurNotification.retirer",

            // --- PROPRIETE : mes commandes, mes paiements ---
            "ControleurCommande.passer", "ControleurCommande.mesCommandes",
            "ControleurCommande.maCommande", "ControleurCommande.annulerMaCommande",
            "ControleurPaiement.payer", "ControleurPaiement.etat", "ControleurPaiement.verifier",
            "ControleurExpedition.monRetrait",

            // --- PROPRIETE : mon après-vente ---
            "ControleurSav.ouvrir", "ControleurSav.mesReclamations",
            "ControleurSav.demanderRetour", "ControleurSav.mesRetours",

            // --- PROPRIETE : mes favoris ---
            "ControleurStatistiques.mesFavoris", "ControleurStatistiques.ajouterFavori",
            "ControleurStatistiques.retirerFavori",

            // --- PROPRIETE : mes discussions ---
            "ControleurConversation.ouvrir", "ControleurConversation.fil",
            "ControleurConversation.miennes", "ControleurConversation.repondre",
            "ControleurConversation.evaluer",
            // L'Assistance du client connecté : sa propriété tient au jeton, et
            // la route refuse tout porteur qui n'est pas client.
            "ControleurConversation.ecrireALAssistance",

            // ⚠️ Les propositions de prix restent ouvertes au client sur SA
            //    conversation. Depuis D-39 le prix est ferme et aucune
            //    interface ne les propose plus — la route survit au bouton qui
            //    l'appelait. A trancher : la réserver au back-office.
            "ControleurConversation.propositions", "ControleurConversation.proposer",
            "ControleurConversation.contreProposer", "ControleurConversation.accepter",
            "ControleurConversation.refuser");

    private static JavaClasses classes;

    @BeforeAll
    static void importer() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.garah.api");
    }

    @Test
    @DisplayName("⚠️ aucune route n'échappe à une décision explicite")
    void aucuneRouteNEchappeAUneDecision() {
        Set<String> nues = new TreeSet<>();

        for (var classe : classes) {
            if (!classe.isAnnotatedWith(RestController.class)) {
                continue;
            }
            // Une classe entièrement gardée couvre ses méthodes.
            if (classe.isAnnotatedWith(PreAuthorize.class)) {
                continue;
            }
            for (JavaMethod methode : classe.getMethods()) {
                if (MAPPINGS.stream().noneMatch(methode::isAnnotatedWith)) {
                    continue;
                }
                if (methode.isAnnotatedWith(PreAuthorize.class)) {
                    continue;
                }
                nues.add(classe.getSimpleName() + "." + methode.getName());
            }
        }

        nues.removeAll(SANS_GARDE_ASSUMEE);

        assertThat(nues)
                .as("""
                        Ces routes n'ont pas de @PreAuthorize et ne figurent pas \
                        dans SANS_GARDE_ASSUMEE.

                        Deux issues, et une seule est un raccourci :
                          - poser le @PreAuthorize qui manque ;
                          - l'inscrire dans la liste, AVEC la raison, si l'accès \
                        repose sur la propriété de la donnée ou si la route est \
                        publique.

                        ⚠️ Ne l'inscrivez pas pour faire passer le test. C'est \
                        exactement comme cela que ADMIN_MODIFIER est resté cinq \
                        versions sans garder quoi que ce soit.""")
                .isEmpty();
    }

    /**
     * ⚠️ Une liste d'exceptions qui grossit sans qu'on la relise finit par tout
     * couvrir. Celle-ci ne doit contenir que des routes qui existent : une
     * entrée périmée est une garde qu'on croit posée.
     */
    @Test
    @DisplayName("la liste d'exceptions ne garde pas de routes disparues")
    void laListeNeGardePasDeRoutesDisparues() {
        Set<String> existantes = new TreeSet<>();
        for (var classe : classes) {
            if (!classe.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod methode : classe.getMethods()) {
                if (MAPPINGS.stream().anyMatch(methode::isAnnotatedWith)) {
                    existantes.add(classe.getSimpleName() + "." + methode.getName());
                }
            }
        }

        Set<String> perimees = new TreeSet<>(SANS_GARDE_ASSUMEE);
        perimees.removeAll(existantes);

        assertThat(perimees)
                .as("Ces entrées ne désignent plus aucune route : à retirer.")
                .isEmpty();
    }
}
