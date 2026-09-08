/*
 * L'agent de service des notifications — BACK-OFFICE.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 🎯 IL DOIT S'APPELER EXACTEMENT `firebase-messaging-sw.js`
 *    ET VIVRE À LA RACINE DU DOMAINE.
 * ═══════════════════════════════════════════════════════════════════════════
 * Firebase l'enregistre lui-même, sous ce nom, sur `/`. Le renommer ou le
 * ranger dans un sous-dossier casse les notifications reçues quand l'onglet
 * est fermé — SANS AUCUN MESSAGE D'ERREUR. `getToken()` réussit, l'abonnement
 * a l'air posé, et rien n'arrive jamais.
 *
 * ⚠️ CE FICHIER NE PASSE PAS PAR LE COMPILATEUR.
 *    Pas de TypeScript, pas d'import de module, pas de jetons de la charte.
 *    C'est du JavaScript brut exécuté par le navigateur, hors de l'application.
 *    Les valeurs sont RECOPIÉES depuis src/environments/firebase.ts et doivent
 *    y rester identiques.
 *
 * ⚠️ L'`appId` est celui de `garah-admin`, PAS celui de la boutique. Les
 *    intervertir ne lève aucune erreur : l'abonnement réussit, et les
 *    notifications de l'équipe partent vers l'application des clients.
 */

importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.12.2/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: 'AIzaSyA5raZbvm3mr_MXL3gXsmHMsc1tvCn4TrU',
  authDomain: 'garah-project.firebaseapp.com',
  projectId: 'garah-project',
  storageBucket: 'garah-project.firebasestorage.app',
  messagingSenderId: '215742260538',
  appId: '1:215742260538:web:086342b06c491662c49c03',
});

const messagerie = firebase.messaging();

/*
 * ⚠️ Le serveur envoie un bloc `notification` : le navigateur affiche alors la
 *    bannière LUI-MÊME. En redessiner une ici en donnerait DEUX pour le même
 *    événement. On ne prend la main que si le message n'en a pas.
 */
messagerie.onBackgroundMessage((charge) => {
  if (charge.notification) {
    return;
  }

  const donnees = charge.data || {};
  self.registration.showNotification(donnees.titre || 'GARAH', {
    body: donnees.corps || '',
    icon: '/favicon.svg',
    tag: donnees.type || 'garah',
    data: donnees,
  });
});

/*
 * 🎯 On RÉUTILISE l'onglet déjà ouvert plutôt que d'en ouvrir un second.
 *
 *    Un back-office reste ouvert toute la journée : sans cette recherche,
 *    chaque notification touchée ajoute un onglet, et on se retrouve avec dix
 *    GARAH sans savoir lequel porte le travail en cours.
 */
self.addEventListener('notificationclick', (evenement) => {
  evenement.notification.close();

  const donnees = evenement.notification.data || {};
  const chemin = destination(donnees.type, donnees.id);

  evenement.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((onglets) => {
      for (const onglet of onglets) {
        if ('focus' in onglet) {
          onglet.navigate(chemin);
          return onglet.focus();
        }
      }
      return self.clients.openWindow(chemin);
    }),
  );
});

/*
 * Le serveur envoie un TYPE et un identifiant ; c'est le client qui décide de
 * l'écran.
 *
 * ⚠️ L'inverse — une route toute faite dans le message — figerait la
 *    navigation du back-office dans le backend : renommer un chemin casserait
 *    les notifications déjà parties.
 */
function destination(type, id) {
  switch (type) {
    case 'CONVERSATION':
    case 'NEGOCIATION':
      return '/conversations';
    case 'FILE_ATTENTE':
      return '/conversations';
    case 'MESSAGE_INTERNE':
      return id ? '/messagerie/' + id : '/messagerie';
    default:
      return '/';
  }
}
