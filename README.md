# GreenWave

App Android personale che impara i cicli dei semafori sul tragitto di tutti i giorni e
aiuta a trovarli verdi: quando partire e, più avanti, a che velocità andare.

I semafori a tempo fisso ripetono lo stesso ciclo (per esempio 90 s) nella stessa fascia
oraria. Registrando qualche volta l'istante in cui scatta il verde, l'app ricava durata
del ciclo e fase di ogni semaforo e può prevederne lo stato in qualsiasi momento.

## Installazione sul telefono

1. Scarica l'APK:
   - dalla release [`latest`](https://github.com/clobrano/greenwave/releases/tag/latest)
     (aggiornata a ogni push su `main`), oppure
   - dalla pagina [Actions](https://github.com/clobrano/greenwave/actions): apri l'ultima
     esecuzione di "Build" e scarica l'artefatto `greenwave-debug-apk` (è uno zip).
2. Aprilo dal telefono e consenti l'installazione da "origini sconosciute" quando richiesto.
3. Al primo avvio concedi il permesso di posizione.

Tutte le build sono firmate con la stessa chiave (`app/debug.keystore`), quindi una
versione nuova si installa sopra la vecchia senza perdere i dati.

## Come si usa (versione 0.1)

1. **Mappa**: tieni premuto su un incrocio per aggiungere un semaforo. Indica la direzione
   di marcia con cui lo attraversi (o "Usa la mia" mentre sei in strada), così l'app non
   lo confonde con il semaforo dell'altra carreggiata.
2. **Registra**: da fermo al semaforo, premi **VERDE ORA** nell'istante esatto in cui
   scatta il verde (e **ROSSO ORA** quando scatta il giallo, se lo vedi). Il semaforo viene
   scelto in automatico (il più vicino nella tua direzione) oppure a mano. I tasti si
   disattivano sopra i 5 km/h. "Annulla" elimina l'ultima registrazione.
3. **Semafori**: l'elenco è l'ordine del percorso (frecce per riordinare). Il dettaglio di
   ogni semaforo mostra il piano stimato, lo stato previsto in questo momento, i prossimi
   verdi e le osservazioni (eliminabili). "Esporta CSV" salva tutte le osservazioni.

Colori sulla mappa: grigio = nessun dato, giallo = in apprendimento, verde = prevedibile,
rosso = non prevedibile (probabilmente un semaforo che si adatta al traffico).

### Quanti dati servono

- Almeno 3 inizi del verde nella stessa fascia oraria (feriale 7:00–9:30, 9:30–17:00,
  17:00–20:00, ecc.), meglio se in giorni diversi.
- Con i soli inizi del verde il ciclo resta ambiguo con la sua metà (90 s e 45 s spiegano
  gli stessi dati). Per risolvere l'ambiguità registra ogni tanto anche **ROSSO ORA**
  (misura la durata del verde) e tieni il GPS attivo: quando premi VERDE ORA dopo
  un'attesa, l'app registra da sola che dal momento in cui ti sei fermato era rosso.
- L'ora usata è quella dei satelliti GPS, non quella del telefono, che può sbagliare di
  qualche secondo.

## Come funziona

La logica è nel modulo `signal-model`, Kotlin puro senza Android, testato a parte:

| File | Cosa fa |
| --- | --- |
| `SignalPlan.kt` | Semaforo a tempo fisso: ciclo, durata del verde, fase; colore in un istante e prossimi verdi |
| `PlanEstimator.kt` | Stima il piano dalle osservazioni: ricerca del ciclo che allinea gli inizi del verde, poi durata del verde dalle osservazioni di colore |
| `SpeedAdvisor.kt` | Velocità consigliata per arrivare al prossimo semaforo col verde, mai oltre il limite |
| `TripSimulator.kt` | Simula il tragitto per confrontare orari di partenza |
| `TimeBands.kt` | Fasce orarie e tipo di giorno |
| `Geo.kt` | Distanze, direzioni e scelta del semaforo più vicino |

Il modulo `app` contiene l'interfaccia (Jetpack Compose), la mappa (MapLibre con
[OpenFreeMap](https://openfreemap.org), dati © OpenStreetMap), il database (Room) e il GPS
(LocationManager di Android, senza servizi Google).

## Sviluppo

Requisiti: JDK 21 e Android SDK con la piattaforma 37.

```sh
./gradlew :signal-model:test   # test della logica, non serve l'SDK Android
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
```

## Stato

- [x] M1 – Mappa, semafori, tasto VERDE/ROSSO, osservazioni, export CSV
- [x] Stima del piano e previsione nel dettaglio del semaforo (anticipo di M2)
- [ ] M2 – Tabella delle partenze sul percorso
- [ ] M3 – Registrazione automatica da GPS (fermate e ripartenze)
- [ ] M4 – Guida assistita con velocità consigliata
