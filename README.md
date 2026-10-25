# TubeGrab

Ad-free Android app to search YouTube inside the app and download a video (MP4) or the audio (MP3/M4A).
Built with Kotlin + Jetpack Compose on top of [yt-dlp](https://github.com/yt-dlp/yt-dlp) (via youtubedl-android) and ffmpeg.

## What it does
- **YouTube search:** suggestions appear as you type, results show thumbnails, duration, channel and views, and a pasted video or playlist link works too
- **Other sites tab:** searches **Internet Archive** (public-domain movies, classic films, documentaries) and **Dailymotion** at the same time
- **Share to TubeGrab:** in the YouTube app tap Share → TubeGrab to open that video here
- **Download formats:**
  - MP3 audio
  - **MP3 – split into songs:** cuts album or jukebox videos at their chapters into one MP3 per song, in `Music/TubeGrab/<video name>/`. You can play or delete each song from the Downloads tab.
  - M4A audio (fast, no re-encode)
  - MP4 video at 360p / 720p / 1080p
- **Progress:** live progress and cancel, with up to 2 downloads at once
- **Saved to:** `Music/TubeGrab` (audio) and `Movies/TubeGrab` (video), so files show up in your music player and gallery
- **App updates like the Play Store:** on launch the app checks this repo's latest release and shows an **Update available** bar, then **Update → Install**. You can also tap **Check app update** in the Downloads tab.
- **Engine updates:** yt-dlp updates itself on every launch, and the Downloads tab has an **Update engine** button
- Works on Android 7.0 and newer

## Version history
- **1.0:** YouTube search plus MP3/MP4 downloads
- **1.1:** Other sites tab (Internet Archive + Dailymotion), fixed signing key so updates install over the old version
- **1.2:** In-app updater, plus MP3 split into songs with per-song play and delete

## How updates are built
Every push to `main` runs the GitHub Action, which builds the APKs and publishes a release tagged `build-N`. N is the GitHub run number, and it's also the app's versionCode. The in-app updater compares the two numbers and downloads the APK that matches the phone's CPU.
Keep this repo **public** so the updater can see releases. If you make it private, the updater needs a separate public APK repo.

## Get the APK, option A: GitHub (no PC setup needed)
1. Create a free account at github.com, then click **New repository** and name it `TubeGrab` (Private is fine).
2. On the repo page click **uploading an existing file**, drag in **everything inside this folder** (including the hidden `.github` folder), then click **Commit**.
   - If the `.github` folder doesn't upload from your browser, create the file `.github/workflows/build.yml` by hand with **Add file → Create new file** and paste in its contents.
3. Open the **Actions** tab and wait for "Build APK" to turn green (about 5–8 min).
4. Open the repo's **Releases** (right side of the repo page) and download the APK straight to your phone:
   - `app-arm64-v8a-release.apk` for almost any phone from the last ~8 years (smallest)
   - `app-universal-release.apk` if you're not sure (bigger, works everywhere)

## Get the APK, option B: Android Studio
1. Install Android Studio, then **File → Open** this folder and let Gradle sync.
2. **Build → Build App Bundle(s)/APK(s) → Build APK(s)**, or plug in your phone with USB debugging on and press ▶ Run.

## Install on your phone
1. Copy the APK to your phone (Google Drive, email, WhatsApp to yourself, or USB).
2. Tap it. Android will ask to allow installs from that app (Files/Chrome/Drive), so tap **Settings → Allow from this source**, go back, and tap **Install**.
3. If Play Protect warns you, tap **More details → Install anyway**. It warns because the app wasn't installed from the Play Store.
4. First launch takes ~15 seconds while the download engine unpacks.

## If downloads start failing
YouTube changes often. Open the **Downloads** tab and tap **Update engine**, then try again.

Newer yt-dlp works best on YouTube with a JavaScript runtime (Deno). This app doesn't bundle one because it would add ~90 MB, so yt-dlp falls back to YouTube clients that don't need it. That covers most videos, but occasionally some formats or videos won't be offered. If that becomes a problem, the fix is to bundle an Android arm64 Deno binary as `app/src/main/jniLibs/arm64-v8a/libdeno.so` (youtubedl-android puts that folder on yt-dlp's PATH).

## Fair use
Only download content you have the right to keep (your own uploads, Creative Commons, etc.). Downloading may go against YouTube's Terms of Service. This app is for personal use and isn't meant for the Play Store.
