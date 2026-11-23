# Spese — report da estratti conto CSV

App Android che legge uno o più estratti conto in CSV e genera il report delle spese
**ricorrenti**, **extra** e **tutto il resto**, mese per mese o su più mesi.
Tutto resta sul telefono: l'app non ha il permesso Internet e i dati sono esclusi dai backup.

Il progetto è sviluppato con [ECC](https://github.com/affaan-m/ecc): la cartella `.claude/` contiene
agenti, skill e regole ECC per Kotlin/Android (vedi [Sviluppo con ECC](#sviluppo-con-ecc)).

## Cosa fa

- **Importa più CSV insieme**, anche di banche diverse, dal selettore file o con "Condividi → Spese".
- Riconosce da solo il formato:
  | Formato | Come esportarlo |
  |---|---|
  | Conto principale – *Elenco movimenti* (`Operazione;Valuta;Tipologia Operazione;Descrizione;Uscite;Entrate`) | home banking → Movimenti → esporta CSV |
  | ING Conto Corrente Arancio – *Lista transazioni* | app/sito ING → Movimenti → Scarica → CSV |
  | PayPal – *Tutte le transazioni* | paypal.com → Attività → Report → Scarica (CSV) |
  | CSV generico con colonne **Data**, **Importo** (oppure **Uscite/Entrate**) e **Descrizione** | qualsiasi banca |
- **Classifica** ogni movimento in un gruppo e una categoria:
  - *Ricorrenti*: affitto, condominio, luce e gas, telefono, TARI, rate, assicurazione auto, abbonamenti, parcheggio mensile
  - *Tutto il resto*: spesa, carburante, auto, trasporti e pedaggi, salute, casa, contanti, cura persona, tasse, formazione
  - *Extra*: ristoranti e delivery, viaggi e tempo libero, shopping online, regali, videogiochi, scommesse
  - *Entrate*: stipendio, interessi, rimborsi
  - *Giroconti e risparmi*: giroconti, piano di accantonamento, depositi vincolati, ricariche PayPal (esclusi dai totali)
- **Niente doppi conteggi con PayPal**: se importi anche l'estratto PayPal, gli addebiti "PayPal" sul conto
  diventano ricariche e la spesa conta col nome vero del negozio. Gli acquisti *Paga in 3* contano per intero
  il giorno dell'acquisto; le rate successive sono escluse.
- **Report**: periodo libero (Da/A) o rapido (ultimo mese, 3, 6, 12, tutto), totali e media mensile, grafico a
  barre impilate (tocca una barra per vedere quel mese), dettaglio per categoria, tabella di confronto tra mesi,
  lista movimenti con filtri per gruppo, fonte e ricerca.
- **Correzioni a mano**: tocca un movimento per spostarlo in un altro gruppo; la scelta resta salvata.
- Reimportare un estratto (o uno più aggiornato) **non duplica** i movimenti: sostituisce i giorni che copre.

## Installare l'APK

Ogni push su `main` compila l'app con GitHub Actions:
**Actions → Android CI → ultimo run → Artifacts → `spese-apk`**. Dentro trovi `app-release.apk`
(da installare sul telefono, consentendo l'installazione da origini sconosciute) e `app-debug.apk`.

Per una release scaricabile: crea un tag, ad esempio `git tag v1.0.0 && git push --tags`; l'APK viene allegato
alla release GitHub.

> L'APK di release è firmato con la chiave di debug, così si installa subito. Per pubblicarlo su uno store
> serve una chiave di firma tua.

## Compilare in locale

Requisiti: JDK 17 e Android SDK (Android Studio va bene).

```bash
./gradlew :domain:test          # test di parser, classificazione e report
./gradlew :app:assembleDebug    # APK in app/build/outputs/apk/debug/
```

## Struttura

```
domain/   Kotlin puro, senza Android: modelli, lettore CSV, parser dei formati,
          Categorizer (regole), Reconciler (unione fonti, niente doppioni), ReportCalculator
app/      Android + Jetpack Compose: schermata report, ViewModel, salvataggio locale
```

Le regole di classificazione sono in
[`Categorizer.kt`](domain/src/main/kotlin/it/gallenti/spese/domain/categorize/Categorizer.kt):
per aggiungere un negozio basta una parola chiave nella regola giusta (parola intera; `~` finale = radice,
es. `PIZZ~` trova "PIZZERIA"). I test usano solo CSV di esempio inventati in `domain/src/test/resources/`.

**Non committare estratti conto veri**: `.gitignore` esclude i `.CSV` e la cartella `estratti/`.

## Sviluppo con ECC

`.claude/` è stato generato con l'installer ECC (profilo Kotlin, senza hook automatici):

```bash
git clone https://github.com/affaan-m/ecc.git
cd spese-report-android
node ../ecc/scripts/install-apply.js --target claude-project --no-hooks kotlin
```

Con Claude Code aperto nel progetto sono disponibili, tra gli altri:

- skill `android-clean-architecture`, `kotlin-patterns`, `kotlin-testing`, `tdd-workflow`
- agenti `kotlin-reviewer`, `kotlin-build-resolver`, `code-reviewer`, `security-reviewer`
- regole `rules/ecc/common` e `rules/ecc/kotlin`

Vedi [`CLAUDE.md`](CLAUDE.md) per le convenzioni del progetto. ECC è rilasciato con licenza MIT
(© Affaan Mustafa); i file ECC copiati in `.claude/` mantengono quella licenza.
