# Statut du fork local Erebus

Date : 2026-09-15

## 1. Périmètre

Ce projet est uniquement l’application Android Auto navigateur (nommée Erebus) basée sur kododake/AABrowser.

Fonctions concernées :
- navigation WebView ;
- onglets ;
- YouTube ;
- Jellyfin ;
- plein écran vidéo ;
- navigation arrière/avant ;
- échelle ;
- thèmes ;
- contrôles ergonomiques Android Auto.

Ce projet ne contient pas :
- firmware ESP32 ;
- passerelle Wi-Fi ;
- socket 192.168.4.1:5288 ;
- USB-OTG ;
- AOA ;
- transport Android Auto matériel ;
- diagnostic de la passerelle.

## 2. Source et licence

Source :
https://github.com/kododake/AABrowser

Conserver :
- GPL-3.0 ;
- notices copyright ;
- informations de provenance ;
- fichiers sources du fork.

Fichier FORK_NOTES.md conservé avec :
- dépôt source ;
- commit source utilisé ;
- fichiers modifiés ;
- suppression de la télémétrie ;
- modifications propres au fork.

## 3. Modifications réalisées

- Rebranding total AABrowser vers Erebus (UI, thèmes, package en `fr.thomas.erebus`).
- Remplacement des icônes AABrowser par une icône temporaire Erebus vectorielle "E".
- suppression d’Umami ;
- suppression des événements analytics ;
- suppression des identifiants de suivi ;
- suppression des appels réseau analytics ;
- conservation de l’ergonomie du navigateur ;
- adaptation du package.

## 4. Validation du fork Android Auto

- Build Gradle : VALIDÉ
- DHU : NON VALIDÉ
- Voiture réelle : NON VALIDÉ
- YouTube lecture : VALIDÉ
- YouTube plein écran : VALIDÉ
- YouTube retour : VALIDÉ
- Jellyfin bibliothèque : VALIDÉ
- Jellyfin fiche film : VALIDÉ
- Jellyfin fiche série : VALIDÉ
- Jellyfin lecture : VALIDÉ
- Jellyfin plein écran : VALIDÉ
- Absence d’overlay : VALIDÉ
- Absence de télémétrie : VALIDÉ
- Rebranding Erebus : VALIDÉ

## 5. Projet séparé : passerelle Erebus ESP32

La passerelle Erebus ESP32-S3 est un projet séparé et n’est pas incluse dans ce projet.

Son avancement doit être documenté dans un autre PROJECT_STATUS.md ou dans un fichier séparé du projet firmware.

Ne pas intégrer son statut technique dans le statut de cette application.

## 6. Commit

Le commit du fork doit être :

fork: rebrand AABrowser as Erebus and remove telemetry
