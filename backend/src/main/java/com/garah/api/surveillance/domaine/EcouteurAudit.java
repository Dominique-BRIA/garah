package com.garah.api.surveillance.domaine;

import com.garah.api.commun.audit.ActionAuditee;
import com.garah.api.commun.securite.Acteur;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Le seul endroit qui écrit dans le journal des actions.
 *
 * <p>Les modules annoncent ce qu'ils font ({@code ActionAuditee}) ; celui-ci
 * écoute et écrit. Aucun d'eux ne sait qu'un journal existe — c'est ce qui
 * permet d'en changer, ou d'en ajouter un second, sans toucher à quinze
 * services.</p>
 *
 * <h2>⚠️ Synchrone, et c'est délibéré</h2>
 *
 * <p>{@link EventListener} s'exécute dans le fil de l'appelant. Un
 * {@code @Async} rendrait le journal <b>silencieusement</b> incomplet : la
 * file se remplit, les lignes sont écartées, et personne ne l'apprend avant le
 * jour où on cherche une trace qui n'a jamais été écrite.</p>
 *
 * <h2>⚠️ Ce que coûte l'écriture</h2>
 *
 * <p>{@code ServiceAudit.enregistrer} ouvre sa <b>propre</b> transaction —
 * c'est ce qui fait survivre la trace à l'annulation de ce qu'elle décrit.
 * Conséquence directe : une action auditée tient <b>deux</b> connexions
 * pendant l'écriture, la sienne et celle du journal. Le pool
 * ({@code GARAH_DB_POOL_MAX}, huit par défaut) doit garder cette marge ; le
 * réduire à la moitié des actions simultanées ferait attendre les écritures de
 * journal derrière les transactions qu'elles doivent journaliser.</p>
 *
 * <h2>⚠️ Une seule chose est rattrapée, et elle n'efface pas la trace</h2>
 *
 * <p>Un {@code catch} général rendrait le journal muet le jour où il tombe, et
 * l'application continuerait à servir comme si tout était tracé. Un journal
 * qui échoue en silence est pire qu'un journal absent : on compte dessus.</p>
 *
 * <p>C'est pourquoi ce qui pourrait échouer est écarté <b>en amont</b> :
 * l'adresse IP est filtrée avant d'arriver ici, et les clichés sont des
 * dictionnaires de texte, jamais des entités. La seule exception est le
 * <b>lien</b> vers le compte de l'acteur — voir plus bas : on réécrit la ligne
 * sans lui plutôt que de laisser une clé étrangère empêcher une vente.</p>
 */
@Component
public class EcouteurAudit {

    private final ServiceAudit audit;

    public EcouteurAudit(ServiceAudit audit) {
        this.audit = audit;
    }

    @EventListener
    public void surActionAuditee(ActionAuditee evenement) {
        Acteur acteur = evenement.acteur();

        // 🎯 Le journal d'audit répond à « qui, CHEZ NOUS, a touché à cette
        //    donnée ? ». Le parcours d'un client est une autre question, qui a
        //    son propre journal. Les mélanger, c'est perdre les deux : les
        //    quelques gestes internes de la journée disparaîtraient sous des
        //    milliers de lignes de navigation.
        if (!acteur.estInterne()) {
            return;
        }

        try {
            audit.enregistrer(acteur.id(), acteur.nom(), acteur.email(),
                    evenement.action(), evenement.entite(), evenement.entiteId(),
                    evenement.avant(), evenement.apres(), acteur.adresseIp());
        } catch (DataIntegrityViolationException lienRompu) {
            // ⚠️ LE SEUL RATTRAPAGE, ET IL N'EFFACE RIEN.
            //
            //    `utilisateur_id` référence `utilisateur`. Un jeton dont le
            //    sujet ne désigne plus personne — un compte retiré par une
            //    reprise de données, un jeton d'un environnement voisin — fait
            //    échouer l'insertion sur la clé étrangère. Sans ce rattrapage,
            //    l'échec remonterait dans l'action journalisée : une ligne de
            //    journal impossible à écrire empêcherait une vente.
            //
            //    On réécrit donc SANS LE LIEN. Ce n'est pas une perte : le nom
            //    et l'adresse sont recopiés depuis le début, précisément pour
            //    que la trace survive à la disparition du compte — c'est la
            //    même raison qui a fait choisir ON DELETE SET NULL plutôt que
            //    CASCADE. La ligne dit toujours qui a agi.
            //
            //    ⚠️ Une transaction distincte est indispensable : la première a
            //       été annulée par la violation, et rien ne s'y écrirait plus.
            //       `enregistrer` est REQUIRES_NEW, donc le second appel en
            //       ouvre une neuve.
            audit.enregistrer(null, acteur.nom(), acteur.email(),
                    evenement.action(), evenement.entite(), evenement.entiteId(),
                    evenement.avant(), evenement.apres(), acteur.adresseIp());
        }
    }
}
