package com.garah.api.notification.infra;

import com.garah.api.notification.domaine.AppareilNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Qui détient réellement une permission.
 *
 * <h2>🎯 C'est la requête de {@code permissionsEffectives}, retournée</h2>
 *
 * <p>Celle du référentiel répond à « que peut cette personne ? ». Celle-ci
 * répond à « qui peut ceci ? » — la question qu'on se pose pour adresser un
 * signal à un rôle. Boucler sur l'équipe en appelant la première donnerait une
 * requête par membre, à chaque conversation ouverte.</p>
 *
 * <h2>⚠️ Le retrait s'applique à la FIN, sur l'union complète</h2>
 *
 * <p>C'est le même piège que dans le référentiel : soustraire catégorie par
 * catégorie laisserait une seconde catégorie redonner un droit qu'on venait
 * de retirer. Ici, la conséquence serait d'envoyer le signal à quelqu'un à qui
 * l'on a précisément retiré le droit d'y répondre.</p>
 *
 * <h2>⚠️ Les responsables INACTIFS sont exclus</h2>
 *
 * <p>Un compte désactivé garde ses catégories — « désactiver, jamais
 * supprimer ». Les notifier ferait vibrer le téléphone d'une personne qui a
 * quitté l'entreprise, et compterait un destinataire de plus dans un signal
 * d'équipe que personne ne traitera.</p>
 *
 * <p>Il hérite de {@code JpaRepository<AppareilNotification, String>} faute de
 * mieux : Spring Data exige une entité racine, et ce module n'a pas le droit
 * de nommer {@code Responsable} comme telle. Seule la requête ci-dessous
 * compte.</p>
 */
public interface DestinataireRoleRepository
        extends JpaRepository<AppareilNotification, String> {

    @Query(value = """
            WITH depuis_categories AS (
                SELECT DISTINCT rc.responsable_id, ccu.cas_utilisation_id
                  FROM responsable_categorie rc
                  JOIN categorie_cas_utilisation ccu ON ccu.categorie_id = rc.categorie_id
            ),
            ajouts AS (
                SELECT responsable_id, cas_utilisation_id
                  FROM responsable_cas_utilisation WHERE type = 'ADD'
            ),
            retraits AS (
                SELECT responsable_id, cas_utilisation_id
                  FROM responsable_cas_utilisation WHERE type = 'REMOVE'
            ),
            porteurs AS (
                SELECT responsable_id, cas_utilisation_id FROM depuis_categories
                 UNION
                SELECT responsable_id, cas_utilisation_id FROM ajouts
            )
            SELECT DISTINCT p.responsable_id
              FROM porteurs p
              JOIN cas_utilisation cu ON cu.id = p.cas_utilisation_id
              JOIN responsable r      ON r.id = p.responsable_id
             WHERE cu.code = :code
               AND cu.statut = 'ACTIF'
               AND r.statut = 'ACTIF'
               AND NOT EXISTS (
                   SELECT 1 FROM retraits t
                    WHERE t.responsable_id = p.responsable_id
                      AND t.cas_utilisation_id = p.cas_utilisation_id)
            """, nativeQuery = true)
    List<Long> ayantLaPermission(@Param("code") String code);
}
