package com.garah.api.finance;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.finance.domaine.*;
import com.garah.api.finance.infra.EcritureMarchandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("Grand livre marchand")
class ServiceGrandLivreTest {

    @Autowired ServiceGrandLivre grandLivre;
    @Autowired EcritureMarchandRepository ecritures;
    @Autowired JdbcTemplate jdbc;

    private Long marchandId;

    @BeforeEach
    void preparer() {
        marchandId = jdbc.queryForObject("""
                INSERT INTO marchand (code, nom, type)
                VALUES ('M-FIN-1', 'Marchand ABC', 'EXTERNE') RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("le cycle complet : vente, retour, règlement, solde à zéro")
    void cycleComplet() {
        // Vente de 10 chemises à 15 000, commission 10 %.
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "10 chemises");
        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("135000.00");

        // Retour de 3 unités : la vente ET la commission sont annulées.
        grandLivre.enregistrerRetour(marchandId, 1L,
                new BigDecimal("45000.00"), new BigDecimal("4500.00"), "3 chemises");
        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("94500.00");

        // Règlement du solde.
        ReglementMarchand reglement = grandLivre.preparer(marchandId,
                new BigDecimal("94500.00"), "VIREMENT", null);

        // Préparer n'est pas payer : le solde n'a pas bougé.
        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("94500.00");

        grandLivre.confirmer(reglement.getId(), "VIR-2026-001");
        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("une vente produit DEUX écritures, pas une")
    void venteEnDeuxEcritures() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "Vente");

        // On pourrait écrire directement le net (135 000), mais on perdrait la
        // réponse à « combien de commission avez-vous prélevé ce mois-ci ? ».
        assertThat(ecritures.findByOrigineTypeAndOrigineId(OrigineEcriture.LIGNE_COMMANDE, 1L))
                .extracting(EcritureMarchand::getType)
                .containsExactlyInAnyOrder(TypeEcriture.VENTE, TypeEcriture.COMMISSION);
    }

    @Test
    @DisplayName("un retour annule la vente ET la commission")
    void retourAnnuleLesDeux() {
        grandLivre.enregistrerRetour(marchandId, 7L,
                new BigDecimal("45000.00"), new BigDecimal("4500.00"), "Retour");

        // Garder la commission sur une marchandise rendue est indéfendable
        // devant le partenaire.
        assertThat(ecritures.findByOrigineTypeAndOrigineId(OrigineEcriture.LIGNE_RETOUR, 7L))
                .extracting(EcritureMarchand::getType)
                .containsExactlyInAnyOrder(TypeEcriture.RETOUR, TypeEcriture.ANNUL_COMMISSION);
    }

    @Test
    @DisplayName("enregistrer deux fois la même vente ne double pas la dette")
    void idempotence() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "Vente");
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "Vente");

        // Indispensable : cette méthode est appelée depuis un webhook rejoué
        // (chapitre 13). Sans la garde, le marchand serait payé deux fois.
        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("135000.00");
    }

    @Test
    @DisplayName("le signe est imposé par le type, pas par l'appelant")
    void signeImposeParLeType() {
        // On passe des montants POSITIFS ; le type les oriente.
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "Vente");

        var lignes = ecritures.findByOrigineTypeAndOrigineId(OrigineEcriture.LIGNE_COMMANDE, 1L);

        assertThat(lignes).filteredOn(e -> e.getType() == TypeEcriture.VENTE)
                .allMatch(e -> e.getMontant().signum() > 0);
        assertThat(lignes).filteredOn(e -> e.getType() == TypeEcriture.COMMISSION)
                .allMatch(e -> e.getMontant().signum() < 0);
    }

    @Test
    @DisplayName("la base refuse une écriture au signe incohérent")
    void baseRefuseLeMauvaisSigne() {
        // Seconde ligne de défense : même en contournant le service, une VENTE
        // négative est impossible. Une inversion de signe fausserait TOUS les
        // soldes sans jamais lever d'erreur.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ecriture_marchand (marchand_id, type, montant, origine_type, origine_id)
                VALUES (?, 'VENTE', -150000, 'LIGNE_COMMANDE', 99)
                """, marchandId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("on ne verse pas plus qu'on ne doit")
    void reglementPlafonne() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("100000.00"), BigDecimal.ZERO, "Vente");

        assertThatThrownBy(() -> grandLivre.preparer(marchandId,
                new BigDecimal("150000.00"), "VIREMENT", null))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("dépasse ce qui est dû");
    }

    @Test
    @DisplayName("un règlement payé ne s'annule pas, il se corrige par une écriture")
    void reglementPayeNonAnnulable() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("50000.00"), BigDecimal.ZERO, "Vente");
        ReglementMarchand reglement = grandLivre.preparer(marchandId,
                new BigDecimal("50000.00"), "VIREMENT", null);
        grandLivre.confirmer(reglement.getId(), "VIR-001");

        // On n'efface pas l'histoire, on l'allonge.
        assertThatThrownBy(() -> grandLivre.annuler(reglement.getId()))
                .isInstanceOf(ConflitEtat.class)
                .hasMessageContaining("ajustement");
    }

    @Test
    @DisplayName("confirmer deux fois un règlement ne creuse pas la dette")
    void confirmationIdempotente() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("50000.00"), BigDecimal.ZERO, "Vente");
        ReglementMarchand reglement = grandLivre.preparer(marchandId,
                new BigDecimal("50000.00"), "VIREMENT", null);

        grandLivre.confirmer(reglement.getId(), "VIR-001");
        grandLivre.confirmer(reglement.getId(), "VIR-001");

        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("un ajustement doit être justifié")
    void ajustementJustifie() {
        assertThatThrownBy(() -> grandLivre.ajuster(marchandId,
                new BigDecimal("-5000.00"), "  ", null))
                .isInstanceOf(RegleMetierViolee.class);

        // Un ajustement de zéro n'apprend rien à personne.
        assertThatThrownBy(() -> grandLivre.ajuster(marchandId,
                BigDecimal.ZERO, "Correction", null))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("le solde est PROUVABLE ligne par ligne")
    void soldeDetaille() {
        grandLivre.enregistrerVente(marchandId, 1L,
                new BigDecimal("150000.00"), new BigDecimal("15000.00"), "10 chemises");
        grandLivre.enregistrerVente(marchandId, 2L,
                new BigDecimal("60000.00"), new BigDecimal("6000.00"), "4 pantalons");

        // C'est la réponse à « pourquoi doit-on 189 000 FCFA au marchand ABC ? ».
        // Le modèle initial ne savait pas y répondre : il n'avait qu'un montant.
        assertThat(grandLivre.detail(marchandId, PageRequest.of(0, 10)).getContent())
                .hasSize(4)
                .extracting(EcritureMarchand::getLibelle)
                .anyMatch(l -> l.contains("chemises"));

        assertThat(grandLivre.solde(marchandId)).isEqualByComparingTo("189000.00");
    }
}
