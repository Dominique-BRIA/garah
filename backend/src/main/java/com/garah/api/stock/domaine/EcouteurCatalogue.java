package com.garah.api.stock.domaine;

import com.garah.api.catalogue.domaine.VarianteCreee;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Le stock naît avec la déclinaison qu'il suit.
 *
 * <p>C'est l'invariant I-15 : « une variante a <b>exactement un</b> stock ».
 * La base garantit qu'il n'y en a jamais deux ({@code UNIQUE (variante_id)}),
 * mais elle ne sait pas exiger qu'il y en ait un — « au moins un » ne
 * s'exprime pas en SQL. La règle vit donc ici.</p>
 *
 * <h2>Ce que coûtait son absence</h2>
 *
 * <p>Rien, longtemps. Puis tout d'un coup : une déclinaison sans ligne de
 * stock traverse la création du produit, la publication, l'affichage en
 * vitrine, l'ajout au panier — et échoue à la <b>réservation</b>, c'est-à-dire
 * au moment où un client valide sa commande, avec un message qui parle d'une
 * variante introuvable. Le défaut se déclare aussi loin que possible de sa
 * cause.</p>
 *
 * <h2>Pourquoi un écouteur plutôt qu'un appel</h2>
 *
 * <p>Le stock dépend du catalogue — il faut bien nommer ce qu'on compte.
 * L'appel inverse formerait un cycle entre les deux domaines, et le test
 * ArchUnit le refuserait. L'événement inverse la dépendance : le catalogue
 * annonce, le stock écoute.</p>
 *
 * <p>⚠️ {@link EventListener} est <b>synchrone</b> : il s'exécute dans la
 * transaction de l'appelant. Si la création du stock échoue, la déclinaison
 * n'est pas créée non plus. C'est exactement ce qu'on veut — les deux existent
 * ensemble ou pas du tout. Un {@code @Async} ou un
 * {@code @TransactionalEventListener(AFTER_COMMIT)} rouvrirait précisément le
 * trou qu'on est en train de boucher.</p>
 */
@Component
public class EcouteurCatalogue {

    private final ServiceStock stock;

    public EcouteurCatalogue(ServiceStock stock) {
        this.stock = stock;
    }

    @EventListener
    public void surVarianteCreee(VarianteCreee evenement) {
        stock.creerPour(evenement.varianteId());
    }
}
