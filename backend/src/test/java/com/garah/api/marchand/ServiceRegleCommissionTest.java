package com.garah.api.marchand;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.marchand.domaine.ServiceCommission;
import com.garah.api.marchand.domaine.ServiceRegleCommission;
import com.garah.api.marchand.domaine.VueRegleCommission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le paramétrage des taux de commission.
 *
 * <p>🎯 Le vrai sujet de ces tests n'est pas la création d'une ligne : c'est
 * que <b>l'écran et le calcul départagent les règles dans le même ordre</b>.
 * S'ils divergent, la liste montre une règle en tête et la vente en applique
 * une autre — un écart qu'on ne remarque qu'en comparant deux factures.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Règles de commission")
class ServiceRegleCommissionTest {

    @Autowired ServiceRegleCommission regles;
    @Autowired ServiceCommission commissions;
    @Autowired JdbcTemplate jdbc;

    private Long marchandId;
    private Long categorieId;

    @BeforeEach
    void preparer() {
        // Les règles existantes fausseraient la résolution : une règle
        // générale d'un autre test gagnerait sur celles d'ici.
        jdbc.update("DELETE FROM regle_commission");

        marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type)
                VALUES ('M-COM-1', 'Marchand commission', 'EXTERNE') RETURNING id
                """, Long.class);

        categorieId = jdbc.queryForObject("""
                INSERT INTO categorie_produit (nom, slug) VALUES ('Textile test', 'textile-test')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("la plus spécifique gagne, et l'écran la montre en tête")
    void resolutionParSpecificite() {
        regles.creer(null, null, new BigDecimal("5.00"), 0, null, null, false);
        regles.creer(marchandId, null, new BigDecimal("8.00"), 0, null, null, false);

        // 🎯 LE TEST QUI COMPTE : le calcul et la liste doivent s'accorder.
        assertThat(commissions.tauxPour(marchandId, categorieId)).isEqualByComparingTo("8.00");
        assertThat(regles.simuler(marchandId, categorieId).taux()).isEqualByComparingTo("8.00");
        assertThat(regles.lister().getFirst().taux()).isEqualByComparingTo("8.00");

        // Un marchand non nommé retombe sur la règle générale.
        assertThat(commissions.tauxPour(999_999L, categorieId)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("les portées sont nommées, jamais numérotées")
    void porteesNommees() {
        regles.creer(marchandId, categorieId, new BigDecimal("7.50"), 0, null, null, false);

        VueRegleCommission vue = regles.lister().getFirst();

        // « marchand 7, catégorie 12 » est illisible dans un tableau qui sert
        // à répondre à « qui paie combien ? ».
        assertThat(vue.marchandNom()).isEqualTo("Marchand commission");
        assertThat(vue.categorieNom()).isEqualTo("Textile test");
        assertThat(vue.active()).isTrue();
    }

    @Test
    @DisplayName("une règle générale n'a ni marchand ni catégorie, et c'est normal")
    void regleGenerale() {
        regles.creer(null, null, new BigDecimal("5.00"), 0, null, null, false);

        VueRegleCommission vue = regles.lister().getFirst();

        // Nuls porteurs de sens : « tous », pas une donnée manquante. C'est à
        // l'écran de l'écrire, mais le service doit les laisser passer.
        assertThat(vue.marchandNom()).isNull();
        assertThat(vue.categorieNom()).isNull();
    }

    @Test
    @DisplayName("on ferme une règle, on ne la supprime pas")
    void fermeture() {
        Long id = regles.creer(marchandId, null, new BigDecimal("8.00"), 0, null, null, false).id();

        assertThat(commissions.tauxPour(marchandId, null)).isEqualByComparingTo("8.00");

        regles.fermer(id, null);

        // Fermée aujourd'hui = elle ne s'applique plus aujourd'hui :
        // `applicables` teste dateFin > jour.
        assertThat(commissions.tauxPour(marchandId, null)).isEqualByComparingTo("0.00");

        // Mais elle RESTE dans la liste. La masquer ferait chercher pendant
        // une heure pourquoi un taux paramétré ne s'applique pas.
        VueRegleCommission fermee = regles.lister().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(fermee.active()).isFalse();
    }

    @Test
    @DisplayName("une règle future se paramètre à l'avance sans s'appliquer")
    void regleFuture() {
        LocalDate demain = LocalDate.now().plusDays(1);
        Long id = regles.creer(marchandId, null, new BigDecimal("12.00"), 0,
                demain, null, false).id();

        // « À partir du 1er janvier, la commission passe à 12 % » se paramètre
        // en décembre — et ne doit rien changer avant.
        assertThat(commissions.tauxPour(marchandId, null)).isEqualByComparingTo("0.00");

        assertThat(regles.lister().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow()
                .active()).isFalse();
    }

    @Test
    @DisplayName("un taux invraisemblable demande confirmation")
    void tauxEleve() {
        // La virgule mal placée : « 4,5 » saisi « 45 » multiplie la commission
        // par dix, et rien d'autre ne le signalerait avant le premier règlement.
        assertThatThrownBy(() ->
                regles.creer(marchandId, null, new BigDecimal("75.00"), 0, null, null, false))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("moins de la moitié");

        // Confirmé, il passe : 75 % peut être légitime, ce n'est pas au code
        // d'en décider.
        assertThat(regles.creer(marchandId, null, new BigDecimal("75.00"), 0, null, null, true)
                .taux()).isEqualByComparingTo("75.00");

        assertThatThrownBy(() ->
                regles.creer(null, null, new BigDecimal("150.00"), 0, null, null, true))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("la simulation appelle le même calcul que la vente")
    void simulationFidele() {
        regles.creer(null, null, new BigDecimal("5.00"), 0, null, null, false);
        regles.creer(null, categorieId, new BigDecimal("9.00"), 0, null, null, false);

        // Si la simulation réécrivait la résolution, elle finirait par
        // répondre autre chose que la réalité — et c'est justement l'écran
        // auquel on fait confiance pour vérifier un paramétrage.
        assertThat(regles.simuler(marchandId, categorieId).taux())
                .isEqualByComparingTo(commissions.tauxPour(marchandId, categorieId));

        // Sans aucune règle applicable : zéro, pas une erreur. Une commande ne
        // doit jamais échouer faute de taux paramétré.
        jdbc.update("DELETE FROM regle_commission");
        assertThat(regles.simuler(marchandId, categorieId).taux()).isEqualByComparingTo("0.00");
        assertThat(regles.simuler(marchandId, categorieId).regleId()).isNull();
    }
}
