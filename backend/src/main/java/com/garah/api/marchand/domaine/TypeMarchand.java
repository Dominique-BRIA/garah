package com.garah.api.marchand.domaine;

/**
 * Correspond a la contrainte {@code marchand_type_valide} du schema (V3).
 *
 * <pre>
 * INTERNE   GARAH vend sa propre marchandise : aucune dette envers un tiers
 * EXTERNE   un partenaire vend via la plateforme : commission et grand livre
 * </pre>
 */
public enum TypeMarchand {
    INTERNE,
    EXTERNE
}
