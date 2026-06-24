# Native third-party artifacts

Pluto capture uses libiio through `PlutoSource`.

The Android app is intended to capture from Pluto through direct USB host mode:

```text
usb:fd:<android_usb_file_descriptor>
```

The Kotlin UI enumerates USB devices with `UsbManager`, requests Android USB
permission for PlutoSDR, selects the Android-visible IIO USB interface, opens
the granted `UsbDeviceConnection`, and then calls the native `PlutoSource` with
a patched libiio URI of `usb:fd:<fd>`. The patched USB backend wraps that
Android-granted file descriptor with `libusb_wrap_sys_device()` and discovers
the IIO interface from the active USB configuration. This avoids depending on
Android and libusb exposing identical interface-array indexes.

That native path requires libiio built with the USB backend and libusb support.
Plain `usb:` discovery is not used for Android production capture, because
unrooted Android commonly blocks direct `/dev/bus/usb` discovery even after
`UsbManager` grants app-level access.

The old `ip:192.168.2.1` USB-Ethernet/RNDIS endpoint is not the production path
for the autonomous receiver, because Android devices may not expose a Pluto
network interface and airplane-mode operation should avoid phone radios.

Expected Android artifact layout:

```text
app/src/main/cpp/third_party/libiio/include/...
app/src/main/jniLibs/arm64-v8a/libiio.so
app/src/main/jniLibs/arm64-v8a/libusb1.0.so
app/src/main/jniLibs/arm64-v8a/libxml2.so
app/src/main/jniLibs/arm64-v8a/libzstd.so
```

Repeat the `jniLibs/<abi>/` library set for every APK ABI that should support
Pluto capture.

CMake auto-enables `SDRVIDEOSCANNER_HAVE_LIBIIO` when both the include directory
and `jniLibs/${ANDROID_ABI}/libiio.so` are present. To prevent accidentally
building a no-Pluto stub APK, configure CMake with:

```text
-DSDRVIDEOSCANNER_REQUIRE_LIBIIO=ON
```
