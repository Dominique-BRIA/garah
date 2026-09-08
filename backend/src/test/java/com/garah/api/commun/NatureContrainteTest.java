package com.garah.api.commun;

import com.garah.api.commun.erreur.ReponseErreur;
import com.garah.api.commun.web.GestionnaireErreursGlobal;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'on dit d'une contrainte que personne n'a pris la peine de nommer.
 *
 * <p>🎯 <b>Une phrase vraie de tout n'aide personne.</b> « L'opération est en
 * conflit avec des données existantes » couvrait aussi bien un doublon qu'une
 * référence manquante ou une règle arithmétique. Une réception de stock
 * refusée a coûté une demi-heure avant qu'on découvre une clé étrangère
 * derrière cette phrase (V28).</p>
 *
 * <p>Ces cas se testent <b>ici et pas par HTTP</b> : provoquer une vraie
 * violation demanderait de casser volontairement le schéma. Ce qui compte est
 * la traduction, et elle se vérifie directement.</p>
 */
@DisplayName("Erreurs : la nature d'une contrainte non nommée")
class NatureContrainteTest {

    private final GestionnaireErreursGlobal gestionnaire = new GestionnaireErreursGlobal();
    private final MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/api/stock/1/entrees");

    /**
     * L'empilement réel, reproduit tel quel.
     *
     * <p>⚠️ Spring enveloppe l'exception d'Hibernate, qui enveloppe celle du
     * pilote. Le SQLState n'existe que sur la <b>dernière</b> : un code qui
     * regarderait seulement la première ne trouverait jamais rien, et le test
     * doit donc reproduire les trois niveaux.</p>
     */
    private static DataIntegrityViolationException violation(String sqlState, String contrainte) {
        SQLException pilote = new SQLException("violates constraint", sqlState);
        ConstraintViolationException hibernate =
                new ConstraintViolationException("could not execute statement", pilote, contrainte);
        return new DataIntegrityViolationException("integrity", hibernate);
    }

    private String messageDe(String sqlState, String contrainte) {
        ResponseEntity<ReponseErreur> reponse =
                gestionnaire.integrite(violation(sqlState, contrainte), requete);
        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        return reponse.getBody().message();
    }

    @Test
    @DisplayName("Une clé étrangère dit qu'il manque un élément, pas « un conflit »")
    void cleEtrangere() {
        // 23503 : c'est exactement ce que la reception de stock declenchait.
        assertThat(messageDe("23503", "mouvement_stock_auteur_fkey"))
                .contains("renvoie à un élément qui n'existe pas");
    }

    @Test
    @DisplayName("Un doublon dit qu'une valeur doit être unique")
    void doublon() {
        assertThat(messageDe("23505", "une_contrainte_sans_nom_connu"))
                .contains("existe déjà");
    }

    @Test
    @DisplayName("Une règle du schéma dit qu'une règle n'est pas respectée")
    void regleDuSchema() {
        assertThat(messageDe("23514", "une_contrainte_sans_nom_connu"))
                .contains("ne respectent pas une règle");
    }

    @Test
    @DisplayName("Une contrainte NOMMÉE garde son message métier")
    void leNomLemporte() {
        // Le SQLState ne sert que de repli. Une contrainte traduite dit ce que
        // l'utilisateur doit comprendre, pas la nature technique du refus.
        assertThat(messageDe("23505", "utilisateur_email_unique"))
                .isEqualTo("Cette adresse e-mail est déjà utilisée.");
    }

    @Test
    @DisplayName("Un état inconnu retombe sur la phrase générale")
    void etatInconnu() {
        assertThat(messageDe("42P01", "une_contrainte_sans_nom_connu"))
                .isEqualTo("L'opération est en conflit avec des données existantes.");
    }
}
