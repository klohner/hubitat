# hubitat

Hubitat stuff: custom device drivers and apps.

## Drivers

Each driver package lives in `drivers/<device-or-vendor>/`.

| Device | Folder | What it does |
| --- | --- | --- |
| Shelly BLU H&T ZB (SBHT-203C) | [drivers/Shelly_BLU_H%26T_ZB](drivers/Shelly_BLU_H%26T_ZB) | Temperature, humidity and battery for the battery-powered Zigbee sensor. Includes the Simple driver (everyday use), the Reference driver (configurable reporting, diagnostics) and the Probe driver (diagnostic tool for development). Install via Hubitat Package Manager or import the `.groovy` files directly. |

See the package's [README](drivers/Shelly_BLU_H%26T_ZB/README.md) for pairing steps, install instructions and the list of files.

All drivers are licensed under the Apache License, Version 2.0.
