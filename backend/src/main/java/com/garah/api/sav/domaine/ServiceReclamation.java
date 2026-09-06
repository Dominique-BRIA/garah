package com.garah.api.sav.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.sav.infra.ReclamationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.List;

/**
 * Les réclamations.
 *
 * <p>C'est la <b>voie de recours</b> du client. Il ne peut pas annuler seul
 * une commande payée (D-12), ni décider d'un retour : il réclame, et un humain
 * tranche.</p>
 */
@Service
public class ServiceReclamation {

    private final ReclamationRepository reclamations;

    public ServiceReclamation(ReclamationRepository reclamations) {
        this.reclamations = reclamations;
    }

    @Transactional
    public Reclamation ouvrir(Long clientId, Long commandeId, String motif, String description) {
        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Une réclamation doit indiquer un motif.");
        }

        return reclamations.save(new Reclamation(
                genererNumero(), clientId, commandeId, motif, description));
    }

    /**
     * Un responsable s'attribue la réclamation.
     *
     * <p>Même logique que les conversations : sans attribution explicite, une
     * réclamation reste sans propriétaire — et c'est exactement comme ça
     * qu'un dossier reste sans réponse pendant trois semaines.</p>
     */
    @Transactional
    public Reclamation prendreEnCharge(Long reclamationId, Long responsableId) {
        Reclamation reclamation = charger(reclamationId);

        if (reclamation.getStatut() != StatutReclamation.OUVERTE) {
            throw new ConflitEtat("RECLAMATION_DEJA_PRISE",
                    "Cette réclamation est déjà prise en charge.");
        }

        reclamation.prendreEnCharge(responsableId);
        return reclamation;
    }

    /**
     * Clôture avec une décision.
     *
     * <p>La contrainte {@code reclamation_resolution_coherente} exige une date
     * de résolution dès que le statut est {@code RESOLUE} ou {@code FERMEE} :
     * une réclamation « résolue » sans date de résolution ne permettrait pas
     * de mesurer les délais de traitement (§20).</p>
     */
    @Transactional
    public Reclamation resoudre(Long reclamationId, boolean favorable) {
        Reclamation reclamation = charger(reclamationId);

        if (reclamation.getStatut() == StatutReclamation.RESOLUE
                || reclamation.getStatut() == StatutReclamation.FERMEE) {
            throw new ConflitEtat("RECLAMATION_CLOSE",
                    "Cette réclamation est déjà close.");
        }

        reclamation.cloturer(favorable ? StatutReclamation.RESOLUE : StatutReclamation.FERMEE);
        return reclamation;
    }

    @Transactional(readOnly = true)
    public List<Reclamation> aTraiter() {
        return reclamations.findByStatutOrderByDateCreationAsc(StatutReclamation.OUVERTE);
    }

    @Transactional(readOnly = true)
    public Page<Reclamation> mesReclamations(Long clientId, Pageable pagination) {
        return reclamations.findByClientIdOrderByDateCreationDesc(clientId, pagination);
    }

    private String genererNumero() {
        return "REC-%d-%06d".formatted(Year.now().getValue(), reclamations.prochainNumero());
    }

    private Reclamation charger(Long reclamationId) {
        return reclamations.findById(reclamationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Réclamation", reclamationId));
    }
}
