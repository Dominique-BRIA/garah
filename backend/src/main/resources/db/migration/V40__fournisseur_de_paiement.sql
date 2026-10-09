-- =============================================================================
-- V40 — Un paiement sait CHEZ QUI il a ete demande
-- =============================================================================
-- Jusqu ici, un seul fournisseur : Campay. Le code l appelait par son nom, et
-- la base n avait pas besoin de s en souvenir.
--
-- MoneyFusion arrive a cote, et Campay reste en reserve (D-55). Un reglage
-- choisit lequel sert les NOUVEAUX paiements. Mais les paiements deja en
-- attente doivent continuer d etre interroges chez celui qui les a recus :
-- demander a MoneyFusion l etat d une reference Campay ne renverrait rien, et
-- le client debite verrait sa commande annulee au bout du delai.
--
-- ⚠️ D ou la regle : le fournisseur se lit sur LE PAIEMENT, jamais sur le
--    reglage courant. Le reglage ne decide que des paiements a venir.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Le fournisseur
-- -----------------------------------------------------------------------------
-- NULL = aucun fournisseur en ligne : un virement, ou un remboursement saisi
-- par le back-office. Ce n est pas une donnee manquante, c est un fait — ces
-- paiements n ont jamais transite par une API.
ALTER TABLE paiement
    ADD COLUMN fournisseur varchar(20);

ALTER TABLE paiement
    ADD CONSTRAINT paiement_fournisseur_valide
        CHECK (fournisseur IS NULL OR fournisseur IN ('CAMPAY', 'MONEYFUSION'));

-- Les encaissements mobiles existants sont tous passes par Campay : c etait le
-- seul fournisseur. Ceux qui n ont jamais atteint l operateur (pas de
-- reference) aussi — c est a Campay qu ils avaient ete demandes.
UPDATE paiement
   SET fournisseur = 'CAMPAY'
 WHERE type = 'ENCAISSEMENT'
   AND moyen IN ('MTN_MOMO', 'ORANGE_MONEY');

COMMENT ON COLUMN paiement.fournisseur IS
    'Chez qui le paiement a ete demande. C est LUI qu on interroge, quel que soit '
    'le fournisseur actif aujourd hui (V40). NULL : virement ou saisie manuelle.';


-- -----------------------------------------------------------------------------
-- 2. Le moyen peut etre inconnu, tant que le fournisseur le sait
-- -----------------------------------------------------------------------------
-- Avec MoneyFusion, le client choisit MTN ou Orange SUR LA PAGE DE PAIEMENT,
-- pas chez nous. A la creation du paiement, GARAH ne le sait donc pas encore :
-- il l apprend a la confirmation.
--
-- ⚠️ On n invente pas de valeur « MOBILE_MONEY » pour boucher le trou. Une
--    valeur qui veut dire « pas encore connu » dans une colonne qui veut dire
--    « MTN ou Orange » finit comptee comme un troisieme operateur dans les
--    statistiques.
ALTER TABLE paiement
    ALTER COLUMN moyen DROP NOT NULL;

-- ⚠️ Inconnu SEULEMENT si un fournisseur en ligne le detient. Un virement ou
--    un remboursement saisi a la main n a personne a qui le redemander : son
--    moyen doit etre dit a la saisie.
--
--    Et la contrainte ne l exige PAS a la confirmation, deliberement : si
--    MoneyFusion annonce un moyen que nous ne savons pas nommer, l argent est
--    quand meme encaisse. Refuser de confirmer laisserait un client debite
--    avec une commande impayee — le pire resultat possible.
ALTER TABLE paiement
    ADD CONSTRAINT paiement_moyen_connu
        CHECK (moyen IS NOT NULL OR fournisseur IS NOT NULL);


-- -----------------------------------------------------------------------------
-- 3. Ce que le fournisseur a dit du moyen, mot pour mot
-- -----------------------------------------------------------------------------
-- MoneyFusion repond « orange », « mtn-cm »… Le moyen ci-dessus en est une
-- TRADUCTION. On garde l original : c est lui qu on montrera au fournisseur le
-- jour d un litige, et c est lui qui dira pourquoi une traduction a echoue.
ALTER TABLE paiement
    ADD COLUMN moyen_fournisseur varchar(40);

COMMENT ON COLUMN paiement.moyen_fournisseur IS
    'Le moyen tel que le fournisseur l a annonce, non traduit (V40).';
