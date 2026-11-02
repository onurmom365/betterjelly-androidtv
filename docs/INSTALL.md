# Install Jellyfin from betterjelly-androidtv

This is an independent fork. The TV app still displays **Jellyfin** and uses the Jellyfin splash screen.

## Fire OS with Downloader

1. Confirm the device runs Android-based Fire OS. Vega OS cannot run this APK.
2. Install **Downloader by AFTVnews** from the device's app store.
3. Allow Downloader to install apps when the device asks. Depending on Fire OS, this is under Settings → My Fire TV → Developer options → Install unknown apps. Menu names vary. If Developer options is hidden, open About and select the device name seven times.
4. In Downloader, enter code **8769319**, or open this direct APK URL:

   `https://github.com/onurmom365/betterjelly-androidtv/releases/latest/download/betterjelly-androidtv.apk`

5. Choose Install, then open **Jellyfin**. No cable or GitHub account is needed. Code **8769319** points to the stable latest APK URL, so it can be reused for future releases that keep the same asset name.

For Shield/Android TV, use Downloader where available, transfer the APK through your preferred file-transfer app, or install with `adb install -r betterjelly-androidtv.apk` after verifying its identity.

## Connect to Jellyfin and Seerr

Sign into the intended Jellyfin server with your own Jellyfin account. Do not use another person's administrator credentials.

For Seerr, open Search and a Seerr title, choose **Server**, and select the correct HTTPS address when away from home. This deployment includes a Remote HTTPS preset; another server can be entered instead. Test and save the address. Sign in using your own account and one of the methods enabled by that Seerr server. Jellyfin sign-in uses your Jellyfin username/password through Seerr; it does not copy your existing Jellyfin token. Passwords are not retained. LAN and HTTPS sessions are saved separately.

Search, Requests and request review can be checked without submitting media. Actual media requests, Retry and Delete change server state and should be used deliberately. Approval rules and quality/tag permissions remain controlled by Seerr. A request saved in Seerr does not guarantee successful downstream acquisition.

## Update while preserving settings

Download the latest APK using the same link and choose **Install** over the existing fork. Do not uninstall or clear data. Matching application ID and signing identity preserve compatible local settings and sessions; expired server sessions may need sign-in again.

The fork's package is `org.jellyfin.androidtv.debug`; official Jellyfin is `org.jellyfin.androidtv`. Both can coexist, even though both display Jellyfin. Existing Jellyfin Debug development installations update to this release and become labelled Jellyfin. Official app settings do not transfer automatically.

There is no in-app updater in 1.0.0. Future releases will use the same package/signing identity and increasing version codes.

## Verification and troubleshooting

The release provides `SHA256SUMS.txt`, `release.json` and a matching source tag. The signing certificate SHA-256 is:

`6644d28e5c9249280a7552f404c675bde1316508eef2438fa2217b10121d8272`

If installation reports an incompatible signature or wrong package, stop rather than uninstalling an app containing wanted data. Check that you downloaded this repository's release APK. Never uninstall official Jellyfin to install this fork.

Fire hardware is not yet validated. After installation, check remote navigation, audio/passthrough, HDR/Dolby Vision where supported, subtitles, seeking/trickplay, intro skipping and Seerr with your own account. Seerr encrypted sessions require Android 6 or later.
