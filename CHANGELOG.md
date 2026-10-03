# Changelog

## 0.9 — Branding e compatibilità

Derivazione separata della base stabile 0.8.

### Branding

Nome app, progetto Gradle, package, log di diagnostica e classe della View
aggiornati a BlocksTV. Nuovi testi nelle immagini Home e banner; logo del
pannello di gioco aggiornato. README e screenshot coerenti con il nuovo nome.

### Input

Nuovo `ControllerInput.kt`: normalizza solo l'input, con D-pad tasti/HAT,
stick sinistro, fallback tastiera, ripetizione laterale, zona morta, rilascio
con isteresi e fusione delle direzioni duplicate. `MainActivity.kt` collega
l'adattatore alla View e resetta gli input alla perdita del focus / pausa o rimozione / riconfigurazione del dispositivo.
`BlocksView.kt` conserva i comandi esistenti e aggiunge Su del gamepad,
conferma nei menu tramite pulsanti gamepad, Start/Select e Back standard.
I grilletti digitali e analogici hanno latch separati per evitare doppie azioni.
Il manifest mantiene il callback Back compatibile con il flusso della View.

### Invariato

`GameEngine.kt` è identico alla v0.8, salvo la dichiarazione del package.
Aspetto del tema, colori, rendering della griglia/blocchi, livelli, collisioni, rotazioni,
punteggi, gravità e tempi di caduta veloce non sono cambiati.

### Risorse di compatibilità

L'attributo `windowLightNavigationBar` già presente nella v0.8 viene isolato
in `values-v27`, mantenendo lo stesso tema sulle versioni che lo supportano.
Il banner 320 × 180 usa la cartella `drawable-xhdpi` prevista per Android TV.

### Collaudo

Compilazione e simulazione degli input descritte in `docs/VALIDATION.md`.
Il collaudo su dispositivi fisici resta necessario prima di promuovere la release.
