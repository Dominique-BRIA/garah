# GARAH

Plateforme de **commerce, service client et logistique**.

Un système d'information permettant à une entreprise de vendre ses propres produits
et ceux de marchands partenaires, de gérer la relation client, d'acheminer les
marchandises à travers des points de transit, et de piloter l'ensemble par des
statistiques et un audit complet.

---

## Stack

| Couche | Technologie |
|---|---|
| Backend | Spring Boot (Java) — une seule API |
| Base de données | PostgreSQL |
| Frontend | Angular — trois applications + une librairie partagée |

### Les trois frontends

| Application | Public | Rôle |
|---|---|---|
| `garah-web` | Tout le monde | Site vitrine, catalogue public, SEO |
| `garah-client` | Clients authentifiés | Panier, commandes, suivi, conversations |
| `garah-admin` | SuperAdmin / Admin / Responsable | Back-office complet |

Les trois partagent `garah-ui` (design system, modèles, client HTTP) tout en
gardant chacune **son propre thème visuel**.

---

## Structure du dépôt

```text
garah/
├── backend/          Spring Boot                (à venir)
├── frontend/         workspace Angular          (à venir)
├── docs/
│   ├── cours/        le cours — commencer par 00-sommaire.md
│   └── decisions.md  journal des décisions structurantes
└── .gitignore
```

---

## Documentation

Ce projet est documenté sous forme de **cours**, pas de documentation technique :
chaque avancée donne lieu à un chapitre expliquant la notion, son application
à GARAH, et les pièges rencontrés.

👉 **Point d'entrée : [docs/cours/00-sommaire.md](docs/cours/00-sommaire.md)**

Le *pourquoi* de chaque choix structurant est dans
**[docs/decisions.md](docs/decisions.md)**.

---

## Conventions

- Code en anglais, **métier en français** (tables, codes de permissions, libellés).
- Commits en français.
- Les codes de cas d'utilisation (`PRODUIT_PUBLIER`, …) ne sont **jamais renommés**
  une fois en production.
