-- =============================================================================
-- V35 — L'Assistance GARAH : une discussion permanente par client
-- =============================================================================
--
-- 🎯 Jusqu'ici, une discussion ne naissait que du bouton « Contacter » d'une
--    fiche produit. Pour une question sur une livraison, un paiement ou son
--    compte, le client n'avait pas de porte. Et GARAH n'avait aucun endroit
--    où lui écrire de sa propre initiative : une offre, une information, un
--    rappel des règles.
--
--    L'Assistance GARAH est cette porte, dans les deux sens. Elle suit la
--    logique des autres discussions — le client écrit, elle entre dans la
--    file, un conseiller la prend — sauf sur un point : ELLE NE SE CLÔT
--    JAMAIS. La clore couperait le seul chemin par lequel on peut prévenir
--    un client.
--
-- =============================================================================

ALTER TABLE conversation
    ADD COLUMN assistance BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN conversation.assistance IS
    'Vrai pour la discussion « Assistance GARAH » du client : une par client, jamais close.';

-- ⚠️ UNE par client, et c'est la base qui le garantit. Deux ouvertures
--    simultanées — deux onglets, un double appui — auraient sinon pu en créer
--    deux, et le client aurait vu ses messages éparpillés entre elles.
--    L'index est PARTIEL : il ne contraint que les assistances, et laisse un
--    client ouvrir autant de discussions sur des produits qu'il le souhaite.
CREATE UNIQUE INDEX conversation_assistance_unique
    ON conversation (client_id)
    WHERE assistance;

-- ⚠️ Elle ne se clôt jamais. Le service le refuse déjà ; la contrainte
--    protège de tout ce qui passerait à côté du service — un script de
--    maintenance, une requête écrite à la main.
ALTER TABLE conversation
    ADD CONSTRAINT conversation_assistance_jamais_close
    CHECK (NOT (assistance AND statut = 'CLOSED'));


-- --- La permission ------------------------------------------------------------
-- ⚠️ DÉDIÉE, et non celle de répondre dans le service client. Écrire à un
--    client de sa propre initiative — une offre, un avertissement — n'est pas
--    répondre à sa question, et les deux ne vont pas forcément aux mêmes
--    personnes : le marketing annonce des offres, il ne traite pas la file.

INSERT INTO cas_utilisation (code, nom, description, module) VALUES
('CLIENT_ASSISTANCE_ECRIRE', 'Écrire à un client comme Assistance GARAH',
 'Envoyer un message dans la discussion « Assistance GARAH » d''un client : une offre, une information, un rappel des règles.',
 'CLIENT');
