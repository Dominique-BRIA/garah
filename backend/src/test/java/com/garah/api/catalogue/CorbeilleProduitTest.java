package com.garah.api.catalogue;

import com.garah.api.catalogue.domaine.CategorieProduit;
import com.garah.api.catalogue.domaine.DetailProduit;
import com.garah.api.catalogue.domaine.ServiceCatalogue;
import com.garah.api.catalogue.domaine.StatutProduit;
import com.garah.api.catalogue.domaine.VueCorbeille;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La corbeille : supprimer en deux temps (V27).
 *
 * <p>Ce qui est vérifié ici ne se voit pas en relisant le code. Une suppression
 * douce <b>fuit</b> dès qu'une requête oublie sa condition : le produit
 * disparaît d'une liste et réapparaît dans une autre, et personne ne sait par
 * où. C'est exactement ce que {@code @SQLRestriction} est censé rendre
 * impossible — encore faut-il le prouver.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Corbeille des produits")
class CorbeilleProduitTest {

    @Autowired ServiceCatalogue catalogue;
    @Autowired CategorieProduitRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long marchandId;
    private Long categorieId;

    @BeforeEach
    void preparer() {
        marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type)
                VALUES ('M-CORB-1', 'Marchand corbeille', 'EXTERNE')
                RETURNING id
                """, Long.class);

        categorieId = categories.save(new CategorieProduit("Catégorie corbeille", null)).getId();
        em.flush();
    }

    private DetailProduit brouillon(String reference, String nom) {
        return catalogue.creerProduit(marchandId, categorieId, reference, nom, null);
    }

    // -------------------------------------------------------------------------
    // Mise à la corbeille
    // -------------------------------------------------------------------------

    /**
     * 🎯 <b>Le test qui justifie toute la mécanique.</b>
     *
     * <p>Sans {@code @SQLRestriction}, il faudrait poser
     * {@code AND date_suppression IS NULL} dans chaque requête du dépôt, et il
     * suffirait d'en oublier une.</p>
     */
    @Test
    @DisplayName("un produit à la corbeille disparaît de la liste d'administration")
    void disparaitDesListes() {
        Long id = brouillon("REF-CORB-001", "Chemise à jeter").id();
        em.flush();

        assertThat(catalogue.administration(null, "TOUS", PageRequest.of(0, 50)).getContent())
                .extracting(r -> r.id())
                .contains(id);

        catalogue.mettreALaCorbeille(id);
        em.flush();
        em.clear();

        assertThat(catalogue.administration(null, "TOUS", PageRequest.of(0, 50)).getContent())
                .extracting(r -> r.id())
                .doesNotContain(id);
    }

    @Test
    @DisplayName("la fiche d'administration ne le trouve plus non plus")
    void introuvableParSonIdentifiant() {
        Long id = brouillon("REF-CORB-002", "Chemise introuvable").id();
        em.flush();

        catalogue.mettreALaCorbeille(id);
        em.flush();
        em.clear();

        // `findById` lui-même ne le voit plus : c'est la restriction qui agit,
        // pas un filtre écrit dans la requête de liste.
        assertThatThrownBy(() -> catalogue.ficheAdministration(id))
                .isInstanceOf(RessourceIntrouvable.class);
    }

    @Test
    @DisplayName("il apparaît dans la corbeille, avec son statut d'origine")
    void apparaitDansLaCorbeille() {
        Long id = brouillon("REF-CORB-003", "Chemise en attente").id();
        em.flush();

        catalogue.mettreALaCorbeille(id);
        em.flush();
        em.clear();

        assertThat(catalogue.corbeille(PageRequest.of(0, 50)).getContent())
                .extracting(VueCorbeille::id)
                .contains(id);

        VueCorbeille vue = catalogue.corbeille(PageRequest.of(0, 50)).getContent().stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow();

        // Le statut est CONSERVÉ, pas écrasé par un « SUPPRIME ». C'est ce qui
        // permet de rendre au produit son état exact à la restauration.
        assertThat(vue.statut()).isEqualTo(StatutProduit.BROUILLON.name());
        assertThat(vue.dateSuppression()).isNotNull();
    }

    /**
     * ⚠️ Un produit publié a pu être vu, mis au panier, négocié.
     *
     * <p>Son chemin est l'archivage, qui préserve les commandes qui le citent.
     * Ouvrir la corbeille aux produits publiés proposerait deux gestes pour la
     * même chose, dont un seul est correct.</p>
     */
    @Test
    @DisplayName("un produit publié est refusé : il s'archive, il ne se jette pas")
    void refusePublie() {
        Long id = brouillon("REF-CORB-004", "Chemise publiée").id();
        jdbc.update("UPDATE produit SET statut = 'PUBLIE' WHERE id = ?", id);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> catalogue.mettreALaCorbeille(id))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("Archivez-le");
    }

    // -------------------------------------------------------------------------
    // Restauration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("restaurer le rend à la liste, avec son statut intact")
    void restaurationRendLeStatut() {
        Long id = brouillon("REF-CORB-005", "Chemise récupérée").id();
        em.flush();

        catalogue.mettreALaCorbeille(id);
        em.flush();
        em.clear();

        catalogue.restaurerProduit(id);
        em.flush();
        em.clear();

        assertThat(catalogue.ficheAdministration(id).statut())
                .isEqualTo(StatutProduit.BROUILLON.name());

        assertThat(catalogue.corbeille(PageRequest.of(0, 50)).getContent())
                .extracting(VueCorbeille::id)
                .doesNotContain(id);
    }

    @Test
    @DisplayName("restaurer un produit qui n'est pas en corbeille est refusé")
    void restaurationImpossibleHorsCorbeille() {
        Long id = brouillon("REF-CORB-006", "Chemise bien vivante").id();
        em.flush();

        assertThatThrownBy(() -> catalogue.restaurerProduit(id))
                .isInstanceOf(RessourceIntrouvable.class);
    }

    // -------------------------------------------------------------------------
    // Effacement définitif
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("vider la corbeille efface réellement la ligne")
    void videEfface() {
        Long id = brouillon("REF-CORB-007", "Chemise effacée").id();
        em.flush();

        catalogue.mettreALaCorbeille(id);
        em.flush();
        em.clear();

        catalogue.viderDeLaCorbeille(id);
        em.flush();
        em.clear();

        // On interroge la base SANS passer par JPA : la restriction masquerait
        // le résultat et le test passerait même si la ligne existait encore.
        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM produit WHERE id = ?", Integer.class, id);
        assertThat(restantes).isZero();
    }

    /**
     * 🎯 <b>La condition qui protège les produits en vente.</b>
     *
     * <p>L'effacement définitif n'agit que sur ce qui est <b>déjà</b> dans la
     * corbeille. Sans cette condition, un identifiant erroné détruirait un
     * produit actif sans passer par aucune étape intermédiaire.</p>
     */
    @Test
    @DisplayName("on ne peut pas effacer un produit qui n'est pas en corbeille")
    void refuseEffacerHorsCorbeille() {
        Long id = brouillon("REF-CORB-008", "Chemise protégée").id();
        em.flush();

        assertThatThrownBy(() -> catalogue.viderDeLaCorbeille(id))
                .isInstanceOf(RessourceIntrouvable.class);

        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM produit WHERE id = ?", Integer.class, id);
        assertThat(restantes).isOne();
    }
}
