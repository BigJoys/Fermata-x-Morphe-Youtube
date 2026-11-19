## !!!Be aware of scam!!! The sites like https :// fermata-auto . com  are 100% scam. This app is free and opensource!


## Fermata Media Player
[![Download the latest APK](https://img.shields.io/github/v/release/AndreyPavlenko/Fermata?label=Download%20APK&logo=github&style=for-the-badge)](https://github.com/AndreyPavlenko/Fermata/releases/latest)

## About
Fermata Media Player is a free, open source audio, video and TV player with a simple and intuitive interface. It is focused on playing media files organized in folders and playlists.

Supported features:

* Play media files organized in folders
* IPTV addon with support for XMLTV EPG and Catchup
* Remembers the last played track and position for each folder
* Support for favorites and playlists
* Support for CUE and M3U playlists
* Support for bookmarks
* Audio effects: Equalizer, Bass/Volume Boost and Virtualizer
* Configure audio effects for individual tracks and folders
* Configure playback speed for individual tracks and folders
* Customizable titles and subtitles
* Support for Android Auto
* Support for Android TV
* Show favorites and playlists on Android TV home screen
* Pluggable media engines: MediaPlayer, ExoPlayer and VLC
* Video player with support for subtitles and audio streams (VLC Engine only)

## Morphe YouTube
In this fork, YouTube is replaced with [Morphe YouTube](https://morphe.software)
(`app.morphe.android.youtube`), YouTube patched with the [Morphe patches](https://github.com/MorpheApp/morphe-patches):

* The YouTube entry (menu, voice commands, "open on start") opens the Morphe YouTube app when it's installed.
* Android Auto: in mirroring mode Morphe YouTube is opened on the car screen and it's in the launcher app list instead
  of the stock YouTube app. Without mirroring, the built-in YouTube player (with SponsorBlock and the media session)
  is used, as an app started from Fermata would show up on the phone instead.
* **Morphe YouTube in Android Auto** (no root): the *Morphe YouTube* app in Android Auto shows the real Morphe
  YouTube app on the car screen, without mirroring the phone. Fermata starts it on a virtual display with
  [Shizuku](https://shizuku.rikka.app), like scrcpy's "new display", and passes the car's touch and key events to it.
  Requires Shizuku, started via wireless debugging after each reboot of the phone, and allowing Fermata in it once.
  The back and home buttons are at the bottom left.
* Settings > YouTube: *Use Morphe YouTube*, *Update Morphe YouTube automatically*, the GitHub repository the updates
  come from and *Check for Morphe YouTube update*.
* Sign-in requires [MicroG-RE](https://github.com/MorpheApp/MicroG-RE).

### Automatic patching and updates
The [Morphe YouTube workflow](.github/workflows/morphe-youtube.yml) runs every 6 hours. When Morphe has released new
patches, it downloads the YouTube version they recommend, patches it with the Morphe CLI and publishes the signed APK as
a release tagged `morphe-youtube-<YouTube version>-<patches version>` (the last 3 are kept). Fermata Auto checks for
these releases over Wi-Fi every few hours and installs a newer one. After Fermata installed Morphe YouTube once,
Android 12+ updates it without asking.

Setup:
1. Every update must be signed with the same key. Create a keystore, or export the one of Morphe Manager
   (to update an app patched by Morphe Manager), and add it as the repository secret `MORPHE_KEYSTORE_BASE64`
   (`base64 -w0 morphe.keystore`). If its alias or passwords differ from Morphe's defaults (alias `Morphe`, key password
   `Morphe`, no store password), also set `MORPHE_KEY_ALIAS`, `MORPHE_KEY_PASSWORD` and `MORPHE_KEYSTORE_PASSWORD`.
2. Scheduled workflows only run on the default branch, so merge it there. Run it once by hand: Actions >
   Morphe YouTube > Run workflow. If the APK download from APKPure fails, pass a direct APK/APKM/XAPK URL as `apk_url`.
3. Optional: the repository variable `MORPHE_STRIPLIBS` sets the ABIs to keep (default `arm64-v8a`, `none` keeps all).
4. In Fermata: Settings > YouTube > *Check for Morphe YouTube update* installs it the first time.

Note that the releases contain a patched YouTube APK and are public in a public repository.

## Building the project
* Download and install the latest Android SDK or Android Studio from https://developer.android.com/studio/
* Set the environment variable ANDROID_SDK_ROOT pointing to the SDK directory
```bash
export ANDROID_SDK_ROOT=<path to android SDK>
```

### Clone the repository
```bash
git clone --recurse-submodules https://github.com/AndreyPavlenko/Fermata.git
cd Fermata
```

### Build AAB
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.aab
```

### Build APK
```bash
./gradlew bundleAutoRelease -PAPP_ID_SFX=.type.your.pkg.sfx.here
find $PWD -name *.apk
```

### Building in docker
```bash
docker run -ti --name Fermata andreypavlenko/fermata
```
Enter the requested key alias and password, when prompted.
Build the required package using the above commands.
To copy the built package to the host machine, open a new terminal and run:
```bash
docker cp Fermata:/home/mobiledevops/Fermata/fermata/build/outputs/bundle/autoRelease/ .
```


## Donation
If you like the application, please consider making a donation:

[PayPal](https://www.paypal.com/donate/?hosted_button_id=NP5Q3YDSCJ98N)

[CloudTips](https://pay.cloudtips.ru/p/a03a73da)

[Yandex Money](https://money.yandex.ru/to/410014661137336)
