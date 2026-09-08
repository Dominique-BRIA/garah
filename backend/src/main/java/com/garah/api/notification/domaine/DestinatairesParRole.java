package com.garah.api.notification.domaine;

import com.garah.api.notification.infra.DestinataireRoleRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Qui prévenir, quand le message s'adresse à un RÔLE.
 *
 * <h2>🎯 « Une conversation attend » ne s'adresse à personne en particulier</h2>
 *
 * <p>Elle s'adresse à quiconque peut la prendre en charge. Dans ce projet, un
 * rôle n'existe pas comme tel : ce qui existe, ce sont des <b>permissions</b>,
 * accordées par des catégories. « Ceux qui peuvent prendre une conversation »
 * se traduit donc par « ceux qui ont {@code CONVERSATION_PRENDRE} ».</p>
 *
 * <p>C'est plus juste qu'une liste de destinataires écrite à la main : le jour
 * où l'on crée une catégorie « Superviseur SAV », elle reçoit les signaux sans
 * qu'aucun code ne change.</p>
 *
 * <h2>⚠️ Les exceptions individuelles sont respectées</h2>
 *
 * <p>Le référentiel permet de <b>retirer</b> une permission à quelqu'un que sa
 * catégorie lui donne. Ignorer ces exceptions enverrait le signal à un agent à
 * qui l'on a précisément retiré le droit d'y répondre.</p>
 */
@Component
public class DestinatairesParRole {

    private final DestinataireRoleRepository destinataires;

    public DestinatairesParRole(DestinataireRoleRepository destinataires) {
        this.destinataires = destinataires;
    }

    /**
     * Les responsables ACTIFS qui détiennent réellement cette permission.
     *
     * <p>Une requête, quel que soit le nombre de destinataires. Résoudre les
     * permissions un par un donnerait une requête par membre de l'équipe, à
     * chaque conversation ouverte.</p>
     */
    @Transactional(readOnly = true)
    public List<Long> ayantLaPermission(String code) {
        return destinataires.ayantLaPermission(code);
    }
}
