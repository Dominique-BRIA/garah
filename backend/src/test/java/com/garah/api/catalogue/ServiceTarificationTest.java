package com.garah.api.catalogue;

import com.garah.api.catalogue.domaine.*;
import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.RegleMetierViolee;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Prix par palier de quantité")
class ServiceTarificationTest {

    @Autowired ServiceCatalogue catalogue;
    @Autowired ServiceTarification tarification;
    @Autowired CategorieProduitRepository categories;
    @Autowired VarianteRepository variantes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long varianteId;

    @BeforeEach
    void preparerUneChemise() {
        Long marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type) VALUES ('M-PRIX-1', 'Marchand prix', 'EXTERNE')
                RETURNING id
                """, Long.class);
        Long categorieId = categories.save(new CategorieProduit("Prix de test", null)).getId();

        Long produitId = catalogue.creerProduit(marchandId, categorieId, "REF-PRIX-1",
                "Chemise tarifée", null).id();
        em.flush();

        varianteId = variantes.findByProduitId(produitId).getFirst().getId();

        // La grille de la spécification, §12.
        tarification.definirPalier(varianteId, 1, 4, new BigDecimal("15000.00"));
        tarification.definirPalier(varianteId, 5, 9, new BigDecimal("13000.00"));
        tarification.definirPalier(varianteId, 10, null, new BigDecimal("11500.00"));
        em.flush();
    }

    @Test
    @DisplayName("chaque quantité tombe dans le bon palier")
    void selectionDuPalier() {
        assertThat(tarification.prixUnitaire(varianteId, 1)).isEqualByComparingTo("15000.00");
        assertThat(tarification.prixUnitaire(varianteId, 4)).isEqualByComparingTo("15000.00");
        assertThat(tarification.prixUnitaire(varianteId, 5)).isEqualByComparingTo("13000.00");
        assertThat(tarification.prixUnitaire(varianteId, 9)).isEqualByComparingTo("13000.00");
        assertThat(tarification.prixUnitaire(varianteId, 10)).isEqualByComparingTo("11500.00");
        assertThat(tarification.prixUnitaire(varianteId, 500)).isEqualByComparingTo("11500.00");
    }

    @Test
    @DisplayName("le prix dégressif s'applique à TOUTES les unités")
    void degressiviteGlobale() {
        // 10 x 11 500 = 115 000, et NON « 4 x 15 000 + 6 x 11 500 ».
        // C'est la question qu'on se pose toujours une fois : elle est tranchée
        // ici, et testée pour ne plus jamais se reposer.
        assertThat(tarification.montantPour(varianteId, 10)).isEqualByComparingTo("115000.00");
    }

    @Test
    @DisplayName("un palier qui chevauche un existant est refusé")
    void chevauchementRefuse() {
        // 3-10 recouvre a la fois 1-4 et 5-9 : « quel prix pour 6 unites ? »
        // aurait deux reponses.
        assertThatThrownBy(() ->
                tarification.definirPalier(varianteId, 3, 10, new BigDecimal("14000.00")))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("chevauche");
    }

    @Test
    @DisplayName("un palier adjacent, lui, est accepté")
    void paliersAdjacents() {
        Long autre = creerAutreVariante();

        tarification.definirPalier(autre, 1, 4, new BigDecimal("100.00"));
        // 5 commence exactement où 4 finit : aucun chevauchement.
        assertThat(tarification.definirPalier(autre, 5, 9, new BigDecimal("90.00")))
                .isNotNull();
    }

    @Test
    @DisplayName("une quantité sans palier donne une erreur explicite")
    void quantiteSansPalier() {
        Long autre = creerAutreVariante();
        tarification.definirPalier(autre, 10, 20, new BigDecimal("100.00"));
        em.flush();

        // Ce n'est pas un bug technique : personne n'a prévu de prix pour
        // moins de 10 unités. Le message doit envoyer le responsable au bon
        // endroit, c'est-à-dire dans la grille tarifaire.
        assertThatThrownBy(() -> tarification.prixUnitaire(autre, 3))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("Aucun prix");
    }

    @Test
    @DisplayName("changer un prix ferme l'ancien palier et en ouvre un nouveau")
    void changementDePrix() {
        var grilleAvant = tarification.grille(varianteId);
        assertThat(grilleAvant).hasSize(3);

        Long palierId = jdbc.queryForObject(
                "SELECT id FROM tarification WHERE variante_id = ? AND quantite_min = 1",
                Long.class, varianteId);

        tarification.changerPrix(palierId, new BigDecimal("16000.00"));
        em.flush();
        em.clear();

        // La grille EN VIGUEUR compte toujours trois paliers...
        List<PalierPrix> apres = tarification.grille(varianteId);
        assertThat(apres).hasSize(3);
        assertThat(tarification.prixUnitaire(varianteId, 2)).isEqualByComparingTo("16000.00");

        // ...mais la table en contient quatre : l'ancien prix est CONSERVE.
        // C'est ce qui permet de repondre plus tard a « quel etait le prix
        // le 12 mars ? », question qui se pose lors d'un litige.
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tarification WHERE variante_id = ?", Long.class, varianteId);
        assertThat(total).isEqualTo(4);
    }

    @Test
    @DisplayName("un prix nul ou négatif est refusé")
    void prixInvalide() {
        Long autre = creerAutreVariante();

        assertThatThrownBy(() -> tarification.definirPalier(autre, 1, null, BigDecimal.ZERO))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("la grille est renvoyée du plus petit palier au plus grand")
    void grilleOrdonnee() {
        assertThat(tarification.grille(varianteId))
                .extracting(PalierPrix::libelle)
                .containsExactly("1 à 4", "5 à 9", "10 et +");
    }

    private Long creerAutreVariante() {
        Long marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type) VALUES ('M-PRIX-2', 'Autre marchand', 'EXTERNE')
                RETURNING id
                """, Long.class);
        Long categorieId = categories.save(new CategorieProduit("Autre prix", null)).getId();
        Long produitId = catalogue.creerProduit(marchandId, categorieId, "REF-PRIX-2",
                "Autre produit", null).id();
        em.flush();
        return variantes.findByProduitId(produitId).getFirst().getId();
    }
}
