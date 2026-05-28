# ResourcePackManager — Plugin du Proxy

> **⚠ Ce plugin n'est utile que pour les serveurs utilisant Geyser + Floodgate.**
> Son seul rôle est de fusionner le resource pack converti en Bedrock de chaque
> backend et de le servir aux clients Bedrock via Geyser. **Les joueurs Java
> Edition n'ont pas besoin de ce plugin** — la livraison des packs pour Java
> dans les configurations en réseau est gérée directement par les backends
> individuels. Si votre réseau est uniquement Java (sans joueurs Bedrock
> passant par Geyser), vous pouvez ignorer entièrement ce dossier.

Ce dossier contient les fichiers JAR du **plugin proxy de RSPM**. Ils vont sur votre
**proxy** (Velocity / BungeeCord / Waterfall), PAS sur le serveur backend Minecraft.
Le plugin du proxy fusionne le resource pack converti en Bedrock de chaque backend
en un seul pack et le livre aux clients Bedrock via Geyser.

Si vous exécutez un serveur autonome (sans proxy devant), vous pouvez ignorer
entièrement ce dossier. Les JAR sont extraits à chaque démarrage de toute façon, ils
sont donc prêts si vous ajoutez un proxy plus tard.

---

## Quel JAR utiliser ?

Choisissez EXACTEMENT UN selon le logiciel de proxy que vous utilisez :

| Logiciel de proxy        | Utilisez ce JAR                          |
|--------------------------|------------------------------------------|
| Velocity                 | `ResourcePackManager-Velocity.jar`       |
| BungeeCord               | `ResourcePackManager-BungeeCord.jar`     |
| Waterfall (fork de Bungee) | `ResourcePackManager-BungeeCord.jar`   |

N'installez pas les deux — seulement celui qui correspond. Installer les deux
provoquera des erreurs de démarrage sur la plateforme qui essaiera de charger le
mauvais JAR.

---

## Installation — 2 étapes

### 1. Copier le JAR sur l'hôte du proxy

Copiez le JAR approprié de CE dossier vers le répertoire `plugins/` de votre proxy.
Méthodes courantes :

- **Même machine** : glisser-déposer, ou `cp` / `copy`.
- **Proxy distant** : `scp ResourcePackManager-Velocity.jar utilisateur@hote-proxy:/chemin/vers/proxy/plugins/`
- **Panneau de proxy hébergé** (Pterodactyl, etc.) : téléversement via le gestionnaire de fichiers du panneau.

### 2. Redémarrer le proxy

C'est tout. Aucune configuration à éditer, aucune clé à coller.

Le plugin lit `plugins/floodgate/key.pem` sur le proxy au démarrage et dérive
automatiquement l'identité de réseau de ce fichier. Comme Floodgate exige déjà
que ce fichier soit le même sur chaque backend ET sur le proxy (pour que
l'authentification Bedrock fonctionne), la clé dérivée correspond automatiquement
à celle de chaque backend.

La première fusion se termine généralement en ~10 secondes après que tous les
backends soient en ligne.

---

## Vérifier que ça fonctionne

**Console du proxy** (dans les ~10s suivant le redémarrage, en supposant que les backends tournent) :

```
[ResourcePackManager] Merged pack ready at .../merged/Bedrock.zip (sha1 ...)
[ResourcePackManager] Geyser mappings deployed to .../custom_mappings
[ResourcePackManager] ✔ Network resource pack is now ready (... KB, sha1ABCD1234)
```

**Client Bedrock** : connectez-vous en Bedrock. Vous devriez voir l'invite de
téléchargement du resource pack avant d'arriver dans le monde. Les objets
personnalisés s'affichent avec leurs modèles prévus au lieu d'armor stands nus.

**`/rspm status`** sur le backend : montre l'état du pack, le mode d'hébergement
et l'empreinte de la network-key. Comparez les 4 derniers caractères à la config
du proxy pour confirmer que les deux côtés sont liés.

---

## Problèmes courants

### "Floodgate key.pem missing" au démarrage du proxy

Le plugin du proxy n'a pas trouvé `plugins/floodgate/key.pem` et reste inactif.
Correction :

1. **Installez Floodgate** sur le proxy. C'est nécessaire pour que les joueurs
   Bedrock atteignent le proxy de toute façon, donc c'est requis indépendamment
   de RSPM.
2. Assurez-vous que `plugins/floodgate/key.pem` sur le proxy est **identique
   octet pour octet** au même fichier sur chaque backend. Floodgate génère par
   défaut des clés différentes pour chaque installation — copiez un `key.pem`
   canonique depuis n'importe quel backend vers tous les autres composants
   (autres backends + proxy), puis redémarrez tout. Floodgate l'exige déjà pour
   l'authentification Bedrock, donc si les joueurs Bedrock fonctionnent
   actuellement sur votre réseau, c'est déjà fait.

### Bedrock se connecte mais ne voit pas les modèles personnalisés

Cause la plus fréquente : le proxy a démarré AVANT que le backend ne produise son
premier pack Bedrock. Geyser n'enregistre les objets personnalisés qu'au démarrage —
une fois lancé avec un fichier de mappings vide, il reste ainsi. **Redémarrez le
proxy** après que le backend ait journalisé `Wrote merged Geyser mappings: N entries`.
Les fusions suivantes sont reprises automatiquement par le chemin de livraison de pack
de Geyser, mais la table des objets personnalisés est fixée au démarrage.

### Avertissements "Duplicate bedrock_identifier" au démarrage du proxy

Deux backends ont émis le même identifiant Bedrock pour le même objet de base.
Le dernier écrivain gagne ; sans conséquence si vous n'avez besoin que d'un backend
pour fournir cet objet. Si les deux backends doivent héberger des objets personnalisés
distincts sous le même objet de base, vous avez une vraie collision — renommez l'un
des modèles Java source pour que les hashes auto-générés diffèrent.

### Mise à jour de RSPM

Après avoir mis à jour le JAR backend de RSPM, recopiez aussi le
`ResourcePackManager-Velocity.jar` / `-BungeeCord.jar` correspondant de ce dossier
vers le proxy et redémarrez le proxy. Le backend les régénère à chaque démarrage,
ils sont donc toujours synchronisés avec la version du backend.

---

Généré par ResourcePackManager v${version} au démarrage du backend.
Autres langues disponibles : voir `README.md` (anglais / English), `README - espanol.md`,
`README - portugues.md`, `README - zhongwen.md`, `README - hindi.md`,
`README - bangla.md`, `README - arabi.md`, `README - russkij.md`, `README - urdu.md`.
