package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import org.springframework.http.HttpStatus;

/**
 * Vérifie un jeton d'identité et en extrait la personne.
 *
 * <p><b>Pourquoi une interface.</b> La vérification réelle interroge le
 * fournisseur pour obtenir ses clés publiques — un appel réseau. Une interface
 * permet aux tests de substituer une identité connue sans sortir de la
 * machine : sans cela, la suite dépendrait de la disponibilité de Google et
 * deviendrait <i>intermittente</i>, ce que le chapitre 20 désigne comme le
 * pire type d'échec.</p>
 */
public interface VerificateurIdentiteSociale {

    /**
     * @throws JetonSocialInvalide si la signature, l'émetteur, le destinataire
     *                             ou la date d'expiration ne conviennent pas
     */
    IdentiteVerifiee verifier(FournisseurIdentite fournisseur, String jeton);

    /**
     * Le jeton ne prouve rien.
     *
     * <p>Volontairement <b>un seul</b> message pour toutes les causes —
     * signature fausse, jeton expiré, destinataire inattendu. Détailler
     * renseignerait un attaquant sur ce qui lui manque pour en forger un
     * valable. C'est le même raisonnement que {@code IdentifiantsInvalides}
     * pour le formulaire de connexion.</p>
     */
    class JetonSocialInvalide extends ErreurMetier {

        public JetonSocialInvalide() {
            super("JETON_SOCIAL_INVALIDE",
                    "Cette connexion n'a pas pu être vérifiée. Réessayez.");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNAUTHORIZED;
        }
    }
}
