# GreenWave

A personal Android app that learns the traffic light cycles on your daily route and helps
you catch them green: when to leave and, later on, how fast to drive.

Fixed-time traffic lights repeat the same cycle (for example 90 s) within the same time
band. By recording a few times the instant a light turns green, the app works out each
light's cycle length and phase, and can predict its state at any moment.

## Installing on the phone

1. Download the APK:
   - from the [`latest`](https://github.com/clobrano/greenwave/releases/tag/latest) release
     (updated on every push to `main`), or
   - from the [Actions](https://github.com/clobrano/greenwave/actions) page: open the latest
     "Build" run and download the `greenwave-debug-apk` artifact (it is a zip).
2. Open it on the phone and allow installing from unknown sources when asked.
3. On first launch, grant the location permission.

Every build is signed with the same key (`app/debug.keystore`), so a new version installs
over the old one without losing data.

## How to use it (version 0.1)

1. **Map**: long-press on an intersection to add a traffic light. Set the driving direction
   you cross it with: it is pre-filled with your heading while driving, otherwise you must
   choose it. The direction tells apart the lights of a crossroads, so a light set to "Any"
   is only recorded with the buttons; such lights show a warning, and they take your
   direction the first time you press GREEN NOW or RED NOW at them. While driving, the map stays locked on the car and turns
   with the driving direction; "Unlock map" (or moving the map by hand) frees it, and
   "Follow car" locks it again.
2. **Record**: while stopped at the light, press **GREEN NOW** at the exact moment it turns
   green (and **RED NOW** when it turns amber, if you see it). The light is picked
   automatically (the nearest one in your direction) or by hand. The buttons are disabled
   above 5 km/h. "Undo" deletes the last recording.
   Above the buttons, a card shows the next light ahead in your direction (up to 500 m).
   Once that light is predictable it shows its expected color with a countdown, and the
   speed to keep to arrive on green ("Keep 38 km/h", never above the light's speed limit),
   or "SLOW DOWN" with the time of the next green. While driving (above about 8 km/h) the
   recording controls are hidden and the prediction fills the screen with very large text,
   to be read at a glance; they come back when you stop.
3. **Auto-record trip** (top of the Record tab): press Start before leaving and drive as
   usual. The app follows the GPS, also with the screen off, and records by itself what
   happens at each of your lights. A notification shows the events detected so far and has
   a Stop button.
   - Passing a light without stopping: it was green at that moment.
   - Stopping within 60 m of a light: it was red from when you stopped, and it turned green
     about 2 s before you moved off (more if cars were queued ahead of you; with more than
     about 4 cars ahead only the red is kept).
   - Only lights whose direction matches yours count: crossing a crossroads on the other
     road, or turning into a side street before the light, records nothing for it.
   - Stops farther away (a pedestrian crossing, a car turning) are ignored, and a button
     press near the same moment is not recorded twice.
4. **Lights**: the list is the route order (arrows to reorder). Each light's detail shows
   the estimated plan, the predicted state right now, the next greens and the observations
   (which can be deleted). "Export CSV" saves all observations.

Map colors: gray = no data, amber = learning, green = predictable, red = unpredictable
(probably a light that adapts to traffic).

### How much data is needed

- Everything counts: passes on green, stops at red, GPS-detected restarts and button presses.
  A pass weighs 1; a start of green (a GREEN NOW press, or a restart after a stop) weighs
  about 4. About 12 is the minimum, spread over different cycles, in the same time band
  (weekday 7:00–9:30, 9:30–17:00, 17:00–20:00, etc.). Passes alone are enough, though it
  usually takes a few dozen of them for the cycle to stand out.
- When there is no prediction, the Record card and the light's detail page say why: not
  enough data (and how much is missing), several possible cycles (with their lengths), or
  observations that fit no fixed cycle (probably a light that adapts to traffic). They also
  say how many observations are in other time bands.
- The time used is the GPS satellite time, not the phone clock, which can be off by a few
  seconds.

## How it works

The logic lives in the `signal-model` module, plain Kotlin without Android, tested on its own:

| File | What it does |
| --- | --- |
| `SignalPlan.kt` | Fixed-time traffic light: cycle, green duration, phase; color at an instant and next greens |
| `PlanEstimator.kt` | Estimates the plan by phase folding: for each candidate cycle, folds every observation (seen green/red, starts of green/red) onto the cycle and finds the arc of green that misplaces the fewest; the cycle with the fewest errors wins and the arc gives start and duration of green |
| `SpeedAdvisor.kt` | Speed to reach the next light on green, never above the limit |
| `TripSimulator.kt` | Simulates the trip to compare departure times |
| `TimeBands.kt` | Time bands and day types |
| `Geo.kt` | Distances, bearings and picking the nearest light |
| `PassDetector.kt` | Infers red and green at each light from the car's trajectory (stops, restarts, passes) |

The `app` module holds the UI (Jetpack Compose), the map (MapLibre with
[OpenFreeMap](https://openfreemap.org), data © OpenStreetMap contributors), the database
(Room) and GPS (Android's LocationManager, no Google services).

## Development

Requirements: JDK 21 and the Android SDK with platform 37.

```sh
./gradlew :signal-model:test   # logic tests, no Android SDK needed
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
```

## Status

- [x] M1 – Map, traffic lights, GREEN/RED buttons, observations, CSV export
- [x] Plan estimate and prediction in the light detail (ahead of M2)
- [ ] M2 – Departure table for the route
- [x] M3 – Automatic recording from GPS (stops and restarts)
- [ ] M4 – Assisted driving with speed advice (first part: prediction and speed on the Record tab)
