package com.garah.api.commerce.domaine;

import com.garah.api.logistique.domaine.EvenementsExpedition;
import com.garah.api.logistique.domaine.ServiceExpedition;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Le statut d'une commande suit ses colis.
 *
 * <h2>🎯 Le défaut que ceci ferme</h2>
 *
 * <p>La même chose s'enregistrait à DEUX endroits sans lien : l'agent
 * enregistrait le départ du colis dans l'expédition, puis quelqu'un devait
 * aller cliquer « Expédiée » sur la commande. Personne n'y pensait, et le
 * client lisait « Payée » pendant que son colis roulait, arrivait, et même
 * après qu'il l'avait emporté.</p>
 *
 * <p>C'est la règle du projet : <b>ce qui est engendré n'est jamais
 * saisi</b>. Le statut d'une commande découle de ce qui est arrivé à ses
 * colis.</p>
 *
 * <h2>⚠️ Synchrone, dans la transaction du fait logistique</h2>
 *
 * <p>Un {@code @EventListener} ordinaire, et non après validation : le départ
 * et « la commande est en route » sont vrais ensemble, et s'écrivent
 * ensemble.</p>
 */
@Component
public class SuiviLogistiqueDesCommandes {

    private final ServiceExpedition expeditions;
    private final ServiceCommande commandes;

    public SuiviLogistiqueDesCommandes(ServiceExpedition expeditions, ServiceCommande commandes) {
        this.expeditions = expeditions;
        this.commandes = commandes;
    }

    @EventListener
    public void surAvancement(EvenementsExpedition.LogistiqueAvancee e) {
        commandes.suivreLaLogistique(e.commandeId(), expeditions.avancement(e.commandeId()));
    }
}
