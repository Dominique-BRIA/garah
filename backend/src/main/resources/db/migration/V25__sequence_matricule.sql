-- =============================================================================
-- V25 — Sequence pour le matricule d un responsable
-- =============================================================================
-- `responsable.matricule` est UNIQUE et doit etre lisible : RES-00042.
--
-- Meme raison qu en V17, V20 et V23 : la tentation `count(*) + 1` donne le
-- MEME matricule a deux creations simultanees, et la contrainte UNIQUE fait
-- alors echouer une creation parfaitement valide. Une sequence est atomique
-- et ne bloque personne.
--
-- ⚠️ Le matricule n est PAS l identifiant technique. Il sert a designer
--    quelqu un dans une conversation, sur un planning ou dans un journal —
--    d ou le format court. L identifiant reste `responsable.id`, qui est
--    aussi `utilisateur.id`.
-- =============================================================================

CREATE SEQUENCE responsable_matricule_seq START 1;

COMMENT ON SEQUENCE responsable_matricule_seq IS
    'Alimente responsable.matricule au format RES-<5 chiffres>. Peut sauter des numeros : voir V17.';
