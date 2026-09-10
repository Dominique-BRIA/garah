-- =============================================================================
-- Le systeme ecrit au client, dans ses discussions
-- =============================================================================
-- 🎯 LE BESOIN
--
--    Quand un colis part, le client doit recevoir son numero de suivi DANS
--    « Mes discussions » : la ou il relit ce qu on lui a dit, et d ou il peut
--    repondre s il a une question.
--
-- ⚠️ TROIS CHOSES L INTERDISAIENT, TOUTES DANS LE SCHEMA
--
--    1. `message.expediteur_id` est NOT NULL et reference `utilisateur`. Le
--       systeme n est pas un utilisateur. Le faire signer par l agent qui a
--       enregistre le depart serait FAUX : il n a rien ecrit, et le client
--       lui repondrait a lui — souvent un agent d entrepot qui ne voit pas
--       les conversations.
--
--    2. Une conversation nait WAITING, c est-a-dire « un client attend une
--       reponse ». Or ici personne n attend rien : l annonce aurait rempli
--       la file de l equipe, declenche « Des clients attendent », et fait
--       ouvrir des dossiers vides a des agents.
--
--    3. Rien ne rattachait une conversation a la commande dont elle parle.
-- =============================================================================


-- --- 1. Un message peut venir du systeme ------------------------------------

ALTER TABLE message ALTER COLUMN expediteur_id DROP NOT NULL;

COMMENT ON COLUMN message.expediteur_id IS
    'Qui a ecrit. NULL = le systeme (une annonce engendree, jamais saisie par une personne).';

-- ⚠️ Les compteurs de non-lus comparent `expediteur_id = conversation.client_id`.
--    NULL n est egal a rien : une annonce ne compte donc JAMAIS comme un
--    message de client a traiter. C est voulu, et c est ce qui evite d avoir
--    a modifier ces requetes.


-- --- 2. Un statut pour « rien n attend personne » ---------------------------

ALTER TABLE conversation DROP CONSTRAINT conversation_statut_valide;
ALTER TABLE conversation
    ADD CONSTRAINT conversation_statut_valide
    CHECK (statut IN ('INFORMATION', 'WAITING', 'ASSIGNED', 'CLOSED'));

-- I-29, etendu : ni INFORMATION ni WAITING n ont ete pris par quelqu un.
ALTER TABLE conversation DROP CONSTRAINT conversation_prise_coherente;
ALTER TABLE conversation
    ADD CONSTRAINT conversation_prise_coherente CHECK (
        (statut IN ('INFORMATION', 'WAITING') AND pris_par IS NULL)
     OR (statut IN ('ASSIGNED', 'CLOSED')     AND pris_par IS NOT NULL)
    );


-- --- 3. La commande dont on parle -------------------------------------------
-- ⚠️ A NE PAS CONFONDRE avec `commande.conversation_id` (V7) : celle-la dit
--    de quelle conversation une commande est NEE — une negociation. Celle-ci
--    dit de quelle commande une conversation PARLE. Deux sens, deux colonnes :
--    en reutiliser une pour l autre ecraserait l origine d une commande
--    negociee au premier colis parti.

ALTER TABLE conversation ADD COLUMN commande_id bigint REFERENCES commande (id);

CREATE INDEX conversation_commande_idx
    ON conversation (commande_id) WHERE commande_id IS NOT NULL;
