package com.garah.api.messagerie.domaine;

import java.time.Instant;

/**
 * Un message interne vient d'etre enregistre.
 *
 * <h2>🎯 Pourquoi un evenement plutot qu un appel direct</h2>
 *
 * <p>Deux choses doivent suivre l envoi : le pousser en temps reel au
 * destinataire s il est connecte, et lui envoyer une notification s il ne l est
 * pas. Ni l une ni l autre n est le metier de la messagerie.</p>
 *
 * <p>⚠️ Surtout : ni l une ni l autre ne doit pouvoir FAIRE ECHOUER l envoi.
 *    Si le WebSocket est tombe ou si Firebase repond mal, le message doit
 *    quand meme etre enregistre — on le lira au prochain rafraichissement. Un
 *    appel direct aurait remonte l echec jusqu a l ecran, et l expediteur
 *    aurait reecrit son message en croyant l avoir perdu.</p>
 */
public record MessageInterneEnvoye(
        Long messageId,
        Long filId,
        Long expediteurId,
        Long destinataireId,
        String contenu,
        Instant dateEnvoi) {
}
