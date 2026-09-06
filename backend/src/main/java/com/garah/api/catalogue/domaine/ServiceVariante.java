package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Les variantes vues du back-office, avec leur grille tarifaire.
 *
 * <p>Surcouche mince sur {@link ServiceCatalogue} et {@link ServiceTarification} :
 * elle assemble ce que l'ecran de gestion d'un produit affiche d'un seul tenant,
 * et convertit en DTO — la couche web n'a pas le droit de toucher une entite.</p>
 */
@Service
public class ServiceVariante {

    private final ServiceCatalogue catalogue;
    private final ServiceTarification tarification;
    private final VarianteRepository variantes;

    public ServiceVariante(ServiceCatalogue catalogue, ServiceTarification tarification,
                           VarianteRepository variantes) {
        this.catalogue = catalogue;
        this.tarification = tarification;
        this.variantes = variantes;
    }

    @Transactional
    public VueVariante ajouter(Long produitId, String sku, String libelle) {
        Variante variante = catalogue.ajouterVariante(produitId, sku, libelle, List.of());
        return VueVariante.de(variante, produitId, List.of());
    }

    /**
     * Les variantes d'un produit, chacune avec sa grille.
     *
     * <p>⚠️ Tout est lu DANS la transaction. La grille est chargee ici, et non
     * a la serialisation : avec {@code open-in-view: false}, un acces paresseux
     * hors transaction leverait — et seulement a l'execution.</p>
     */
    @Transactional(readOnly = true)
    public List<VueVariante> lister(Long produitId) {
        return variantes.findByProduitId(produitId).stream()
                .map(v -> VueVariante.de(v, produitId, tarification.grille(v.getId())))
                .toList();
    }

    @Transactional
    public VueVariante definirPalier(Long varianteId, int quantiteMin, Integer quantiteMax,
                                     BigDecimal prixUnitaire) {
        tarification.definirPalier(varianteId, quantiteMin, quantiteMax, prixUnitaire);
        return recharger(varianteId);
    }

    @Transactional(readOnly = true)
    public VueVariante detail(Long varianteId) {
        return recharger(varianteId);
    }

    private VueVariante recharger(Long varianteId) {
        Variante variante = variantes.findById(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));

        return VueVariante.de(variante, variante.getProduit().getId(),
                tarification.grille(varianteId));
    }
}
