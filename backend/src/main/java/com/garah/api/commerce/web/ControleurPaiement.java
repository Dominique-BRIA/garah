package com.garah.api.commerce.web;

import com.garah.api.commerce.domaine.MoyenPaiement;
import com.garah.api.commerce.domaine.StatutPaiement;
import com.garah.api.commerce.domaine.TypePaiement;
import com.garah.api.commerce.domaine.ServicePaiement;
import com.garah.api.commerce.domaine.ResumePaiement;
import com.garah.api.commerce.domaine.ServicePaiementMobile;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Les routes du paiement — y compris le webhook de l'opérateur.
 *
 * <p>C'est la seule porte par laquelle une commande peut devenir
 * {@code PAYEE}. Sans elle, tout le domaine commerce est écrit, testé, et
 * inatteignable.</p>
 */
@RestController
@RequestMapping("/api/paiements")
public class ControleurPaiement {

    private static final Logger log = LoggerFactory.getLogger(ControleurPaiement.class);

    private final ServicePaiementMobile mobile;
    private final ServicePaiement paiements;

    /**
     * Clé de vérification des notifications entrantes.
     *
     * <p>Vide = aucune vérification de signature. Voir
     * {@link #notificationCampay} pour ce que cela change réellement —
     * beaucoup moins qu'on ne le croit.</p>
     */
    private final String cleWebhook;

    /**
     * Refuser une notification dont la signature ne se vérifie pas.
     *
     * <p><b>Faux par défaut, et c'est délibéré.</b> Le format exact de la
     * signature Campay n'est pas documenté publiquement. Activer le refus sans
     * l'avoir vérifié transformerait une inconnue en panne totale : toutes les
     * notifications seraient rejetées, et plus aucune commande ne serait payée
     * — sans que rien ne le signale, puisqu'un webhook refusé ne produit pas
     * d'erreur visible côté client.</p>
     *
     * <p>À passer à {@code true} une fois le format confirmé avec Campay, en
     * observant les journaux : ils disent, à chaque notification, si la
     * signature se vérifie.</p>
     */
    private final boolean signatureObligatoire;

    public ControleurPaiement(ServicePaiementMobile mobile,
                              ServicePaiement paiements,
                              @Value("${GARAH_CAMPAY_WEBHOOK_KEY:}") String cleWebhook,
                              @Value("${GARAH_CAMPAY_WEBHOOK_STRICT:false}") boolean strict) {
        this.mobile = mobile;
        this.paiements = paiements;
        this.cleWebhook = cleWebhook == null ? "" : cleWebhook.strip();
        this.signatureObligatoire = strict;
    }

    // -------------------------------------------------------------------------
    // Le client paie
    // -------------------------------------------------------------------------

    /**
     * Démarre un paiement mobile money.
     *
     * <p>Aucune permission n'est exigée : un client n'a pas de droits, son
     * accès repose sur la <b>propriété</b> de ses données. C'est le service qui
     * vérifie que la commande lui appartient.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ServicePaiementMobile.DemandePaiement payer(@Valid @RequestBody DemandePaiementMobile demande,
                                                       @AuthenticationPrincipal Jwt jeton) {
        return mobile.demander(demande.commandeId(), identite(jeton),
                demande.moyen(), demande.telephone());
    }

    /** L'état d'un paiement. Réservé au propriétaire de la commande. */
    @GetMapping("/{id}")
    public ServicePaiementMobile.DemandePaiement etat(@PathVariable Long id,
                                                      @AuthenticationPrincipal Jwt jeton) {
        return mobile.etat(id, identite(jeton));
    }

    /**
     * « Où en est mon paiement ? » — redemande à l'opérateur, tout de suite.
     *
     * <p>L'écran de suivi tourne, le client s'impatiente. Plutôt que d'attendre
     * la réconciliation périodique, on interroge Campay à la demande.</p>
     */
    @PostMapping("/{id}/verification")
    public ServicePaiementMobile.DemandePaiement verifier(@PathVariable Long id,
                                                          @AuthenticationPrincipal Jwt jeton) {
        return mobile.verifier(id, identite(jeton));
    }

    // -------------------------------------------------------------------------
    // L'opérateur notifie
    // -------------------------------------------------------------------------

    /**
     * Le webhook Campay. <b>Route publique — elle doit l'être.</b>
     *
     * <h2>Ce que cette route croit, et ce qu'elle ne croit pas</h2>
     *
     * <p>Elle ne retient <b>qu'un seul champ</b> de la notification : la
     * référence de transaction. Le statut, le montant, l'opérateur annoncés
     * sont ignorés. {@code ServicePaiementMobile} redemande ensuite l'état
     * réel à Campay, sur une connexion que nous ouvrons.</p>
     *
     * <pre>
     * ce que la notification apporte   « la transaction ABC a bougé »
     * ce qui décide                    GET /transaction/ABC/ chez Campay
     * </pre>
     *
     * <p>🎯 <b>Conséquence : forger une notification ne sert à rien.</b> Un
     * inconnu qui poste ici la référence de son choix déclenche une question
     * dont il ne contrôle pas la réponse. Il ne peut ni déclarer une commande
     * payée, ni modifier un montant.</p>
     *
     * <h2>Toujours 200</h2>
     *
     * <p>Même sur référence inconnue. Un opérateur qui reçoit autre chose que
     * {@code 200} <b>rejoue</b> sa notification, indéfiniment. Répondre 404 à
     * une référence inconnue nous ferait donc marteler par des notifications
     * qu'on ne saura jamais traiter.</p>
     */
    @PostMapping("/notifications/campay")
    public ResponseEntity<Map<String, Object>> notificationCampay(@RequestBody Map<String, Object> corps) {

        boolean signatureValide = verifierSignature(corps);

        if (!signatureValide && signatureObligatoire) {
            log.warn("Notification Campay refusee : signature invalide (mode strict).");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("recu", false, "motif", "SIGNATURE_INVALIDE"));
        }

        String reference = valeur(corps, "reference");
        boolean traite = mobile.traiterNotification(reference);

        log.info("Notification Campay reference={} signature={} traitee={}",
                reference, signatureValide ? "ok" : "absente/invalide", traite);

        // 200 dans tous les cas : voir la javadoc.
        return ResponseEntity.ok(Map.of("recu", true, "traite", traite));
    }

    /**
     * Vérifie la signature, quand il y en a une et qu'on a la clé.
     *
     * <p>Campay place un JWT dans le champ {@code signature}. On le vérifie en
     * HS256 avec {@code GARAH_CAMPAY_WEBHOOK_KEY}.</p>
     *
     * <p>⚠️ <b>Cette vérification est une défense supplémentaire, pas le socle
     * de sécurité.</b> Le socle, c'est de redemander l'état à Campay. C'est
     * pour cette raison qu'un échec ici n'est pas fatal par défaut : se tromper
     * d'algorithme ferait tomber les paiements sans rien protéger de plus.</p>
     */
    private boolean verifierSignature(Map<String, Object> corps) {
        String signature = valeur(corps, "signature");

        if (cleWebhook.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }

        try {
            JWSVerifier verificateur =
                    new MACVerifier(cleWebhook.getBytes(StandardCharsets.UTF_8));
            return SignedJWT.parse(signature).verify(verificateur);
        } catch (Exception e) {
            // Volontairement large : une signature illisible n'est pas un bug
            // de notre côté, c'est une notification qu'on n'authentifie pas.
            log.debug("Signature Campay non verifiable : {}", e.getClass().getSimpleName());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Le back-office rembourse
    // -------------------------------------------------------------------------

    /**
     * Rembourse tout ou partie d'une commande.
     *
     * <p>Jamais accessible au client : D-12 ferme l'annulation après paiement,
     * et sa voie de recours est la réclamation. Un remboursement est une
     * décision humaine.</p>
     */
    @PostMapping("/remboursements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAIEMENT_REMBOURSER')")
    public ResumePaiement rembourser(@Valid @RequestBody DemandeRemboursement demande) {
        return paiements.rembourserEtResumer(
                demande.commandeId(), demande.montant(), demande.moyen(),
                demande.origineType(), demande.origineId());
    }

    /**
     * La liste du back-office : encaissements et remboursements.
     *
     * <p>⚠️ Cette route est <b>séparée</b> de {@code GET /api/paiements/{id}},
     * qui est réservée au propriétaire de la commande. Deux publics, deux
     * règles d'accès, deux routes : la règle devient une annotation qu'on lit
     * d'un coup d'œil au lieu d'une branche de code.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PAIEMENT_CONSULTER')")
    public Page<ResumePaiement> lister(
            @RequestParam(required = false) StatutPaiement statut,
            @RequestParam(required = false) TypePaiement type,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return paiements.administration(statut, type, recherche,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    /**
     * Tout ce qui s'est passé sur l'argent d'une commande.
     *
     * <p>Encaissements et remboursements ensemble : un remboursement n'a de
     * sens qu'en regard de l'encaissement qu'il défait.</p>
     */
    @GetMapping("/commandes/{commandeId}")
    @PreAuthorize("hasAuthority('PAIEMENT_CONSULTER')")
    public List<ResumePaiement> pourCommande(@PathVariable Long commandeId) {
        return paiements.pourCommande(commandeId);
    }

    /** Le reste dû sur une commande — pour le back-office. */
    @GetMapping("/commandes/{commandeId}/reste-a-payer")
    @PreAuthorize("hasAuthority('PAIEMENT_CONSULTER')")
    public Map<String, BigDecimal> resteAPayer(@PathVariable Long commandeId) {
        return Map.of("resteAPayer", paiements.resteAPayer(commandeId));
    }

    // -------------------------------------------------------------------------

    /**
     * L'identifiant de l'appelant, lu dans le jeton.
     *
     * <p>⚠️ <b>Jamais depuis le corps de la requête.</b> Un {@code clientId}
     * envoyé par le client permettrait de payer — et surtout de consulter — la
     * commande de n'importe qui. Le sujet du jeton est signé, lui.</p>
     */
    private static Long identite(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    private static String valeur(Map<String, Object> corps, String cle) {
        Object v = corps.get(cle);
        return v == null ? null : String.valueOf(v);
    }

    /** Ce que le client envoie pour payer. */
    public record DemandePaiementMobile(
            @NotNull(message = "La commande est obligatoire.")
            Long commandeId,

            @NotNull(message = "Le moyen de paiement est obligatoire.")
            MoyenPaiement moyen,

            @NotBlank(message = "Le numéro de téléphone est obligatoire.")
            @Size(max = 30, message = "Numéro trop long.")
            String telephone) {
    }

    /** Ce que le back-office envoie pour rembourser. */
    public record DemandeRemboursement(
            @NotNull(message = "La commande est obligatoire.")
            Long commandeId,

            @NotNull(message = "Le montant est obligatoire.")
            @DecimalMin(value = "1", message = "Le montant doit être positif.")
            BigDecimal montant,

            @NotNull(message = "Le moyen de remboursement est obligatoire.")
            MoyenPaiement moyen,

            /*
             * Obligatoire : la contrainte paiement_remboursement_justifie le
             * refuserait de toute façon. On le dit ici pour produire un message
             * clair plutôt qu'une erreur d'intégrité.
             */
            @NotBlank(message = "Le motif du remboursement est obligatoire.")
            @Pattern(regexp = "RETOUR|RECLAMATION",
                    message = "Le motif doit être RETOUR ou RECLAMATION.")
            String origineType,

            @NotNull(message = "La référence du motif est obligatoire.")
            Long origineId) {
    }
}
