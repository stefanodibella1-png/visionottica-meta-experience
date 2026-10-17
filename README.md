# VisionOttica Meta Experience

Esperienza 3D per i negozi VisionOttica: showroom virtuale con **Sofia**, consulente digitale animata e parlante, e le funzioni dei **Ray-Ban Meta**.

| Parte | Cartella | Stato |
|---|---|---|
| Showroom 3D, Sofia, sei stazioni, funzioni (web) | `web/` | funzionante, pubblicata come artifact su claude.ai |
| App Android con **Meta Wearables Device Access Toolkit 1.0.1** | `android/` | compilata da GitHub Actions, **da verificare sugli occhiali** |
| Strumenti (voce, divisa, occhi, sync) | `tools/` | — |

## App Android

Basata sull'esempio ufficiale Meta *CameraAccess* (DAT 1.0.1, Maven Central `com.meta.wearable`).

- All'avvio: lo showroom 3D (la stessa esperienza web, inclusa offline nell'APK).
- **Collega gli occhiali** → registrazione tramite l'app Meta AI (`Wearables.startRegistration`).
- **Fotocamera degli occhiali** → sessione DAT, anteprima live dagli occhiali, foto e video (codice ufficiale Meta).
- Voce di Sofia: frasi registrate (Kokoro "Sara", Apache-2.0) + sintesi vocale Android per le risposte dinamiche.

### Installare l'APK
Ogni push su `main` compila l'app. Scarica `VisionOttica-Meta.apk` dalla release **apk-latest**.

### Usare gli occhiali (sviluppo)
1. App **Meta AI** installata, occhiali abbinati.
2. Nell'app Meta AI attiva la **Modalità sviluppatore** (le credenziali dell'APK sono `0`, cioè modalità sviluppatore).
3. Apri VisionOttica Meta → **Collega gli occhiali** → conferma nell'app Meta AI.

Per la distribuzione in negozio servono `APPLICATION_ID` e `CLIENT_TOKEN` dal Wearables Developer Center (in `android/local.properties`).

### Non ancora inclusi (sperimentali, richiedono approvazione nel Wearables Developer Center)
- "Hey Meta, avvia VisionOttica" (Voice Invocations)
- Tasto azione degli occhiali (Inputs), audio dai microfoni degli occhiali

## Aggiornare l'esperienza web dentro l'app
```
python3 tools/sync_web_to_android.py <cartella three.js r186>
```

## Crediti
- Sofia: Microsoft Rocketbox (MIT), animazioni motion capture Rocketbox.
- Voce: Kokoro-82M, voce if_sara (Apache-2.0), generata offline con sherpa-onnx.
- Modello occhiali: asset Sketchfab fornito dal committente (verificare licenza).
- Meta Wearables Device Access Toolkit: soggetto ai Meta Wearables Developer Terms.
