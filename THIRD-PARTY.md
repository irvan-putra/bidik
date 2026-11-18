# Third-party components

## deltachat-rpc-server (app/src/main/jniLibs/arm64-v8a/librpcsrv.so)

The Delta Chat JSON-RPC server binary, vendored so the in-app Python
runtime can exec it from the app's native library directory.

- Obtained from the official PyPI wheel:
  https://files.pythonhosted.org/packages/50/66/7b6428ac9a4a73bfc3199a26477b2fb3d0e60d78485c1d0d0b2b755a04f8/deltachat_rpc_server-2.69.0-py3-none-android_21_arm64_v8a.whl
  (file inside the wheel: deltachat_rpc_server/deltachat-rpc-server)
- Version: 2.69.0, matching the deltachat-rpc-client pin in app/build.gradle.
- SHA-256 of the vendored binary:
  101c7c59675f295efa601eba0e4bcf01fa2456e44b8aa9ed2fa999c6fb995257
- License: Mozilla Public License Version 2.0 (full text in
  THIRD-PARTY.MPL-2.0.txt, copied from the same wheel).
- Corresponding source code for this exact version:
  https://github.com/chatmail/core/tree/v2.69.0
  (release archive: https://github.com/chatmail/core/archive/refs/tags/v2.69.0.tar.gz)

Per MPL-2.0 section 3.2, this file is how recipients of the executable
form are informed where the Source Code Form is available.

## zxing-android-embedded

Barcode scanner activity used by the setup screen to scan the pairing
QR code.

- Gradle dependency: com.journeyapps:zxing-android-embedded:4.3.0
- License: Apache License 2.0
- Source: https://github.com/journeyapps/zxing-android-embedded

## DejaVu Sans Bold (tools/fonts/DejaVuSans-Bold.ttf)

The badge font used by tools/badge_icon.py, vendored so every machine -
including CI runners - rasterizes the version strip from the identical
font file. The generator refuses any file whose SHA-256 differs.

- Source: DejaVu fonts project, https://dejavu-fonts.github.io/
- SHA-256: d1c3ff99f1e1ce1827a33efd4dad81f40babda06bff9e43bd7591c86662a287b
- License: Bitstream Vera license (permissive); full text in
  tools/fonts/LICENSE-DejaVu.txt
