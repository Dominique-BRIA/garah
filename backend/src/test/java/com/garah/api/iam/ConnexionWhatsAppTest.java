package com.garah.api.iam;

import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.CodeConnexionRepository;
import com.garah.api.iam.infra.IdentiteSocialeRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * « Continuer avec WhatsApp ».
 *
 * <p><b>L'envoi est remplacé par une doublure qui RETIENT le code</b>, au lieu
 * de l'expédier. Deux raisons, et la seconde compte autant :</p>
 *
 * <ul>
 *   <li>l'envoi réel appelle Meta — un test qui en dépendrait deviendrait
 *       intermittent, et chaque exécution serait facturée ;</li>
 *   <li>c'est la <b>seule</b> façon de connaître le code : il n'est stocké que
 *       haché. Un test qui pourrait le lire en base signalerait justement un
 *       défaut.</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("Connexion WhatsApp")
class ConnexionWhatsAppTest {

    private static final String NUMERO = "+237699000777";

    @Autowired ServiceConnexionWhatsApp whatsapp;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired IdentiteSocialeRepository identites;
    @Autowired CodeConnexionRepository codes;
    @Autowired JdbcTemplate sql;

    @MockitoBean EnvoiWhatsApp envoi;

    /** Les codes réellement « envoyés », dans l'ordre. */
    private final List<String> envoyes = new ArrayList<>();

    @BeforeEach
    void preparer() {
        envoyes.clear();

        when(envoi.estActif()).thenReturn(true);
        when(envoi.envoyerLeCode(anyString(), anyString())).thenAnswer(appel -> {
            envoyes.add(appel.getArgument(1));
            return true;
        });

        sql.update("DELETE FROM code_connexion WHERE telephone LIKE '+2376990007%'");
        sql.update("DELETE FROM evenement_securite WHERE utilisateur_id IN "
                + "(SELECT id FROM utilisateur WHERE telephone LIKE '+2376990007%')");
        sql.update("DELETE FROM client WHERE id IN "
                + "(SELECT id FROM utilisateur WHERE telephone LIKE '+2376990007%')");
        // Emporte identite_sociale par cascade — le trigger de V38 refuserait
        // qu'on retire l'identité d'un compte qui n'a pas de mot de passe.
        sql.update("DELETE FROM utilisateur WHERE telephone LIKE '+2376990007%'");
    }

    private String demanderLeCode() {
        whatsapp.demanderUnCode(NUMERO);
        return envoyes.getLast();
    }

    // -------------------------------------------------------------------------
    // Le parcours normal
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un numéro inconnu obtient un compte client complet")
    void creeLeCompte() {
        String code = demanderLeCode();

        ResultatConnexion session = whatsapp.verifier(NUMERO, code, "10.0.0.1");

        assertThat(session.type()).isEqualTo(TypeUtilisateur.CLIENT);

        Utilisateur cree = utilisateurs.findById(session.utilisateurId()).orElseThrow();

        // ⚠️ AUCUNE adresse e-mail — c'est ce que V39 a rendu possible, et la
        //    limite assumée : WhatsApp atteste un numéro, jamais une adresse.
        assertThat(cree.getEmail()).isNull();
        assertThat(cree.getMotDePasse()).isNull();
        assertThat(cree.getTelephone()).isEqualTo(NUMERO);

        // La ligne client DOIT exister, sinon le compte se connecte puis échoue
        // au premier ajout au panier — et le défaut ne se voit qu'au paiement.
        Integer lignesClient = sql.queryForObject(
                "SELECT count(*) FROM client WHERE id = ?", Integer.class, cree.getId());
        assertThat(lignesClient).isEqualTo(1);
    }

    @Test
    @DisplayName("revenir ne crée pas un second compte")
    void deuxiemeConnexionReutiliseLeCompte() {
        Long premier = whatsapp.verifier(NUMERO, demanderLeCode(), "10.0.0.1").utilisateurId();
        Long second = whatsapp.verifier(NUMERO, demanderLeCode(), "10.0.0.1").utilisateurId();

        assertThat(second).isEqualTo(premier);
    }

    /**
     * La normalisation fait son travail jusqu'au bout.
     *
     * <p>Demander le code avec une écriture et le vérifier avec une autre doit
     * marcher — sinon la même personne se retrouverait avec deux comptes selon
     * qu'elle met des espaces ou non.</p>
     */
    @Test
    @DisplayName("l'écriture du numéro n'a aucune importance")
    void lEcritureNImportePas() {
        whatsapp.demanderUnCode("699 00 07 77");
        String code = envoyes.getLast();

        Long id = whatsapp.verifier("+237699000777", code, "10.0.0.1").utilisateurId();

        assertThat(identites.findByFournisseurAndSujet(FournisseurIdentite.WHATSAPP, NUMERO))
                .isPresent()
                .get()
                .satisfies(i -> assertThat(i.getUtilisateur().getId()).isEqualTo(id));
    }

    // -------------------------------------------------------------------------
    // Ce qui protège
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le code n'est JAMAIS stocké en clair")
    void leCodeEstHache() {
        String code = demanderLeCode();

        String hache = sql.queryForObject(
                "SELECT code_hache FROM code_connexion WHERE telephone = ? AND NOT consomme",
                String.class, NUMERO);

        // Une fuite de la table — sauvegarde, export, console SQL — ne doit
        // donner aucun code utilisable.
        assertThat(hache).isNotEqualTo(code).doesNotContain(code);
    }

    @Test
    @DisplayName("un code ne sert qu'UNE fois")
    void usageUnique() {
        String code = demanderLeCode();
        whatsapp.verifier(NUMERO, code, "10.0.0.1");

        assertThatThrownBy(() -> whatsapp.verifier(NUMERO, code, "10.0.0.1"))
                .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);
    }

    /**
     * ⚠️ Demander un nouveau code TUE le précédent.
     *
     * <p>Sans cette règle, chaque demande laisserait un code de plus valable :
     * on multiplierait les chances d'en deviner un, et on offrirait le moyen
     * d'inonder de messages le téléphone de quelqu'un d'autre.</p>
     */
    @Test
    @DisplayName("un nouveau code invalide l'ancien")
    void unSeulCodeVivant() {
        String ancien = demanderLeCode();
        String nouveau = demanderLeCode();

        assertThat(nouveau).isNotEqualTo(ancien);

        assertThatThrownBy(() -> whatsapp.verifier(NUMERO, ancien, "10.0.0.1"))
                .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);

        assertThat(whatsapp.verifier(NUMERO, nouveau, "10.0.0.1")).isNotNull();
    }

    /**
     * 🎯 Le plafond, et non la longueur du code, est ce qui rend l'attaque
     * impossible. Six chiffres, c'est un million de combinaisons — quelques
     * minutes de script sans lui.
     */
    @Test
    @DisplayName("cinq essais ratés brûlent le code, même le bon ensuite")
    void plafondDeTentatives() {
        String code = demanderLeCode();

        for (int i = 0; i < CodeConnexion.TENTATIVES_MAX; i++) {
            assertThatThrownBy(() -> whatsapp.verifier(NUMERO, "000000", "10.0.0.1"))
                    .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);
        }

        assertThatThrownBy(() -> whatsapp.verifier(NUMERO, code, "10.0.0.1"))
                .as("le bon code ne doit plus ouvrir : le code est brule")
                .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);
    }

    /**
     * ⚠️ Et le code brûlé ne doit pas enfermer la personne dehors.
     *
     * <p>Au plafond, le code est <b>consommé</b> et non simplement refusé.
     * Sinon il resterait « vivant » pour l'index unique, et une faute de frappe
     * répétée empêcherait d'en redemander un.</p>
     */
    @Test
    @DisplayName("après un code brûlé, on peut en redemander un")
    void onPeutRedemanderApresLePlafond() {
        demanderLeCode();

        for (int i = 0; i < CodeConnexion.TENTATIVES_MAX; i++) {
            assertThatThrownBy(() -> whatsapp.verifier(NUMERO, "000000", "10.0.0.1"))
                    .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);
        }

        String nouveau = demanderLeCode();
        assertThat(whatsapp.verifier(NUMERO, nouveau, "10.0.0.1")).isNotNull();
    }

    @Test
    @DisplayName("un numéro sans code demandé ne peut rien vérifier")
    void sansDemandePasDeVerification() {
        assertThatThrownBy(() -> whatsapp.verifier(NUMERO, "123456", "10.0.0.1"))
                .isInstanceOf(ServiceConnexionWhatsApp.CodeInvalide.class);
    }

    /**
     * ⚠️ AUCUN rattachement automatique à un compte existant portant ce numéro
     * — contrairement à Google.
     *
     * <p>La différence est de fond : Google atteste une adresse qu'il a
     * lui-même vérifiée. Ici, {@code utilisateur.telephone} a été <b>saisi</b>
     * par quelqu'un, sans le moindre contrôle. Rattacher sur cette base
     * laisserait prendre le compte de quiconque a tapé son numéro par erreur —
     * ou celui d'un autre exprès.</p>
     */
    @Test
    @DisplayName("un compte existant portant ce numéro n'est PAS repris")
    void pasDeRattachementSurUnNumeroSaisi() {
        Long victime = sql.queryForObject("""
                INSERT INTO utilisateur (type, nom, email, mot_de_passe, telephone, email_verifie)
                VALUES ('CLIENT', 'Titulaire', 'titulaire.wa@garah.cm', 'x', ?, true)
                RETURNING id
                """, Long.class, NUMERO);
        sql.update("INSERT INTO client (id, code_client) VALUES (?, ?)",
                victime, "CLI-W%05d".formatted(victime % 100000));

        Long obtenu = whatsapp.verifier(NUMERO, demanderLeCode(), "10.0.0.1").utilisateurId();

        assertThat(obtenu)
                .as("le compte du titulaire ne doit PAS etre repris")
                .isNotEqualTo(victime);

        sql.update("DELETE FROM client WHERE id = ?", victime);
        sql.update("DELETE FROM utilisateur WHERE id = ?", victime);
    }

    @Test
    @DisplayName("un numéro étranger est refusé avant tout envoi")
    void numeroEtrangerRefuse() {
        assertThatThrownBy(() -> whatsapp.demanderUnCode("+33612345678"))
                .isInstanceOf(NumeroTelephone.NumeroInvalide.class);

        assertThat(envoyes).as("aucun message ne doit partir").isEmpty();
    }
}
