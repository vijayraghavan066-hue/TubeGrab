// Tube Grab Alexa skill: plays audio that the TubeGrab Android app sends to your Echo.
//
// The app posts each track ({mode, title, by, url}) to a private ntfy.sh topic (your "Echo code").
// "Alexa, open Tube Grab" plays the last track sent with "Play on Echo", then continues through
// anything you added with "Add to Echo queue". ntfy keeps messages for 12 hours.
//
// SETUP: paste the Echo code from TubeGrab (Downloads tab → Echo code → Copy) below.
const TOPIC = process.env.TUBEGRAB_TOPIC || 'PASTE-YOUR-ECHO-CODE-HERE';

const https = require('https');

// ---------------------------------------------------------------- track list from ntfy

function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { timeout: 6000 }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve(data));
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timeout')));
  });
}

/** All tracks sent in the last 12 hours, oldest first: [{id, mode, title, by, url}] */
async function loadTracks() {
  const body = await getJson(`https://ntfy.sh/${encodeURIComponent(TOPIC)}/json?poll=1&since=all`);
  const tracks = [];
  for (const line of body.split('\n')) {
    if (!line.trim()) continue;
    try {
      const ev = JSON.parse(line);
      if (ev.event !== 'message' || !ev.message) continue;
      const t = JSON.parse(ev.message);
      if (t && typeof t.url === 'string' && t.url.startsWith('https://')) {
        tracks.push({ id: ev.id, mode: t.mode || 'play', title: t.title || 'TubeGrab audio', by: t.by || '', url: t.url });
      }
    } catch (e) { /* skip anything that isn't ours */ }
  }
  return tracks;
}

/** Where "open Tube Grab" starts: the newest "Play on Echo" track (or the first queued one). */
function startIndex(tracks) {
  for (let i = tracks.length - 1; i >= 0; i--) if (tracks[i].mode === 'play') return i;
  return tracks.length ? 0 : -1;
}

const indexOf = (tracks, token) => tracks.findIndex((t) => t.id === token);

// ---------------------------------------------------------------- responses

function respond({ speech, directives, end = true } = {}) {
  const response = { shouldEndSession: end };
  if (speech) response.outputSpeech = { type: 'PlainText', text: speech };
  if (directives) response.directives = directives;
  return { version: '1.0', response };
}

function playDirective(track, behavior, offset = 0, previousToken) {
  const stream = { url: track.url, token: track.id, offsetInMilliseconds: offset };
  if (behavior === 'ENQUEUE') stream.expectedPreviousToken = previousToken;
  return {
    type: 'AudioPlayer.Play',
    playBehavior: behavior,
    audioItem: { stream, metadata: { title: track.title, subtitle: track.by || 'TubeGrab' } },
  };
}

const stop = () => ({ type: 'AudioPlayer.Stop' });

// Requests from AudioPlayer / PlaybackController can't include speech.
function play(track, offset = 0, withSpeech = true) {
  return respond({
    speech: withSpeech ? `Playing ${track.title}` : undefined,
    directives: [playDirective(track, 'REPLACE_ALL', offset)],
  });
}

// ---------------------------------------------------------------- handler

exports.handler = async (event) => {
  const out = await handle(event);
  const type = (event.request && event.request.type) || '';
  // AudioPlayer / PlaybackController / system requests may only carry AudioPlayer directives
  if (!(type === 'LaunchRequest' || type === 'IntentRequest')) {
    delete out.response.shouldEndSession;
    delete out.response.outputSpeech;
  }
  return out;
};

async function handle(event) {
  const req = event.request || {};
  const type = req.type || '';
  const intent = (req.intent && req.intent.name) || '';
  const player = (event.context && event.context.AudioPlayer) || {};
  const currentToken = req.token || player.token;
  const isController = type.startsWith('PlaybackController.');

  if (TOPIC.startsWith('PASTE-')) {
    return respond({ speech: 'Tube Grab is not set up yet. Paste your Echo code from the TubeGrab app into the skill code.' });
  }

  try {
    // Opening the skill: play what the phone sent
    if (type === 'LaunchRequest' || intent === 'PlayLatestIntent' || type === 'PlaybackController.PlayCommandIssued' && !currentToken) {
      const tracks = await loadTracks();
      const i = startIndex(tracks);
      if (i < 0) return respond({ speech: 'Nothing to play yet. In the TubeGrab app, tap Echo on a video, then say: Alexa, open Tube Grab.' });
      return play(tracks[i], 0, !isController);
    }

    // Queue: line up the next track before this one ends
    if (type === 'AudioPlayer.PlaybackNearlyFinished') {
      const tracks = await loadTracks();
      const i = indexOf(tracks, currentToken);
      if (i >= 0 && i + 1 < tracks.length) {
        return respond({ directives: [playDirective(tracks[i + 1], 'ENQUEUE', 0, currentToken)] });
      }
      return respond();
    }

    if (intent === 'AMAZON.NextIntent' || type === 'PlaybackController.NextCommandIssued' ||
        intent === 'AMAZON.PreviousIntent' || type === 'PlaybackController.PreviousCommandIssued') {
      const forward = intent === 'AMAZON.NextIntent' || type === 'PlaybackController.NextCommandIssued';
      const tracks = await loadTracks();
      const i = indexOf(tracks, currentToken);
      const j = i < 0 ? startIndex(tracks) : i + (forward ? 1 : -1);
      if (j < 0 || j >= tracks.length) {
        return isController ? respond() : respond({ speech: forward ? "That's the end of the queue." : "That's the first track." });
      }
      return play(tracks[j], 0, !isController);
    }

    if (intent === 'AMAZON.ResumeIntent' || type === 'PlaybackController.PlayCommandIssued' || intent === 'AMAZON.StartOverIntent') {
      const tracks = await loadTracks();
      const i = indexOf(tracks, currentToken);
      if (i < 0) return isController ? respond() : respond({ speech: 'That track has expired. Send it again from TubeGrab.' });
      const offset = intent === 'AMAZON.StartOverIntent' ? 0 : (player.offsetInMilliseconds || 0);
      return play(tracks[i], offset, false);
    }

    if (intent === 'AMAZON.PauseIntent' || intent === 'AMAZON.StopIntent' || intent === 'AMAZON.CancelIntent' ||
        type === 'PlaybackController.PauseCommandIssued') {
      return respond({ directives: [stop()] });
    }

    if (intent === 'AMAZON.HelpIntent') {
      return respond({
        speech: 'In the TubeGrab app on your phone, tap Echo on any video, then say: Alexa, open Tube Grab. You can say pause, resume, next, or previous.',
        end: false,
      });
    }

    if (type === 'IntentRequest') {
      // Loop, shuffle, repeat and anything else we don't do
      return respond({ speech: "Sorry, Tube Grab can't do that. Try pause, resume, next, or previous." });
    }
  } catch (e) {
    console.log('Tube Grab error', e);
    if (type === 'LaunchRequest' || type === 'IntentRequest') {
      return respond({ speech: "Sorry, I couldn't reach your TubeGrab queue. Try again in a moment." });
    }
  }

  // AudioPlayer events, SessionEndedRequest, System.ExceptionEncountered: nothing to say
  if (type === 'System.ExceptionEncountered' || type === 'AudioPlayer.PlaybackFailed') console.log(JSON.stringify(req));
  return respond();
}
