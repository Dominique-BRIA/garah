package com.garah.api.commerce;

import com.garah.api.commerce.infra.ClientCampay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un refus de l'opérateur n'est pas une panne — et sa raison lui appartient.
 *
 * <h2>🎯 Deux défauts, corrigés l'un après l'autre</h2>
 *
 * <p><b>Le premier :</b> tout échec d'appel devenait « le service de paiement
 * est momentanément injoignable ». C'était faux la moitié du temps — un
 * {@code 400} signifie que Campay a répondu, parfaitement, pour dire non.</p>
 *
 * <p><b>Le second, introduit en corrigeant le premier :</b> on traduisait
 * « le corps contient le mot <i>amount</i> » en « le minimum est de 100 FCFA ».
 * Une supposition présentée comme un fait. Interrogé directement, Campay
 * répond tout autre chose :</p>
 *
 * <pre>
 * Minimum amount for Orange is 10.00          le seuil dépend de L'OPÉRATEUR
 * This is a demo system. Maximum amount is 25.00 XAF   et de L'ENVIRONNEMENT
 * Rate limit per Phone number exceeded        et ce n'est pas toujours un refus
 * </pre>
 *
 * <p>⚠️ Le garde-fou « minimum 100 » fermait donc la seule fenêtre utilisable
 * en démonstration : rien ne pouvait plus passer, ni en dessous de 100 (nous
 * refusions), ni au-dessus de 25 (Campay refusait). Un essai à 20 FCFA,
 * parfaitement valide, était bloqué par notre propre contrôle.</p>
 */
@DisplayName("Refus de l'opérateur")
class RefusOperateurTest {

    @Test
    @DisplayName("⚠️ le refus rend 422, jamais 503")
    void leRefusRend422() {
        // 503 dirait « réessayez plus tard ». Or réessayer à l'identique
        // donnera le même refus : le montant ne change pas tout seul.
        var refus = new ClientCampay.OperateurRefuse("Montant trop faible.");

        assertThat(refus.getStatut()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("une panne, elle, reste un 503")
    void lIndisponibiliteRend503() {
        // ⚠️ La distinction ne vaut que si les DEUX restent justes : ramener
        //    toute erreur à un 422 déplacerait simplement le mensonge.
        var panne = new ClientCampay.OperateurIndisponible("Injoignable.");

        assertThat(panne.getStatut()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("⚠️ aucun seuil de montant n'est écrit chez nous")
    void aucunSeuilNEstEcritChezNous() {
        // 🎯 LE TEST QUI EMPÊCHE LA RECHUTE.
        //
        //    Les seuils appartiennent à l'opérateur : ils varient par
        //    opérateur (Orange 10) et par environnement (démo : 25 maximum).
        //    Les recopier revient à figer une valeur qui ne nous appartient
        //    pas — et c'est exactement ce qui a bloqué un essai valide.
        //
        //    Ce test échoue si quelqu'un réintroduit une constante de seuil.
        assertThat(ClientCampay.class.getDeclaredFields())
                .as("aucun champ ne doit nommer un montant minimum ou maximum")
                .noneMatch(f -> {
                    String n = f.getName().toUpperCase(java.util.Locale.ROOT);
                    return n.contains("MONTANT_MIN") || n.contains("MONTANT_MAX");
                });
    }
}
