/**
 *  Aqara Vibration Sensor (DJT11LM / lumi.vibration.aq1) - Hubitat Elevation driver
 *  Version 4.0.1
 *
 *  Documentation and install: https://github.com/klohner/hubitat/tree/main/drivers/Aqara_DJT11LM_Vibration_Sensor
 *
 *  OVERVIEW
 *    Motion (vibration), acceleration (tilt), pushed (drop) and an optional contact (calibrated open/closed position),
 *    plus 3-axis vector, magnitude, orientation, activity level, tilt angle, battery, temperature, a confirmed
 *    sensitivity setting with a persistent "pending until the sensor confirms" queue, and online/offline health.
 *
 *  PAIRING
 *    Hold the sensor's reset button until its LED flashes, keep it awake with short presses while the hub interviews it.
 *    After pairing the driver identifies the sensor from its own payloads (Device Data: manufacturer, model, identity).
 *
 *  CHANGING THE SENSITIVITY (the only setting this sensor has)
 *    The sensor sleeps and listens only for an instant after it transmits, so the hub cannot push a setting at will.
 *      1. Change "Sensitivity" (or run setSensitivity). "configStatus" shows "pending: <level>".
 *      2. Within the single-tap window (default 60 s) TAP THE SENSOR'S RESET BUTTON ONCE. About 1 s later the sensor
 *         acknowledges and "configStatus" becomes "synced".
 *      3. If the window has passed: tap, wait ~5 s, tap again (within 15 s). Each tap tells you if it was acknowledged.
 *    After a re-pair no tap is needed: the driver re-sends the value by itself for ~45 s.
 *    Anything unconfirmed stays queued (it survives hub reboots) and is retried at every later tap or re-pair.
 *
 *  DEVICE PAGE
 *    Attributes   motion, acceleration, contact, pushed, battery, batteryVoltage, temperature, sensorState,
 *                 activityLevel, magnitude, orientation (x+ x- y+ y- z+ z- angled), vector ("x,y,z"), tiltAngle,
 *                 sensitivity (last value the sensor CONFIRMED), configStatus, healthStatus, lastVibration, lastTilt,
 *                 lastDrop, lastCheckin, batteryLastReplaced
 *    Commands     setSensitivity, readDeviceInfo, setOpenPosition, setClosedPosition, clearPositions,
 *                 resetBatteryReplacedDate, resetDriverState, Configure, Refresh, push
 *    Device Data  manufacturer, model, identity ("confirmed by device payload"), batteryType, driverVersion, networkAddress;
 *                 after readDeviceInfo: zclVersion, stackVersion, hardwareVersion, dateCode, softwareBuild, powerSource
 *    State        pending, sent, sensCode (sensitivity queue), lastRx, open, closed (calibration)
 *    Logging      Info: events and sync outcomes. Debug (off by default; turns itself off after 30 minutes): every RX/TX
 *                 frame with its interpretation, e.g. "RX [+Δ since previous RX] <raw description> => <decoded>".
 *
 *  BEHAVIOUR NOTES
 *    - Vibration -> motion active until "Motion reset timeout" after the LAST vibration report. Tilt and drop are
 *      2-second pulses (acceleration / pushed 1). Contact appears only after setOpenPosition and setClosedPosition.
 *    - The battery reading sags 100-300 mV right after joins and button presses; sudden drops of more than 100 mV within
 *      10 minutes are ignored. Fresh CR2032 cells read 3.1-3.15 V.
 *    - The sensor reports a status bundle (battery, temperature, x/y/z) about hourly (58-64 min); it sends nothing else
 *      while untouched, so the default offline timeout is 3 hours (minimum 2).
 *
 *  DESIGN NOTES (why the sensitivity code looks the way it does; details in DJT11LM_Technical_Reference.md)
 *    - The sensor is a battery sleepy end device. Its parent router holds a message for it only briefly and the sensor
 *      receives it only when it polls. In field logs the sensor polled ~0.8 s after a reset-button frame and accepted a
 *      write in about 4 of 5 taps when the write was already queued before the tap, but in only ~1 of 4 when it was
 *      sent at the tap. Event reports (vibration, tilt, drop, activity, accelerometer) never opened a receive window.
 *    - Hence: exactly one downlink message at a time (never a write+read pair), kept queued every few seconds only while
 *      a tap is expected (single-tap window, tap window, join window); a write younger than ~6.5 s is never replaced.
 *    - Queued messages are delivered first-in-first-out, about one per tap, and stale copies can arrive ~15 s late, so
 *      any confirmation (write-ack or read-back, "read attr" or catchall form, at any time) clears the queue, and a
 *      different operation (readDeviceInfo) waits until a pending write is confirmed.
 *    - The sensor has no Poll Control cluster and cannot be told to check in or poll faster.
 *
 *  KNOWN LIMITATIONS
 *    - Sensitivity cannot be changed without touching the sensor (one tap, see above).
 *    - Heartbeat interval, LED and other settings are not configurable on this model.
 *    - Position (contact) is only refreshed when the sensor reports (events, taps, hourly), not continuously.
 *
 *  Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *  Protocol groundwork by veeceeoh and the Hubitat community (xiaomi-aqara-vibration-sensor driver).
 */

import groovy.transform.Field
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_VERSION = '4.0.1'

metadata {
    definition(name: 'Aqara Vibration Sensor DJT11LM', namespace: 'klohner', author: 'Karl Lohner', singleThreaded: true,
               importUrl: 'https://raw.githubusercontent.com/klohner/hubitat/main/drivers/Aqara_DJT11LM_Vibration_Sensor/Aqara_DJT11LM_Vibration_Sensor.groovy') {
        capability 'Sensor'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Battery'
        capability 'TemperatureMeasurement'
        capability 'MotionSensor'
        capability 'AccelerationSensor'
        capability 'ContactSensor'
        capability 'PushableButton'

        attribute 'sensorState',         'enum', ['stationary', 'vibration', 'tilt', 'drop']
        attribute 'activityLevel',       'number'
        attribute 'magnitude',           'number'    // length of the 3-axis vector in raw sensor counts (~1 g at rest)
        attribute 'orientation',         'enum', ['x+', 'x-', 'y+', 'y-', 'z+', 'z-', 'angled']
        attribute 'vector',              'string'    // "x,y,z" raw counts
        attribute 'tiltAngle',           'number'    // degrees reported with the last tilt event
        attribute 'batteryVoltage',      'number'
        attribute 'batteryLastReplaced', 'string'
        attribute 'sensitivity',         'enum', ['low', 'medium', 'high', 'unknown']   // last value CONFIRMED by the sensor
        attribute 'configStatus',        'string'    // "synced" or "pending: <level>"
        attribute 'healthStatus',        'enum', ['online', 'offline']
        attribute 'lastVibration',       'string'
        attribute 'lastTilt',            'string'
        attribute 'lastDrop',            'string'
        attribute 'lastCheckin',         'string'

        command 'setSensitivity', [[name: 'Sensitivity', type: 'ENUM', constraints: ['low', 'medium', 'high']]]
        command 'setOpenPosition'
        command 'setClosedPosition'
        command 'clearPositions'
        command 'resetBatteryReplacedDate'
        command 'readDeviceInfo'
        command 'resetDriverState'

        fingerprint profileId: '0104', deviceId: '000A', inClusters: '0000,0003,0019,0101',
                    outClusters: '0000,0004,0003,0005,0019,0101', manufacturer: 'LUMI', model: 'lumi.vibration.aq1',
                    deviceJoinName: 'Aqara Vibration Sensor DJT11LM'
    }

    preferences {
        input name: 'sensitivityLevel', type: 'enum', title: 'Sensitivity',
              description: 'Applied when you tap the sensor\'s reset button after saving (see "Single-tap window"). "configStatus" shows pending / synced.',
              options: ['low': 'Low', 'medium': 'Medium', 'high': 'High'], defaultValue: 'medium', required: true
        input name: 'singleTapSeconds', type: 'number', title: 'Single-tap window after a sensitivity change (seconds, 0 = two-tap only)',
              description: 'For this long after you change the sensitivity a write is kept queued (one small Zigbee message every 6 s) so ONE tap of the reset button is enough.',
              range: '0..300', defaultValue: 60
        input name: 'resetSeconds', type: 'number', title: 'Motion reset timeout (seconds)',
              description: 'Motion returns to inactive this long after the last vibration report.',
              range: '1..3600', defaultValue: 65
        input name: 'contactTolerance', type: 'decimal', title: 'Open/closed tolerance (degrees)',
              description: 'Used only after setOpenPosition and setClosedPosition have been run.',
              range: '1..45', defaultValue: 10.0
        input name: 'temperatureOffset', type: 'decimal', title: 'Temperature offset (degrees, in the hub\'s temperature scale)',
              description: 'Added to every reading in the scale chosen under Hub Details (the current scale is shown as "temperatureScale" in Device Data). The sensor reads its own chip temperature, a few degrees above the room: enter a negative value to correct it. If the hub scale is changed later the offset is converted automatically.',
              range: '-20..20', defaultValue: 0
        input name: 'vMin', type: 'decimal', title: 'Battery 0% voltage (V)', range: '1.8..3.0', defaultValue: 2.5
        input name: 'vMax', type: 'decimal', title: 'Battery 100% voltage (V)', range: '2.6..3.4', defaultValue: 3.0
        input name: 'offlineHours', type: 'number', title: 'Mark offline after this many hours without any message (0 = never, minimum 2)',
              description: 'The sensor reports about once an hour while untouched, so keep this at 3 or more.',
              range: '0..48', defaultValue: 3
        input name: 'txtEnable', type: 'bool', title: 'Enable info logging', defaultValue: true
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging',
              description: 'Logs every frame sent and received with its interpretation. Turns itself off after 30 minutes.', defaultValue: false
    }
}

/* ========================================================================== */
/* Constants                                                                  */
/* ========================================================================== */

@Field static final int CLUSTER_BASIC     = 0x0000
@Field static final int CLUSTER_VIBRATION = 0x0101
@Field static final int CLUSTER_ANNOUNCE  = 0x0013

@Field static final int ATTR_MANUFACTURER = 0x0004
@Field static final int ATTR_MODEL        = 0x0005   // also what the sensor reports when its reset button is tapped
@Field static final int ATTR_XIAOMI       = 0xFF01   // battery / temperature / x,y,z struct (hourly check-in)
@Field static final int ATTR_SENSITIVITY  = 0xFF0D   // manufacturer-specific (0x115F), uint8
@Field static final int ATTR_EVENT        = 0x0055
@Field static final int ATTR_TILT_ANGLE   = 0x0503
@Field static final int ATTR_ACTIVITY     = 0x0505
@Field static final int ATTR_ACCEL        = 0x0508
@Field static final int ATTR_DOOR_STATE   = 0x0003   // standard Door Lock attribute (meaning unknown)

@Field static final Map<Integer, String> SENS_LABEL = [1: 'high', 11: 'medium', 21: 'low']
@Field static final Map<String, Integer> SENS_CODE  = ['high': 1, 'medium': 11, 'low': 21]

// ZCL / Xiaomi data type -> value length in bytes (0x41 / 0x42 are length-prefixed)
@Field static final Map<Integer, Integer> TYPE_LEN = [
    0x08: 1, 0x09: 2, 0x0A: 3, 0x0B: 4, 0x0C: 5, 0x0D: 6, 0x0E: 7, 0x0F: 8, 0x10: 1, 0x18: 1, 0x19: 2, 0x1A: 3, 0x1B: 4,
    0x20: 1, 0x28: 1, 0x21: 2, 0x29: 2, 0x22: 3, 0x2A: 3, 0x23: 4, 0x2B: 4, 0x39: 4,
    0x24: 5, 0x2C: 5, 0x25: 6, 0x2D: 6, 0x26: 7, 0x27: 8, 0x30: 1, 0x31: 2, 0x3A: 8
]

@Field static final long MIN_SEND_GAP_MS    = 800L      // never more than one downlink message per 0.8 s
@Field static final long PRIME_INTERVAL_MS  = 6000L     // single-tap window: a fresh write every 6 s (parent hold time is ~7.7 s)
@Field static final long TAP_WINDOW_MS      = 15000L    // after a reset-button tap with a change pending ...
@Field static final long TAP_INTERVAL_MS    = 5000L     // ... keep a write queued so a second tap within 15 s collects it
@Field static final long JOIN_WINDOW_MS     = 45000L    // after a (re)join the sensor polls for a while ...
@Field static final long JOIN_INTERVAL_MS   = 3000L     // ... one write every 3 s until acknowledged
@Field static final long HOLD_MS          = 6500L     // a queued write stays at the parent ~7.7 s; do not replace it before ~6.5 s
@Field static final long TAP_CHECK_MS       = 2500L     // how long after a tap we wait for an acknowledgement before saying so
@Field static final int  TRANSIENT_SECONDS  = 2         // tilt / drop are momentary pulses
@Field static final int  DEBUG_AUTO_OFF_SECONDS = 1800

// Transient (non-persistent) runtime data keyed by device id: keeps the device's "State Variables" short.
@Field static ConcurrentHashMap<String, Map> RT = new ConcurrentHashMap<String, Map>()

/* ========================================================================== */
/* Lifecycle & commands                                                       */
/* ========================================================================== */

void installed() {
    logInfo 'Installed. Pair the sensor, then see the driver header for how to change its sensitivity.'
    state.lastRx = now()
    ensureDefaults()
    resetBatteryReplacedDate()
    queueSensitivity(true)
    refreshDeviceData()
    scheduleHealthCheck()
}

void updated() {
    logInfo 'Preferences saved.'
    unschedule('logsOff')
    if (settings.logEnable) {
        runIn(DEBUG_AUTO_OFF_SECONDS, 'logsOff')
        logInfo 'Debug logging is ON and will turn itself off in 30 minutes.'
    }
    validatePreferences()
    BigDecimal tOff = temperatureOffset()
    if (tOff != 0G) logInfo "Temperature offset ${tOff} °${location.temperatureScale} is added to every reading."
    ensureDefaults()
    if (device.getDataValue('legacyPurged') == null) purgeLegacy()
    queueSensitivity(false, true)
    refreshDeviceData()
    scheduleHealthCheck()
}

void uninstalled() {
    unschedule()
    RT.remove(device.id.toString())
}

/** Keeps the two battery voltages consistent. */
private void validatePreferences() {
    double lo = (settings.vMin ?: 2.5) as double
    double hi = (settings.vMax ?: 3.0) as double
    if (hi < lo + 0.1d) {
        BigDecimal fixed = new BigDecimal(lo + 0.5d).setScale(2, RoundingMode.HALF_UP)
        device.updateSetting('vMax', [value: fixed, type: 'decimal'])
        logWarn "Battery 100% voltage must be at least 0.1 V above the 0% voltage; set to ${fixed} V."
    }
}

/** Sleepy device: only (re)queues the desired sensitivity; it is delivered when the sensor wakes. */
void configure() {
    logInfo 'Configure requested.'
    queueSensitivity(true, true)
}

/** Sleepy device: cannot be polled. Asks for a read-back of the sensitivity at the next wake. */
void refresh() {
    rt().readback = true
    logInfo 'Refresh requested: the sensor sleeps, so sensitivity will be read back at its next wake (tap its reset button).'
}

/** One-time read of the static Basic-cluster identity data (versions, date code, power source). Delivered at a tap, like a write. */
void readDeviceInfo() {
    if (state.pending != null) {
        // The parent delivers queued messages in order, one per tap: a second kind of message queued behind an unconfirmed write
        // would cost an extra tap and confuse the write. Finish the sensitivity change first.
        rt().identityAfterSync = true
        logInfo 'A sensitivity change is still pending; device info will be requested right after it is confirmed.'
        return
    }
    startIdentityRead()
}

private void startIdentityRead() {
    rt().identity = true
    logInfo 'Device info requested. Tap the sensor\'s reset button ONCE within 60 s (tap, wait ~5 s, tap again later).'
    openWindow('prime', 60000L, PRIME_INTERVAL_MS, true)
}

void setSensitivity(String level) {
    if (!SENS_CODE.containsKey(level)) { logWarn "setSensitivity: '${level}' is not low/medium/high."; return }
    device.updateSetting('sensitivityLevel', [value: level, type: 'enum'])
    queueSensitivity(false, true)
}

void setOpenPosition()   { capturePosition('open') }
void setClosedPosition() { capturePosition('closed') }

void clearPositions() {
    state.remove('open'); state.remove('closed')
    try { device.deleteCurrentState('contact') } catch (ignored) { }
    logInfo 'Open/closed calibration cleared.'
}

void resetBatteryReplacedDate() {
    emit('batteryLastReplaced', new Date().format('yyyy-MM-dd', location.timeZone), null, 'Battery replacement date recorded.')
}

void resetDriverState() {
    unschedule()
    purgeLegacy()
    state.clear()
    rt().clear()
    state.lastRx = now()
    ensureDefaults()
    queueSensitivity(true)
    refreshDeviceData()
    scheduleHealthCheck()
    logInfo 'Driver state cleared; sensitivity re-queued.'
}

void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info "${device.displayName}: debug logging disabled automatically."
}

/** One-time clean-up of attributes, data values and settings left behind by earlier driver versions. */
private void purgeLegacy() {
    ['sensorStatus', 'lastCheckinEpoch', 'lastCheckinTime', 'lastDropEpoch', 'lastDropTime', 'lastStationaryEpoch', 'lastStationaryTime',
     'lastTiltEpoch', 'lastTiltTime', 'lastVibrationEpoch', 'lastVibrationTime', 'sensitivityStatus', 'currentX', 'currentY', 'currentZ',
     'pitchPsi', 'rollPhi', 'yawTheta', 'driverVersion', 'driver', 'lastTilted', 'lastDropped', 'lastStationary', 'lastAccelerometer',
     'accelerometerMagnitude', 'accelerometerSource', 'diagnosticStatus', 'lastResetWake', 'reportedDni', 'dniFirstRecognizedAt',
     'currentDniRecognizedAt', 'dniChangeCount', 'notPresentCounter', 'restoredCounter'].each { String n ->
        try { device.deleteCurrentState(n) } catch (ignored) { }
    }
    ['driver', 'lastSensitivityWrite'].each { String n -> device.removeDataValue(n) }
    ['sensitivityLevelSetting', 'resetTimeSetting', 'margin', 'vMinSetting', 'vMaxSetting', 'sensitivityReadbackSetting', 'healthCheckEnable',
     'healthCheckWarningEnable', 'recoveryMode', 'lastCheckinEpochEnable', 'autoDisableLogsMinutes', 'protocolLogging', 'infoEnable',
     'debugEnable', 'traceEnable', 'motionreset', 'marginOfError', 'enableSensCommands', 'voltsmin', 'voltsmax', 'infoLogging', 'debugLogging',
     'lastCheckinEnable', 'otherDateTimeEnable', 'oldFirmware', 'retryWindow', 'txBursts', 'writeMethod', 'txDelayMs'].each { String n ->
        try { device.removeSetting(n) } catch (ignored) { }
    }
    device.updateDataValue('legacyPurged', DRIVER_VERSION)
    logDebug 'Legacy attributes, data and settings from earlier driver versions removed.'
}

/* ========================================================================== */
/* Sensitivity sync: queue -> deliver on wake -> confirm                      */
/*   state.pending = target code not yet confirmed (survives reboots)         */
/*   state.sent    = last value we transmitted (to match a late write-ack)    */
/*   state.sensCode= last value the sensor confirmed                          */
/* ========================================================================== */

private int desiredCode() { SENS_CODE[(settings.sensitivityLevel ?: 'medium').toString()] ?: 11 }

/** Record what we want. force = re-send even if we believe the sensor already holds it. userAction = open the single-tap window. */
private void queueSensitivity(boolean force, boolean userAction = false) {
    int want = desiredCode()
    if (!force && state.sensCode == want) {
        state.remove('pending')
        stopWindow()
    } else if (!force && state.pending == want) {
        logDebug "Sensitivity ${SENS_LABEL[want]} is already pending; nothing to do."
    } else {
        state.pending = want
        int win = Math.max(0, Math.min(300, ((settings.singleTapSeconds ?: 60) as int)))
        if (userAction && win > 0) {
            logInfo "Sensitivity ${SENS_LABEL[want]} queued. Tap the sensor's reset button ONCE within ${win} s to apply it (later: tap, wait ~5 s, tap again)."
            openWindow('prime', win * 1000L, PRIME_INTERVAL_MS, true)
        } else {
            logInfo "Sensitivity ${SENS_LABEL[want]} queued. Tap the sensor's reset button, wait ~5 s, then tap it again to apply it."
        }
    }
    publishConfigStatus()
}

private void publishConfigStatus() {
    Integer p = state.pending as Integer
    emit('configStatus', p == null ? 'synced' : "pending: ${SENS_LABEL[p]}")
}

/** True while something still has to be delivered to the sensor. */
private boolean outstanding() { state.pending != null || rt().readback == true || rt().identity == true }

/**
 * Called from parse() the instant an unsolicited report proves the sensor is awake.
 * joined = the sensor just (re)joined the network (device announce, or its network address changed).
 */
private void onWake(long t, String trigger, boolean joined) {
    Map r = rt()
    if (joined) openWindow('join', JOIN_WINDOW_MS, JOIN_INTERVAL_MS, false)
    if (!outstanding()) return

    long sinceSend = t - ((r.lastSendAt ?: 0L) as long)
    boolean tap = (trigger == 'button') && (state.pending != null || r.identity == true)
    if (tap) {
        r.tapSeq = ((r.tapSeq ?: 0) as int) + 1
        logDebug "TAP #${r.tapSeq}: last write was ${r.lastSendAt != null ? sinceSend + ' ms' : 'never'} ago (a write queued >= 1 s and < 7.7 s before a tap is what gets delivered); window=${windowKind(t)}."
    }
    // A write sent less than ~6.5 s ago is still held by the parent; replacing it at the instant of a tap can disturb its delivery
    // (a tap 0.98 s after the previous write failed when the write was re-sent, but worked when it was left alone).
    long holdMs = tap ? HOLD_MS : MIN_SEND_GAP_MS
    if (sinceSend >= holdMs) sendOne(trigger)
    else logDebug "TX skipped (${trigger}): last write only ${sinceSend} ms ago and still queued at the parent."

    if (tap) {
        if (windowKind(t) == 'none') openWindow('tap', TAP_WINDOW_MS, TAP_INTERVAL_MS, false)   // so a second tap collects a queued write
        runInMillis(TAP_CHECK_MS, 'tapCheck', [overwrite: true])
    }
}

/** Reports the outcome of a tap that had a change pending. */
void tapCheck() {
    if (state.pending == null) return                    // acknowledged: confirmSensitivity() already logged it
    long t = now()
    String w = windowKind(t)
    logInfo "Tap #${rt().tapSeq} was not acknowledged. " + (w == 'prime' ? 'Tap the reset button again; the single-tap window is still open.' : 'Wait ~5 s and tap again within 15 s (a write is queued for the next tap).')
}

private String windowKind(long t) { t < ((rt().windowEnd ?: 0L) as long) ? (rt().windowKind ?: 'none') : 'none' }

/**
 * Keeps one write queued at the parent while a window is open: one message every intervalMs until acknowledged or
 * the window ends. firstNow = send the first message immediately.
 */
private void openWindow(String kind, long lengthMs, long intervalMs, boolean firstNow) {
    Map r = rt()
    long t = now()
    boolean active = t < ((r.windowEnd ?: 0L) as long)
    if (active && kind == 'tap') return                                  // never shorten / replace a longer window
    r.windowKind = kind
    r.windowEnd = t + lengthMs
    r.windowInterval = intervalMs
    logDebug "WINDOW ${kind} opened: ${lengthMs / 1000} s, one write every ${intervalMs / 1000} s."
    if (firstNow || kind == 'join') {
        if (outstanding() && t - ((r.lastSendAt ?: 0L) as long) >= MIN_SEND_GAP_MS) sendOne(kind)
    }
    runInMillis(intervalMs, 'windowTick', [overwrite: true])
}

void windowTick() {
    Map r = rt()
    long t = now()
    if (!outstanding()) return
    if (t >= ((r.windowEnd ?: 0L) as long)) { windowEnded(r.windowKind as String); return }
    if (t - ((r.lastSendAt ?: 0L) as long) >= MIN_SEND_GAP_MS) sendOne((r.windowKind ?: 'window') as String)
    runInMillis(((r.windowInterval ?: PRIME_INTERVAL_MS) as long), 'windowTick', [overwrite: true])
}

private void windowEnded(String kind) {
    if (kind == 'prime') logInfo "The single-tap window closed without an answer. Tap the reset button, wait ~5 s, then tap again" + (state.pending != null ? " to apply sensitivity ${SENS_LABEL[state.pending as Integer]}." : '.')
    else if (kind == 'join') logInfo 'No acknowledgement during the join window; the write stays queued for the next tap.'
    else logDebug "WINDOW ${kind} closed."
}

private void stopWindow() {
    rt().windowEnd = 0L
    unschedule('windowTick')
    unschedule('tapCheck')
}

/** Sends exactly ONE downlink message: the write if pending, otherwise a one-shot read-back (Refresh). */
private void sendOne(String trigger) {
    Map r = rt()
    String dni = device.deviceNetworkId
    String cmd, what
    if (state.pending != null) {
        int code = state.pending as int
        cmd = "he wattr 0x${dni} 0x01 0x0000 0xFF0D 0x20 {${hex2(code)}} {115F}".toString()
        what = "write ${SENS_LABEL[code]} (0x${hex2(code)})".toString()
        state.sent = code
    } else if (r.readback == true) {
        cmd = "he raw 0x${dni} 1 0x01 0x0000 {04 5F 11 00 00 0D FF}".toString()   // manufacturer-specific read of 0xFF0D
        what = 'read-back sensitivity'
        r.remove('readback')
    } else if (r.identity == true) {
        // Standard ZCL Read Attributes on Basic: ZCLVersion 0, AppVersion 1, StackVersion 2, HWVersion 3, DateCode 6, PowerSource 7, SWBuildID 0x4000.
        // The sensor cannot fragment: a response larger than ~71 bytes is silently never sent (the 9-attribute read incl. the
        // manufacturer/model strings was 85 bytes and got no answer), so those two strings are left out (Hubitat already has them).
        r.tsn = (((r.tsn ?: 0) as int) + 1) % 256
        cmd = "he raw 0x${dni} 1 0x01 0x0000 {10 ${hex2(r.tsn as int)} 00 00 00 01 00 02 00 03 00 06 00 07 00 00 40}".toString()
        what = 'read device info (Basic 0x0000-0x0003, 0x0006, 0x0007, 0x4000)'
    } else {
        return
    }
    r.lastSendAt = now()
    r.txCount = ((r.txCount ?: 0) as int) + 1
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    logDebug "TX #${r.txCount} [${trigger}] ${what}: ${cmd}"
}

/** The sensor has told us (write-ack or read-back) that it now holds `code`. Reconcile the queue. */
private void confirmSensitivity(int code, String via) {
    state.sensCode = code
    String label = SENS_LABEL[code]
    // The queue is satisfied if the sensor holds what is pending OR what the preference currently asks for.
    if (state.pending != null && ((state.pending as Integer) == code || desiredCode() == code)) {
        state.remove('pending')
        stopWindow()
        logInfo "Sensitivity change confirmed by sensor: ${label} (${via}); removed from the queue."
    }
    emit('sensitivity', label, null, "Sensitivity is ${label} (${via}).")
    publishConfigStatus()
    if (state.pending == null && rt().identityAfterSync == true) { rt().remove('identityAfterSync'); startIdentityRead() }
}

/** Sensor reported its current sensitivity (read-response or report); may arrive at any time. */
private void onSensitivityValue(int code, List<String> notes) {
    String label = SENS_LABEL[code]
    if (label == null) { notes << "sensitivity=0x${hex2(code)} (unknown code)".toString(); return }
    notes << "sensitivity=${label}".toString()
    if (state.pending != null && ((state.pending as Integer) == code || desiredCode() == code)) {
        confirmSensitivity(code, 'read-back')
    } else {
        state.sensCode = code
        emit('sensitivity', label)
        if (state.pending == null && code != desiredCode()) {          // e.g. a battery swap reset the sensor
            state.pending = desiredCode()
            logInfo "Sensor reports ${label} but the preference is ${SENS_LABEL[desiredCode()]}; re-queued. Tap the reset button, wait ~5 s, then tap again to apply."
            publishConfigStatus()
        }
    }
}

/** Write Attributes Response. Success = the sensor accepted the last value we sent (state.sent), whenever it arrives. */
private void onWriteResponse(String data, List<String> notes) {
    int status = data ? hexInt(data, 0) : -1
    if (status == 0) {
        Integer sent = state.sent as Integer
        notes << "write-ack OK (for ${sent != null ? SENS_LABEL[sent] : 'unknown value'})".toString()
        if (sent != null) confirmSensitivity(sent, 'write acknowledged')
    } else {
        String code = status >= 0 ? hex2(status) : '??'
        notes << "write-ack FAILED status=0x${code}".toString()
        logWarn "Sensor rejected the write (status 0x${code}); it will be retried on the next tap."
    }
}

/* ========================================================================== */
/* Parse: Zigbee description -> frame -> attributes                           */
/* ========================================================================== */

void parse(String description) {
    if (!description) return
    long t = now()
    Map f = parseFrame(description)
    if (f == null) { logDebug "RX ${description} => ignored (not a sensor frame)"; return }

    Map r = rt()
    boolean dbg = debugOn()
    long prevRx = (r.lastRx ?: t) as long
    long sinceTx = t - ((r.lastSendAt ?: 0L) as long)
    boolean afterTx = r.lastSendAt != null && sinceTx < 60000L
    boolean solicited = (f.kind == 'catchall' && f.cmd in ['01', '04']) || (f.kind == 'attr' && f.cmd == '01')

    markAlive(t)

    // A rejoin / battery swap can reset the sensor's settings: re-queue before deciding to transmit.
    // A re-pair is recognised by the device announce OR by the sensor suddenly using a new network address.
    boolean addressChanged = networkAddressChanged(f.dni as String)
    boolean joined = (f.kind == 'announce') || addressChanged
    if (joined && !(r.lastJoinAt != null && t - (r.lastJoinAt as long) < 60000L)) {   // a sensor with hidden attr FF07=0 re-announces every 20-60 s
        r.lastJoinAt = t
        state.pending = desiredCode()
        publishConfigStatus()
        logInfo "Sensor (re)joined the network (address ${f.dni ?: '?'}); sensitivity ${SENS_LABEL[desiredCode()]} will be re-sent."
    }
    // Time-critical: if the sensor is awake right now, talk to it first and decode afterwards.
    if (f.wake || joined) onWake(t, f.wake ? triggerName(f) : 'join', joined)

    List<String> notes = []
    try {
        switch (f.kind) {
            case 'announce':
                notes << 'device announce (sensor (re)joined; sensitivity will be re-sent)'
                break
            case 'attr':
                handleAttributes(walkAttrFrame(f), f.cmd == '0A', notes)
                break
            case 'catchall':
                if (f.cluster == CLUSTER_BASIC && f.cmd == '04') {
                    onWriteResponse(f.data, notes)
                } else if (f.cmd == '01') {
                    handleAttributes(walkReadResponse(f.data, f.cluster), false, notes)
                } else {
                    notes << "catchall cluster=0x${hex4(f.cluster)} cmd=${f.cmd} (not used)".toString()
                }
                break
        }
    } catch (Exception e) {
        logWarn "Could not decode frame: ${description} (${e.message})"
    }
    if (dbg) {
        if (joined) notes << 'JOIN detected'
        if (solicited && afterTx) notes << "RESPONSE ${sinceTx} ms after TX #${r.txCount}".toString()
        String timing = "[+${t - prevRx} ms since prev RX" + (afterTx ? ", +${sinceTx} ms since TX #${r.txCount}" : '') + ']'
        log.debug "${device.displayName}: RX ${timing} ${description} => ${notes ? notes.join('; ') : 'nothing to do'}"
    }
}

/** True when the sensor now talks from a different network address than last time (it was re-paired). */
private boolean networkAddressChanged(String dni) {
    if (!dni) return false
    String cur = dni.toUpperCase()
    String last = device.getDataValue('networkAddress')
    if (last == cur) return false
    device.updateDataValue('networkAddress', cur)
    return last != null                                   // the very first frame only records the address
}

private String triggerName(Map f) {
    if (f.kind == 'announce') return 'announce'
    switch (f.attr as int) {
        case ATTR_EVENT: return 'event'
        case ATTR_ACTIVITY: return 'activity'
        case ATTR_ACCEL: return 'accel'
        case ATTR_MODEL: return 'button'
        case ATTR_XIAOMI: return 'checkin'
        default: return "attr-0x${hex4(f.attr as int)}".toString()
    }
}

/** Turns a Hubitat description string into a small map. Unsolicited reports and announces set wake=true. */
private Map parseFrame(String d) {
    try {
        if (d.startsWith('read attr -')) {
            Map m = [:]
            d.substring(11).split(',').each { String p ->
                int i = p.indexOf(':')
                if (i > 0) m[p.substring(0, i).trim()] = p.substring(i + 1).trim()
            }
            return [kind: 'attr', dni: m.dni, cluster: Integer.parseInt(m.cluster, 16), attr: Integer.parseInt(m.attrId, 16),
                    type: Integer.parseInt(m.encoding, 16), cmd: m.command, value: (m.value ?: ''), wake: (m.command == '0A')]
        }
        if (d.startsWith('catchall:')) {
            // catchall: profile cluster srcEP dstEP options msgType dni isClusterSpecific isMfgSpecific mfgId command direction [data]
            List<String> tk = d.substring(9).trim().split(/\s+/) as List<String>
            if (tk.size() < 12) return null
            int cluster = Integer.parseInt(tk[1], 16)
            return [kind: (cluster == CLUSTER_ANNOUNCE ? 'announce' : 'catchall'), dni: tk[6], cluster: cluster, cmd: tk[10],
                    data: (tk.size() > 12 ? tk[12] : ''), wake: (cluster == CLUSTER_ANNOUNCE)]
        }
    } catch (Exception e) {
        logWarn "Unparseable description: ${d} (${e.message})"
    }
    return null
}

/** "read attr" frames carry the first attribute in header fields and any further attributes appended to value. */
private List<Map> walkAttrFrame(Map f) {
    List<Map> out = []
    String v = f.value
    Map r = readValue(v, 0, f.type as int)
    if (r == null) return out
    out << [cluster: f.cluster, attr: f.attr, type: f.type, hex: r.hex]
    int i = r.next
    while (i + 6 <= v.length()) {
        int attr = leInt(v.substring(i, i + 4)); i += 4
        if (f.cmd == '01') {                                  // read-response form carries a status byte
            int st = hexInt(v, i); i += 2
            if (st != 0) continue
        }
        int ty = hexInt(v, i); i += 2
        r = readValue(v, i, ty)
        if (r == null) break
        out << [cluster: f.cluster, attr: attr, type: ty, hex: r.hex]
        i = r.next
    }
    return out
}

/** Catchall read-response: repeated [attrLE(2) status(1) (type(1) value)]. */
private List<Map> walkReadResponse(String data, int cluster) {
    List<Map> out = []
    int i = 0
    while (i + 6 <= data.length()) {
        int attr = leInt(data.substring(i, i + 4)); i += 4
        int st = hexInt(data, i); i += 2
        if (st != 0) continue                                  // e.g. 0x86 unsupported attribute
        int ty = hexInt(data, i); i += 2
        Map r = readValue(data, i, ty)
        if (r == null) break
        out << [cluster: cluster, attr: attr, type: ty, hex: r.hex]
        i = r.next
    }
    return out
}

private void handleAttributes(List<Map> attrs, boolean unsolicited, List<String> notes) {
    attrs.each { Map a ->
        if (a.cluster == CLUSTER_BASIC) {
            switch (a.attr as int) {
                case ATTR_MANUFACTURER: onIdentity('manufacturer', hexToAscii(a.hex), notes); break
                case ATTR_MODEL:
                    onIdentity('model', hexToAscii(a.hex), notes)
                    if (unsolicited) {
                        notes << 'reset-button tap (sensor awake)'
                        if (state.pending != null || rt().identity == true) logInfo 'Reset button tapped; sensor is awake.'
                        else logDebug 'Reset button tapped; sensor is awake.'
                    }
                    break
                case 0x0000: case 0x0002: case 0x0003: case 0x0006: case 0x0007: case 0x4000:
                    onBasicInfo(a.attr as int, a.type as int, a.hex, notes); break
                case ATTR_XIAOMI:       onXiaomiStruct(a.hex, notes); break
                case ATTR_SENSITIVITY:  onSensitivityValue(hexInt(a.hex, 0), notes); break
                default: notes << "basic attr 0x${hex4(a.attr as int)} ignored".toString()
            }
        } else if (a.cluster == CLUSTER_VIBRATION) {
            switch (a.attr as int) {
                case ATTR_EVENT:
                    onSensorEvent(leInt(a.hex), notes); break
                case ATTR_TILT_ANGLE:
                    int deg = leInt(a.hex)
                    notes << "tilt angle=${deg}deg".toString()
                    emit('tiltAngle', deg, '°', null, true)
                    break
                case ATTR_ACTIVITY:
                    int level = (int) ((leLong(a.hex) >> 16) & 0xFFFF)
                    notes << "activity=${level}".toString()
                    emit('activityLevel', level, null, null, true)
                    break
                case ATTR_ACCEL:
                    onAccelHex(a.hex, notes); break
                case ATTR_DOOR_STATE:
                    // Standard Door Lock "DoorState". Seen once as an unsolicited report during heavy handling; it is NOT a periodic
                    // heartbeat (FF0C=2 produced no periodic frames in 7.5 idle minutes). The real heartbeat is the hourly 0xFF01 report.
                    notes << "DoorState ${unsolicited ? 'report' : 'value'}=${leInt(a.hex)}".toString()
                    break
                default: notes << "vibration attr 0x${hex4(a.attr as int)} ignored".toString()
            }
        }
    }
}

/* ========================================================================== */
/* Sensor events                                                              */
/* ========================================================================== */

private void onSensorEvent(int code, List<String> notes) {
    String stamp = fmtTime(now())
    switch (code) {
        case 1:
            notes << 'event=vibration'
            emit('lastVibration', stamp)
            emit('motion', 'active', null, 'Motion active (vibration detected).')
            runIn(resetSeconds(), 'endMotion', [overwrite: true])   // re-armed by every vibration report
            break
        case 2:
            notes << 'event=tilt'
            emit('lastTilt', stamp)
            rt().momentary = 'tilt'
            emit('acceleration', 'active', null, 'Acceleration active (tilt detected).')
            runIn(TRANSIENT_SECONDS, 'endMomentary', [overwrite: true])
            break
        case 3:
            notes << 'event=drop'
            emit('lastDrop', stamp)
            rt().momentary = 'drop'
            sendEvent(name: 'pushed', value: 1, isStateChange: true, descriptionText: 'Drop detected (button 1 pushed).')
            logInfo 'Drop detected (button 1 pushed).'
            runIn(TRANSIENT_SECONDS, 'endMomentary', [overwrite: true])
            break
        default:
            notes << "event=0x${hex2(code)} (unknown)".toString()
            return
    }
    recomputeSensorState()
}

void endMotion() {
    emit('motion', 'inactive', null, "Motion inactive (no vibration for ${resetSeconds()} s).")
    recomputeSensorState()
}

void endMomentary() {
    rt().remove('momentary')
    emit('acceleration', 'inactive')
    recomputeSensorState()
}

private void recomputeSensorState() {
    String s = rt().momentary ?: (device.currentValue('motion') == 'active' ? 'vibration' : 'stationary')
    emit('sensorState', s)
}

private int resetSeconds() { Math.max(1, ((settings.resetSeconds ?: 65) as int)) }

/* ========================================================================== */
/* Telemetry: identity, battery, temperature, 3-axis vector                   */
/* ========================================================================== */

private void onIdentity(String field, String value, List<String> notes) {
    if (!value) return
    notes << "${field}=${value}".toString()
    device.updateDataValue(field, value)
    boolean modelOk = (device.getDataValue('model') ?: '').startsWith('lumi.vibration')
    boolean mfgOk   = (device.getDataValue('manufacturer') ?: '').equalsIgnoreCase('LUMI')
    if (field == 'model' && !modelOk) logWarn "Unexpected model '${value}': this driver is written for lumi.vibration.aq1."
    if (modelOk && mfgOk) device.updateDataValue('identity', 'confirmed by device payload')
}

/** Static identity data from a Basic-cluster read (readDeviceInfo): shown under Device Data. */
private void onBasicInfo(int attr, int type, String hex, List<String> notes) {
    String v
    switch (attr) {
        case 0x0000: v = "${leInt(hex)}";                          device.updateDataValue('zclVersion', v); break
        case 0x0002: v = "${leInt(hex)}";                          device.updateDataValue('stackVersion', v); break
        case 0x0003: v = "${leInt(hex)}";                          device.updateDataValue('hardwareVersion', v); break
        case 0x0006: v = hexToAscii(hex).replace('\u0000', '').trim(); device.updateDataValue('dateCode', v); break
        case 0x0007: v = (leInt(hex) == 3 ? 'battery' : "0x${hex2(leInt(hex))}"); device.updateDataValue('powerSource', v); break
        case 0x4000: v = hexToAscii(hex).replace('\u0000', '').trim(); device.updateDataValue('softwareBuild', v); break
        default: return
    }
    notes << "basic 0x${hex4(attr)}=${v}".toString()
    if (rt().identity == true && attr == 0x0007) {
        rt().remove('identity')
        logInfo 'Device info received (see Device Data).'
        if (!outstanding()) stopWindow()
    }
}

/** Xiaomi 0xFF01 struct: repeated [tag(1) type(1) value]. 0x01 = battery mV, 0x03 = temp C, 0x9A = x,y,z. */
private void onXiaomiStruct(String h, List<String> notes) {
    Map<Integer, Map> tags = [:]
    int i = 0
    while (i + 4 <= h.length()) {
        int tag = hexInt(h, i), ty = hexInt(h, i + 2)
        Map r = readValue(h, i + 4, ty)
        if (r == null) break
        tags[tag] = [type: ty, hex: r.hex]
        i = r.next
    }
    notes << 'check-in'
    emit('lastCheckin', fmtTime(now()))
    if (tags[0x01]) onBattery(leInt(tags[0x01].hex), notes)
    if (tags[0x03]) onTemperature((int) (byte) leInt(tags[0x03].hex), notes)
    if (tags[0x9A] && tags[0x9A].hex.length() == 12) onAccelHex(tags[0x9A].hex, notes)
}

private void onBattery(int mv, List<String> notes) {
    if (mv < 1800 || mv > 3600) { notes << "battery ${mv}mV (out of range, ignored)".toString(); return }
    Map r = rt()
    Integer lastMv = r.lastBattMv as Integer
    if (lastMv != null && mv < lastMv - 100 && (now() - ((r.lastBattAt ?: 0L) as long)) < 600000L) {
        notes << "battery ${mv}mV ignored (transient sag vs ${lastMv}mV a moment ago; typical right after join/button)".toString()
        return
    }
    r.lastBattMv = mv
    r.lastBattAt = now()
    double vMin = (settings.vMin ?: 2.5) as double
    double vMax = (settings.vMax ?: 3.0) as double
    if (vMax <= vMin) vMax = vMin + 0.1d
    int pct = (int) Math.max(0L, Math.min(100L, Math.round(((mv / 1000.0d) - vMin) / (vMax - vMin) * 100.0d)))
    BigDecimal volts = new BigDecimal(mv).divide(1000G, 2, RoundingMode.HALF_UP)
    notes << "battery=${mv}mV -> ${pct}%".toString()
    emit('batteryVoltage', volts, 'V')
    emit('battery', pct, '%', "Battery ${pct}% (${volts} V).")
}

/**
 * The offset preference is a number in the hub's temperature scale (Hub Details). If the hub scale has changed since the
 * offset was entered, convert it (a temperature DIFFERENCE: x 1.8 for C->F, / 1.8 for F->C) so its physical size is kept.
 */
private BigDecimal temperatureOffset() {
    String cur = location.temperatureScale
    String saved = device.getDataValue('temperatureScale')
    BigDecimal off = ((settings.temperatureOffset ?: 0) as BigDecimal)
    if (saved != cur) {
        if (saved != null && off != 0G) {
            BigDecimal conv = (cur == 'F' ? off * 1.8G : off / 1.8G).setScale(1, RoundingMode.HALF_UP)
            device.updateSetting('temperatureOffset', [value: conv, type: 'decimal'])
            logInfo "The hub temperature scale changed from °${saved} to °${cur}; the temperature offset was converted from ${off} to ${conv} °${cur}."
            off = conv
        }
        device.updateDataValue('temperatureScale', cur)
    }
    return off
}

private void onTemperature(int c, List<String> notes) {
    if (c < -40 || c > 100) { notes << "temperature ${c}C (out of range, ignored)".toString(); return }
    BigDecimal val = new BigDecimal(convertTemperatureIfNeeded(c as BigDecimal, 'C', 1).toString())
    BigDecimal off = temperatureOffset()
    if (off != 0G) val = (val + off).setScale(1, RoundingMode.HALF_UP)
    notes << "temperature=${c}C".toString()
    emit('temperature', val, "°${location.temperatureScale}", "Temperature ${val}°${location.temperatureScale}.")
}

private void onAccelHex(String hex, List<String> notes) {
    if (hex.length() != 12) return
    onVector(s16(hex.substring(0, 4)), s16(hex.substring(4, 8)), s16(hex.substring(8, 12)), notes)
}

private void onVector(int x, int y, int z, List<String> notes) {
    double mag = Math.sqrt(((double) x * x) + ((double) y * y) + ((double) z * z))
    String orient = 'angled'
    if (mag > 0) {
        int[] a = [x, y, z] as int[]
        int k = 0
        for (int j = 1; j < 3; j++) if (Math.abs(a[j]) > Math.abs(a[k])) k = j
        if (Math.abs(a[k]) / mag >= 0.8d) orient = ['x', 'y', 'z'][k] + (a[k] >= 0 ? '+' : '-')
    }
    notes << "vector=${x},${y},${z} |v|=${Math.round(mag)} orientation=${orient}".toString()
    emit('vector', "${x},${y},${z}".toString())
    emit('magnitude', (int) Math.round(mag))
    emit('orientation', orient)
    evaluateContact(x, y, z, notes)
}

/* ========================================================================== */
/* Open/closed calibration                                                    */
/* ========================================================================== */

private Map currentVector() {
    String v = device.currentValue('vector')
    if (!v) return null
    List p = v.split(',') as List
    return p.size() == 3 ? [x: p[0] as int, y: p[1] as int, z: p[2] as int] : null
}

private void capturePosition(String which) {
    Map v = currentVector()
    if (v == null) { logWarn "No accelerometer sample yet; vibrate the sensor or tap its reset button, then try again."; return }
    state[which] = v
    logInfo "Captured ${which} position ${v.x},${v.y},${v.z}."
    evaluateContact(v.x as int, v.y as int, v.z as int, [])
}

private double angleBetween(int x, int y, int z, Map p) {
    double dot = x * (p.x as double) + y * (p.y as double) + z * (p.z as double)
    double n = Math.sqrt(((double) x * x) + ((double) y * y) + ((double) z * z)) * Math.sqrt((p.x as double) ** 2 + (p.y as double) ** 2 + (p.z as double) ** 2)
    if (n == 0d) return 999d
    return Math.toDegrees(Math.acos(Math.max(-1d, Math.min(1d, dot / n))))
}

private void evaluateContact(int x, int y, int z, List<String> notes) {
    Map o = state.open as Map, c = state.closed as Map
    if (!o && !c) return
    double tol = (settings.contactTolerance ?: 10.0) as double
    double ao = o ? angleBetween(x, y, z, o) : 999d
    double ac = c ? angleBetween(x, y, z, c) : 999d
    String res = (ac <= tol && ac <= ao) ? 'closed' : (ao <= tol ? 'open' : null)
    if (res) emit('contact', res, null, "Contact ${res} (${String.format('%.1f', Math.min(ao, ac))}° from calibrated position).")
    else notes << "contact: ${Math.round(ao)}°/${Math.round(ac)}° from open/closed, outside tolerance".toString()
}

/* ========================================================================== */
/* Health                                                                     */
/* ========================================================================== */

private long lastHeard() { Math.max(((rt().lastRx ?: 0L) as long), ((state.lastRx ?: 0L) as long)) }

private void markAlive(long t) {
    rt().lastRx = t
    if (t - ((state.lastRx ?: 0L) as long) >= 60000L) state.lastRx = t          // persisted at most once a minute
    if (device.currentValue('healthStatus') == 'offline') emit('healthStatus', 'online', null, 'Sensor is back online.')
}

private void scheduleHealthCheck() {
    unschedule('healthCheck')
    if (offlineHours() > 0) runEvery1Hour('healthCheck')
}

private int offlineHours() {
    int h = ((settings.offlineHours ?: 0) as int)
    return h <= 0 ? 0 : Math.max(2, h)                       // the sensor reports hourly: shorter timeouts give false alarms
}

void healthCheck() {
    int hrs = offlineHours()
    if (hrs <= 0 || lastHeard() == 0L) return
    if (now() - lastHeard() > hrs * 3600000L && device.currentValue('healthStatus') != 'offline') {
        emit('healthStatus', 'offline')
        logWarn "No message from the sensor for over ${hrs} h; marked offline."
    }
}

/* ========================================================================== */
/* Helpers                                                                    */
/* ========================================================================== */

private Map rt() {
    String id = device.id.toString()
    Map m = RT.get(id)
    if (m == null) { m = new ConcurrentHashMap<String, Object>(); RT.put(id, m) }
    return m
}

private void ensureDefaults() {
    [motion: 'inactive', acceleration: 'inactive', sensorState: 'stationary', healthStatus: 'online', sensitivity: 'unknown'].each { k, v ->
        if (device.currentValue(k) == null) sendEvent(name: k, value: v)
    }
    if (device.currentValue('numberOfButtons') == null) sendEvent(name: 'numberOfButtons', value: 1)
}

private void refreshDeviceData() {
    device.updateDataValue('driverVersion', DRIVER_VERSION)
    device.updateDataValue('batteryType', 'CR2032')
    if (!device.getDataValue('identity')) device.updateDataValue('identity', 'unconfirmed (fingerprint only)')
}

/** Send an event only if the value changed (or force). Optional description is also logged at info level. */
private void emit(String name, def value, String unit = null, String desc = null, boolean force = false) {
    if (!force && device.currentValue(name)?.toString() == value?.toString()) return
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    if (desc) e.descriptionText = desc
    if (force) e.isStateChange = true
    sendEvent(e)
    if (desc) logInfo desc
}

private Map readValue(String h, int i, int type) {
    if (type == 0x41 || type == 0x42) {
        if (i + 2 > h.length()) return null
        int end = i + 2 + hexInt(h, i) * 2
        return end > h.length() ? null : [hex: h.substring(i + 2, end), next: end]
    }
    Integer n = TYPE_LEN[type]
    if (n == null) return null
    int end = i + n * 2
    return end > h.length() ? null : [hex: h.substring(i, end), next: end]
}

private int hexInt(String s, int i) { Integer.parseInt(s.substring(i, i + 2), 16) }
private long leLong(String h) { long v = 0L; for (int i = h.length() - 2; i >= 0; i -= 2) v = (v << 8) | hexInt(h, i); v }
private int leInt(String h) { (int) leLong(h) }
private int s16(String h) { (int) (short) leInt(h) }
private String hex2(int v) { String.format('%02X', v & 0xFF) }
private String hex4(int v) { String.format('%04X', v & 0xFFFF) }
private String hexToAscii(String h) { StringBuilder sb = new StringBuilder(); for (int i = 0; i + 2 <= h.length(); i += 2) sb.append((char) hexInt(h, i)); sb.toString() }
private String fmtTime(long t) { new Date(t).format('yyyy-MM-dd HH:mm:ss', location.timeZone) }

private boolean debugOn() { settings.logEnable == true }
private void logInfo(String m)  { if (settings.txtEnable != false) log.info "${device.displayName}: ${m}" }
private void logDebug(String m) { if (debugOn()) log.debug "${device.displayName}: ${m}" }
private void logWarn(String m)  { log.warn "${device.displayName}: ${m}" }
