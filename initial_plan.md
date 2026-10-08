# Sunsetter
Vikram Anantha
Oct 2026


## Initial Idea

I have this sunset lamp in my room that is pretty nice, has a simple switch on the power cable and also an app where I can control it on my phone. 
The app kind of sucks though.
I was thinking of recreating the app on my own by reverse engineering the app in some way to see how it is connecting to the light to send the bluetooth msgs.
This app would have a better interface, no ads, and also would be completely controlled by me.
Then, I can use NFC tags strategically placed in my room that would let me change the light by simply tapping my phone to the tag

## Parts

There are 3-4 distict stages to building this:

### Reverse engineer the app

The original app is called MeRGBW. 
I have been told that there are ways to reverse engineer the API calls that apps make by loading them into Android Studio and seeing how they work under the hood. 
I have never tried this but I think it might be cool to see how it would work.
My guess is it is sending messages over Bluetooth to the lights because it has already been paired (I don't remember if I paired it to the phone or not).
If that is the case, then I would just have to reverse engineer the messages it is sending.

### Creating a new app

This new app would improve on the old app in the following ways:
 - No ads (obv)
 - cleaner interface to change a light
 - more accurate colors
 - widget to change the light

### NFC Tags

I want to have 2 NFC tags placed in my room that do the following:
 - the one at the entrance will toggle the lights on/off when I tap my phone to it
 - the one by my bed will turn the lights off when I tap my phone to it

This should be fairly easy to implement given that I have complete control of the app

### Optional: Create a watch app

Recreate the app from the phone to my watch so that I can turn the lights on and off from my watch

## Claude's initial analysis

Target: Android phone (Kotlin), Wear OS watch.

### 1. Reverse engineer the app — done

https://github.com/SplitterBlue/mergbw-protocol documents the protocol, decompiled from `com.mergbw.android`. The lamp is an LT-06 "Sunset lights" (app device type 5).

- Service `fff0`, write to `fff3` (write *without* response), notifications on `fff4`
- Frame: `55 <cmd> FF <total_len> <payload...> <checksum>`, where checksum = `~sum(all previous bytes) & 0xFF`
- Commands type 5 uses:
  - `0x01` power: `55 01 FF 06 01 A3` on, `55 01 FF 06 00 A4` off
  - `0x03` RGB: payload `[R, G, B]`
  - `0x05` brightness: payload 5–100. **Clamp it.** Values above 100 wrap, so the lamp gets dimmer.
  - `0x06` scene: one-byte index; `134` = "Sunset", which drives the separate **amber** emitter

#### Findings from testing with nRF Connect (Oct 6, 2026)

Confirmed on my lamp:
- The lamp advertises as **"Sunset lights"** at MAC `FF:10:10:B1:99:84`. "Not bonded" is normal; there's no pairing.
- It has service `FFF0`, with `FFF3` (WRITE, WRITE NO RESPONSE) and `FFF4` (NOTIFY). This matches the repo.
- Power, RGB, brightness and amber commands from the repo all work. Close MeRGBW (Force stop) first, since the lamp accepts only one connection.

**Every command gets an acknowledgement** on `FFF4`: `56 <cmd> FF 06 00 <chk>`. A reply starts with `56` where commands start with `55`, and byte 4 = `00` means OK.

| Command sent | Ack received |
|---|---|
| Power `01` | `56 01 FF 06 00 4A` |
| Color `03` | `56 03 FF 06 00 8D` |
| Brightness `05` | `56 05 FF 06 00 8B` |
| Scene `06` | `56 06 FF 06 00 8A` |

The ack is the same for on and off, so it confirms the command arrived but says nothing about the lamp's state. The app can use it to retry writes that didn't arrive.

**The status request is `55 00 FF 05 A6`.** The reply is 15 bytes: `56 00 FF 0F <power> <brightness> 00 00 00 00 00 00 00 00 <chk>`

| State | Reply |
|---|---|
| Off, brightness 100 | `56 00 FF 0F 00 64 00…00 46` |
| On, brightness 100 | `56 00 FF 0F 01 64 00…00 45` |
| On, brightness 5 | `56 00 FF 0F 01 05 00…00 A4` |

- Byte 4 = power (`01` on, `00` off). Byte 5 = brightness (5–100), still reported while the lamp is off.
- Bytes 6–13 stayed `00` after setting red, blue and amber. **Color and mode are not reported**, so the app has to remember them itself.
- Checksums on replies: the status replies fit the command formula with the length byte left out of the sum. The acks don't fit that formula. For now the app can skip checking checksums on replies.

**The toggle tag works like this:** send status → read byte 4 → send the opposite power command → wait for the `56 01 …` ack.

Still to check: what Sunrise (`5506FF068B14`) and Summer sun (`5506FF068E11`) look like, connect time, the lamp's state after a power cycle at the cable switch, and range from the bed and the door.

Optional, for the learning experience: open the APK in **jadx-gui** and find `com.mergbw.core.ble.CommandList` and the type-5 status parser yourself.

### 2. Creating a new app

Stack: Kotlin + Jetpack Compose. Write a single `LampController` class that the app, widget, NFC handler and watch all share.

- **Bluetooth basics**
  - Permissions: `BLUETOOTH_CONNECT` (plus `BLUETOOTH_SCAN` with `neverForLocation` for the one-time setup scan).
  - Scan once to find the lamp and save its MAC address. After that, call `adapter.getRemoteDevice(mac).connectGatt(ctx, false, cb)` with no scan, which is much faster.
  - After `onServicesDiscovered`, write with `WRITE_TYPE_NO_RESPONSE`. On API 33+ use the `writeCharacteristic(char, bytes, writeType)` overload.
  - Android GATT runs one operation at a time, so queue writes. Retry on the well-known `status 133` connect error.
  - Keep the connection open while the app is in the foreground so sliders feel instant. Throttle slider writes to about every 50 ms.
- **More accurate colors**
  - The lamp has two separate light sources: RGB LEDs and an amber emitter. "Warm white" sent as RGB looks purple-ish, so make **Amber** its own mode (scene 134 + brightness). Don't fake it with RGB.
  - For RGB, use a hue wheel or saturation picker and send brightness separately through `0x05`, rather than baking brightness into RGB. Add a gamma curve and per-channel multipliers, tuned by eye against the real lamp.
  - Save presets.
- **Widget**
  - Jetpack Glance `GlanceAppWidget` with buttons → `actionRunCallback<…>()`. The callback connects, writes, and disconnects.
  - Also consider a **Quick Settings tile** (`TileService`). On Android it's arguably the best on/off control.

### 3. NFC tags

Buy NTAG213 or NTAG215 stickers. Use the **NFC Tools** app to write a URI record onto each tag: `sunsetter://toggle` (entrance) and `sunsetter://off` (bed).

- In the manifest, add an `NDEF_DISCOVERED` intent filter on an activity for `scheme="sunsetter"`.
- Give that activity a translucent theme. It reads `intent.data`, starts the BLE command in an application-scoped coroutine, then `finish()`es right away, so no UI shows up.
- Android only reads NFC with the screen on and unlocked. That's fine for tapping at the door or bed.
- **Toggle needs to know the current state.** Read status (from step 1.2) before deciding on or off. If the status format turns out to be a pain, fall back to remembering the last state in the app.
- Hard limit: if the physical switch on the cable is off, the lamp has no power and can't receive Bluetooth, so no tag or app can turn it on.

### 4. Optional: watch app (Wear OS)

- Wear OS supports BLE central, so the watch can talk to the lamp **directly** using the same `LampController` code. Put it in a shared Gradle module.
- UI: a Wear Compose screen with on/off and amber plus a few presets. A **Tile** and/or a complication gives one-tap access.
- If the lamp is out of the watch's range, the fallback is relaying through the phone over the Wearable Data Layer (`MessageClient`).

### Suggested order

1. Verify with nRF Connect.
2. Decode the status packets.
3. Build a bare app with `LampController` (on, off, RGB, brightness, amber).
4. NFC tags.
5. Widget / Quick Settings tile.
6. Polished UI and color calibration.
7. Wear OS.