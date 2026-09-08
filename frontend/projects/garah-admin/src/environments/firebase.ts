/**
 * La configuration Firebase du BACK-OFFICE.
 *
 * <h2>⚠️ Ce n'est pas la même application que la boutique</h2>
 *
 * <p>Même projet, même {@code messagingSenderId}, mais un {@code appId}
 * distinct : {@code garah-admin} et non {@code garah-web}. Les intervertir ne
 * lève aucune erreur — l'abonnement réussit, et les notifications partent vers
 * l'autre application.</p>
 *
 * <h2>Rien ici n'est un secret</h2>
 *
 * <p>Ces valeurs partent dans le paquet JavaScript. Ce sont des
 * <b>identifiants de projet</b>, pas des clés : la {@code apiKey} de Firebase
 * n'autorise rien par elle-même. Ce qui EST un secret — la clé de compte de
 * service, celle qui permet d'<i>envoyer</i> — vit dans les réglages d'Azure,
 * côté serveur.</p>
 *
 * <p>La clé VAPID est attachée au <b>projet</b> : c'est la même que celle de
 * la boutique.</p>
 */
export const firebase = {
  apiKey: 'AIzaSyA5raZbvm3mr_MXL3gXsmHMsc1tvCn4TrU',
  authDomain: 'garah-project.firebaseapp.com',
  projectId: 'garah-project',
  storageBucket: 'garah-project.firebasestorage.app',
  messagingSenderId: '215742260538',
  appId: '1:215742260538:web:086342b06c491662c49c03',
};

export const cleVapid =
  'BJcq8tKSf81QjiT6FQ9QNuVw1qvKL_1fdYnHDUX-7u-H4u16eIhy-J-XYSGJZ6YloI8mL7a5Jh5xTHtz-Bc6Eww';
