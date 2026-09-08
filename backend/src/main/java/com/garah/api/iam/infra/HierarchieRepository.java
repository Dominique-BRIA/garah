package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.ResponsableCategorie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Les questions de la hiérarchie, en une requête chacune.
 *
 * <p>Elles sont posées à <b>chaque geste</b> d'un chef sur un membre. Les
 * résoudre en chargeant les entités et en parcourant les collections
 * coûterait plusieurs allers-retours pour répondre « oui » ou « non ».</p>
 */
public interface HierarchieRepository
        extends JpaRepository<ResponsableCategorie, ResponsableCategorie.Cle> {

    /**
     * Les services que dirige ce responsable.
     *
     * <p>Plusieurs sont possibles : quelqu'un peut cumuler deux profils et
     * diriger l'un des deux — ou les deux.</p>
     */
    @Query("""
            SELECT rc.categorie.id FROM ResponsableCategorie rc
             WHERE rc.responsable.id = :responsableId
               AND rc.chef = true
            """)
    List<Long> servicesDiriges(@Param("responsableId") Long responsableId);

    /**
     * Cette personne est-elle membre d un service que je dirige ?
     *
     * <p>⚠️ C est LA question de l autorisation, et elle se pose en une fois.
     *    La decomposer — « quels services je dirige », puis « de quels
     *    services est-il membre », puis l intersection cote Java — ferait deux
     *    requetes et une logique de plus a maintenir a chaque appel.</p>
     *
     * <p>Le chef ne peut pas s y trouver lui-meme : il est bien membre de son
     * propre service, et c est le RANG qui l en empeche, pas cette requete.
     * Les deux controles sont distincts a dessein.</p>
     */
    @Query("""
            SELECT count(cible) > 0
              FROM ResponsableCategorie cible
             WHERE cible.responsable.id = :cibleId
               AND cible.categorie.id IN (
                   SELECT chef.categorie.id FROM ResponsableCategorie chef
                    WHERE chef.responsable.id = :chefId
                      AND chef.chef = true)
            """)
    boolean estSousMesOrdres(@Param("chefId") Long chefId, @Param("cibleId") Long cibleId);

    /**
     * Les membres d un service, chef compris.
     *
     * <p>C est ce que voit un chef quand il ouvre « mon service ».</p>
     */
    @Query("""
            SELECT rc.responsable.id FROM ResponsableCategorie rc
             WHERE rc.categorie.id = :categorieId
            """)
    List<Long> membresDu(@Param("categorieId") Long categorieId);

    /**
     * Demet le chef actuel d un service.
     *
     * <p>⚠️ UNE REQUETE, ET EXECUTEE TOUT DE SUITE. C est ce qui compte ici.
     *
     *    Un index unique partiel interdit deux chefs par service. En passant
     *    par les entites, Hibernate decide SEUL de l ordre de ses ecritures :
     *    la nomination du nouveau chef partait avant la demission de l ancien,
     *    et la base refusait — « la valeur d une cle dupliquee rompt la
     *    contrainte unique ». Le test l a trouve ; a l ecran, cela aurait
     *    donne une erreur incomprehensible au moment de nommer.
     *
     *    Une requete de modification s execute au moment ou on l appelle. L
     *    ordre cesse d etre une question.
     *
     * <p>⚠️ {@code clearAutomatically} n est pas decoratif : sans lui, les
     *    entites deja chargees garderaient {@code chef = true} en memoire, et
     *    la lecture suivante rendrait deux chefs.</p>
     */
    /**
     * Qui dirige ce service, et sous quel nom.
     *
     * <p>Rend une ligne au plus — l index unique s en assure — ou rien du tout
     * si le service n a pas encore de chef.</p>
     *
     * <p>⚠️ {@code Utilisateur} est joint pour le nom : « chef n 12 » n a
     *    jamais dit a personne qui dirige.</p>
     */
    @Query("""
            SELECT rc.responsable.id, u.prenom, u.nom
              FROM ResponsableCategorie rc
              JOIN Utilisateur u ON u.id = rc.responsable.id
             WHERE rc.categorie.id = :categorieId
               AND rc.chef = true
            """)
    List<Object[]> chefDu(@Param("categorieId") Long categorieId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ResponsableCategorie rc
               SET rc.chef = false
             WHERE rc.categorie.id = :categorieId
               AND rc.chef = true
            """)
    int demettreLeChef(@Param("categorieId") Long categorieId);
}
