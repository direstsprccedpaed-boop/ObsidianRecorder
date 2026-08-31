# Obsidian Recorder

Application Android d'enregistrement audio professionnel avec :

- Moteur audio bas niveau `AudioRecord` (PCM 16-bit, 44.1 kHz, mono)
- Encodage AAC-LC temps réel via `MediaCodec` en mode asynchrone (callback-driven, sans NDK)
- Muxing `.m4a` via `MediaMuxer`
- Auto-trigger / skip silence avec hystérésis et pré-roll anti-clipping
- Transcription en direct via `SpeechRecognizer` (mode continu, redémarrage automatique)
- Export `.txt`, copie presse-papiers, partage via Sharesheet
- Découpe rapide du fichier final par taille cible sans ré-encodage (`MediaExtractor` / `MediaMuxer`)
- Foreground Service avec `PARTIAL_WAKE_LOCK`, conforme Android 14/15
- UI Compose "Obsidian" (dark minimalist, Material You)

## Build manuel via GitHub Actions

1. Poussez ce dépôt sur GitHub (branche `main`).
2. Le workflow `.github/workflows/android-build.yml` se déclenche automatiquement.
3. Récupérez les APKs (`debug` et `release non signé`) dans l'onglet **Actions > Artifacts**.

## Build local

```bash
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

## Structure du projet

```
app/
 ├─ build.gradle.kts
 ├─ src/main/AndroidManifest.xml
 └─ src/main/java/com/spasfonk/obsidianrecorder/
     ├─ MainActivity.kt
     ├─ RecorderApplication.kt
     ├─ audio/AudioRecordEngine.kt
     ├─ audio/AudioSplitter.kt
     ├─ audio/TranscriptionManager.kt
     ├─ service/RecordingService.kt
     └─ ui/ (ViewModel, Screen, composants, thème)
```
