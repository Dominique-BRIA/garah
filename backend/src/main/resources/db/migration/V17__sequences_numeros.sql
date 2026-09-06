-- =============================================================================
-- V17 — Sequences pour les numeros metier
-- =============================================================================
-- Une commande porte un numero lisible : CMD-2026-000001.
--
-- LA MAUVAISE FACON, et elle est tentante :
--
--     SELECT count(*) + 1 FROM commande;
--
-- Deux clients qui commandent a la meme seconde lisent le meme compte et
-- obtiennent le MEME numero. La contrainte UNIQUE fait alors echouer une
-- commande deja payee. C'est le meme bug que la survente du chapitre 11,
-- deguise en numerotation.
--
-- LA BONNE FACON : une sequence PostgreSQL.
--   - elle est atomique, sans verrou ;
--   - elle ne recule jamais, meme en cas d'annulation de transaction.
--
-- ⚠️ Une sequence PEUT sauter des numeros : une transaction annulee consomme
--    quand meme sa valeur. C'est voulu, et acceptable ici. Si un jour la
--    reglementation impose une numerotation de factures SANS TROU, il faudra
--    une table de compteur verrouillee — beaucoup plus lente, et reservee aux
--    documents qui l'exigent vraiment (voir D-11, la TVA).
-- =============================================================================

CREATE SEQUENCE commande_numero_seq   START 1;
CREATE SEQUENCE expedition_numero_seq START 1;
CREATE SEQUENCE reclamation_numero_seq START 1;
CREATE SEQUENCE retour_numero_seq     START 1;
CREATE SEQUENCE reglement_numero_seq  START 1;

COMMENT ON SEQUENCE commande_numero_seq IS
    'Alimente commande.numero au format CMD-<annee>-<6 chiffres>. Peut sauter des numeros : voir V17.';
