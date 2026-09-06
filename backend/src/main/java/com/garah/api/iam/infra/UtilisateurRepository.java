package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {

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
