# Verifica v0.9 — 3 ottobre 2026

## Base e perimetro

Archivio originale v0.8 conservato senza modifiche.
SHA-256 dell'archivio originale:
`62ed2363c43b7d596276ed196c17a00693e94942debe0a06800e40ae1c454cca`.

Confronto automatico: `GameEngine.kt` identico dopo la sola sostituzione del
package. `colors.xml` identico; tema identico sulle versioni che supportano
l'attributo della barra di navigazione, isolato ora in `values-v27`.
Nessuna modifica a collisioni, punteggio, pezzi, rotazioni o gravità.

## Verifiche effettuate

- Compilazione Android `assembleDebug`: riuscita.
- Android `lintDebug`: zero errori; restano 6 avvisi non bloccanti; il controllo completo non segnala errori.
  Alcuni avvisi riguardano impostazioni conservate della base, tra cui
  target SDK 35, orientamento fisso e costruttore della View per editor.
- Simulazione JVM del **codice ControllerInput compilato**, con stub Android
  per eventi e temporizzazione: **22 asserzioni superate**.
- Casi simulati: telecomando inoltrato senza alterazioni, zona morta,
  movimento laterale e repeat, ritorno al centro, rotazione senza repeat,
  priorità della diagonale verso il basso, fusione tasti/HAT, rilascio combinato,
  isteresi, fallback tastiera, reset al focus, flat del dispositivo,
  esclusione del touchscreen, Escape e pulsanti generici.
- Controllo visivo degli asset Home e banner: nome BLOCKSTV corretto,
  composizione neon e pulsanti Home conservati.

La simulazione non esegue il framework Android completo e non verifica
il comportamento fisico del firmware TCL, i grilletti, i callback di focus
oppure ogni modalità proprietaria. Nessuna prova su TV / controller fisici
è stata eseguita: completare `COMPATIBILITY_CHECKLIST.md` prima di dichiarare
la v0.9 stabile.

## Distribuzione e rollback

Il progetto sorgente non contiene cache, configurazione SDK locale o chiavi.
L'APK di collaudo usa firma debug, package `com.alexcantini.blockstv`,
versionName `0.9`, versionCode `9`. Può convivere con la v0.8.
La v0.8 resta la base stabile; riaprirla è il percorso di rollback.
Il repository remoto non è stato modificato.

## Riferimenti tecnici consultati

- [Controller Android: tasti, assi e zona morta](https://developer.android.com/games/sdk/game-controller/controller-input)
- [Banner Android TV: 320 × 180 xhdpi](https://developer.android.com/training/tv/get-started/create)
- [Compatibilità della gestione Back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
