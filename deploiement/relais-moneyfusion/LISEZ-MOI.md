# Relais à IP fixe vers MoneyFusion

> Pourquoi il existe : D-55, ajout du 10/10/2026.
> MoneyFusion n'accepte qu'**une** IP déclarée ; Azure sort par 25 IP qui tournent.

## Où il tourne

| | |
|---|---|
| Serveur | VPS **prêté** par un collègue — `alanyavox.com`, IP fixe `141.95.170.46` |
| Logiciel | `tinyproxy` 1.11 (paquet Ubuntu), service `tinyproxy` |
| Port | `18443` |
| Côté GARAH | `GARAH_MONEYFUSION_PROXY=141.95.170.46:18443` |

⚠️ **D'autres sites tournent sur ce serveur.** On n'y touche à rien d'autre : ni
nginx, ni PostgreSQL, ni Redis, ni coturn, ni les applications Node.

## Ce qu'il voit : rien

C'est un proxy **CONNECT**. Le chiffrement TLS va de GARAH jusqu'à MoneyFusion ;
le relais ne passe que des octets chiffrés. Il ne voit ni le lien secret, ni les
montants, ni les clients. La seule ligne en clair est `CONNECT hôte:443`.

## Les deux verrous

1. **Pare-feu** (ufw, refus par défaut) : le port 18443 n'est ouvert qu'aux 25
   IP d'Azure — 25 règles commentées « relais GARAH MoneyFusion ».
2. **tinyproxy** : n'accepte que ces mêmes IP (`Allow`), et ne relaie que vers
   `*.moneyfusion.net:443` (`filtre-garah`, `FilterDefaultDeny Yes`).

## Les fichiers

| Dans ce dossier | Sur le serveur |
|---|---|
| `tinyproxy.conf` | `/etc/tinyproxy/tinyproxy.conf` (l'original : `tinyproxy.conf.orig`) |
| `filtre-garah` | `/etc/tinyproxy/filtre-garah` |

## Vérifier qu'il marche (depuis le serveur)

```bash
systemctl is-active tinyproxy                                    # active
curl -s -o /dev/null -w "%{http_code}\n" -x http://127.0.0.1:18443 https://pay.moneyfusion.net/paiementNotif/x   # 201
curl -s -o /dev/null -w "%{http_code}\n" -x http://127.0.0.1:18443 https://www.google.com/                      # 000 : refusé
sudo tail /var/log/tinyproxy/tinyproxy.log
```

## ⚠️ Si les IP sortantes d'Azure changent

Elles changent si l'offre Azure change (B1 → autre). Il faut alors mettre à jour
**les deux** listes : les lignes `Allow` de `tinyproxy.conf` et les règles ufw.
Sinon les paiements échouent avec une erreur de connexion au relais.

## Le jour où le serveur est rendu

```bash
sudo apt-get remove tinyproxy
sudo ufw status numbered        # repérer les règles « relais GARAH MoneyFusion »
sudo ufw delete <numéro>        # une par une, en partant de la plus grande
```

Puis déclarer une nouvelle IP fixe chez MoneyFusion et changer
`GARAH_MONEYFUSION_PROXY`. Entre les deux, les paiements MoneyFusion
s'arrêtent : remettre `GARAH_PAIEMENT_FOURNISSEUR=CAMPAY` le temps du changement.
