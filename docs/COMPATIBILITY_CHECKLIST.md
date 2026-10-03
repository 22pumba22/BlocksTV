# Collaudo v0.9 su dispositivi

Stato iniziale: tutte le prove hardware sono **da eseguire**.
Annotare TV, versione Android, modello controller, modalità e collegamento.

| Dispositivo / modalità | Home e livelli | ← → ↓ ↑ | OK in gioco inerte | Pausa / riprendi | Uscita / annulla | Stato |
|---|---|---|---|---|---|---|
| Telecomando TCL della v0.8 | □ | □ | □ | □ | □ | Da eseguire |
| Telecomando Android TV standard | □ | □ | □ | □ | □ | Da eseguire |
| Gamepad Android, D-pad a tasti | □ | □ | □ | □ | □ | Da eseguire |
| Gamepad Android, D-pad HAT | □ | □ | □ | □ | □ | Da eseguire |
| Stick analogico sinistro | □ | □ | □ | □ | □ | Da eseguire |
| Controller economico, ogni modalità | □ | □ | □ | □ | □ | Da eseguire |

## Casi da controllare

1. Tenere ← o →: ripetizione regolare; rilascio immediato e nessun movimento residuo.
2. Tenere ↓ e rilasciare: caduta veloce, poi ritorno alla gravità normale.
3. Premere ↑: una sola rotazione. Ripetere dopo il rilascio / centro dello stick.
4. Diagonale verso il basso: nessuno spostamento laterale accidentale.
5. Stick vicino al centro: nessun movimento involontario; HAT prioritario sullo stick.
6. D-pad che invia sia tasti sia assi: nessuna doppia azione.
7. L2/R2 inviati come tasti e assi: una sola azione per pressione.
8. EXIT TCL seguito da Back: popup aperto una sola volta, senza richiudersi.
9. Back standard: stessa conferma d'uscita; mai uscita diretta dalla partita.
10. Confermare ANNULLA / ESCI con OK e pulsanti gamepad; annullare scelta livello.
11. Pausa e GAME OVER: comandi come v0.8; OK non modifica la partita attiva.
12. Cambio app / perdita del focus mentre si tiene ↓: input rilasciato e partita in pausa.
13. Scollegamento del controller durante una direzione tenuta: verificare comportamento del dispositivo.
14. Confrontare v0.8 e v0.9 allo stesso livello per velocità, grafica, punteggio e flusso dei menu.

Se una modalità non funziona, raccogliere i log `BlocksTVKey` di pressione e
rilascio e indicare se il D-pad / stick è riconosciuto da Android come joystick.
Non promuovere la v0.9 a base stabile prima del collaudo dei controller usati.
