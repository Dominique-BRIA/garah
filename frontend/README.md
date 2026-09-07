# Le frontend GARAH

Un espace de travail Angular contenant **une librairie** et **une application** :

| Projet | Rôle |
|---|---|
| `garah-ui` | la librairie partagée : thème, composants, modèles, service de session |
| `garah-admin` | le back-office |

La vitrine et l'espace client viendront s'ajouter ici (D-03).

---

## Lancer le back-office

⚠️ **Deux terminaux. Ce n'est pas une préférence, c'est une nécessité.**

```bash
cd frontend

# Terminal 1 — la librairie, en surveillance
npm run ui:watch

# Terminal 2 — l'application
npm run admin
```

Puis http://localhost:4200

### Pourquoi deux, et ce qui arrive si on n'en lance qu'un

`garah-admin` consomme `garah-ui` de **deux façons différentes**, et c'est le
piège le plus coûteux de ce dépôt :

| Ce que l'application consomme | D'où | Rechargé à chaud ? |
|---|---|---|
| `theme/*.scss` — couleurs, cartes, boutons | **la source**, directement | oui |
| `src/lib/**` — composants, icônes, modèles | **`dist/garah-ui`** | **non** |

Sans le terminal 1, `ng serve` sert une version **périmée** de la librairie.
Le symptôme est déroutant parce qu'il est *partiel* : les changements de style
apparaissent, les changements de composants non. On a perdu une demi-heure à
chercher pourquoi des icônes ajoutées et bien présentes dans le code ne
s'affichaient pas — elles étaient dans la source et dans `dist`, mais le
serveur, démarré avant la reconstruction, servait l'ancienne.

`npm run ui:watch` reconstruit la librairie à chaque modification. Le problème
disparaît définitivement.

### Si le port est déjà pris

`ng serve` refuse de démarrer et propose un autre port. Le processus précédent
survit souvent à un `Ctrl+C` mal pris. Pour le libérer :

```powershell
Get-NetTCPConnection -LocalPort 4200 -State Listen |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

---

## ⚠️ Le mode développement parle à l'API de PRODUCTION

`environment.development.ts` pointe sur le Worker Cloudflare, c'est-à-dire la
vraie base. C'est **délibéré** — voir le commentaire du fichier : on exerce
pour de vrai le CORS, les cookies `SameSite=None` et les URL signées, trois
mécanismes qu'un backend sur `localhost` masquerait entièrement.

**Deux conséquences à connaître :**

1. **Une suppression depuis le back-office supprime pour de bon.**

2. **Une route ajoutée en local n'existe pas tant qu'elle n'est pas déployée.**
   L'écran répondra « Cette adresse n'existe pas » alors que le code est
   correct sur la machine. Il faut pousser sur `main` — Render redéploie tout
   seul — et attendre environ deux minutes de démarrage.

Pour travailler contre un backend local, changer `urlApi` en
`http://localhost:8080` et lancer l'API (voir `backend/`). Le CORS acceptera
`localhost:4200` s'il figure dans `GARAH_CORS_ORIGINS`.

---

## Compiler

```bash
npm run build          # ng build (les deux projets selon la cible)
npx ng build garah-ui  # la librairie seule
npx ng build garah-admin
```

La librairie doit toujours être compilée **avant** l'application : celle-ci
lit `dist/garah-ui`, pas les sources.

---

## Les scripts

| Commande | Ce qu'elle fait |
|---|---|
| `npm run ui:watch` | reconstruit `garah-ui` à chaque modification |
| `npm run admin` | sert le back-office sur le port 4200 |
| `npm run build` | compile |
| `npm test` | les tests unitaires |
