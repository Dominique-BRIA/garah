-- =============================================================================
-- V29 — Les appareils qui reçoivent les notifications
-- =============================================================================
-- 🎯 UN JETON APPARTIENT À UN COMPTE, PAS À UN TÉLÉPHONE.
--
--    C'est toute la difficulté de cette table. Un jeton FCM désigne une
--    installation d'application, pas une personne : sur un téléphone partagé —
--    ce qui est la norme, pas l'exception, sur le corridor Douala-Bangui — deux
--    clients se succèdent derrière le même jeton.
--
--    D'où la clé primaire sur le JETON seul, et non sur (utilisateur, jeton) :
--    un jeton ne peut appartenir qu'à UN compte à la fois. Quand le suivant se
--    connecte, la même ligne change de propriétaire. Une clé composite aurait
--    laissé les deux inscriptions vivre côte à côte, et « votre marchandise
--    vous attend » serait parti au précédent — pour un colis qui n'est pas le
--    sien.
-- =============================================================================

CREATE TABLE appareil_notification (
    -- Le jeton FCM lui-même. Long : Google ne s'engage sur aucune taille, et
    -- les jetons observés dépassent régulièrement 160 caractères.
    jeton           varchar(500) PRIMARY KEY,

    utilisateur_id  bigint      NOT NULL REFERENCES utilisateur (id) ON DELETE CASCADE,

    plateforme      varchar(20) NOT NULL,

    -- ⚠️ Sert au MÉNAGE, pas à la statistique.
    --
    --    Un jeton qu'on n'a pas revu depuis des mois désigne une application
    --    désinstallée. Le garder ferait grossir la table indéfiniment et
    --    facturerait des envois qui échouent tous.
    date_maj        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT appareil_plateforme_connue
        CHECK (plateforme IN ('ANDROID', 'IOS', 'WEB'))
);

-- « À qui dois-je écrire ? » est LA question posée à chaque envoi. Sans cet
-- index, chaque notification balaie toute la table.
CREATE INDEX idx_appareil_utilisateur ON appareil_notification (utilisateur_id);

COMMENT ON TABLE appareil_notification IS
    'Les appareils abonnés aux notifications. Le jeton est la clé : il ne peut '
    'appartenir qu''à un compte à la fois, sinon un téléphone partagé enverrait '
    'au client précédent les notifications du suivant.';
