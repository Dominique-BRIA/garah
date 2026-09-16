package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteVerifiee;
import com.garah.api.iam.domaine.VerificateurIdentiteSociale.JetonSocialInvalide;
import com.garah.api.iam.domaine.VerificateurParFournisseur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La vérification réelle d'un jeton OIDC, avec les clés publiques du
 * fournisseur.
 *
 * <p><b>Aucune dépendance nouvelle.</b> {@code spring-boot-starter-oauth2-
 * resource-server} est déjà au projet depuis le chapitre 08 pour valider
 * <i>nos</i> jetons ; il sait tout aussi bien valider ceux de Google.</p>
 *
 * <h2>Ce qui est vérifié, et pourquoi chaque contrôle compte</h2>
 *
 * <pre>
 * signature    contre le JWKS de l emetteur, telecharge et mis en cache
 *              → sans elle, un jeton se fabrique au clavier
 *
 * iss          l emetteur est bien celui qu on attendait
 *              → sinon un jeton signe par un AUTRE fournisseur passerait
 *
 * aud          le destinataire est NOTRE identifiant client
 *              → c est le controle qu on oublie, et le plus grave :
 *                sans lui, un jeton Google emis pour l application de
 *                QUELQU UN D AUTRE ouvre une session chez nous. N importe
 *                quel developpeur pourrait faire signer a Google un jeton
 *                portant l adresse de sa victime.
 *
 * exp          la date d expiration, verifiee par Nimbus
 * </pre>
 *
 * <p>📌 <b>Le même {@code aud} pour le web et pour Android.</b> Sur Android,
 * le jeton d'identité se demande avec l'identifiant du client <b>Web</b>
 * ({@code serverClientId}) ; le client Android, lui, ne sert qu'à prouver la
 * signature de l'APK. Les deux plateformes produisent donc des jetons portant
 * le même destinataire — une seule valeur à configurer ici.</p>
 */
@Component
public class VerificateurOidc implements VerificateurParFournisseur {

    private static final Logger log = LoggerFactory.getLogger(VerificateurOidc.class);

    /**
     * Les destinataires acceptés, par fournisseur.
     *
     * <p>Plusieurs valeurs sont permises, séparées par des virgules : iOS a
     * son propre identifiant client, et une migration d'identifiant impose
     * d'accepter l'ancien et le nouveau pendant quelques semaines.</p>
     */
    private final List<String> destinatairesGoogle;

    /**
     * Un décodeur par émetteur, fabriqué à la première demande.
     *
     * <p>⚠️ <b>Pas dans le constructeur.</b> {@code fromIssuerLocation} fait un
     * appel réseau : le faire au démarrage rendrait l'API incapable de
     * démarrer quand Google est injoignable — y compris pour servir le
     * catalogue, qui n'a rien à voir. Un démarrage ne doit jamais dépendre
     * d'un tiers.</p>
     *
     * <p>Le décodeur, lui, met le JWKS en cache et le renouvelle seul : un
     * jeton signé avec une clé fraîchement tournée ne provoque qu'un seul
     * appel supplémentaire.</p>
     */
    private final Map<FournisseurIdentite, JwtDecoder> decodeurs = new ConcurrentHashMap<>();

    public VerificateurOidc(
            @Value("${GARAH_GOOGLE_CLIENT_IDS:}") String destinatairesGoogle) {
        this.destinatairesGoogle = decouper(destinatairesGoogle);

        if (this.destinatairesGoogle.isEmpty()) {
            log.warn("GARAH_GOOGLE_CLIENT_IDS n est pas renseignee : "
                    + "« Continuer avec Google » refusera toutes les connexions.");
        }
    }

    @Override
    public FournisseurIdentite fournisseur() {
        return FournisseurIdentite.GOOGLE;
    }

    @Override
    public boolean estActif() {
        return !destinatairesGoogle.isEmpty();
    }

    @Override
    public IdentiteVerifiee verifier(String jeton) {
        FournisseurIdentite fournisseur = FournisseurIdentite.GOOGLE;

        Jwt verifie;
        try {
            verifie = decodeur(fournisseur).decode(jeton);
        } catch (JwtException e) {
            // En DEBUG, jamais en WARN : une connexion sociale qui échoue est
            // souvent un jeton expiré dans un onglet laissé ouvert. Le
            // journaliser comme une anomalie noierait les vraies.
            log.debug("Jeton {} refuse : {}", fournisseur, e.getMessage());
            throw new JetonSocialInvalide();
        }

        if (!destinatairesGoogle.contains(premierDestinataire(verifie))) {
            log.warn("Jeton {} valide mais emis pour une AUTRE application : {}",
                    fournisseur, premierDestinataire(verifie));
            throw new JetonSocialInvalide();
        }

        String sujet = verifie.getSubject();
        if (sujet == null || sujet.isBlank()) {
            throw new JetonSocialInvalide();
        }

        return new IdentiteVerifiee(
                fournisseur,
                sujet,
                verifie.getClaimAsString("email"),
                Boolean.TRUE.equals(verifie.getClaim("email_verified")),
                verifie.getClaimAsString("name"));
    }

    private JwtDecoder decodeur(FournisseurIdentite fournisseur) {
        return decodeurs.computeIfAbsent(fournisseur,
                f -> JwtDecoders.fromIssuerLocation(f.emetteur()));
    }

    private static String premierDestinataire(Jwt jeton) {
        List<String> destinataires = jeton.getAudience();
        return destinataires == null || destinataires.isEmpty() ? "" : destinataires.getFirst();
    }

    private static List<String> decouper(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return List.of();
        }
        return List.of(valeur.strip().split("\\s*,\\s*"));
    }
}
