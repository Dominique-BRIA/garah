package com.garah.api.logistique;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.domaine.TypeMedia;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.domaine.*;
import com.garah.api.commerce.infra.LigneCommandeRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.logistique.domaine.*;
import com.garah.api.logistique.infra.LieuRepository;
import com.garah.api.sav.domaine.EtatArticle;
import com.garah.api.sav.domaine.Reclamation;
import com.garah.api.sav.domaine.ResumeReclamation;
import com.garah.api.sav.domaine.ResumeRetour;
import com.garah.api.sav.domaine.Retour;
import com.garah.api.sav.domaine.ServiceReclamation;
import com.garah.api.sav.domaine.ServiceRetour;
import com.garah.api.sav.domaine.StatutReclamation;
import com.garah.api.sav.domaine.StatutRetour;
import com.garah.api.stock.domaine.ServiceStock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le parcours complet : commande → paiement → expédition → retrait → retour.
 *
 * <p>Un test de bout en bout n'est pas un test d'intégration paresseux : c'est
 * le seul qui vérifie que les <b>frontières entre domaines</b> tiennent. Les
 * tests unitaires de chaque module passaient déjà ; c'est leur assemblage qui
 * casse en vrai.</p>
 */
@SpringBootTest
@DisplayName("Parcours logistique et SAV")
class ParcoursLogistiqueTest {

    private static final String CODE_MARCHAND = "M-LOG-1";
    private static final String EMAIL = "client.log@garah.cm";

    @Autowired ServicePanier panier;
    @Autowired ServiceCommande commandes;
    @Autowired ServicePaiement paiements;
    @Autowired ServiceExpedition expeditions;
    @Autowired ServiceRetour retours;
    @Autowired ServiceReclamation reclamations;
    @Autowired ServiceCatalogue catalogue;
    @Autowired ServiceTarification tarification;
    @Autowired ServiceStock stock;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired LieuRepository lieux;
    @Autowired LigneCommandeRepository lignesCommande;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired ResponsableRepository responsables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;
    private Long responsableId;
    private Long varianteId;
    private Long entrepotId;
    private Long transitId;
    private Long pointRetraitId;
    private DetailCommande commande;

    @BeforeEach
    void preparerUneCommandePayee() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Owona", EMAIL, "x"));
            u.marquerEmailVerifie();
            clientId = clients.save(new Client(u, "CLI-LOG-1")).getId();

            Utilisateur r = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "David", "resp.log@garah.cm", "x"));
            r.marquerEmailVerifie();
            responsableId = responsables.save(new Responsable(r, "M-LOG-R1")).getId();

            Long marchandId = jdbc.queryForObject("""
                    INSERT INTO marchand (code, nom, type)
                    VALUES (?, 'Marchand logistique', 'EXTERNE') RETURNING id
                    """, Long.class, CODE_MARCHAND);

            Long categorieId = categories.save(new CategorieProduit("Logistique", null)).getId();
            Long produitId = catalogue.creerProduit(marchandId, categorieId,
                    "REF-LOG-1", "Chemise acheminée", null).id();
            varianteId = variantes.findByProduitId(produitId).getFirst().getId();

            tarification.definirPalier(varianteId, 1, null, new BigDecimal("15000.00"));
            catalogue.ajouterMedia(produitId, TypeMedia.PHOTO, "produits/log/1.jpg", true);
            catalogue.publier(produitId);

            // Le stock naît AVEC la déclinaison depuis que `VarianteCreee` est écouté
            // (I-15). L'appeler ici leverait « cette variante a déjà un stock ».
            stock.entrer(varianteId, 30, null, "Mise en place");

            entrepotId = lieux.save(new Lieu(TypeLieu.ENTREPOT, "Entrepôt Douala",
                    "Cameroun", "Douala")).getId();
            transitId = lieux.save(new Lieu(TypeLieu.POINT_TRANSIT, "Transit Bertoua",
                    "Cameroun", "Bertoua")).getId();

            Lieu bangui = new Lieu(TypeLieu.POINT_RECUPERATION, "Bangui PK5", "RCA", "Bangui");
            bangui.setFraisAcheminement(new BigDecimal("8000.00"));
            pointRetraitId = lieux.save(bangui).getId();
        });

        panier.ajouter(clientId, varianteId, 10);
        commande = commandes.passer(clientId, pointRetraitId, "fr");

        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(paiement.getId(), "MOMO-LOG-" + System.nanoTime());
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM ligne_retour WHERE retour_id IN (SELECT r.id FROM retour r JOIN client c ON c.id = r.client_id WHERE c.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM retour WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-LOG-1')");
        // Sans cette ligne, les réclamations d'un test resteraient visibles
        // dans la liste du suivant — et l'assertion « doesNotContain » d'un
        // filtre passerait ou échouerait selon l'ordre d'exécution.
        jdbc.update("DELETE FROM reclamation WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM retrait_marchandise WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM evenement_expedition WHERE colis_id IN (SELECT co.id FROM colis co JOIN expedition e ON e.id = co.expedition_id JOIN commande cm ON cm.id = e.commande_id JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM ligne_colis WHERE colis_id IN (SELECT co.id FROM colis co JOIN expedition e ON e.id = co.expedition_id JOIN commande cm ON cm.id = e.commande_id JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM colis WHERE expedition_id IN (SELECT e.id FROM expedition e JOIN commande cm ON cm.id = e.commande_id JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM expedition WHERE commande_id IN (SELECT cm.id FROM commande cm JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM tentative_paiement WHERE paiement_id IN (SELECT p.id FROM paiement p JOIN commande cm ON cm.id = p.commande_id JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM paiement WHERE commande_id IN (SELECT cm.id FROM commande cm JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM ligne_commande WHERE commande_id IN (SELECT cm.id FROM commande cm JOIN client cl ON cl.id = cm.client_id WHERE cl.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM commande WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM ligne_panier WHERE panier_id IN (SELECT p.id FROM panier p JOIN client c ON c.id = p.client_id WHERE c.code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM panier WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-LOG-1')");
        jdbc.update("DELETE FROM mouvement_stock WHERE stock_id IN (SELECT s.id FROM stock s JOIN variante v ON v.id = s.variante_id JOIN produit p ON p.id = v.produit_id JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM stock WHERE variante_id IN (SELECT v.id FROM variante v JOIN produit p ON p.id = v.produit_id JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM tarification WHERE variante_id IN (SELECT v.id FROM variante v JOIN produit p ON p.id = v.produit_id JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM media WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM variante WHERE produit_id IN (SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM produit WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        // Le grand livre ecrit lors de la confirmation du paiement (chapitre 17).
        // La cle etrangere a signale l oubli : c est son role.
        jdbc.update("DELETE FROM ecriture_marchand WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM reglement_marchand WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM marchand WHERE code = ?", CODE_MARCHAND);
        jdbc.update("DELETE FROM categorie_produit WHERE nom = 'Logistique'");
        jdbc.update("DELETE FROM lieu WHERE nom IN ('Entrepôt Douala', 'Transit Bertoua', 'Bangui PK5')");
        jdbc.update("DELETE FROM client WHERE code_client = 'CLI-LOG-1'");
        jdbc.update("DELETE FROM responsable WHERE matricule = 'M-LOG-R1'");
        jdbc.update("DELETE FROM utilisateur WHERE email IN (?, 'resp.log@garah.cm')", EMAIL);
    }

    private Colis colisPret() {
        Expedition expedition = expeditions.creer(commande.id(), entrepotId, pointRetraitId, null);
        Colis colis = expeditions.ajouterColis(expedition.getId(), null);
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();
        expeditions.remplir(colis.getId(), ligneId, 10);
        return colis;
    }

    // -------------------------------------------------------------------------
    // Expédition
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("une expédition part sans itinéraire : une livraison locale n'en a pas")
    void itineraireFacultatif() {
        Expedition expedition = expeditions.creer(commande.id(), entrepotId, pointRetraitId, null);

        assertThat(expedition.getItineraireId()).isNull();
        assertThat(expedition.getNumero()).startsWith("EXP-");
        assertThat(expedition.getStatut()).isEqualTo(StatutExpedition.CREEE);
    }

    @Test
    @DisplayName("la destination doit être un point de récupération")
    void destinationInvalide() {
        assertThatThrownBy(() -> expeditions.creer(commande.id(), entrepotId, transitId, null))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("point de récupération");
    }

    @Test
    @DisplayName("on ne met pas en colis plus que ce qui a été commandé")
    void triggerQuantiteColis() {
        Expedition expedition = expeditions.creer(commande.id(), entrepotId, pointRetraitId, null);
        Colis colis = expeditions.ajouterColis(expedition.getId(), null);
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();

        // I-35, porté par un TRIGGER : SQL ne sait pas exprimer « la somme des
        // lignes filles ne dépasse pas une valeur de la ligne mère ».
        assertThatThrownBy(() -> {
            expeditions.remplir(colis.getId(), ligneId, 12);
            jdbc.queryForObject("SELECT 1", Integer.class);   // force le flush
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("le statut du colis suit ses événements")
    void projectionDuStatut() {
        Colis colis = colisPret();

        expeditions.enregistrer(colis.getId(), entrepotId, responsableId,
                TypeEvenement.DEPART, null);
        assertThat(statutColis(colis.getId())).isEqualTo("EN_TRANSIT");

        expeditions.enregistrer(colis.getId(), transitId, responsableId,
                TypeEvenement.ARRIVEE, null);
        assertThat(statutColis(colis.getId())).isEqualTo("EN_TRANSIT");

        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId,
                TypeEvenement.ARRIVEE, null);
        // Arrivé au POINT DE RÉCUPÉRATION : cette fois, c'est disponible.
        assertThat(statutColis(colis.getId())).isEqualTo("DISPONIBLE");
    }

    @Test
    @DisplayName("un colis peut passer par un lieu hors itinéraire")
    void deroutementAutorise() {
        Colis colis = colisPret();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);

        // Batouri n'est dans aucun itinéraire. Une contrainte qui empêche
        // d'enregistrer la réalité pousse l'opérateur à saisir n'importe quoi
        // d'autre — et la traçabilité est perdue (A9).
        Long batouri = transactions.execute(s ->
                lieux.save(new Lieu(TypeLieu.POINT_TRANSIT, "Transit Bertoua", "Cameroun", "Batouri")).getId());

        assertThat(expeditions.enregistrer(colis.getId(), batouri, responsableId,
                TypeEvenement.ARRIVEE, "Route coupée, déroutement")).isNotNull();
    }

    @Test
    @DisplayName("une anomalie doit être décrite")
    void anomalieSansDescription() {
        Colis colis = colisPret();

        assertThatThrownBy(() -> expeditions.enregistrer(colis.getId(), transitId,
                responsableId, TypeEvenement.ANOMALIE, "   "))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("la projection reste cohérente avec le journal")
    void projectionCoherente() {
        Colis colis = colisPret();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), transitId, responsableId, TypeEvenement.ARRIVEE, null);
        expeditions.enregistrer(colis.getId(), transitId, responsableId, TypeEvenement.CONTROLE, "RAS");

        // CONTROLE ne change pas le statut : la projection doit le savoir.
        assertThat(expeditions.projectionCoherente(colis.getId())).isTrue();
        assertThat(expeditions.parcours(colis.getId())).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // La liste du back-office
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste joint la commande sans creer de cycle entre domaines")
    void listeAdministration() {
        Colis colis = colisPret();
        Long expeditionId = colis.getExpedition().getId();

        // ⚠️ CE test existe pour UNE raison. La requete fait un
        // « LEFT JOIN Commande c ON c.id = e.commandeId » — une jointure
        // ad hoc entre deux entites SANS relation JPA declaree, ecrite ainsi
        // pour eviter un cycle de paquetages que ArchUnit refuserait.
        //
        // Elle compile, elle demarre, et c'est exactement le genre de chose
        // qui casse a la premiere execution reelle. Le verifier au demarrage
        // ne suffit pas : il faut l'APPELER.
        Page<ResumeExpedition> page =
                expeditions.administration(null, null, PageRequest.of(0, 25));

        assertThat(page.getContent()).isNotEmpty();

        ResumeExpedition trouvee = page.getContent().stream()
                .filter(e -> e.id().equals(expeditionId))
                .findFirst()
                .orElseThrow();

        // Le numero de commande vient de l'autre domaine, par la jointure.
        assertThat(trouvee.commandeNumero()).isNotBlank();
        // Le nom du point de recuperation vient de la seconde jointure.
        assertThat(trouvee.pointNom()).isNotBlank();
        // La sous-requete de comptage a bien compte le colis.
        assertThat(trouvee.nombreColis()).isPositive();
    }

    @Test
    @DisplayName("la recherche porte sur le numero de commande, pas seulement d'expedition")
    void listeRecherchee() {
        Colis colis = colisPret();
        String numeroCommande = expeditions.administration(null, null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(e -> e.id().equals(colis.getExpedition().getId()))
                .findFirst()
                .orElseThrow()
                .commandeNumero();

        // Quand un client appelle, il donne son numero de COMMANDE — jamais
        // celui de l'expedition, qu'il n'a jamais vu.
        assertThat(expeditions.administration(null, numeroCommande, PageRequest.of(0, 25)))
                .isNotEmpty();

        assertThat(expeditions.administration(null, "INTROUVABLE-XYZ", PageRequest.of(0, 25)))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Retrait
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("on ne prépare pas un retrait avant l'arrivée")
    void retraitTropTot() {
        Colis colis = colisPret();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);

        assertThatThrownBy(() -> expeditions.preparerRetrait(
                colis.getExpedition().getId(), clientId))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("pas encore disponible");
    }

    @Test
    @DisplayName("la destination de l'expédition se déduit de la commande")
    void destinationDeduite() {
        // Personne ne ressaisit la ville d'arrivée : le client l'a choisie en
        // commandant, et il a payé l'acheminement de CE point-là. Ce test
        // exécute la requête HQL vers Commande — une @Query cassée n'échouerait
        // qu'au moment où on l'appelle.
        Expedition expedition = expeditions.creer(commande.id(), entrepotId, null);

        assertThat(expedition.getPointRecuperationId()).isEqualTo(pointRetraitId);
    }

    @Test
    @DisplayName("le destinataire du retrait se déduit de la commande")
    void destinataireDeduit() {
        Colis colis = colisPret();
        Long expeditionId = colis.getExpedition().getId();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId, TypeEvenement.ARRIVEE, null);

        // Personne ne saisit le client : l'agent n'a sous les yeux qu'une
        // expédition. Ce test exécute réellement la jointure HQL vers Commande,
        // la seule façon de prouver qu'elle est valide — une @Query cassée
        // n'échoue qu'au moment où on l'appelle.
        RetraitMarchandise retrait = expeditions.preparerRetrait(expeditionId);

        assertThat(retrait.getClientId()).isEqualTo(clientId);
    }

    @Test
    @DisplayName("le comptoir montre ce que le code désigne, sans rien remettre")
    void comptoirAvantRemise() {
        Colis colis = colisPret();
        Long expeditionId = colis.getExpedition().getId();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId, TypeEvenement.ARRIVEE, null);

        RetraitMarchandise retrait = expeditions.preparerRetrait(expeditionId);

        VueComptoir vue = expeditions.auComptoir(retrait.getCodeRetrait());

        // L'agent doit voir QUELS colis sortir. Sans ça, confirmer serait un
        // geste aveugle : le code validé, mais la marchandise au hasard.
        assertThat(vue.expedition().colis()).extracting("numeroSuivi")
                .contains(colis.getNumeroSuivi());
        assertThat(vue.dejaRemis()).isFalse();

        // Regarder ne remet rien : le retrait est toujours en attente, et un
        // code mal tapé ne consomme donc rien.
        assertThat(expeditions.auComptoir(retrait.getCodeRetrait()).dejaRemis()).isFalse();

        // Le code n'est jamais renvoyé par le comptoir : celui qui demande le
        // connaît déjà, et le répéter le ferait apparaître dans un journal de
        // plus.
        assertThat(vue.retrait().codeRetrait()).isNull();

        expeditions.confirmerRetrait(retrait.getCodeRetrait(), responsableId);
        assertThat(expeditions.auComptoir(retrait.getCodeRetrait()).dejaRemis()).isTrue();
    }

    @Test
    @DisplayName("le suivi public nomme les lieux, et tait le reste")
    void suiviPublic() {
        Colis colis = colisPret();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId, TypeEvenement.ARRIVEE, null);

        VueSuivi suivi = expeditions.suiviPublic(colis.getNumeroSuivi());

        // « lieu 12 » ne répond à personne : la liste des lieux demande une
        // authentification, et le suivi est public.
        assertThat(suivi.etapes()).isNotEmpty();
        assertThat(suivi.etapes()).allSatisfy(e -> {
            assertThat(e.lieu()).isNotBlank();
            assertThat(e.ville()).isNotBlank();
        });

        // Un numéro de suivi circule par SMS : il ne prouve rien sur celui qui
        // le présente. Le nom de l'agent qui a scanné n'a donc rien à y faire —
        // et le record n'a même pas le champ pour le porter.
        assertThat(VueSuivi.Etape.class.getRecordComponents())
                .extracting("name")
                .containsExactly("type", "lieu", "ville", "observation", "dateHeure");
    }

    @Test
    @DisplayName("le code de retrait est la seule preuve de la remise")
    void retraitParCode() {
        Colis colis = colisPret();
        Long expeditionId = colis.getExpedition().getId();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId, TypeEvenement.ARRIVEE, null);

        RetraitMarchandise retrait = expeditions.preparerRetrait(expeditionId, clientId);

        // Le code exclut les caractères ambigus : il sera épelé au téléphone.
        assertThat(retrait.getCodeRetrait()).matches("[ACDEFGHJKLMNPQRSTUVWXYZ2345679]{4}-[ACDEFGHJKLMNPQRSTUVWXYZ2345679]{4}");

        assertThatThrownBy(() -> expeditions.confirmerRetrait("XXXX-XXXX", responsableId))
                .isInstanceOf(RegleMetierViolee.class);

        RetraitMarchandise confirme = expeditions.confirmerRetrait(
                retrait.getCodeRetrait(), responsableId);

        assertThat(confirme.estConfirme()).isTrue();
        assertThat(statutColis(colis.getId())).isEqualTo("REMIS");
    }

    @Test
    @DisplayName("un colis remis ne reçoit plus d'événement")
    void plusRienApresLaRemise() {
        Colis colis = colisPret();
        Long expeditionId = colis.getExpedition().getId();
        expeditions.enregistrer(colis.getId(), entrepotId, responsableId, TypeEvenement.DEPART, null);
        expeditions.enregistrer(colis.getId(), pointRetraitId, responsableId, TypeEvenement.ARRIVEE, null);

        RetraitMarchandise retrait = expeditions.preparerRetrait(expeditionId, clientId);
        expeditions.confirmerRetrait(retrait.getCodeRetrait(), responsableId);

        assertThatThrownBy(() -> expeditions.enregistrer(colis.getId(), pointRetraitId,
                responsableId, TypeEvenement.ARRIVEE, null))
                .isInstanceOf(ConflitEtat.class);
    }

    // -------------------------------------------------------------------------
    // Retour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste des retours nomme le client et la commande")
    void listeDesRetours() {
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();
        Retour retour = retours.demander(commande.id(), clientId, "Taille incorrecte", List.of(
                new ServiceRetour.DemandeLigne(ligneId, 2, EtatArticle.NEUF),
                new ServiceRetour.DemandeLigne(ligneId, 1, EtatArticle.ABIME)));

        // Ce test exécute réellement les quatre requêtes de la liste — noms de
        // clients, numéros de commande, totaux agrégés. Une @Query cassée
        // n'échouerait qu'au moment où on l'appelle.
        ResumeRetour vu = retours.administration(null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(r -> r.id().equals(retour.getId()))
                .findFirst()
                .orElseThrow();

        // Un identifiant nu obligerait à ouvrir chaque ligne pour savoir de
        // qui vient le retour.
        assertThat(vu.clientNom()).isNotBlank();
        assertThat(vu.commandeNumero()).isNotBlank();

        // 🎯 Les deux chiffres ne disent PAS la même chose. 3 articles sont
        //    ANNONCÉS ; rien n'est encore remboursé, parce que personne n'a
        //    ouvert le colis.
        assertThat(vu.nombreArticles()).isEqualTo(3);
        assertThat(vu.montantRembourse()).isEqualByComparingTo("0.00");

        // Le filtre par statut : accepter, réceptionner et valider sont trois
        // métiers, et chacun a sa file.
        assertThat(retours.administration(StatutRetour.DEMANDE, PageRequest.of(0, 25)))
                .isNotEmpty();
        assertThat(retours.administration(StatutRetour.CLOTURE, PageRequest.of(0, 25))
                .getContent().stream().map(ResumeRetour::id))
                .doesNotContain(retour.getId());

        // Après validation, le second chiffre devient un FAIT : 3 × 15 000.
        retours.accepter(retour.getId());
        retours.receptionner(retour.getId());
        retours.valider(retour.getId(), MoyenPaiement.MTN_MOMO);

        ResumeRetour apres = retours.administration(StatutRetour.VALIDE, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(r -> r.id().equals(retour.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(apres.montantRembourse()).isEqualByComparingTo("45000.00");
    }

    @Test
    @DisplayName("la liste des réclamations se cherche par numéro de commande")
    void listeDesReclamations() {
        Reclamation reclamation = reclamations.ouvrir(clientId, commande.id(),
                "ARTICLE_MANQUANT", "Il manque une chemise dans le colis.");

        ResumeReclamation vue = reclamations
                .administration(null, null, PageRequest.of(0, 25))
                .getContent().stream()
                .filter(r -> r.id().equals(reclamation.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(vue.clientNom()).isNotBlank();
        assertThat(vue.commandeNumero()).isEqualTo(commande.numero());

        // Quand un client rappelle, il donne son numéro de COMMANDE : il n'a
        // souvent jamais noté celui de la réclamation. La sous-requête vers
        // Commande est donc exécutée pour de bon ici.
        assertThat(reclamations.administration(null, commande.numero(), PageRequest.of(0, 25)))
                .isNotEmpty();

        // Et par son propre numéro, quand on le lui a communiqué.
        assertThat(reclamations.administration(null, reclamation.getNumero(),
                PageRequest.of(0, 25))).isNotEmpty();

        assertThat(reclamations.administration(null, "INTROUVABLE-XYZ", PageRequest.of(0, 25)))
                .isEmpty();

        assertThat(reclamations.administration(StatutReclamation.RESOLUE, null,
                PageRequest.of(0, 25)).getContent().stream().map(ResumeReclamation::id))
                .doesNotContain(reclamation.getId());
    }

    @Test
    @DisplayName("un retour partiel : 3 unités sur 10, dont 1 abîmée")
    void retourPartiel() {
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);

        Retour retour = retours.demander(commande.id(), clientId, "Taille incorrecte", List.of(
                new ServiceRetour.DemandeLigne(ligneId, 2, EtatArticle.NEUF),
                new ServiceRetour.DemandeLigne(ligneId, 1, EtatArticle.ABIME)));

        // À la DEMANDE, rien ne bouge : la marchandise n'est pas revenue.
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);

        retours.accepter(retour.getId());
        retours.receptionner(retour.getId());
        Retour valide = retours.valider(retour.getId(), MoyenPaiement.MTN_MOMO);

        assertThat(valide.getStatut()).isEqualTo(StatutRetour.VALIDE);

        // 2 unités NEUF redeviennent vendables, 1 ABIME ne le sera jamais.
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(22);
        assertThat(stock.etat(varianteId).endommage()).isEqualTo(1);

        // Remboursement au prix RÉELLEMENT PAYÉ : 3 × 15 000.
        assertThat(valide.montantTotalRembourse()).isEqualByComparingTo("45000.00");

        Long remboursements = jdbc.queryForObject("""
                SELECT count(*) FROM paiement
                 WHERE commande_id = ? AND type = 'REMBOURSEMENT'
                """, Long.class, commande.id());
        // Un seul remboursement pour tout le retour : le client reçoit un
        // virement, pas trois.
        assertThat(remboursements).isEqualTo(1);
    }

    @Test
    @DisplayName("le grand livre marchand suit tout le cycle")
    void grandLivreDeBoutEnBout() {
        // Le paiement a déjà été confirmé dans la préparation du test :
        // 10 × 15 000 = 150 000, sans règle de commission (donc 0 %).
        BigDecimal apresVente = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.montant), 0) FROM ecriture_marchand e
                  JOIN marchand m ON m.id = e.marchand_id WHERE m.code = ?
                """, BigDecimal.class, CODE_MARCHAND);
        assertThat(apresVente).isEqualByComparingTo("150000.00");

        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();
        Retour retour = retours.demander(commande.id(), clientId, "Erreur", List.of(
                new ServiceRetour.DemandeLigne(ligneId, 2, EtatArticle.NEUF)));
        retours.accepter(retour.getId());
        retours.receptionner(retour.getId());
        retours.valider(retour.getId(), MoyenPaiement.MTN_MOMO);

        // Le retour annule la vente correspondante : 150 000 − 30 000.
        BigDecimal apresRetour = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.montant), 0) FROM ecriture_marchand e
                  JOIN marchand m ON m.id = e.marchand_id WHERE m.code = ?
                """, BigDecimal.class, CODE_MARCHAND);
        assertThat(apresRetour).isEqualByComparingTo("120000.00");

        // Et le solde est PROUVABLE : chaque ligne pointe vers sa pièce.
        Long lignes = jdbc.queryForObject("""
                SELECT count(*) FROM ecriture_marchand e
                  JOIN marchand m ON m.id = e.marchand_id WHERE m.code = ?
                """, Long.class, CODE_MARCHAND);
        assertThat(lignes).isEqualTo(2);   // 1 VENTE + 1 RETOUR
    }

    @Test
    @DisplayName("on ne retourne pas plus qu'on n'a acheté")
    void triggerQuantiteRetour() {
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();

        // I-40, porté par un TRIGGER, et qui tient compte des retours
        // PRÉCÉDENTS : 2 en mars puis 2 en avril sur 3 achetés doit échouer.
        assertThatThrownBy(() -> retours.demander(commande.id(), clientId, "Trop", List.of(
                new ServiceRetour.DemandeLigne(ligneId, 12, EtatArticle.NEUF))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("le remboursement ne part qu'après contrôle physique")
    void pasDeRemboursementAvantValidation() {
        Long ligneId = lignesCommande.findByCommandeId(commande.id()).getFirst().getId();
        Retour retour = retours.demander(commande.id(), clientId, "Erreur", List.of(
                new ServiceRetour.DemandeLigne(ligneId, 1, EtatArticle.NEUF)));

        // Valider directement, sans réception, saute le contrôle physique.
        assertThatThrownBy(() -> retours.valider(retour.getId(), MoyenPaiement.MTN_MOMO))
                .isInstanceOf(ConflitEtat.class);
    }

    /**
     * Lit le statut directement en base.
     *
     * <p>Relire l'objet Java en mémoire ne prouverait rien : c'est la valeur
     * <b>persistée</b> qui compte, et c'est elle que les autres services
     * liront.</p>
     */
    private String statutColis(Long colisId) {
        return jdbc.queryForObject("SELECT statut FROM colis WHERE id = ?", String.class, colisId);
    }
}
