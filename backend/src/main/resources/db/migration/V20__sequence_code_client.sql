-- =============================================================================
-- V20 — Sequence pour le code client
-- =============================================================================
-- L inscription cree un `client.code_client` unique et lisible : CLI-000042.
--
-- Meme raison qu en V17 pour les numeros de commande : la tentation
-- `count(*) + 1` donne le MEME code a deux inscriptions simultanees, et la
-- contrainte UNIQUE fait alors echouer une inscription parfaitement valide.
-- Une sequence est atomique et ne bloque personne.
--
-- ⚠️ Le code client n est PAS l identifiant technique. Il sert a etre dicte
--    au telephone et lu sur un bordereau — d ou le format court et sans
--    caractere ambigu. L identifiant reste `client.id`.
-- =============================================================================

CREATE SEQUENCE client_code_seq START 1;

COMMENT ON SEQUENCE client_code_seq IS
    'Alimente client.code_client au format CLI-<6 chiffres>. Peut sauter des numeros : voir V17.';
