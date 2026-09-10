-- =============================================================================
-- Les statistiques disent ce qu'elles savent, et comptent l'argent encaisse
-- =============================================================================
-- 🎯 DEUX DEFAUTS (D-47)
--
--    1. Le resume de nuit n'ecrivait une ligne que pour les produits qui
--       avaient bouge. Une journee sans visite ni vente ne laissait AUCUNE
--       trace, et l'ecran annoncait « ces jours-la n'ont pas ete resumes »
--       pour des jours simplement calmes. Il ne pouvait pas distinguer une
--       nuit manquee d'une journee vide.
--
--    2. Le chiffre d'affaires additionnait toute commande CREEE dans la
--       journee : impayees, annulees et expirees le gonflaient.
-- =============================================================================


-- --- 1. Chaque journee resumee, meme vide ------------------------------------
-- Elle porte aussi l'ARGENT de la journee : l'encaisse comprend les frais
-- d'acheminement, que les lignes par produit ne portent pas.

CREATE TABLE journee_resumee (
    jour                date          PRIMARY KEY,
    date_resume         timestamptz   NOT NULL DEFAULT now(),
    montant_encaisse    numeric(15,2) NOT NULL DEFAULT 0,
    montant_rembourse   numeric(15,2) NOT NULL DEFAULT 0,
    commandes_payees    int           NOT NULL DEFAULT 0,
    commandes_annulees  int           NOT NULL DEFAULT 0,

    CONSTRAINT journee_resumee_positifs CHECK (
        montant_encaisse >= 0 AND montant_rembourse >= 0
        AND commandes_payees >= 0 AND commandes_annulees >= 0)
);

COMMENT ON TABLE journee_resumee IS
    'Une ligne par journee RESUMEE, meme sans activite. Son absence veut dire : nuit manquee.';

-- ⚠️ ON NE REMPLIT PAS les journees passees. Leur absence est vraie : aucune
--    n'a ete notee comme resumee. Le rattrapage de l'ecran les resume, dans
--    la limite de la retention du detail des vues (90 jours).


-- --- 2. Le jour de l'annulation ----------------------------------------------

ALTER TABLE commande ADD COLUMN date_annulation timestamptz;

-- ⚠️ ON NE REMPLIT PAS retroactivement. Le journal d'audit ne connait que
--    les annulations faites par l'EQUIPE : ni celles du client, ni celles
--    faites faute de paiement. Reprendre une partie des dates, et deduire les
--    autres de `date_modification`, serait une supposition — et une
--    supposition dans un chiffre qu'on presente est pire qu'une case vide.
COMMENT ON COLUMN commande.date_annulation IS
    'Quand la commande a ete annulee. NULL pour les annulations anterieures a V36 : inconnu, pas suppose.';

ALTER TABLE commande
    ADD CONSTRAINT commande_annulation_coherente
    CHECK (date_annulation IS NULL OR statut = 'ANNULEE');
