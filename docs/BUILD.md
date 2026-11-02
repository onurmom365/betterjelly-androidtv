# Build and release

Use JDK 21, the Android SDK and the checked-in Gradle wrapper. The SDK versions are pinned in `gradle/libs.versions.toml`. No private server credentials are needed to compile or run the unit tests. Existing local SDK/cache/signing files are not part of the source distribution.

## Development

Set `JAVA_HOME` and `ANDROID_HOME` for your installation, then run:

```sh
./gradlew :app:testDebugUnitTest assembleDebug
```

The default fork version is 1.0.0. Set `JELLYFIN_VERSION` to a semantic release version for subsequent builds. Version codes use the existing upstream mapping: 1.0.0 → 1000099, 1.0.1 → 1000199. Never publish a release whose code does not exceed the installed/released version.

Both build types use `org.jellyfin.androidtv.debug`, preserving the existing fork identity independently of official Jellyfin. Release builds display Jellyfin; development builds display Jellyfin Debug. Both use the original Jellyfin artwork/splash assets. Building a debug APK on another machine normally creates a different signing identity and will not update a maintainer-signed installation.

## Signed release

Only the maintainer's existing private signing identity can update distributed installations. Keep the key backed up privately; do not commit, upload or place it in a public build artifact. This release retains the identity used by the tested development builds.

Supply these environment variables locally, using your private signing configuration:

- `KEYSTORE_FILE`
- `KEYSTORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `JELLYFIN_VERSION`

Build:

```sh
./gradlew :app:testReleaseUnitTest assembleRelease
```

On Windows, use `gradlew.bat`. Release output is under `app/build/outputs/apk/release/`. No release signing configuration means an unsigned APK; do not distribute it as an installable release. The APK uploaded to Releases uses the stable asset name `betterjelly-androidtv.apk`.

Before publishing, verify the actual APK package/version/label, `debuggable=false`, minimum SDK, ARM ABIs and signature with Android SDK `aapt`/`apkanalyzer`/`apksigner`. Independently reject official `org.jellyfin.androidtv` and any certificate other than the retained signing identity. Test an in-place update of the fork and confirm official Jellyfin metadata and fork data identity remain intact. Never install an unchecked APK or clear official data.

Publish the tested APK, `SHA256SUMS.txt`, `release.json` and release notes from the exact committed/tagged source. GitHub's source archives at that tag provide the corresponding source; all copyright/license notices and dependency declarations remain included. Keep local device snapshots, credentials, sessions, keys and SDK configuration out of uploads. The upstream deployment workflow has been removed; publication is deliberately performed from verified local assets, not an unconfigured cloud signing job.

## Future updater

1.0.0 supports manual in-place APK updates. `release.json` records the package, code, minimum SDK, asset URL, hash and signing certificate for release tracking; the current app does not consume it. A later client milestone can check releases outside playback, show changes, validate a download and invoke the system installer. Normal installation confirmation should be expected on Shield/Fire OS; silent installation is not promised.
