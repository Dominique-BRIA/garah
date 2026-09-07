-- =============================================================================
-- V27 — La corbeille des produits
-- =============================================================================
-- Jusqu ici, supprimer un produit l effacait sur-le-champ. Un clic de trop, et
-- la fiche, ses declinaisons, sa grille tarifaire et ses photos disparaissaient
-- sans retour possible.
--
-- La suppression devient donc un GESTE EN DEUX TEMPS :
--
--     brouillon  --supprimer-->  corbeille  --vider-->  efface pour de bon
--                                    |
--                                    +--restaurer-->  brouillon
--
-- =============================================================================
-- Pourquoi une DATE et pas un statut « SUPPRIME »
-- =============================================================================
-- Le statut dit ou en est le produit dans sa vie commerciale : brouillon,
-- publie, masque, archive. La corbeille est une dimension SEPAREE — un
-- brouillon mis a la corbeille reste un brouillon, et le redevient tel quel
-- s il est restaure.
--
-- Les fondre ferait perdre l etat d origine : au retour de corbeille, on ne
-- saurait plus si le produit etait brouillon ou masque. Il faudrait alors une
-- SECONDE colonne pour s en souvenir — c est-a-dire exactement ce qu on
-- cherchait a eviter.
--
-- La date apporte en prime ce qu un booleen ne donne pas : « supprime il y a
-- trois jours », et la possibilite de purger automatiquement au-dela d un
-- delai.
-- =============================================================================

ALTER TABLE produit
    ADD COLUMN date_suppression timestamptz;

COMMENT ON COLUMN produit.date_suppression IS
    'Non nulle = dans la corbeille. Le produit reste invisible partout sauf dans la corbeille elle-meme, et garde son statut d origine pour la restauration.';

-- -----------------------------------------------------------------------------
-- L index est PARTIEL, et c est ce qui le rend gratuit
-- -----------------------------------------------------------------------------
-- Toutes les listes du catalogue filtrent desormais sur
-- « date_suppression IS NULL » — c est-a-dire l ecrasante majorite des lignes.
--
-- Un index sur toute la colonne indexerait ces millions de NULL pour rien.
-- Celui-ci n indexe QUE les lignes en corbeille : quelques dizaines au plus.
-- Il sert la seule requete qui les cherche, et ne coute presque rien a
-- maintenir.
CREATE INDEX produit_corbeille
    ON produit (date_suppression DESC)
    WHERE date_suppression IS NOT NULL;
