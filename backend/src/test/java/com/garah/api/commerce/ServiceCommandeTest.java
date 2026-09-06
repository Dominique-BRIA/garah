package com.garah.api.commerce;

import com.garah.api.catalogue.domaine.*;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commerce.domaine.*;
import com.garah.api.commerce.infra.CommandeRepository;
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
import com.garah.api.marchand.domaine.RegleCommission;
import com.garah.api.marchand.infra.RegleCommissionRepository;
import com.garah.api.stock.domaine.ServiceStock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le passage de commande, de bout en bout.
 *
 * <p>⚠️ Cette classe n'est <b>pas</b> {@code @Transactional}, contrairement à
 * la plupart des autres. Raison : on veut vérifier qu'un échec de réservation
 * de stock <b>annule réellement</b> la commande. Dans une transaction de test
 * unique, l'exception marquerait simplement la transaction comme « à annuler »
 * et on ne pourrait plus rien lire ensuite.</p>
 *
 * <p>On commit donc pour de vrai, et on nettoie.</p>
 */
@SpringBootTest
@DisplayName("Commande")
class ServiceCommandeTest {

    private static final String CODE_MARCHAND = "M-CMD-1";
    private static final String EMAIL = "client.commande@garah.cm";

    @Autowired ServicePanier panier;
    @Autowired ServiceCommande commandes;
    @Autowired ServiceCatalogue catalogue;
    @Autowired ServiceTarification tarification;
    @Autowired ServiceStock stock;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired LieuRepository lieux;
    @Autowired RegleCommissionRepository regles;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired CommandeRepository depotCommandes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;
    private Long varianteId;
    private Long pointRetraitId;
    private Long entrepotId;

    @BeforeEach
    void preparer() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Ngono", EMAIL, "empreinte"));
            clientId = clients.save(new Client(u, "CLI-CMD-1")).getId();

            Long marchandId = jdbc.queryForObject("""
                    INSERT INTO marchand (code, nom, type)
                    VALUES (?, 'Marchand commande', 'EXTERNE') RETURNING id
                    """, Long.class, CODE_MARCHAND);

            Long categorieId = categories.save(new CategorieProduit("Commandes", null)).getId();
            regles.save(new RegleCommission(marchandId, null, new BigDecimal("10.00"), 10));

            Long produitId = catalogue.creerProduit(marchandId, categorieId,
                    "REF-CMD-1", "Chemise Oxford", null).id();
            varianteId = variantes.findByProduitId(produitId).getFirst().getId();

            tarification.definirPalier(varianteId, 1, 4, new BigDecimal("15000.00"));
            tarification.definirPalier(varianteId, 5, null, new BigDecimal("13000.00"));

            catalogue.ajouterMedia(produitId, TypeMedia.PHOTO, "produits/cmd/1.jpg", true);
            catalogue.publier(produitId);

            stock.creerPour(varianteId);
            stock.entrer(varianteId, 20, null, "Mise en place");

            Lieu bangui = new Lieu(TypeLieu.POINT_RECUPERATION, "Bangui PK5", "RCA", "Bangui");
            bangui.setFraisAcheminement(new BigDecimal("8000.00"));
            pointRetraitId = lieux.save(bangui).getId();

            entrepotId = lieux.save(new Lieu(TypeLieu.ENTREPOT, "Entrepôt Douala",
                    "Cameroun", "Douala")).getId();
        });
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM ligne_commande WHERE commande_id IN (SELECT id FROM commande WHERE numero LIKE 'CMD-%')");
        jdbc.update("DELETE FROM commande WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-CMD-1')");
        jdbc.update("DELETE FROM ligne_panier WHERE panier_id IN (SELECT p.id FROM panier p JOIN client c ON c.id = p.client_id WHERE c.code_client = 'CLI-CMD-1')");
        jdbc.update("DELETE FROM panier WHERE client_id IN (SELECT id FROM client WHERE code_client = 'CLI-CMD-1')");
        jdbc.update("""
                DELETE FROM mouvement_stock WHERE stock_id IN (
                    SELECT s.id FROM stock s JOIN variante v ON v.id = s.variante_id
                      JOIN produit p ON p.id = v.produit_id JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)""", CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM stock WHERE variante_id IN (
                    SELECT v.id FROM variante v JOIN produit p ON p.id = v.produit_id
                      JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)""", CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM tarification WHERE variante_id IN (
                    SELECT v.id FROM variante v JOIN produit p ON p.id = v.produit_id
                      JOIN marchand m ON m.id = p.marchand_id WHERE m.code = ?)""", CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM media WHERE produit_id IN (
                    SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)""", CODE_MARCHAND);
        jdbc.update("""
                DELETE FROM variante WHERE produit_id IN (
                    SELECT p.id FROM produit p JOIN marchand m ON m.id = p.marchand_id
                     WHERE m.code = ?)""", CODE_MARCHAND);
        jdbc.update("DELETE FROM produit WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM regle_commission WHERE marchand_id IN (SELECT id FROM marchand WHERE code = ?)", CODE_MARCHAND);
        jdbc.update("DELETE FROM marchand WHERE code = ?", CODE_MARCHAND);
        jdbc.update("DELETE FROM categorie_produit WHERE nom = 'Commandes'");
        jdbc.update("DELETE FROM lieu WHERE nom IN ('Bangui PK5', 'Entrepôt Douala')");
        jdbc.update("DELETE FROM client WHERE code_client = 'CLI-CMD-1'");
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le panier ne réserve aucun stock")
    void panierNeReserveRien() {
        panier.ajouter(clientId, varianteId, 3);

        // Mettre un article au panier n'engage rien : sinon un visiteur
        // pourrait immobiliser tout le catalogue sans jamais payer.
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);
        assertThat(stock.etat(varianteId).reserve()).isZero();
    }

    @Test
    @DisplayName("le panier affiche le prix du jour, pas celui de l'ajout")
    void prixDuJourDansLePanier() {
        panier.ajouter(clientId, varianteId, 2);
        assertThat(panier.contenu(clientId).montantArticles()).isEqualByComparingTo("30000.00");

        // 6 unités tombent dans le palier « 5 et + »
        panier.definirQuantite(clientId, varianteId, 6);
        assertThat(panier.contenu(clientId).montantArticles()).isEqualByComparingTo("78000.00");
    }

    @Test
    @DisplayName("commander fige les prix et réserve le stock")
    void passageDeCommande() {
        panier.ajouter(clientId, varianteId, 3);

        DetailCommande commande = commandes.passer(clientId, pointRetraitId, "fr");

        assertThat(commande.numero()).startsWith("CMD-");
        assertThat(commande.statut()).isEqualTo("EN_ATTENTE_PAIEMENT");
        assertThat(commande.montantArticles()).isEqualByComparingTo("45000.00");
        assertThat(commande.montantFrais()).isEqualByComparingTo("8000.00");
        assertThat(commande.montantTotal()).isEqualByComparingTo("53000.00");

        // Le stock est passé de disponible à réservé, sans rien perdre.
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(17);
        assertThat(stock.etat(varianteId).reserve()).isEqualTo(3);
    }

    @Test
    @DisplayName("la commission est figée à la commande")
    void commissionFigee() {
        panier.ajouter(clientId, varianteId, 2);
        DetailCommande commande = commandes.passer(clientId, pointRetraitId, "fr");

        BigDecimal commission = jdbc.queryForObject(
                "SELECT montant_commission FROM ligne_commande WHERE commande_id = ?",
                BigDecimal.class, commande.id());

        // 10 % de 30 000 TTC
        assertThat(commission).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("changer le prix du catalogue ne modifie PAS une commande passée")
    void leprixEstUnePhoto() {
        panier.ajouter(clientId, varianteId, 2);
        DetailCommande commande = commandes.passer(clientId, pointRetraitId, "fr");

        Long palierId = jdbc.queryForObject(
                "SELECT id FROM tarification WHERE variante_id = ? AND quantite_min = 1",
                Long.class, varianteId);
        tarification.changerPrix(palierId, new BigDecimal("25000.00"));

        // C'est TOUTE la raison d'être de la règle de la photographie :
        // une facture de mars reste juste en septembre.
        assertThat(commandes.detail(commande.id()).montantTotal())
                .isEqualByComparingTo("38000.00");
    }

    @Test
    @DisplayName("le panier est converti, il n'est plus actif")
    void panierConverti() {
        panier.ajouter(clientId, varianteId, 1);
        commandes.passer(clientId, pointRetraitId, "fr");

        assertThat(panier.contenu(clientId).lignes()).isEmpty();
    }

    @Test
    @DisplayName("un panier vide ne peut pas devenir une commande")
    void panierVide() {
        assertThatThrownBy(() -> commandes.passer(clientId, pointRetraitId, "fr"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("vide");
    }

    @Test
    @DisplayName("un entrepôt n'est pas un point de récupération")
    void lieuInvalide() {
        panier.ajouter(clientId, varianteId, 1);

        assertThatThrownBy(() -> commandes.passer(clientId, entrepotId, "fr"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("point de récupération");
    }

    @Test
    @DisplayName("stock insuffisant : AUCUNE commande n'est créée")
    void echecDeReservationAnnuleToutelaCommande() {
        panier.ajouter(clientId, varianteId, 25);   // il n'y en a que 20

        assertThatThrownBy(() -> commandes.passer(clientId, pointRetraitId, "fr"))
                .isInstanceOf(ConflitEtat.class);

        // Le test qui compte : la transaction a tout annulé. Sans atomicité,
        // il resterait une commande fantôme, ou du stock réservé sans client.
        Long commandesCreees = jdbc.queryForObject(
                "SELECT count(*) FROM commande WHERE client_id = ?", Long.class, clientId);
        assertThat(commandesCreees).isZero();
        assertThat(stock.etat(varianteId).reserve()).isZero();
        assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);
    }

    @Test
    @DisplayName("annuler une commande impayée libère le stock")
    void annulationLibereLeStock() {
        panier.ajouter(clientId, varianteId, 4);
        DetailCommande commande = commandes.passer(clientId, pointRetraitId, "fr");
        assertThat(stock.etat(varianteId).reserve()).isEqualTo(4);

        commandes.annuler(commande.id(), "Test");

        assertThat(stock.etat(varianteId).disponible()).isEqualTo(20);
        assertThat(stock.etat(varianteId).reserve()).isZero();
    }

    @Test
    @DisplayName("une commande retirée ne peut plus changer d'état")
    void retireeEstTerminal() {
        panier.ajouter(clientId, varianteId, 1);
        Long id = commandes.passer(clientId, pointRetraitId, "fr").id();

        commandes.changerStatut(id, StatutCommande.PAYEE);
        commandes.changerStatut(id, StatutCommande.EN_PREPARATION);
        commandes.changerStatut(id, StatutCommande.PRETE);
        commandes.changerStatut(id, StatutCommande.EXPEDIEE);
        commandes.changerStatut(id, StatutCommande.DISPONIBLE);
        commandes.changerStatut(id, StatutCommande.RETIREE);

        assertThatThrownBy(() -> commandes.changerStatut(id, StatutCommande.EXPEDIEE))
                .isInstanceOf(ConflitEtat.class);
    }

    @Test
    @DisplayName("une commande d'un autre client est introuvable, pas interdite")
    void isolationDesClients() {
        panier.ajouter(clientId, varianteId, 1);
        Long id = commandes.passer(clientId, pointRetraitId, "fr").id();

        // 404 et non 403 : répondre « interdit » confirmerait que la commande
        // existe. Un client n'a aucune permission, son accès repose sur la
        // propriété de ses données.
        assertThatThrownBy(() -> commandes.detailPourClient(id, -999L))
                .isInstanceOf(com.garah.api.commun.erreur.RessourceIntrouvable.class);
    }

    @Test
    @DisplayName("le numéro de commande est unique même en série")
    void numerosUniques() {
        panier.ajouter(clientId, varianteId, 1);
        String premier = commandes.passer(clientId, pointRetraitId, "fr").numero();

        panier.ajouter(clientId, varianteId, 1);
        String second = commandes.passer(clientId, pointRetraitId, "fr").numero();

        assertThat(premier).isNotEqualTo(second);
        assertThat(depotCommandes.findByNumero(premier)).isPresent();
    }
}
