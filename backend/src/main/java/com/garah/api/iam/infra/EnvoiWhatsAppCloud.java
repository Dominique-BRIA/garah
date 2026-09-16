package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.EnvoiWhatsApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * L'envoi réel, par l'API WhatsApp Business Cloud de Meta.
 *
 * <h2>Ce qu'il faut avoir obtenu avant que cette classe serve à quoi que ce soit</h2>
 *
 * <pre>
 * 1. un compte Meta Business VERIFIE        documents legaux, plusieurs semaines
 * 2. un numero dedie                        inutilisable dans WhatsApp normal
 * 3. un modele de message APPROUVE          categorie « authentification »
 * </pre>
 *
 * <p>⚠️ <b>C'est la vérification d'entreprise qui est le vrai délai</b>, pas le
 * code. Tant qu'elle n'est pas obtenue, {@link #estActif()} vaut faux et le
 * bouton n'apparaît nulle part — l'application fonctionne exactement comme
 * avant.</p>
 *
 * <h2>Sur la gratuité</h2>
 *
 * <p>L'API n'a pas de frais de plateforme. Mais Meta facture les messages de
 * catégorie <b>authentification</b> à l'unité dans la plupart des marchés. Le
 * tarif applicable au Cameroun et à la RCA est à vérifier sur leur grille :
 * <b>un coût par connexion n'est pas un coût par client</b>, et quelqu'un qui
 * se connecte depuis trois appareils paie trois fois.</p>
 *
 * <p>C'est aussi pourquoi la limitation de débit n'est pas qu'une mesure de
 * sécurité ici : sans elle, un script qui demande des codes en boucle produit
 * une facture.</p>
 */
@Component
public class EnvoiWhatsAppCloud implements EnvoiWhatsApp {

    private static final Logger log = LoggerFactory.getLogger(EnvoiWhatsAppCloud.class);

    private final String identifiantNumero;
    private final String jeton;
    private final String modele;
    private final String langue;
    private final RestClient http;

    public EnvoiWhatsAppCloud(
            @Value("${GARAH_WHATSAPP_PHONE_NUMBER_ID:}") String identifiantNumero,
            @Value("${GARAH_WHATSAPP_TOKEN:}") String jeton,
            @Value("${GARAH_WHATSAPP_TEMPLATE:garah_code_connexion}") String modele,
            @Value("${GARAH_WHATSAPP_TEMPLATE_LANG:fr}") String langue,
            @Value("${GARAH_WHATSAPP_API:https://graph.facebook.com/v21.0}") String base) {

        this.identifiantNumero = identifiantNumero == null ? "" : identifiantNumero.strip();
        this.jeton = jeton == null ? "" : jeton.strip();
        this.modele = modele;
        this.langue = langue;
        this.http = RestClient.builder().baseUrl(base).build();

        if (!estActif()) {
            log.warn("WhatsApp non configure (GARAH_WHATSAPP_PHONE_NUMBER_ID / _TOKEN) : "
                    + "« Continuer avec WhatsApp » ne sera pas propose.");
        }
    }

    @Override
    public boolean estActif() {
        return !identifiantNumero.isEmpty() && !jeton.isEmpty();
    }

    @Override
    public boolean envoyerLeCode(String telephone, String code) {
        if (!estActif()) {
            return false;
        }

        try {
            http.post()
                    .uri("/{numero}/messages", identifiantNumero)
                    .header("Authorization", "Bearer " + jeton)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(corps(telephone, code))
                    .retrieve()
                    .toBodilessEntity();

            return true;
        } catch (RuntimeException e) {
            // ⚠️ On journalise le NUMERO et l'erreur, jamais le CODE.
            //    Un code en clair dans les journaux vaut un mot de passe en
            //    clair : les journaux se relisent, s'exportent, et vivent plus
            //    longtemps que les cinq minutes du code.
            log.error("Envoi WhatsApp vers {} echoue : {}", telephone, e.getMessage());
            return false;
        }
    }

    /**
     * Le corps d'un message de modèle « authentification ».
     *
     * <p>Meta impose deux composants pour cette catégorie : le corps, qui porte
     * le code, et le bouton de copie — qui porte <b>le même code</b>. C'est le
     * bouton « Copier le code » que voit l'utilisateur, et l'omettre fait
     * échouer l'envoi avec une erreur peu parlante.</p>
     *
     * <p>Le numéro part <b>sans le {@code +}</b> : l'API l'attend ainsi.</p>
     */
    private Map<String, Object> corps(String telephone, String code) {
        Map<String, Object> parametre = Map.of("type", "text", "text", code);

        return Map.of(
                "messaging_product", "whatsapp",
                "to", telephone.replace("+", ""),
                "type", "template",
                "template", Map.of(
                        "name", modele,
                        "language", Map.of("code", langue),
                        "components", List.of(
                                Map.of("type", "body",
                                        "parameters", List.of(parametre)),
                                Map.of("type", "button",
                                        "sub_type", "url",
                                        "index", "0",
                                        "parameters", List.of(parametre)))));
    }
}
