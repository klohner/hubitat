# hubitat

Hubitat stuff: custom device drivers and apps.

## Drivers

Each driver lives in `drivers/<device-or-vendor>/`.

| Device | Folder | Files | What it does |
| --- | --- | --- | --- |
| Shelly BLU H&T ZB (SBHT-203C) | [drivers/shelly_blu_ht_zb](drivers/shelly_blu_ht_zb) | `Shelly_BLU_HT_ZB_Simple.groovy` | Temperature, humidity and battery for the battery-powered Zigbee sensor. Reporting settings are fixed to driver defaults, with "Reset Reporting To Factory" and "Reset Reporting To Recommended" commands and a health status attribute. |
| Shelly BLU H&T ZB (SBHT-203C) | [drivers/shelly_blu_ht_zb](drivers/shelly_blu_ht_zb) | `Shelly_BLU_HT_ZB_Reference.groovy` | Full-featured version of the same driver. Adds configurable reporting preferences and presets, a sleepy-device command queue, a ZCL/ZDO frame parser with debug and trace logging, and developer tools (Self Test, Replay Frame). |

### Shelly BLU H&T ZB

Pairing: Add Device > Zigbee > "Pair using strict Zigbee 3.0 mode", then press the button 5 times quickly.

Use the Simple driver for everyday use. For custom reporting intervals, switch the device to the Reference driver, change the settings, then switch back.

Both drivers are licensed under the Apache License, Version 2.0.
