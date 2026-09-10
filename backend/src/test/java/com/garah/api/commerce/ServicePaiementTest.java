package com.garah.api.commerce;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.ServiceTarification;
import com.garah.api.catalogue.domaine.TypeMedia;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.domaine.*;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.logistique.domaine.Lieu;
import com.garah.api.logistique.domaine.TypeLieu;
import com.garah.api.logistique.infra.LieuRepository;
import com.garah.api.stock.domaine.ServiceStock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@DisplayName("Paiement mobile money")
class ServicePaiementTest {

    private static final String CODE_MARCHAND = "M-PAY-1";
    private static final String EMAIL = "client.paiement@garah.cm";

    @Autowired ServicePanier panier;
    @Autowired ServiceCommande commandes;
    @Autowired ServicePaiement paiements;
    @Autowired com.garah.api.mesure.domaine.ServiceStatistiques stats;
    @Autowired ServiceCatalogue catalogue;
    @Autowired ServiceTarification tarification;
    @Autowired ServiceStock stock;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired LieuRepository lieux;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;
    private Long varianteId;
    private Long pointRetraitId;
    private DetailCommande commande;

    @BeforeEach
    void preparerUneCommande() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Fotso", EMAIL, "empreinte"));
            u.marquerEmailVerifie();
            clientId = clients.save(new Client(u, "CLI-PAY-1")).getId();

            Long marchandId = jdbc.queryForObject("""
                    INSERT INTO marchand (code, nom, type)
                    VALUES (?, 'Marchand paiement', 'EXTERNE') RETURNING id
                    """, Long.class, CODE_MARCHAND);

            Long categorieId = categories.save(new CategorieProduit("Paiements", null)).getId();
            Long produitId = catalogue.creerProduit(marchandId, categorieId,
                    "REF-PAY-1", "Article payé", null).id();
            varianteId = variantes.findByProduitId(produitId).getFirst().getId();

            tarification.definirPalier(varianteId, 1, null, new BigDecimal("10000.00"));
            catalogue.ajouterMedia(produitId, TypeMedia.PHOTO, "produits/pay/1.jpg", true);
            catalogue.publier(produitId);

            // Le stock naît AVEC la déclinaison depuis que `VarianteCreee` est écouté
            // (I-15). L'appeler ici leverait « cette variante a déjà un stock ».
            stock.entrer(varianteId, 10, null, "Mise en place");

            Lieu lieu = new Lieu(TypeLieu.POINT_RECUPERATION, "Yaoundé Centre", "Cameroun", "Yaoundé");
            lieu.setFraisAcheminement(new BigDecimal("2000.00"));
            pointRetraitId = lieux.save(lieu).getId();
        });

        panier.ajouter(clientId, varianteId, 3);
        commande = commandes.passer(clientId, pointRetraitId, "fr");
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM tentative_paiement WHERE paiement_id IN (SELECT p.id FROM paiement p JOIN commande c ON c.id = p.commande_id JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM paiement WHERE commande_id IN (SELECT c.id FROM commande c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM ligne_commande WHERE commande_id IN (SELECT c.id FROM commande c JOIN client cl ON cl.id = c.client_id WHERE cl.code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM commande WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM ligne_panier WHERE panier_id IN (SELECT p.id FROM panier p JOIN client c ON c.id = p.client_id WHERE c.code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM panier WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-PAY-1')");
        jdbc.update("DELETE FROM evenement_securite WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE email = ?)", EMAIL);
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
        jdbc.update("DELETE FROM categorie_produit WHERE nom = 'Paiements'");
        jdbc.update("DELETE FROM lieu WHERE nom = 'Yaoundé Centre'");
        jdbc.update("DELETE FROM client WHERE code_client = 'CLI-PAY-1'");
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le montant vient de la commande, jamais du client")
    void montantLuSurLaCommande() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);

        // 3 x 10 000 + 2 000 de frais. Accepter un montant venu de l'extérieur
        // permettrait de payer 100 FCFA une commande de 32 000.
        assertThat(paiement.getMontant()).isEqualByComparingTo("32000.00");
        assertThat(paiement.getStatut()).isEqualTo(StatutPaiement.INITIE);
    }

    @Test
    @DisplayName("confirmer fait basculer la commande et sortir le stock")
    void confirmationCompleteLeCycle() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);

        assertThat(stock.etat(varianteId).reserve()).isEqualTo(3);

        paiements.confirmer(paiement.getId(), "MOMO-REF-0001");

        assertThat(commandes.detail(commande.id()).statut()).isEqualTo("PAYEE");
        // La marchandise quitte enfin l'entrepôt.
        assertThat(stock.etat(varianteId).reserve()).isZero();
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(7);
        assertThat(stock.etat(varianteId).total()).isEqualTo(7);
    }

    /**
     * LE test du chapitre. Les opérateurs rejouent leurs webhooks.
     */
    @Test
    @DisplayName("un webhook rejoué trois fois ne produit qu'un seul effet")
    void webhookIdempotent() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.ORANGE_MONEY);

        paiements.confirmer(paiement.getId(), "OM-REF-0001");
        paiements.confirmer(paiement.getId(), "OM-REF-0001");
        paiements.confirmer(paiement.getId(), "OM-REF-0001");

        // Sans idempotence : trois sorties de stock pour une seule commande,
        // et un stock négatif refusé par la base... ou pire, accepté.
        assertThat(stock.etat(varianteId).total()).isEqualTo(7);
        assertThat(stock.etat(varianteId).reserve()).isZero();
        assertThat(commandes.detail(commande.id()).statut()).isEqualTo("PAYEE");

        Long confirmes = jdbc.queryForObject(
                "SELECT count(*) FROM paiement WHERE commande_id = ? AND statut = 'CONFIRME'",
                Long.class, commande.id());
        assertThat(confirmes).isEqualTo(1);
    }

    @Test
    @DisplayName("la base refuse deux paiements avec la même référence opérateur")
    void referenceOperateurUnique() {
        Paiement premier = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(premier.getId(), "MOMO-DOUBLON");

        // Seconde ligne de défense (V18) : même en contournant le service,
        // la même transaction opérateur ne peut pas être enregistrée deux fois.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO paiement (commande_id, type, montant, moyen, reference_transaction, statut)
                VALUES (?, 'ENCAISSEMENT', 100, 'MTN_MOMO', 'MOMO-DOUBLON', 'CONFIRME')
                """, commande.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("un échec est conservé et laisse une trace de sécurité")
    void echecConserve() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);

        paiements.echouer(paiement.getId(), "SOLDE_INSUFFISANT", "Solde insuffisant", clientId);

        Long tentatives = jdbc.queryForObject(
                "SELECT count(*) FROM tentative_paiement WHERE paiement_id = ?",
                Long.class, paiement.getId());
        assertThat(tentatives).isEqualTo(1);

        // Sans cet enregistrement, le signal « échecs de paiement » du score
        // de risque (§19) n'existerait tout simplement pas.
        Long evenements = jdbc.queryForObject("""
                SELECT count(*) FROM evenement_securite
                 WHERE utilisateur_id = ? AND type = 'ECHEC_PAIEMENT'
                """, Long.class, clientId);
        assertThat(evenements).isEqualTo(1);

        // La commande n'a pas bougé : le stock reste réservé le temps du délai.
        assertThat(commandes.detail(commande.id()).statut()).isEqualTo("EN_ATTENTE_PAIEMENT");
        assertThat(stock.etat(varianteId).reserve()).isEqualTo(3);
    }

    @Test
    @DisplayName("on ne peut pas faire échouer un paiement déjà confirmé")
    void pasDeDeconfirmation() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(paiement.getId(), "MOMO-REF-0002");

        assertThatThrownBy(() ->
                paiements.echouer(paiement.getId(), "X", "tentative tardive", clientId))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("on ne rembourse jamais plus qu'on n'a encaissé")
    void remboursementPlafonne() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(paiement.getId(), "MOMO-REF-0003");

        paiements.rembourser(commande.id(), new BigDecimal("12000.00"),
                MoyenPaiement.MTN_MOMO, "RETOUR", 1L);

        // Le premier remboursement n'est pas encore CONFIRME, donc il ne
        // compte pas dans le cumul : la limite reste celle de l'encaissement.
        assertThatThrownBy(() -> paiements.rembourser(commande.id(), new BigDecimal("40000.00"),
                MoyenPaiement.MTN_MOMO, "RETOUR", 1L))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("dépasserait");
    }

    @Test
    @DisplayName("un remboursement doit dire ce qui le justifie")
    void remboursementJustifie() {
        assertThatThrownBy(() -> paiements.rembourser(commande.id(), new BigDecimal("100.00"),
                MoyenPaiement.MTN_MOMO, null, null))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("une commande déjà payée n'accepte plus de paiement")
    void pasDeDoublePaiement() {
        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(paiement.getId(), "MOMO-REF-0004");

        assertThatThrownBy(() -> paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("le reste à payer tombe à zéro après confirmation")
    void resteAPayer() {
        assertThat(paiements.resteAPayer(commande.id())).isEqualByComparingTo("32000.00");

        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.VIREMENT);
        paiements.confirmer(paiement.getId(), "VIR-REF-0001");

        assertThat(paiements.resteAPayer(commande.id())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("⚠️ le chiffre d'affaires est l'argent encaissé, au jour du paiement")
    void chiffreDAffairesEncaisse() {
        // 🎯 LE DEFAUT QUE CE TEST FERME.
        //
        //    Le resume additionnait toute commande CREEE dans la journee :
        //    impayees, annulees et expirees gonflaient le chiffre d'affaires.
        java.time.LocalDate jour = java.time.LocalDate.now();
        stats.agregerLeJour(jour);
        var avant = stats.bilan(jour, jour, 10);

        // ⚠️ Une commande CREEE maintenant — apres la mesure — et jamais payee.
        //    Celle de la preparation ne suffisait pas : nee AVANT la premiere
        //    mesure, elle etait deja dans le « avant », et l'ancien calcul, qui
        //    comptait les commandes creees, passait ce test sans broncher.
        panier.ajouter(clientId, varianteId, 1);
        commandes.passer(clientId, pointRetraitId, "fr");
        stats.agregerLeJour(jour);
        assertThat(stats.bilan(jour, jour, 10).chiffreAffaires())
                .as("une commande impayee n'est pas une vente")
                .isEqualByComparingTo(avant.chiffreAffaires());

        Paiement paiement = paiements.initier(commande.id(), MoyenPaiement.MTN_MOMO);
        paiements.confirmer(paiement.getId(), "STAT-REF-0001");
        stats.agregerLeJour(jour);
        var apres = stats.bilan(jour, jour, 10);

        // 3 x 10 000 + 2 000 de frais : l'encaisse comprend l'acheminement.
        assertThat(apres.chiffreAffaires().subtract(avant.chiffreAffaires()))
                .isEqualByComparingTo("32000.00");
        assertThat(apres.commandes() - avant.commandes()).isEqualTo(1);
    }
}
