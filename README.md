# Obsidian Recorder

Application Android d'enregistrement audio professionnel.

## Build via GitHub Actions

1. Poussez ce dépôt sur GitHub (branche `main`).
2. Le workflow `.github/workflows/android-build.yml` se déclenche automatiquement.
3. Ce workflow installe Gradle 8.9 puis exécute `gradle wrapper` pour régénérer
   un `gradle-wrapper.jar` valide avant de lancer `./gradlew assembleDebug`
   et `assembleRelease`. Cela évite de dépendre d'un binaire JAR committé
   dans le dépôt, qui pourrait être corrompu ou obsolète.
4. Récupérez les APKs générés dans l'onglet **Actions > Artifacts**.

## Build local

Si vous avez Gradle installé localement (ou Android Studio), régénérez
le wrapper une seule fois avant de builder :

```bash
gradle wrapper --gradle-version 8.9 --distribution-type bin
./gradlew assembleDebug
```

Alternativement, ouvrez simplement le dossier dans Android Studio : il
régénère le wrapper automatiquement au premier import du projet.
