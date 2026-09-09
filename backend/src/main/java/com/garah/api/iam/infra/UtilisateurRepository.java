package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {

    /**
     * Ce compte est-il de ce type ?
     *
     * <p>⚠️ Rend un <b>booléen</b>, et non l'utilisateur : {@link
     * com.garah.api.iam.web.GardeEquipe} vit dans la couche web, à qui le test
     * d'architecture interdit de toucher une entité. Charger la ligne entière
     * pour lire une colonne aurait de toute façon été payer trop cher.</p>
     */
    boolean existsByIdAndType(Long id, com.garah.api.iam.domaine.TypeUtilisateur type);


    /**
     * Recherche insensible à la casse, comme l'index unique
     * {@code utilisateur_email_unique} qui porte sur {@code lower(email)}.
     *
     * <p>⚠️ Si on écrivait {@code findByEmail} (sensible à la casse), un
     * utilisateur inscrit avec « Paul@x.cm » ne pourrait plus se connecter en
     * tapant « paul@x.cm » — alors que la base, elle, refuserait de créer un
     * second compte. Deux règles qui divergent : le pire des cas.</p>
     */
    Optional<Utilisateur> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<Utilisateur> findByType(TypeUtilisateur type);
}
