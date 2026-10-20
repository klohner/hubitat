# Aqara Vibration Sensor DJT11LM – Technical reference

Hubitat driver version 4.0.1 (`Aqara_DJT11LM_Vibration_Sensor.groovy`). This page covers what the driver does, how to configure it, how its calibration and health checks work, and the evidence behind the design. The Zigbee-level details are in [zigbee_protocol_spec.md](zigbee_protocol_spec.md).

Evidence labels: **[verified]** = seen in test logs or in the driver code; **[driver note]** = stated in the driver's own comments from field testing, not reproduced in the logs kept here; **[unknown]** = open.

---

## 1. Overview

The Aqara Vibration Sensor DJT11LM is a battery-powered Zigbee sensor. It measures vibration, tilt and drops, has a 3-axis accelerometer, and reports its own temperature and battery level. The driver exposes these as standard Hubitat capabilities and a set of extra attributes.

| Hubitat capability / attribute | Source on the sensor | Notes |
|---|---|---|
| `motion` (active / inactive) | Vibration event (`0x0101/0x0055` = 1) | Returns to inactive after `resetSeconds` with no new vibration |
| `acceleration` (active / inactive) | Tilt event (`0x0101/0x0055` = 2) | 2-second pulse |
| `pushed` (button 1) | Drop event (`0x0101/0x0055` = 3) | 2-second pulse; `pushed` = 1 |
| `contact` (open / closed) | Computed from the accelerometer, against two positions the user calibrates | Only after `setOpenPosition` and `setClosedPosition` |
| `sensorState` | Derived: `tilt` or `drop` during a pulse, `vibration` while motion is active, else `stationary` | |
| `vector`, `magnitude`, `orientation` | 3-axis accelerometer (`0x0101/0x0508` or the `0xFF01` struct) | |
| `tiltAngle`, `activityLevel` | `0x0101/0x0503` and `0x0101/0x0505` | |
| `battery`, `batteryVoltage` | `0xFF01` struct tag `0x01` (mV) | Linear mapping, see §4 |
| `temperature` | `0xFF01` struct tag `0x03` (°C) | Chip temperature, see §4 |
| `healthStatus` (online / offline) | Any message from the sensor | §5 |
| `sensitivity` | Read-back of `0xFF0D` (only confirmed values) | §3 |
| `configStatus` | `synced` or `pending: <level>` | §3 |

---

## 2. Pairing and installation

**Install the driver:** Driver code, *New Driver → Import*, using the GitHub raw URL in the package README. Or install via Hubitat Package Manager with the package's `repository.json`.

**Pair:** hold the sensor's reset button until its LED flashes, and keep it awake with short presses while the hub interviews it. The driver identifies the sensor from its own payloads (Device Data `identity` becomes "confirmed by device payload" once manufacturer `LUMI` and model `lumi.vibration.aq1` are received).

After pairing, the driver queues the saved sensitivity. It is applied the next time the sensor is tapped (§3).

---

## 3. Sensitivity

Sensitivity is the only setting stored in the sensor (attribute `0xFF0D`). The codes are:

| Level | Code (`0xFF0D`) |
|---|---|
| high | `0x01` |
| medium (default) | `0x0B` (11) |
| low | `0x15` (21) |

### 3.1 Why a tap is needed

The sensor sleeps and listens only for a moment after it transmits. The hub cannot push a setting on demand. Each change is queued in the driver, and the queue is delivered in the windows that open after a tap or a re-join. The protocol side is in [zigbee_protocol_spec.md §6](zigbee_protocol_spec.md#6-sleepy-device-delivery).

### 3.2 Procedure

1. Change **Sensitivity** in the preferences (or run `setSensitivity`). `configStatus` shows `pending: <level>`.
2. **Within the single-tap window** (60 s by default) tap the reset button once. About a second later the sensor acknowledges, `configStatus` becomes `synced`, and `sensitivity` shows the new level.
3. **If the window has passed:** tap, wait about 5 seconds, then tap again within 15 seconds. Each tap is logged with whether it was acknowledged.

After a re-pair, no tap is needed. The driver re-sends the value by itself for about 45 seconds.

Anything unconfirmed stays queued. The queue survives hub reboots, and it is retried at each later tap or re-pair.

### 3.3 Confirmation rules

- A **write-acknowledgement** of success confirms the last value the driver sent.
- A **read-back** that matches the pending value or the current preference also confirms it.
- A read-back that shows a different value (for example after a battery change resets the sensor) re-queues the preference value and logs it.

### 3.4 Preferences that affect the queue

| Preference | Default | Effect |
|---|---|---|
| Single-tap window (s) | 60 (range 0–300) | How long one tap is enough after a change. 0 = two-tap procedure only. |

### 3.5 Commands

| Command | Effect |
|---|---|
| `setSensitivity(low / medium / high)` | Sets the preference and queues the value |
| `configure` | Re-queues the saved sensitivity |
| `refresh` | Asks for a read-back at the next wake |
| `readDeviceInfo` | Requests Basic cluster identity data at the next tap (after any pending sensitivity change is confirmed) |
| `resetDriverState` | Clears the driver's state and re-queues the sensitivity |

---

## 4. Telemetry

### 4.1 Battery

The sensor sends battery as millivolts. The driver converts it to a percentage:

```
percent = clamp( (mV/1000 − vMin) / (vMax − vMin) × 100 , 0, 100 )
```

Defaults: `vMin` = 2.5 V, `vMax` = 3.0 V (the 0 % and 100 % points). The driver enforces `vMax ≥ vMin + 0.1 V`.

A fresh CR2032 reads 3.10–3.15 V. [driver note]

**Sag filter:** a reading more than 100 mV below the previous one, within 10 minutes, is ignored. The voltage sags for a moment after a join or a tap. [driver note]

Readings outside 1800–3600 mV are ignored.

`resetBatteryReplacedDate` records today's date in `batteryLastReplaced`. The driver also sets this when it is first installed.

### 4.2 Temperature

The sensor reports its own chip temperature. It reads a few degrees above room temperature. [driver note]

- The **Temperature offset** preference (default 0, range −20 to +20) is added to every reading, in the hub's temperature scale.
- If the hub's temperature scale changes, the saved offset is converted automatically: ×1.8 for C→F, ÷1.8 for F→C. The conversion treats the offset as a difference, not an absolute temperature.
- Readings outside −40 to 100 °C are ignored.

### 4.3 Accelerometer

- `vector` = raw `x,y,z` counts, `magnitude` = √(x² + y² + z²), rounded.
- `orientation` = the axis whose absolute value is at least 80 % of `magnitude`, with its sign (`x+`, `x-`, `y+`, `y-`, `z+`, `z-`). If none reaches 80 %, the value is `angled`.
- `magnitude` is about 1 g at rest. [driver note]

### 4.4 Tilt and activity

- `tiltAngle` is the angle in degrees from the last tilt event.
- `activityLevel` is the high 16 bits of attribute `0x0505`. Values of 6–9 were seen in the logs. [verified] The meaning of the scale is not documented.

---

## 5. Motion, sensor state and health

### 5.1 Motion

| Event | Result |
|---|---|
| Vibration (event 1) | `motion` = active, `lastVibration` updated. Motion returns to inactive `resetSeconds` after the last vibration. Each new vibration restarts the timer. |
| Tilt (event 2) | `acceleration` = active for 2 s (`lastTilt` updated) |
| Drop (event 3) | `pushed` = 1 for 2 s, `acceleration` not changed (`lastDrop` updated) |

`resetSeconds` (Motion reset timeout) defaults to 65 s, range 1–3600 s.

### 5.2 Sensor state

`sensorState` is `tilt` or `drop` during a pulse. Otherwise it is `vibration` while motion is active, and `stationary` at all other times.

### 5.3 Health

- Any message from the sensor (event, check-in, announce, read response) marks it online.
- An hourly check marks it offline if nothing has been heard for `offlineHours`. The default is 3 hours. Values other than 0 are raised to a minimum of 2, because the sensor reports about once an hour. [driver note]
- `offlineHours` = 0 turns the check off.

---

## 6. Contact (open / closed)

The contact state is computed from the accelerometer, against two saved positions.

1. Run **setOpenPosition** with the sensor held in its open position.
2. Run **setClosedPosition** with it in its closed position.
3. For each new sample, the driver measures the angle between the sample and each saved position.

| Condition | Result |
|---|---|
| angle to closed ≤ tolerance **and** angle to closed ≤ angle to open | `closed` |
| angle to open ≤ tolerance | `open` |
| neither | `contact` is not changed |

- `contactTolerance` (degrees) defaults to 10, range 1–45.
- If only one position is saved, the other angle is treated as very large, so the rule still works.
- `clearPositions` removes both positions and the `contact` state.

The contact state is only refreshed when the sensor sends a report: an event, a tap, or the hourly check-in. It is not continuous. [verified: driver behaviour; driver note on the reporting pattern]

---

## 7. Preferences (reference)

| Preference | Default | Range | Purpose |
|---|---|---|---|
| Sensitivity | medium | low / medium / high | Queued for the next tap (§3) |
| Single-tap window (s) | 60 | 0–300 | §3.2 |
| Motion reset timeout (s) | 65 | 1–3600 | §5.1 |
| Open/closed tolerance (°) | 10 | 1–45 | §6 |
| Temperature offset | 0 | −20 to +20 (hub scale) | §4.2 |
| Battery 0 % voltage (V) | 2.5 | 1.8–3.0 | §4.1 |
| Battery 100 % voltage (V) | 3.0 | 2.6–3.4 | §4.1 |
| Offline after (h) | 3 | 0–48, minimum 2 when on | §5.3 |
| Info logging | on | — | Events and sync outcomes |
| Debug logging | off | — | Every frame, decoded. Turns itself off after 30 minutes. |

---

## 8. Driver internals

These are for anyone maintaining the driver.

| Store | Contents |
|---|---|
| `state.pending` | Sensitivity code not yet confirmed. Survives reboots. |
| `state.sent` | Last sensitivity code sent, to match a write-acknowledgement |
| `state.sensCode` | Last sensitivity code the sensor confirmed |
| `state.open`, `state.closed` | Calibrated vectors (`x`, `y`, `z`) |
| `state.lastRx` | Last time a message was heard (saved at most once a minute) |
| Device Data | `manufacturer`, `model`, `identity`, `networkAddress`, `batteryType` (CR2032), `driverVersion`, and after `readDeviceInfo`: `zclVersion`, `stackVersion`, `hardwareVersion`, `dateCode`, `softwareBuild`, `powerSource`, plus `temperatureScale` |
| Runtime map (`RT`, in memory) | Send times, tap counter, window state, the last battery reading, and the `identity` / `readback` flags. Cleared on restart. |

Startup and preference changes run `ensureDefaults`, `purgeLegacy` (once per device, removes data from older driver versions) and `queueSensitivity`.

### 8.1 Message handling order

`parse()` does the following for each message:

1. Decode the description into a frame (§2 of the protocol spec).
2. Mark the sensor online.
3. Check whether it has re-joined: a device announce, or a new network address. If so, re-queue the sensitivity.
4. If the sensor is awake (any unsolicited report or an announce), send the next queued message first if the hold time allows (§6.2 of the protocol spec), then decode the frame.
5. Decode attributes and update the attributes.
6. In debug mode, log the frame with its timing: `RX [+Δ since previous RX, +Δ since TX #n] <raw> => <decoded>`.

---

## 9. Known limitations

- **Sensitivity needs a tap** (§3). The driver cannot change it on its own.
- **Other settings are not configurable on this model.** Heartbeat interval, LED and the hidden attributes are not exposed. Version 4.0.1 does not write them (see the protocol spec §3.3).
- **Contact is not continuous** (§6).
- **Write acknowledgements are matched to the most recently sent value.** If the setting changes between a send and its acknowledgement, a late acknowledgement for the earlier value would be recorded against the newer one. [analysis of driver code; not seen in logs]
- **The battery percentage is an estimate.** It depends on the voltage range set in the preferences. Its accuracy has not been measured against a discharge curve. [unknown]
- **Temperature is the chip temperature** and needs an offset (§4.2). The right offset has not been measured. [unknown]
- **The health check cannot see a sensor that is quiet by design.** It relies on the hourly check-in. If that check-in changes, the offline timeout must change too. [driver note]

---

## 10. Open items

1. Confirm the meaning of the hidden attributes (`0xFF03`–`0xFF0F`), using a spare sensor. See [zigbee_protocol_spec.md §9](zigbee_protocol_spec.md#9-open-questions).
2. Decode the remaining `0xFF01` struct tags (`0x04`, `0x05`, `0x06`, `0x08`, `0x0A`, `0x98`, `0x99`).
3. Find out why some writes get no response after four taps, and measure how often.
4. Measure the battery discharge curve so the 0 % and 100 % voltages can be set from data.
5. Measure the chip-temperature offset against a reference thermometer.
6. Decide whether `DoorState` (`0x0003`) is worth decoding.
