# Aqara Vibration Sensor DJT11LM – Hubitat driver

Driver for the Aqara Vibration Sensor DJT11LM (`lumi.vibration.aq1`), a battery-powered Zigbee sleepy end device. It reports motion (vibration), tilt, drops, an optional open/closed contact position, 3-axis vector and orientation, battery, temperature and online/offline health.

File: `Aqara_DJT11LM_Vibration_Sensor.groovy` (driver version 4.0.1)

## Install

**Hubitat Package Manager:** Install → Add a repository (or Search by Keyword/From a URL) with `https://raw.githubusercontent.com/klohner/hubitat/main/drivers/Aqara_DJT11LM_Vibration_Sensor/repository.json`, then install "Aqara DJT11LM Vibration Sensor".

**Manual:** Drivers Code → New Driver → Import, with this URL:

`https://raw.githubusercontent.com/klohner/hubitat/main/drivers/Aqara_DJT11LM_Vibration_Sensor/Aqara_DJT11LM_Vibration_Sensor.groovy`

## Pairing

Hold the sensor's reset button until its LED flashes, and keep it awake with short presses while the hub interviews it. The driver identifies the sensor from its own payloads after pairing.

## Changing the sensitivity

The sensor sleeps and only listens for a moment after it transmits, so the hub cannot push a setting at will:

1. Change "Sensitivity" (or run `setSensitivity`). `configStatus` shows `pending: <level>`.
2. Within the single-tap window (60 s by default), tap the sensor's reset button once. About a second later the sensor acknowledges, and `configStatus` changes to `synced`.
3. If the window has passed, tap, wait about 5 seconds, then tap again within 15 seconds.

After a re-pair no tap is needed; the driver re-sends the value itself for about 45 seconds. Unconfirmed values stay queued (they survive hub reboots) and are retried at later taps or re-pairs.

## Known limitations

- Sensitivity can only be changed by tapping the sensor.
- Heartbeat interval, LED and other settings are not configurable on this model.
- Contact position is refreshed only when the sensor reports (events, taps, hourly), not continuously.

The driver's header comments describe the protocol and design in more detail.

Licensed under the Apache License, Version 2.0.
