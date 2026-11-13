# Tube Grab Alexa skill (play TubeGrab audio on an Echo Dot)

Tap **▶ Echo** on any video in TubeGrab, then say **"Alexa, open Tube Grab"**. The Echo streams the audio itself, so your phone doesn't need to stay connected.

```
TubeGrab app ──(audio link)──▶ ntfy.sh private topic ◀──(reads)── Tube Grab skill ──▶ Echo Dot plays it
```

## One-time setup (about 15 minutes, free, works from a phone browser)

1. Open **developer.amazon.com/alexa/console/ask** and sign in with the **same Amazon account your Echo uses**.
2. Tap **Create Skill**:
   - Name: `Tube Grab`, locale **English (US)**
   - Type: **Other → Custom**; hosting: **Alexa-hosted (Node.js)**
   - Template: **Start from scratch**, then **Create**. Wait about a minute while it sets up.
3. **Build** tab:
   - Open **Interaction Model → JSON Editor**, delete what's there, paste all of [`interactionModel.json`](interactionModel.json), then tap **Save**.
   - Open **Interfaces**, turn on **Audio Player**, then tap **Save**.
   - Tap **Build skill** and wait for "Build successful".
4. **Code** tab:
   - Open `index.js`, replace everything with [`lambda/index.js`](lambda/index.js), and save.
   - In TubeGrab, open **Downloads tab → Echo code → Copy**.
   - Paste the code over `PASTE-YOUR-ECHO-CODE-HERE` on the `const TOPIC` line, then tap **Save** and **Deploy**.
5. **Test** tab: change "Off" to **Development**. The skill is now enabled on your own Echo devices and nobody else's.

## Using it
- **▶ Echo → Play on Echo:** starts a new list. Then say "Alexa, open Tube Grab".
- **▶ Echo → Add to Echo queue:** adds the track after the last one you sent. It plays automatically when the current track ends.
- Voice controls: "Alexa, pause", "Alexa, resume", "Alexa, next", "Alexa, previous", "Alexa, start over"

## Limits
- **Same Wi-Fi:** your phone needs to be on the same home internet as the Echo when you tap Echo, because YouTube links only work from the connection that requested them. Internet Archive tracks work from anywhere.
- **Time limits:** sent tracks are kept for 12 hours, and YouTube links stop working after about 6 hours. If one fails, send it again.
- **Voice start:** Alexa doesn't let apps start playback on their own, so the first "Alexa, open Tube Grab" is always by voice. After that, the queue plays on by itself.
- **Echo code privacy:** the code is a random private topic name on ntfy.sh. It only carries the stream link and title, but don't share it.
- **Your own downloads:** saved MP3s live on your phone, where the Echo can't reach them. To play one, pair the phone with the Echo over Bluetooth ("Alexa, pair Bluetooth"), then tap **Play** in the Downloads tab.
