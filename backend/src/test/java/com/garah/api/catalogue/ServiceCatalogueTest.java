package com.garah.api.catalogue;

import com.garah.api.catalogue.domaine.*;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.TarificationRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Catalogue")
class ServiceCatalogueTest {

    @Autowired ServiceCatalogue catalogue;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired TarificationRepository tarifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long marchandId;
    private Long categorieId;

    @BeforeEach
    void preparer() {
        // Le marchand appartient à un autre domaine, et le catalogue ne le
        // référence que par son identifiant (chapitre 06 §4). On l'insère donc
        // directement, sans dépendre d'une entité qu'on n'a pas encore écrite.
        marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type) VALUES ('M-CAT-1', 'Marchand catalogue', 'EXTERNE')
                RETURNING id
                """, Long.class);

        categorieId = categories.save(new CategorieProduit("Vêtements de test", null)).getId();
        em.flush();
    }

    private DetailProduit creerChemise() {
        return catalogue.creerProduit(marchandId, categorieId, "REF-CAT-001", "Chemise Oxford", null);
    }

    // -------------------------------------------------------------------------
    // Création
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("créer un produit crée AUSSI sa variante par défaut")
    void creationAvecVarianteParDefaut() {
        DetailProduit produit = creerChemise();

        // C'est le cœur de D-01 : jamais de produit sans variante, même sans
        // déclinaison réelle. Sinon tout le code aval devrait tester
        // « ce produit a-t-il des variantes ? ».
        assertThat(produit.variantes()).hasSize(1);
        assertThat(produit.variantes().getFirst().parDefaut()).isTrue();
        assertThat(produit.variantes().getFirst().sku()).isEqualTo("REF-CAT-001");
        assertThat(produit.statut()).isEqualTo("BROUILLON");
    }

    @Test
    @DisplayName("le slug est dérivé du nom")
    void slugDeriveDuNom() {
        assertThat(creerChemise().slug()).isEqualTo("chemise-oxford");
    }

    @Test
    @DisplayName("deux produits ne peuvent pas partager la même référence")
    void referenceUnique() {
        creerChemise();
        em.flush();

        assertThatThrownBy(this::creerChemise)
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("déjà utilisée");
    }

    // -------------------------------------------------------------------------
    // Publication — invariant I-12
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("publier sans prix est refusé")
    void publicationSansPrix() {
        Long id = creerChemise().id();
        catalogue.ajouterMedia(id, TypeMedia.PHOTO, "produits/1/photo.jpg", true);
        em.flush();

        assertThatThrownBy(() -> catalogue.publier(id))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("aucun prix");
    }

    @Test
    @DisplayName("publier sans photo est refusé")
    void publicationSansPhoto() {
        Long id = creerChemise().id();
        donnerUnPrix(id);
        em.flush();

        assertThatThrownBy(() -> catalogue.publier(id))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("aucune photo");
    }

    @Test
    @DisplayName("publier avec une variante, un prix et une photo réussit")
    void publicationComplete() {
        Long id = creerChemise().id();
        donnerUnPrix(id);
        catalogue.ajouterMedia(id, TypeMedia.PHOTO, "produits/1/photo.jpg", true);
        em.flush();

        assertThat(catalogue.publier(id).statut()).isEqualTo("PUBLIE");
    }

    // -------------------------------------------------------------------------
    // Machine à états
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un produit archivé ne peut plus être republié")
    void archiveEstTerminal() {
        Long id = creerChemise().id();
        catalogue.changerStatut(id, StatutProduit.ARCHIVE);
        em.flush();

        // ARCHIVE est terminal : un produit vendu une fois doit rester
        // consultable dans l'historique, mais jamais revenir à la vente.
        assertThatThrownBy(() -> catalogue.publier(id))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("ARCHIVE");
    }

    @Test
    @DisplayName("un brouillon ne peut pas être masqué : il n'a jamais été visible")
    void brouillonNonMasquable() {
        Long id = creerChemise().id();
        em.flush();

        assertThatThrownBy(() -> catalogue.changerStatut(id, StatutProduit.MASQUE))
                .isInstanceOf(ConflitEtat.class);
    }

    // -------------------------------------------------------------------------
    // Médias
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le premier média devient principal même sans le demander")
    void premierMediaPrincipal() {
        Long id = creerChemise().id();

        Media premier = catalogue.ajouterMedia(id, TypeMedia.PHOTO, "produits/1/a.jpg", false);

        // Sinon un produit se retrouverait sans vignette en liste, et
        // personne ne s'en apercevrait avant la mise en ligne.
        assertThat(premier.estPrincipal()).isTrue();
    }

    @Test
    @DisplayName("désigner une nouvelle photo principale retire l'ancienne")
    void uneSeulePhotoPrincipale() {
        Long id = creerChemise().id();
        catalogue.ajouterMedia(id, TypeMedia.PHOTO, "produits/1/a.jpg", true);
        em.flush();

        catalogue.ajouterMedia(id, TypeMedia.PHOTO, "produits/1/b.jpg", true);
        em.flush();
        em.clear();

        // L'index unique partiel media_principal_unique aurait rejeté
        // l'insertion si le service n'avait pas retiré la marque d'abord.
        Long principales = jdbc.queryForObject(
                "SELECT count(*) FROM media WHERE produit_id = ? AND principal", Long.class, id);
        assertThat(principales).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Variantes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un SKU ne peut pas être réutilisé")
    void skuUnique() {
        Long id = creerChemise().id();
        catalogue.ajouterVariante(id, "CHO-M-BLE", "M / Bleu", List.of());
        em.flush();

        assertThatThrownBy(() -> catalogue.ajouterVariante(id, "CHO-M-BLE", "autre", List.of()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("SKU");
    }

    // -------------------------------------------------------------------------
    // La liste du back-office
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste d'administration montre les brouillons, la vitrine non")
    void administrationMontreLesBrouillons() {
        DetailProduit brouillon = creerChemise();
        em.flush();

        // La vitrine ne montre que le publié : un brouillon n'y a rien à faire.
        assertThat(catalogue.catalogue(null, PageRequest.of(0, 10)).getContent())
                .extracting(ResumeProduit::id)
                .doesNotContain(brouillon.id());

        // Le back-office, lui, doit voir exactement ce sur quoi il reste du
        // travail. Une liste de gestion qui cache les brouillons rend
        // introuvable le produit créé le matin même.
        assertThat(catalogue.administration(null, null, "TOUS", PageRequest.of(0, 10)).getContent())
                .extracting(ResumeProduit::id)
                .contains(brouillon.id());
    }

    @Test
    @DisplayName("la liste porte le marchand, la catégorie et le prix d'appel")
    void listeEnrichie() {
        Long id = creerChemise().id();
        donnerUnPrix(id);
        em.flush();

        ResumeProduit resume = catalogue.administration("Chemise", null, "TOUS", PageRequest.of(0, 10))
                .getContent().getFirst();

        // Les trois colonnes qu'on lit pour décider sur quelle ligne cliquer.
        // Elles étaient absentes du DTO : la liste affichait des cases vides.
        assertThat(resume.marchandNom()).isEqualTo("Marchand catalogue");
        assertThat(resume.categorieNom()).isEqualTo("Vêtements de test");
        assertThat(resume.prixMin()).isEqualByComparingTo("15000.00");
        assertThat(resume.devise()).isEqualTo("XAF");
    }

    @Test
    @DisplayName("un produit sans prix n'en invente pas un")
    void listeSansPrix() {
        creerChemise();
        em.flush();

        ResumeProduit resume = catalogue.administration("Chemise", null, "TOUS", PageRequest.of(0, 10))
                .getContent().getFirst();

        // `null`, et surtout pas zéro : un produit à 0 FCFA serait annoncé
        // gratuit, alors qu'il n'a simplement pas encore de tarif.
        assertThat(resume.prixMin()).isNull();
        assertThat(resume.marchandNom()).isEqualTo("Marchand catalogue");
    }

    @Test
    @DisplayName("la recherche porte sur le nom comme sur la référence")
    void rechercheParNomOuReference() {
        creerChemise();
        em.flush();

        assertThat(catalogue.administration("oxford", null, "TOUS", PageRequest.of(0, 10))).hasSize(1);
        assertThat(catalogue.administration("REF-CAT", null, "TOUS", PageRequest.of(0, 10))).hasSize(1);
        assertThat(catalogue.administration("introuvable", null, "TOUS", PageRequest.of(0, 10))).isEmpty();
    }

    private void donnerUnPrix(Long produitId) {
        Variante variante = variantes.findByProduitId(produitId).getFirst();
        tarifications.save(new Tarification(variante, 1, null, new BigDecimal("15000.00")));
    }
}
