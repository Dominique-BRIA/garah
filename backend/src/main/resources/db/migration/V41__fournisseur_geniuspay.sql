-- =============================================================================
-- V41 — GeniusPay rejoint les fournisseurs de paiement (D-56)
-- =============================================================================
-- MoneyFusion n a pas valide le compte marchand. GeniusPay l a fait : il
-- devient le troisieme fournisseur, a cote de Campay et de MoneyFusion.
--
-- Seule la liste des valeurs permises change. Le reste de V40 tient deja :
-- un paiement retient chez qui il a ete demande, et c est la qu on
-- l interroge, quel que soit le fournisseur actif du jour.
--
-- ⚠️ DROP puis ADD dans la meme migration, donc dans la meme transaction :
--    a aucun instant la colonne n est sans contrainte.
-- =============================================================================

ALTER TABLE paiement
    DROP CONSTRAINT paiement_fournisseur_valide;

ALTER TABLE paiement
    ADD CONSTRAINT paiement_fournisseur_valide
        CHECK (fournisseur IS NULL OR fournisseur IN ('CAMPAY', 'MONEYFUSION', 'GENIUSPAY'));
