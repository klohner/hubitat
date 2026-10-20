# Aqara Vibration Sensor DJT11LM (lumi.vibration.aq1) – Zigbee protocol specification

Scope: the Zigbee behaviour the Hubitat driver `Aqara_DJT11LM_Vibration_Sensor.groovy` (version 4.0.1) relies on, and the behaviour observed in the test logs in this folder (2026-10-07 to 2026-10-10). Sections marked **[verified]** are seen in logs or in the driver's decoding; **[driver note]** are facts the driver's comments state from field testing but which are not reproduced in the logs kept here; **[unknown]** marks open questions.

Companion document: [DJT11LM_Technical_Reference.md](DJT11LM_Technical_Reference.md) (design, calibration, configuration and evidence).

---

## 1. Device identity

| Item | Value | Source |
|---|---|---|
| Manufacturer (Basic 0x0004) | `LUMI` | [verified] driver `onIdentity` |
| Model (Basic 0x0005) | `lumi.vibration.aq1` | [verified] |
| Zigbee profile | `0x0104` (Home Automation) | [verified] logs (`catchall: 0104 …`) |
| Endpoint | `01` | [verified] logs |
| Manufacturer code | `0x115F` (LUMI) | [verified] logs (`mfgCode 0x115F`) |
| Node type | Sleepy end device, battery (CR2032) | [verified] driver `refreshDeviceData`; see §8 |
| Hubitat fingerprint | profile `0104`, deviceId `000A`, inClusters `0000,0003,0019,0101`, outClusters `0000,0004,0003,0005,0019,0101` | [verified] driver metadata |

Clusters used by the driver:

| Cluster | Name | Use in 4.0.1 |
|---|---|---|
| `0x0000` | Basic | Identity, manufacturer-specific sensitivity (`0xFF0D`), Xiaomi status struct (`0xFF01`), identity read |
| `0x0003` | Identify | Listed in fingerprint; not used by the driver. An Identify Query (`0x0003`, cmd `0x01`) got no response in tests (§7.3). |
| `0x0013` | Device Announce (ZDO) | Detects a (re)join |
| `0x0101` | Vibration (Aqara-specific, Door Lock number space) | Events, tilt angle, activity, acceleration |

---

## 2. Message formats seen by the driver

Hubitat delivers each received message to `parse()` as one of two strings. The driver decodes both.

### 2.1 `read attr - …` (attribute reports and read responses from the ZCL "Report Attributes" or "Read Attributes Response" path)

```
read attr - raw: <hex>, dni: <NWK hex>, endpoint: 01, cluster: <hex>, size: <hex>,
             attrId: <hex>, encoding: <hex type>, command: <hex>, value: <hex>
```

- `command: 0A` = unsolicited report (the driver treats this as a wake-up); `command: 01` = read-attributes response (solicited).
- `encoding` is the ZCL data-type byte. The driver takes `value` as the payload of the first attribute, then walks any further attributes appended in `value`. Each further attribute is `attrLE(2)` followed by, for read responses, `status(1)` and then `type(1)` and the value.

### 2.2 `catchall: …` (command responses and other frames not decoded by Hubitat)

```
catchall: <profile> <cluster> <srcEP> <dstEP> <options> <msgType> <dni>
          <isClusterSpecific> <isMfgSpecific> <mfgId> <command> <direction> [<data>]
```

Examples from the logs:

| Frame | Meaning |
|---|---|
| `catchall: 0104 0000 01 01 0040 00 9513 00 01 115F 04 01 00` | Write Attributes Response (cmd `04`), manufacturer-specific, status `00` = success |
| `catchall: 0000 0013 00 00 0040 00 9513 00 00 0000 00 00 CB1395BC…` | Device Announce (ZDO cluster `0x0013`), see §4 |

### 2.3 Address

`dni` is the sensor's 16-bit network (NWK) address in hex. The driver stores it in Device Data `networkAddress`. A change of this value means the sensor has re-joined under a new address (§6.1).

---

## 3. Attributes

### 3.1 Vibration cluster `0x0101`

| Attr | Name | Type | Decoding in 4.0.1 | Evidence |
|---|---|---|---|---|
| `0x0055` | Event | `0x21` uint16 (value read as LE int) | `1` = vibration → motion active; `2` = tilt → acceleration pulse; `3` = drop → `pushed` pulse; anything else logged as unknown | [verified] logs: `attrId 0055, encoding 21, value 0100` = vibration |
| `0x0503` | Tilt angle | uint32 (LE) | degrees, shown as `tiltAngle` | [verified] driver; [unknown] exact type byte in logs |
| `0x0505` | Activity | uint32 (LE) | high 16 bits (`(value >> 16) & 0xFFFF`) = `activityLevel` | [verified] log line "Activity level Cluster 0x0101, Attribute 0x0505" |
| `0x0508` | Acceleration | 3 × int16 (LE): x, y, z | raw counts → `vector`, `magnitude`, `orientation` (§5) | [verified] driver, logs |
| `0x0003` | DoorState (standard Door Lock attribute number) | uint | logged only | [verified] seen once as an unsolicited report during heavy handling; not a heartbeat (driver comment) |

Event code `4` (stationary) was reported by the earlier test driver in the logs (`sendSensorEvent(value=4)`). Version 4.0.1 does not act on it, and logs it as `event=0x04 (unknown)`. [verified]

### 3.2 Basic cluster `0x0000` – standard attributes

| Attr | Name | Used for | Evidence |
|---|---|---|---|
| `0x0004` | Manufacturer name (string) | Device Data `manufacturer` | [verified] |
| `0x0005` | Model identifier (string) | Device Data `model`; also sent when the reset button is tapped (§4.2) | [verified] |
| `0x0000` | ZCL version | Device Data `zclVersion` (after `readDeviceInfo`) | driver |
| `0x0002` | Stack version | Device Data `stackVersion` | driver |
| `0x0003` | Hardware version | Device Data `hardwareVersion` | driver |
| `0x0006` | Date code (string) | Device Data `dateCode` | driver |
| `0x0007` | Power source (enum8; `3` = battery) | Device Data `powerSource` | driver |
| `0x4000` | Software build ID (string) | Device Data `softwareBuild` | driver |

The driver reads these with one standard Read Attributes request (§6.4). Reading the manufacturer and model strings in the same request produced no answer (85-byte request, see the driver comment), so those two strings are not requested.

### 3.3 Manufacturer-specific attributes on Basic (cluster `0x0000`, mfg code `0x115F`)

| Attr | Type | Name / role | Values seen | Use in 4.0.1 |
|---|---|---|---|---|
| `0xFF01` | octet string (`0x42`) | Xiaomi status struct (battery, temperature, x/y/z, others). See §3.4 | — | Decoded on hourly check-in and on reads |
| `0xFF0D` | uint8 (`0x20`) | **Sensitivity**: `0x01` = high, `0x0B` (11) = medium, `0x15` (21) = low | confirmed by write-ack and read-back | Written and read back by 4.0.1 |
| `0xFF03` | uint16 (`0x21`) | Unknown. Default `60`. A write of `2` was acknowledged and later read back as `2` in some sessions; reads in other sessions show `60` again. | 60, 2 | Not used by 4.0.1 [unknown function; see §9] |
| `0xFF04` | uint8 | Unknown. Read as `1` (or `0`). | 0, 1 | Not used |
| `0xFF07` | uint8 | Unknown. Driver note: when `0`, the sensor re-announces every 20–60 s (the driver ignores announces within 60 s of each other). | 0, 1 | Not used |
| `0xFF09`, `0xFF0A`, `0xFF0B` | uint8 | Unknown. Read as `0`. | 0, 1 | Not used |
| `0xFF0C` | uint8 | Unknown. Read as `1`, `2` or `60`. Driver note: `2` produced no periodic frames in 7.5 idle minutes. | 1, 2, 60 | Not used |
| `0xFF0F` | uint8 | Unknown. Read as `0` or `1`. | 0, 1 | Not used |

Writes to `0xFF04`, `0xFF09`, `0xFF0A`, `0xFF0B`, `0xFF0C` and `0xFF03` (value `60`) were attempted by an earlier test driver. Some got a success response and some got no response after four taps. Their effect is not recorded (§9). Version 4.0.1 writes only `0xFF0D`.

### 3.4 `0xFF01` status struct (Xiaomi)

Octet string made of repeated `[tag(1) type(1) value]` entries. The driver decodes these tags:

| Tag | Type | Meaning | Decoding |
|---|---|---|---|
| `0x01` | `0x21` uint16 | Battery voltage in mV | driver: valid range 1800–3600 mV |
| `0x03` | `0x28` int8 | Temperature in °C | driver: range −40 to 100 °C |
| `0x9A` | `0x25` 6 bytes | x, y, z accelerometer, 3 × int16 LE | same as `0x0508` |

Other tags seen in the logs and not decoded by the driver: `0x04` (uint16), `0x05` (uint16, small values, possibly a signal-strength field), `0x06` (uint40, counter-like), `0x08` (uint16), `0x0A` (uint16), `0x98` (uint16), `0x99` (uint16). [unknown]

Worked example (from a log, 2026-10-09):

```
0121 490C   tag 01  uint16 0x0C49 = 3145 mV          -> 3.15 V battery
0328 1C     tag 03  int8   0x1C   = 28 °C            -> temperature
9A25 4A005E005EFD   tag 9A 6 bytes x=74, y=94, z=-674 counts
```

The struct arrives about once an hour (driver note: 58–64 min apart). It is the only periodic report the driver has seen from an untouched sensor.

---

## 4. Announce and tap behaviour

### 4.1 Device Announce (ZDO cluster `0x0013`)

The sensor sends a Device Announce when it joins or re-joins. The payload is 12 bytes after the cluster header: `TSN(1) NWK(2, LE) IEEE(8, LE) Capability(1)`. Example from logs:

```
CB 13 95 BC E3 26 07 00 8D 15 00 80
TSN=CB  NWK=0x9513  IEEE=00:15:8D:00:07:26:E3:BC  Capability=0x80
```

Capability `0x80` = "allocate address" only. Mains power, receiver-on-when-idle and router bits are clear, which is consistent with a battery sleepy end device. [verified]

The driver treats an announce as a join (§6.1).

### 4.2 Reset-button tap

Tapping the reset button wakes the sensor and sends a Basic `0x0005` (model) report with `command: 0A`. The driver reads that as a `button` wake trigger. [driver note; the driver's `triggerName` and `ATTR_MODEL` handling]

The tap is the only way to talk to the sensor on demand. See §6.

---

## 5. Derived values (driver)

| Quantity | Formula | Notes |
|---|---|---|
| `magnitude` | √(x² + y² + z²) of the raw counts | about 1 g at rest (driver comment). One sample in the 2026-10-09 logs (x=74, y=94, z=−674) gives |v| ≈ 683; the logs do not say whether that sample was at rest |
| `orientation` | dominant axis `k` = argmax \|a_k\|; if \|a_k\| / magnitude ≥ 0.8 → `x±`, `y±` or `z±` by sign; else `angled` | driver `onVector` |
| `activityLevel` | high 16 bits of `0x0505` | [verified] log shows values 6–9 |
| `tiltAngle` | `0x0503` in degrees | [verified] driver |
| battery % | `(mV/1000 − vMin) / (vMax − vMin) × 100`, clamped 0–100; `vMin` default 2.5 V, `vMax` default 3.0 V | driver |
| temperature | `int8` °C, then convert to the hub scale, then add the user offset | driver `onTemperature` |

---

## 6. Sleepy-device delivery

The sensor sleeps and listens only briefly after it transmits. The parent router holds a message for it only for a short time, and the sensor collects it when it polls. This is the key constraint of the protocol. The driver's comments and the test results give these facts.

### 6.1 Windows

| Event | Window the driver opens | Source |
|---|---|---|
| Device Announce, or network address changed | **Join window**: 45 s, one write every 3 s until acknowledged | driver `JOIN_WINDOW_MS`, `JOIN_INTERVAL_MS` |
| Sensitivity changed in preferences | **Single-tap window** (default 60 s, configurable 0–300 s): one write every 6 s | driver `PRIME_INTERVAL_MS` |
| A tap happens while a write is pending | **Tap window**: 15 s, one write every 5 s, so a second tap collects a queued write | driver `TAP_WINDOW_MS`, `TAP_INTERVAL_MS` |
| Identity read requested | Single-tap window (60 s) | driver `startIdentityRead` |

Event reports (vibration, tilt, drop, activity, accelerometer) never opened a receive window. [driver note; consistent with §4.2]

### 6.2 Timing constants

| Constant | Value | Why |
|---|---|---|
| `MIN_SEND_GAP_MS` | 800 ms | never more than one downlink message per 0.8 s |
| `HOLD_MS` | 6500 ms | a queued write stays at the parent about 7.7 s. A write younger than 6.5 s is not replaced, because replacing it at the instant of a tap disturbed delivery in one test ("a tap 0.98 s after the previous write failed when the write was re-sent, but worked when it was left alone"). [driver note] |
| `TAP_CHECK_MS` | 2500 ms | how long after a tap the driver waits before logging "not acknowledged" |

### 6.3 Rules the driver follows

1. **One downlink message at a time.** A write and a read are never sent as a pair.
2. **FIFO delivery.** Queued messages are delivered in order, about one per tap. Stale copies can arrive about 15 s late. Any confirmation clears the queue, whichever form it arrives in (write-ack or read-back, `read attr` or `catchall`, at any time).
3. **Read-back only for a different operation.** A new operation (`readDeviceInfo`) waits until the pending write is confirmed, to avoid an extra tap.
4. **Delivery is a tap, not a timer.** Delivery needs a tap of the reset button, or a re-join. The hub cannot push a setting on a timer.

### 6.4 Command templates (Hubitat `he` commands sent by the driver)

Each is a single message.

| Purpose | Command | ZCL meaning |
|---|---|---|
| Write sensitivity | `he wattr 0x<dni> 0x01 0x0000 0xFF0D 0x20 {<code>} {115F}` | Write Attributes, mfg `0x115F`, Basic `0xFF0D`, uint8 |
| Read sensitivity | `he raw 0x<dni> 1 0x01 0x0000 {04 5F 11 00 00 0D FF}` | Read Attributes (frame control `04` = mfg-specific, global command `00`), mfg `5F11`, attr `0D FF` |
| Read device info | `he raw 0x<dni> 1 0x01 0x0000 {10 <TSN> 00 00 00 01 00 02 00 03 00 06 00 07 00 00 40}` | Read Attributes (frame control `10` = disable default response) on Basic `0x0000, 0x0001, 0x0002, 0x0003, 0x0006, 0x0007, 0x4000` |

Note: `0x0001` (application version) is requested by the read but not decoded by the driver.

### 6.5 Acknowledgements

- **Write Attributes Response** (`catchall`, cluster `0x0000`, cmd `04`, mfg `115F`): data byte `00` = success. A non-zero byte is a failure. [verified logs: `… 04 01 00 => WRITE-RESPONSE … SUCCESS`]
- **Read Attributes Response** (`read attr` with `command: 01`, or `catchall` cmd `01`): each attribute is `attrLE(2) status(1) type(1) value`. Status `00` = success, `86` = unsupported attribute.

---

## 7. Observed behaviour (test logs)

### 7.1 Writes and read-backs of sensitivity [verified]

- The write `0xFF0D` = `0x0B` (medium), `0x15` (low), `0x01` (high) was acknowledged with success in several sessions.
- Read-back after the write returned the same code (`0xFF0D = 1/11/21`).
- Some writes got **no response after four taps** (logged as `NO RESPONSE after 4 taps … try retrySilent`). The later driver design (§6.3) keeps one write queued at the parent between taps for that reason.

### 7.2 Tap timing [verified, one session]

The tap-timing log lines in one session (`TAP #n: last write was X ms ago`) show taps at 109 ms to 5.1 s after the last write. Write-acks came back 0.4–2.2 s after the tap that delivered the write (`RESPONSE 1805 ms after window #6 start`, `RESPONSE 2218 ms …`). The driver's comment says the acknowledgement is about 1 s after the tap.

### 7.3 Probes that got no response [verified]

These were tried by an earlier test driver. None produced a reply after four taps:

- Identify Query (cluster `0x0003`, cmd `0x01`)
- Manufacturer-specific scan of Basic `0x00F0`–`0x00F7`
- Read of attributes `0xFF03, 04, 07, 09, 0A, 0B, 0C, 0F` in a single request that included the strings (85 bytes) — the driver's comment says the sensor cannot send responses over about 71 bytes

Reads of the hidden attributes in smaller requests did get answers (§3.3).

### 7.4 Device Announce [verified]

Announces were logged many times in one session. Each had the same NWK `0x9513` and IEEE `00:15:8D:00:07:26:E3:BC`, with a changing TSN. The driver note on `0xFF07` says a sensor with `0xFF07 = 0` re-announces every 20–60 s; the logs kept here do not record the interval.

### 7.5 Battery [driver note]

The reading sags by 100–300 mV right after a join or a tap. The driver ignores a fall of more than 100 mV within 10 minutes. A fresh CR2032 reads 3.10–3.15 V.

---

## 8. Power and node type

- The sensor is a sleepy end device. It cannot be polled. It has no Poll Control cluster and cannot be told to check in or poll faster. [driver note]
- Battery: CR2032 (driver Device Data `batteryType`).
- The sensor reports its status struct about hourly. Between reports it sends only event frames.

---

## 9. Open questions

1. **Hidden attributes `0xFF03`–`0xFF0F`.** Their meaning is not established. `0xFF03` (default 60, once acknowledged as `2`) may be a report or check-in period, but this is unverified. Changing any of them should be tested on a spare unit before use.
2. **Struct tags `0x04`, `0x05`, `0x06`, `0x08`, `0x0A`, `0x98`, `0x99`.** Not decoded.
3. **Why some writes get no response after four taps.** The logs show it happens; the cause is not known. Candidates include the delivery timing (§6.2) and a write that was already acknowledged.
4. **DoorState `0x0003`.** Seen once. Not understood.
5. **Event code 4 (stationary).** Sent by the earlier test driver. Its trigger is not documented in 4.0.1.
