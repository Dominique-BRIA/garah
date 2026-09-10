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
    /**
     * Ce compte a-t-il sa place dans la messagerie INTERNE ?
     *
     * <p>⚠️ La question portait sur {@code Responsable}, et excluait donc les
     * ADMIN et SUPER_ADMIN — non par décision, mais parce que le schéma
     * d'origine y renvoyait (V32). L'administration fait partie de la maison :
     * elle a autant de raisons d'écrire à un chef de service qu'il en a de lui
     * répondre.</p>
     *
     * <p>⚠️ Un CLIENT reste exclu, et c'est la seule exclusion voulue : cette
     * messagerie est interne. La base ne sait pas l'exprimer — {@code
     * utilisateur} porte les quatre types — donc c'est cette requête qui le
     * dit, et un test qui la tient.</p>
     */
    @Query("""
            SELECT count(u) > 0 FROM Utilisateur u
             WHERE u.id = :id
               AND u.type <> com.garah.api.iam.domaine.TypeUtilisateur.CLIENT
            """)
    boolean estCompteInterne(@Param("id") Long id);


    @Query("""
            SELECT u.id, u.prenom, u.nom
              FROM Utilisateur u
             WHERE u.id IN :ids
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
    /**
     * ⚠️ Le statut lu est celui de l'UTILISATEUR, plus celui du responsable :
     * un ADMIN n'a pas de ligne `responsable`, donc pas de statut à y lire.
     * Les deux valent 'ACTIF' / 'INACTIF' et disaient la même chose pour un
     * responsable.
     */
    @Query("""
            SELECT u.id, u.prenom, u.nom
              FROM Utilisateur u
             WHERE u.id <> :moi
               AND u.type <> com.garah.api.iam.domaine.TypeUtilisateur.CLIENT
               AND u.statut = com.garah.api.iam.domaine.StatutUtilisateur.ACTIF
               AND NOT EXISTS (
                   SELECT 1 FROM BlocageMessage b
                    WHERE b.bloqueurId = u.id AND b.bloqueId = :moi)
             ORDER BY u.prenom, u.nom
            """)
    List<Object[]> joignablesPar(@Param("moi") Long utilisateurId);
}
