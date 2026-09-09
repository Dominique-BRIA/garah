package com.garah.api.commun.audit;

import com.garah.api.commun.audit.GesteClient.TypeGesteClient;
import com.garah.api.commun.securite.ActeurCourant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Le parcours d'un client, écrit d'une ligne.
 *
 * <h2>🎯 Jumelle de {@link JournalActions}, et volontairement</h2>
 *
 * <p>Deux journaux, deux façades, la même façon d'écrire. Un service qui trace
 * une action interne appelle {@code journal.geste(…)} ; un service qui trace
 * un geste de client appelle {@code parcours.commande(…)}. Personne n'a à
 * savoir qu'il y a des événements derrière, ni lequel.</p>
 *
 * <p>⚠️ Les méthodes nomment le <b>geste</b>, pas le type technique. Écrire
 * {@code parcours.enregistrer(TypeGesteClient.COMMANDE, …)} à quinze endroits
 * aurait fini par mettre AJOUT_PANIER sur un paiement, et rien ne l'aurait
 * signalé : la contrainte de la base accepte les huit valeurs, pas la bonne.</p>
 */
@Component
public class JournalParcours {

    private final ApplicationEventPublisher evenements;
    private final ActeurCourant acteurs;

    public JournalParcours(ApplicationEventPublisher evenements, ActeurCourant acteurs) {
        this.evenements = evenements;
        this.acteurs = acteurs;
    }

    public void connexion() {
        publier(TypeGesteClient.CONNEXION);
    }

    public void deconnexion() {
        publier(TypeGesteClient.DECONNEXION);
    }

    public void ajoutAuPanier(Long varianteId, String designation, int quantite) {
        publier(TypeGesteClient.AJOUT_PANIER,
                "varianteId", varianteId, "article", designation, "quantite", quantite);
    }

    public void commande(Long commandeId, String numero, Object montant) {
        publier(TypeGesteClient.COMMANDE,
                "commandeId", commandeId, "numero", numero, "montant", montant);
    }

    public void annulation(Long commandeId, String numero, String motif) {
        publier(TypeGesteClient.ANNULATION,
                "commandeId", commandeId, "numero", numero, "motif", motif);
    }

    public void paiement(Long commandeId, String moyen, Object montant) {
        publier(TypeGesteClient.PAIEMENT,
                "commandeId", commandeId, "moyen", moyen, "montant", montant);
    }

    public void reclamation(Long reclamationId, String motif) {
        publier(TypeGesteClient.RECLAMATION,
                "reclamationId", reclamationId, "motif", motif);
    }

    /**
     * ⚠️ L'acteur est lu <b>ici</b>, au moment du geste — pas passé en
     * paramètre. Un identifiant de client transmis de main en main finit par
     * être celui d'un autre : c'est exactement ce que le contexte de sécurité
     * évite, et {@link JournalActions} tient le même raisonnement.
     */
    private void publier(TypeGesteClient type, Object... contexte) {
        evenements.publishEvent(GesteClient.de(acteurs.maintenant(), type, contexte));
    }
}
