package com.garah.api.sav.domaine;

import com.garah.api.iam.domaine.NomClient;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un retour tel qu'une liste l'affiche.
 *
 * <h2>Deux chiffres, et ils ne disent pas la même chose</h2>
 *
 * <p>{@code nombreArticles} est ce qui est <b>annoncé</b> : ce que le client
 * dit renvoyer. {@code montantRembourse} est ce qui a été <b>rendu</b>, et il
 * vaut zéro tant que le retour n'est pas validé.</p>
 *
 * <p>⚠️ Les afficher comme un seul « montant du retour » serait un
 * contresens : entre les deux, quelqu'un a ouvert le colis et constaté l'état
 * réel des articles. Un article annoncé neuf et reçu cassé ne se rembourse
 * pas pareil. Le premier chiffre est une déclaration, le second un fait.</p>
 *
 * @param clientNom      {@code null} si le client a disparu. Le retour reste :
 *                       la marchandise est physiquement revenue.
 * @param commandeNumero le numéro que le client donne au téléphone — il ne
 *                       connaît jamais le numéro de retour avant qu'on le lui
 *                       communique.
 */
public record ResumeRetour(
        Long id,
        String numero,
        String statut,
        String motif,
        Long clientId,
        String clientCode,
        String clientNom,
        Long commandeId,
        String commandeNumero,
        Long reclamationId,
        long nombreArticles,
        BigDecimal montantRembourse,
        Instant dateCreation,
        Instant dateReception) {

    public static ResumeRetour de(Retour r, NomClient client, String commandeNumero,
                                  long nombreArticles, BigDecimal montantRembourse) {
        return new ResumeRetour(
                r.getId(), r.getNumero(), r.getStatut().name(), r.getMotif(),
                r.getClientId(),
                client == null ? null : client.code(),
                client == null ? null : client.nom(),
                r.getCommandeId(), commandeNumero, r.getReclamationId(),
                nombreArticles, montantRembourse,
                r.getDateCreation(), r.getDateReception());
    }
}
