package com.garah.api.surveillance.domaine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garah.api.surveillance.infra.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Le journal des actions internes.
 *
 * <p>Le chapitre 01 distinguait <b>trois</b> journaux qui se ressemblent.
 * Voici la règle, une bonne fois :</p>
 *
 * <pre>
 * activite_client      ce que fait LE CLIENT          → comprendre son parcours
 * evenement_securite   faits d'AUTHENTIFICATION       → détecter une anomalie
 * audit_log            actions INTERNES sur données   → responsabilité
 * </pre>
 *
 * <p>Un même geste peut légitimement écrire dans deux d'entre eux. Ce qui
 * n'est pas légitime, c'est de tout mettre dans un seul : plus rien n'est
 * exploitable.</p>
 */
@Service
public class ServiceAudit {

    private final AuditLogRepository journal;
    private final ObjectMapper json;

    public ServiceAudit(AuditLogRepository journal, ObjectMapper json) {
        this.journal = journal;
        this.json = json;
    }

    /**
     * Enregistre une action interne.
     *
     * <p>{@code REQUIRES_NEW}, comme le journal de sécurité (chapitre 08 §9) :
     * <b>l'audit doit survivre à ce qu'il audite</b>. Si l'opération échoue
     * après coup, la trace de la tentative reste — et c'est souvent la trace
     * la plus intéressante.</p>
     *
     * <p>Le nom de l'acteur est <b>copié</b>, pas seulement référencé : un
     * audit qu'on efface en supprimant un utilisateur n'est pas un audit.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog enregistrer(Long utilisateurId, String acteurNom, String acteurEmail,
                                String action, String entite, Long entiteId,
                                Object avant, Object apres, String adresseIp) {
        return journal.save(new AuditLog(
                utilisateurId, acteurNom, acteurEmail, action, entite, entiteId,
                serialiser(avant), serialiser(apres), adresseIp));
    }

    /**
     * Variante pour un changement de champ unique — le cas le plus fréquent.
     *
     * <pre>
     * audit.changement(…, "PRIX_MODIFIER", "tarification", 42L,
     *                  "prixUnitaire", "15000.00", "16000.00", ip);
     * </pre>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog changement(Long utilisateurId, String acteurNom, String acteurEmail,
                               String action, String entite, Long entiteId,
                               String champ, Object avant, Object apres, String adresseIp) {
        return enregistrer(utilisateurId, acteurNom, acteurEmail, action, entite, entiteId,
                Map.of(champ, String.valueOf(avant)),
                Map.of(champ, String.valueOf(apres)),
                adresseIp);
    }

    /** L'historique d'un objet : « qui a touché à ce produit ? ». */
    @Transactional(readOnly = true)
    public List<AuditLog> historiqueDe(String entite, Long entiteId) {
        return journal.findByEntiteAndEntiteIdOrderByDateHeureDesc(entite, entiteId);
    }

    /** L'historique d'une personne : « qu'a fait ce responsable ? ». */
    @Transactional(readOnly = true)
    public Page<AuditLog> actionsDe(Long utilisateurId, Pageable pagination) {
        return journal.findByUtilisateurIdOrderByDateHeureDesc(utilisateurId, pagination);
    }

    /**
     * Le journal entier, filtré.
     *
     * <p>C'est la lecture du SuperAdmin : « qui a annulé cette commande ? »,
     * « qu'est-ce qui a bougé hier soir ? ». Les filtres se combinent et sont
     * tous facultatifs.</p>
     *
     * <p>⚠️ Une chaîne vide n'est pas un filtre : elle vient d'un champ de
     * formulaire qu'on a ouvert puis refermé. La traiter comme une valeur
     * ferait chercher les actions dont le code est « », c'est-à-dire aucune,
     * et l'écran répondrait « rien à afficher » sur un journal plein.</p>
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> rechercher(String action, String entite, Long utilisateurId,
                                     Pageable pagination) {
        return journal.rechercher(vide(action), vide(entite), utilisateurId, pagination);
    }

    /** Les valeurs réellement présentes — on ne propose pas un filtre qui ne rend rien. */
    @Transactional(readOnly = true)
    public List<String> actionsConnues() {
        return journal.actionsConnues();
    }

    @Transactional(readOnly = true)
    public List<String> entitesConnues() {
        return journal.entitesConnues();
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.strip();
    }

    /**
     * La même question, posée par un chef de service — et donc allégée.
     *
     * <p>⚠️ La réduction se fait <b>ici</b>, et pas à l'écran. Les clichés JSON
     * du journal peuvent porter n'importe quelle donnée du système ; les
     * laisser sortir en comptant sur l'interface pour ne pas les afficher
     * reviendrait à poser un rideau devant une fenêtre ouverte. Voir
     * {@link VueActivite}.</p>
     */
    @Transactional(readOnly = true)
    public Page<VueActivite> activiteDe(Long utilisateurId, Pageable pagination) {
        return journal.findByUtilisateurIdOrderByDateHeureDesc(utilisateurId, pagination)
                .map(VueActivite::de);
    }

    private String serialiser(Object valeur) {
        if (valeur == null) {
            return null;
        }
        try {
            return json.writeValueAsString(valeur);
        } catch (JsonProcessingException e) {
            return "{\"erreur\":\"serialisation\"}";
        }
    }
}
