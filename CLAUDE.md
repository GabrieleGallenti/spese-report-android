# CLAUDE.md — Spese (Android)

App Android che importa estratti conto CSV e genera report spese mensili. Lingua dell'interfaccia e dei
commenti: italiano.

## Architettura (ECC `android-clean-architecture`)

- `:domain` — Kotlin puro, nessuna dipendenza Android. Qui vivono parsing, classificazione e calcolo report.
  Ogni modifica alla logica va qui, con test.
- `:app` — Compose + ViewModel + `LocalStore`. Niente logica di business nelle composable.
- Dipendenze: `app → domain`. Mai il contrario.

## Regole

- Segui `.claude/rules/ecc/common` e `.claude/rules/ecc/kotlin` (niente `!!`, `val` di default, `Result`/sealed per gli errori,
  mai catturare `CancellationException`).
- TDD (skill `tdd-workflow`, `kotlin-testing`): un nuovo formato CSV o una nuova regola di classificazione
  arriva con un test in `domain/src/test` e un CSV di esempio **inventato** in `domain/src/test/resources`.
- Privacy: niente permesso INTERNET, `allowBackup=false`, nessun dato reale nel repo, nei log o nei test.
- Importi sempre in centesimi (`Long`), mai `Double` per i soldi.

## Comandi

```bash
./gradlew :domain:test
./gradlew :app:assembleDebug
```

## Agenti utili

- `kotlin-reviewer` prima di ogni PR
- `kotlin-build-resolver` se la build Gradle fallisce
- `security-reviewer` quando si tocca import file o salvataggio dati
