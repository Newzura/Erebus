# Erebus

**Erebus par newzura, 2026**

Erebus est une application Android permettant de dupliquer l'écran d'un smartphone Android sur l'unité embarquée Android Auto en mode plein écran natif, avec proxy MediaSession pour contrôler les médias du téléphone depuis l'interface Android Auto, et des fonctionnalités privilégiées pour adapter la résolution et autoriser l'injection tactile réelle.

---

## Compilation et installation

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.newzura.erebus android:project_media allow
```

---

## Test avec Desktop Head Unit (DHU)

1. SDK Manager → SDK Tools → « Android Auto Desktop Head Unit emulator »
2. Téléphone branché USB : Android Auto → Paramètres → 10 taps sur « Version et infos d'autorisation » → ⋮ → Developer Settings → Head unit server
3. Sur l'ordinateur :
   ```bash
   adb forward tcp:5277 tcp:5277
   $HOME/Library/Android/sdk/extras/google/auto/desktop-head-unit/desktop-head-unit -t
   ```
4. Erebus doit apparaître dans le launcher DHU. Debug :
   ```bash
   adb logcat -s Erebus CarApp
   ```

---

## Procédure d'activation depuis un ordinateur (ADB)

Pour activer les fonctionnalités d'adaptation d'écran et d'injection tactile sans root ni Shizuku, connectez le téléphone en débogage USB à un ordinateur et exécutez les deux commandes suivantes dans un terminal :

```bash
adb shell pm grant com.newzura.erebus android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.newzura.erebus android.permission.INJECT_EVENTS
```

> **Notes d'exploitation :**
> - Procédure **à refaire après chaque réinstallation complète** de l'APK (désinstallation puis installation).
> - Une mise à jour avec `adb install -r` conserve automatiquement les permissions accordées.
> - L'extinction matérielle de la dalle du téléphone (`SurfaceControl.setDisplayPowerMode`) nécessite des privilèges système plus profonds et reste réservée aux voies **Shizuku** ou **Root**.

---

## États de l'accès privilégié

L'état actuel s'affiche en temps réel sur la carte d'accueil et dans les paramètres :
- **Permissions accordées (via ordinateur)** : activation directe via `WRITE_SECURE_SETTINGS` et `INJECT_EVENTS`.
- **Shizuku** : service utilisateur lié via Shizuku API v12.
- **Root** : service privilégié IPC lié via `libsu` (`RootService`).
- **Aucun accès privilégié** : mode standard (avec repli tactile via le service d'accessibilité si activé).

---

## Limitations connues

- **Contenu protégé DRM (Netflix, Disney+)** : écran noir — restriction de la plateforme Android interdisant la capture vidéo des surfaces sécurisées DRM (`FLAG_SECURE`).
- **Sans Shizuku/root**, l'écran du téléphone doit rester allumé pendant le mirroring (les permissions `pm grant` n'autorisent pas l'appel à `DisplayControl`/`SurfaceControl`).
- **Android 14+** : si Erebus n'apparaît pas dans le launcher AA, activer le mode développeur AA → Developer Settings → Unknown sources (Sources inconnues).

---

## Checklist configuration appareil

- [ ] Activer le débogage USB dans les options développeur du smartphone.
- [ ] Autoriser l'accès aux notifications pour Erebus (`Paramètres > Accès aux notifications`) afin d'activer le proxy média.
- [ ] Accorder la permission de superposition (`SYSTEM_ALERT_WINDOW`) pour les raccourcis flottants si souhaité.
- [ ] Pour le tactile sans accès privilégié : activer le service d'accessibilité Erebus (`Paramètres > Accessibilité`).
- [ ] Pour l'accès privilégié sans root : exécuter le script `adb shell pm grant` ou démarrer Shizuku.

---

## Sécurité et résilience

- **Relecture-vérification** : le système ne valide aucune modification d'affichage sans avoir au préalable lu et sauvegardé la géométrie native, et sans avoir vérifié la relecture après écriture.
- **Fusible de touches** : toute touche injectée est surveillée (`pending keys`) pour éviter qu'un événement `ACTION_DOWN` ne reste maintenu indéfiniment.
- **Restauration d'urgence** : en cas d'interruption du service, les résolutions et densités natives du téléphone sont automatiquement restaurées via le jeton `linkToDeath`.
