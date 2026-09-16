package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteVerifiee;
import com.garah.api.iam.domaine.VerificateurIdentiteSociale.JetonSocialInvalide;
import com.garah.api.iam.domaine.VerificateurParFournisseur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * « Continuer avec TikTok ».
 *
 * <h2>⚠️ Le plus lourd des trois, et celui qui rapporte le moins</h2>
 *
 * <p>Il faut le dire avant d'aller plus loin : <b>TikTok ne donne aucune
 * adresse e-mail.</b> Le Login Kit rend un identifiant ({@code open_id}), un
 * pseudonyme et un avatar. Rien d'autre.</p>
 *
 * <p>Or GARAH a besoin d'une adresse confirmée pour commander (D-23). Un compte
 * ouvert par TikTok devra donc en fournir une, puis la confirmer — c'est-à-dire
 * <b>exactement l'étape que le bouton était censé supprimer</b>. Le gain se
 * réduit à ne pas choisir de mot de passe.</p>
 *
 * <h2>Et le flux est d'une autre nature</h2>
 *
 * <pre>
 * Google     le client rend un JETON D IDENTITE  → on verifie une signature
 * Facebook   le client rend un JETON D ACCES     → on interroge Meta
 * TikTok     le client rend un CODE              → on l ECHANGE d abord, avec
 *                                                  notre SECRET, puis on lit
 * </pre>
 *
 * <p>C'est pourquoi le champ transmis par le client s'appelle {@code jeton} de
 * façon générique : pour TikTok, il porte un <b>code d'autorisation</b>, à
 * usage unique et de courte durée.</p>
 *
 * <p>📌 <b>L'échange se fait ici, jamais sur le client.</b> Il exige le secret
 * de l'application : mis dans un APK, celui-ci se lit en quelques minutes avec
 * un décompilateur, et n'importe qui pourrait alors se faire passer pour
 * GARAH auprès de TikTok.</p>
 *
 * <h2>Ce que ça n'empêche pas</h2>
 *
 * <p>Le code est lié à notre {@code client_key} : TikTok refuse de l'échanger
 * si le couple ne correspond pas. C'est ce qui remplace, ici, le contrôle du
 * {@code aud} de Google et le {@code debug_token} de Facebook — le contrôle
 * existe, il est simplement porté par l'échange lui-même.</p>
 */
@Component
public class VerificateurTikTok implements VerificateurParFournisseur {

    private static final Logger log = LoggerFactory.getLogger(VerificateurTikTok.class);

    private final String cleClient;
    private final String secretClient;
    private final String redirection;
    private final RestClient http;

    public VerificateurTikTok(
            @Value("${GARAH_TIKTOK_CLIENT_KEY:}") String cleClient,
            @Value("${GARAH_TIKTOK_CLIENT_SECRET:}") String secretClient,
            @Value("${GARAH_TIKTOK_REDIRECT_URI:}") String redirection,
            @Value("${GARAH_TIKTOK_API:https://open.tiktokapis.com/v2}") String base) {

        this.cleClient = cleClient == null ? "" : cleClient.strip();
        this.secretClient = secretClient == null ? "" : secretClient.strip();
        this.redirection = redirection == null ? "" : redirection.strip();
        this.http = RestClient.builder().baseUrl(base).build();

        if (!estActif()) {
            log.warn("TikTok non configure (GARAH_TIKTOK_CLIENT_KEY / _CLIENT_SECRET) : "
                    + "« Continuer avec TikTok » ne sera pas propose.");
        }
    }

    @Override
    public FournisseurIdentite fournisseur() {
        return FournisseurIdentite.TIKTOK;
    }

    @Override
    public boolean estActif() {
        return !cleClient.isEmpty() && !secretClient.isEmpty();
    }

    @Override
    public IdentiteVerifiee verifier(String codeAutorisation) {
        String jetonAcces = echanger(codeAutorisation);
        return lireLeProfil(jetonAcces);
    }

    /**
     * Échange le code contre un jeton d'accès.
     *
     * <p>⚠️ Le code est à <b>usage unique</b> : un échec ici ne se réessaie
     * pas avec le même code. L'écran doit relancer l'autorisation depuis le
     * début, pas proposer « réessayer ».</p>
     */
    private String echanger(String codeAutorisation) {
        MultiValueMap<String, String> formulaire = new LinkedMultiValueMap<>();
        formulaire.add("client_key", cleClient);
        formulaire.add("client_secret", secretClient);
        formulaire.add("code", codeAutorisation);
        formulaire.add("grant_type", "authorization_code");
        if (!redirection.isEmpty()) {
            formulaire.add("redirect_uri", redirection);
        }

        Map<?, ?> reponse;
        try {
            reponse = http.post()
                    .uri("/oauth/token/")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formulaire)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.debug("Echange du code TikTok en echec : {}", e.getMessage());
            throw new JetonSocialInvalide();
        }

        Object jeton = reponse == null ? null : reponse.get("access_token");
        if (jeton == null || String.valueOf(jeton).isBlank()) {
            throw new JetonSocialInvalide();
        }

        return String.valueOf(jeton);
    }

    private IdentiteVerifiee lireLeProfil(String jetonAcces) {
        Map<?, ?> reponse;
        try {
            reponse = http.get()
                    .uri(builder -> builder.path("/user/info/")
                            .queryParam("fields", "open_id,display_name")
                            .build())
                    .header("Authorization", "Bearer " + jetonAcces)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.debug("Lecture du profil TikTok en echec : {}", e.getMessage());
            throw new JetonSocialInvalide();
        }

        // La réponse est imbriquée : { data: { user: { open_id, display_name } } }
        Object niveauData = reponse == null ? null : reponse.get("data");
        Object niveauUser = niveauData instanceof Map<?, ?> d ? d.get("user") : null;

        if (!(niveauUser instanceof Map<?, ?> utilisateur)) {
            throw new JetonSocialInvalide();
        }

        Object sujet = utilisateur.get("open_id");
        if (sujet == null || String.valueOf(sujet).isBlank()) {
            throw new JetonSocialInvalide();
        }

        return new IdentiteVerifiee(
                FournisseurIdentite.TIKTOK,
                String.valueOf(sujet),
                // ⚠️ null, TOUJOURS. TikTok ne fournit aucune adresse.
                //
                //    Le service lèvera donc ADRESSE_INDISPONIBLE (422), et
                //    c'est le comportement voulu tant que l'écran « complétez
                //    votre profil » n'existe pas : mieux vaut un refus clair
                //    qu'un compte qu'on ne pourra jamais joindre.
                null,
                false,
                utilisateur.get("display_name") == null
                        ? null : String.valueOf(utilisateur.get("display_name")));
    }
}
