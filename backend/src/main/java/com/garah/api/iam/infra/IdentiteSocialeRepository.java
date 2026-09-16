package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteSociale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdentiteSocialeRepository extends JpaRepository<IdentiteSociale, Long> {

    /**
     * La seule lecture qui identifie quelqu'un.
     *
     * <p>Remarque qu'aucune méthode ne cherche par e-mail : ce serait la
     * porte ouverte à une prise de compte par simple homonymie d'adresse.</p>
     */
    Optional<IdentiteSociale> findByFournisseurAndSujet(FournisseurIdentite fournisseur, String sujet);

    boolean existsByUtilisateurIdAndFournisseur(Long utilisateurId, FournisseurIdentite fournisseur);

    /**
     * Ce compte est-il entré par un réseau, quel qu'il soit ?
     *
     * <p>C'est la condition de D-53 : un compte ouvert par Google, Facebook,
     * WhatsApp ou TikTok commande sans autre vérification.</p>
     */
    boolean existsByUtilisateurId(Long utilisateurId);
}
