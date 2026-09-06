-- =============================================================================
-- V18 — La reference d'une transaction operateur est unique
-- =============================================================================
-- Le webhook d'un operateur mobile money N'EST PAS appele une seule fois.
-- MTN et Orange rejouent la notification tant qu'ils n'ont pas recu un accuse
-- de reception : timeout reseau, redemarrage de notre serveur, lenteur de
-- Render qui sortait de veille (D-14). Deux appels pour le meme paiement sont
-- la NORME, pas l'exception.
--
-- Sans garde-fou, un paiement de 53 000 FCFA confirme deux fois produirait :
--   - deux sorties de stock pour une seule commande ;
--   - deux ecritures de vente au marchand ;
--   - un solde faux, sans aucune erreur visible.
--
-- Le service est deja idempotent (il ignore une confirmation deja faite).
-- Cet index est la DEUXIEME ligne de defense : meme un import manuel ou un
-- second service ne pourra pas enregistrer deux fois la meme transaction.
--
-- Index PARTIEL : plusieurs paiements peuvent legitimement n'avoir aucune
-- reference (un paiement encore INITIE, un remboursement interne). Un UNIQUE
-- classique les interdirait a partir du second.
-- =============================================================================

CREATE UNIQUE INDEX paiement_reference_unique
    ON paiement (reference_transaction)
    WHERE reference_transaction IS NOT NULL;

COMMENT ON COLUMN paiement.reference_transaction IS
    'Identifiant chez l operateur. UNIQUE (V18) : le webhook peut etre rejoue.';
