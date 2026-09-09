package com.garah.api.messagerie.infra;

import com.garah.api.messagerie.domaine.FilInterne;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Les noms des collègues, résolus en UNE requête.
 *
 * <h2>⚠️ {@code Responsable} et {@code Utilisateur} sont NOMMÉS en HQL, jamais
 * importés</h2>
 *
 * <p>C'est le procédé déjà employé entre {@code logistique} et
 * {@code commerce}. Une importation ferait dépendre {@code messagerie} de
 * {@code iam}, et le jour où {@code iam} aurait besoin de savoir si deux
 * membres se sont écrit, le test de cycles refuserait la compilation.</p>
 *
 * <p>Savoir <b>à qui</b> l'on écrit n'est pas savoir <b>qui c'est</b> : ce
 * module ne manipule que des identifiants, et emprunte les noms au moment de
 * les afficher.</p>
 *
 * <p>⚠️ Il hérite de {@code JpaRepository<FilInterne, Long>} faute de mieux :
 * Spring Data exige une entité racine, et {@code messagerie} n'a pas le droit
 * de nommer {@code Responsable} comme telle. Aucune méthode héritée n'est
 * utilisée — seule la requête ci-dessous compte.</p>
 */
public interface NomResponsableRepository extends JpaRepository<FilInterne, Long> {

    /**
     * Ce compte est-il un responsable ?
     *
     * <p>⚠️ La messagerie interne relie les membres de l'équipe. Un ADMIN ou un
     * SUPER_ADMIN n'a pas de ligne `responsable` : sans ce contrôle, la clé
     * étrangère refuse et l'erreur se lit « élément supprimé entre-temps »,
     * ce qui est faux dans les deux moitiés de la phrase.</p>
     */
    @Query("SELECT count(r) > 0 FROM Responsable r WHERE r.id = :id")
    boolean existsResponsable(@Param("id") Long id);


    @Query("""
            SELECT r.id, u.prenom, u.nom
              FROM Responsable r
              JOIN Utilisateur u ON u.id = r.id
             WHERE r.id IN :ids
            """)
    List<Object[]> nomsPar(@Param("ids") Collection<Long> ids);

    /**
     * Les collegues a qui l on peut ecrire.
     *
     * <p>⚠️ Ceux qui m ont BLOQUE en sont retires, et les INACTIFS aussi. Les
     *    laisser dans la liste ferait choisir un destinataire pour se voir
     *    refuser l envoi juste apres — un refus qu on aurait pu eviter avant
     *    le clic.</p>
     *
     * <p>Moi non plus je n y suis pas : on ne s ecrit pas a soi-meme.</p>
     */
    @Query("""
            SELECT r.id, u.prenom, u.nom
              FROM Responsable r
              JOIN Utilisateur u ON u.id = r.id
             WHERE r.id <> :moi
               AND r.statut = 'ACTIF'
               AND NOT EXISTS (
                   SELECT 1 FROM BlocageMessage b
                    WHERE b.bloqueurId = r.id AND b.bloqueId = :moi)
             ORDER BY u.prenom, u.nom
            """)
    List<Object[]> joignablesPar(@Param("moi") Long responsableId);
}
