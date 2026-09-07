package com.garah.api.catalogue.domaine;

/**
 * Une déclinaison vient de naître.
 *
 * <h2>Pourquoi un événement plutôt qu'un appel direct</h2>
 *
 * <p>L'invariant I-15 dit : « une variante a <b>exactement un</b> stock ». La
 * ligne de stock doit donc naître avec la déclinaison. Mais le catalogue ne
 * peut pas appeler le stock : depuis qu'il faut désigner ce qu'on compte, le
 * <b>stock dépend du catalogue</b>. L'appel inverse formerait un cycle entre
 * les deux domaines — et le test ArchUnit « aucun cycle entre les domaines »
 * refuserait le build.</p>
 *
 * <p>L'événement casse le nœud : le catalogue annonce un fait, il ne commande
 * rien. Qui l'écoute ne le regarde pas.</p>
 *
 * <p>⚠️ L'écouteur est <b>synchrone</b> et s'exécute dans la <b>même
 * transaction</b>. Ce n'est pas un détail : une déclinaison enregistrée sans
 * sa ligne de stock ferait échouer la première commande, longtemps après sa
 * création, avec un message qui ne dirait rien de la cause.</p>
 */
public record VarianteCreee(Long varianteId) {
}
