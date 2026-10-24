# TubeGrab

Ad-free Android app to search YouTube inside the app and download a video (MP4) or the audio (MP3/M4A).
Built with Kotlin + Jetpack Compose on top of [yt-dlp](https://github.com/yt-dlp/yt-dlp) (via youtubedl-android) and ffmpeg.

## What it does
- **Search as you type:** suggestions while typing, plus a results list with thumbnails, duration, channel and views
- **Paste a link:** a video or playlist URL works in the same box
- **Share to TubeGrab:** in the YouTube app tap Share → TubeGrab to open that video here
- **Download:** MP3, M4A (fast, no re-encode), or 360p / 720p / 1080p MP4, with live progress and cancel. Up to 2 run at once.
- **Saved to:** `Music/TubeGrab` (audio) and `Movies/TubeGrab` (video), so files show up in your music player and gallery
- **Auto-update:** yt-dlp updates itself on every launch, and the Downloads tab has an "Update engine" button
- Tapping a thumbnail opens the video in YouTube
- Works on Android 7.0 and newer

## Get the APK, option A: GitHub (no PC setup needed)
1. Create a free account at github.com, then click **New repository** and name it `TubeGrab` (Private is fine).
2. On the repo page click **uploading an existing file**, drag in **everything inside this folder** (including the hidden `.github` folder), then click **Commit**.
   - If the `.github` folder doesn't upload from your browser, create the file `.github/workflows/build.yml` by hand with **Add file → Create new file** and paste in its contents.
3. Open the **Actions** tab and wait for "Build APK" to turn green (about 5–8 min).
4. Click the run, scroll to **Artifacts**, and download **TubeGrab-APKs** (a zip).
5. Unzip it and pick:
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
