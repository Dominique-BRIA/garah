package com.garah.api.catalogue.infra;

import com.garah.api.catalogue.domaine.Media;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MediaRepository extends JpaRepository<Media, Long> {

    List<Media> findByProduitIdOrderByOrdreAsc(Long produitId);

    long countByProduitId(Long produitId);

    /**
     * Retire la marque « principal » de tous les medias d'un produit.
     *
     * <p>Indispensable AVANT de designer une nouvelle photo principale :
     * l'index unique partiel {@code media_principal_unique} refuserait une
     * seconde ligne a true. On enleve, puis on pose.</p>
     */
    @Modifying
    @Query("UPDATE Media m SET m.principal = false WHERE m.produit.id = :produitId AND m.principal = true")
    void retirerPrincipal(Long produitId);
}
