package com.garah.api.iam;

import com.garah.api.iam.domaine.ServiceJoignabilite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui peut commander — la règle de D-52, qui remplace celle de D-23.
 *
 * <p>D-23 exigeait une <b>adresse e-mail confirmée</b>. Sa justification, elle,
 * parlait d'autre chose : pouvoir prévenir le client de l'arrivée de son colis
 * et lui transmettre son code de retrait. Ce qui était exigé, c'est d'être
 * <b>joignable</b> — l'e-mail n'en était que le seul moyen disponible.</p>
 *
 * <p>Depuis V39 il y en a un second, et il prouve exactement la même chose :
 * quelqu'un contrôle ce canal.</p>
 */
@SpringBootTest
@DisplayName("Joignabilité")
class JoignabiliteTest {

    @Autowired ServiceJoignabilite joignabilite;
    @Autowired JdbcTemplate sql;

    @BeforeEach
    void nettoyer() {
        sql.update("DELETE FROM client WHERE id IN "
                + "(SELECT id FROM utilisateur WHERE lower(email) LIKE 'joignable%')");
        sql.update("DELETE FROM utilisateur WHERE lower(email) LIKE 'joignable%'");
        sql.update("DELETE FROM utilisateur WHERE telephone = '+237699000888'");
    }

    private Long creerAvecEmail(String suffixe, boolean verifie) {
        return sql.queryForObject("""
                INSERT INTO utilisateur (type, nom, email, mot_de_passe, email_verifie)
                VALUES ('CLIENT', 'Essai', ?, 'x', ?)
                RETURNING id
                """, Long.class, "joignable." + suffixe + "@garah.cm", verifie);
    }

    @Test
    @DisplayName("une adresse confirmée suffit — la règle de D-23 tient toujours")
    void emailConfirme() {
        assertThat(joignabilite.estJoignable(creerAvecEmail("oui", true))).isTrue();
    }

    @Test
    @DisplayName("une adresse NON confirmée ne suffit pas")
    void emailNonConfirme() {
        assertThat(joignabilite.estJoignable(creerAvecEmail("non", false))).isFalse();
    }

    /**
     * 🎯 Le changement apporté par D-52.
     *
     * <p>Un compte né par WhatsApp n'a aucune adresse e-mail, et peut pourtant
     * commander : le code envoyé sur son numéro a été recopié, donc le canal
     * est prouvé. Sur l'axe Douala → Bangui, c'est souvent le plus fiable des
     * deux.</p>
     */
    @Test
    @DisplayName("un numéro prouvé par WhatsApp suffit, SANS aucune adresse")
    void numeroProuveSuffit() {
        // ⚠️ UNE SEULE instruction, et c'est obligatoire.
        //
        //    Ma première version faisait deux `sql.update()`. Chacun étant
        //    auto-validé, le compte était commis SEUL — sans mot de passe et
        //    sans identité — et le trigger de V38 l'a refusé :
        //
        //        Le compte n aurait plus aucun moyen de connexion
        //
        //    La base avait raison : entre les deux instructions, ce compte
        //    était réellement enfermé dehors. Le CTE fait tenir les deux
        //    écritures dans la même transaction, exactement comme le service.
        Long id = sql.queryForObject("""
                WITH nouveau AS (
                    INSERT INTO utilisateur (type, nom, email, mot_de_passe, telephone, email_verifie)
                    VALUES ('CLIENT', 'Par WhatsApp', NULL, NULL, '+237699000888', false)
                    RETURNING id
                )
                INSERT INTO identite_sociale (utilisateur_id, fournisseur, sujet)
                SELECT id, 'WHATSAPP', '+237699000888' FROM nouveau
                RETURNING utilisateur_id
                """, Long.class);

        assertThat(joignabilite.estJoignable(id)).isTrue();
    }

    /**
     * ⚠️ Le piège que ce test ferme, et qui est le cœur de la règle.
     *
     * <p>Un numéro <b>saisi</b> dans un formulaire ne prouve rien : personne
     * n'a vérifié qu'il appartient à cette personne, ni même qu'il existe. Un
     * chiffre de travers y ressemble à un numéro juste.</p>
     *
     * <p>Accepter un simple {@code utilisateur.telephone} rouvrirait exactement
     * le défaut que D-23 fermait : le colis arrive à Bangui, et l'avis part
     * chez quelqu'un d'autre.</p>
     */
    @Test
    @DisplayName("un numéro SAISI ne prouve rien, et ne suffit pas")
    void numeroSaisiNeSuffitPas() {
        Long id = sql.queryForObject("""
                INSERT INTO utilisateur (type, nom, email, mot_de_passe, telephone, email_verifie)
                VALUES ('CLIENT', 'Numero tape', ?, 'x', '+237699000888', false)
                RETURNING id
                """, Long.class, "joignable.tape@garah.cm");

        assertThat(joignabilite.numeroProuve(id)).isFalse();
        assertThat(joignabilite.estJoignable(id))
                .as("un numero du formulaire n atteste rien")
                .isFalse();
    }

    /**
     * D-53 : toute identite sociale suffit, meme sans aucun canal externe.
     *
     * <p>⚠️ C est la decision la plus lourde de consequence de ce fichier. Un
     * compte TikTok n a ni adresse ni numero : si son colis arrive a Bangui et
     * qu il n ouvre pas l application, <b>personne ne peut le prevenir</b>.</p>
     *
     * <p>Ce qui l attenue sans l annuler : les notifications poussees et
     * l Assistance dans l application (D-41). Un client qui garde
     * l application installee est joignable ; un client qui la desinstalle ne
     * l est plus du tout.</p>
     */
    @Test
    @DisplayName("une identite sociale suffit, meme sans adresse ni numero")
    void identiteSocialeSuffit() {
        Long id = sql.queryForObject("""
                WITH nouveau AS (
                    INSERT INTO utilisateur (type, nom, email, mot_de_passe, email_verifie)
                    VALUES ('CLIENT', 'Par TikTok', NULL, NULL, false)
                    RETURNING id
                )
                INSERT INTO identite_sociale (utilisateur_id, fournisseur, sujet)
                SELECT id, 'TIKTOK', 'open-id-essai-53' FROM nouveau
                RETURNING utilisateur_id
                """, Long.class);

        assertThat(joignabilite.estJoignable(id)).isTrue();

        sql.update("DELETE FROM utilisateur WHERE id = ?", id);
    }

    @Test
    @DisplayName("un compte inconnu n'est pas joignable — le doute refuse")
    void compteInconnu() {
        assertThat(joignabilite.estJoignable(999_999_999L)).isFalse();
        assertThat(joignabilite.estJoignable(null)).isFalse();
    }
}
