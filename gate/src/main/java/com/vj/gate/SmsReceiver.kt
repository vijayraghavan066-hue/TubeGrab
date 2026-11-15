package com.vj.gate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Receiver phone: checks incoming "GATE ..." texts and hands good ones to [GateService].
 * Sender phone: picks up the gate phone's "Gate Remote: ..." replies to show them in the app.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        /** Last reply seen on the sender phone. */
        val lastReply = MutableStateFlow<String?>(null)
        private var lastRun = 0L
        private var lastBody = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }.trim()

        when (Prefs.mode(context)) {
            Prefs.SENDER -> {
                if (body.startsWith(Command.REPLY_PREFIX) &&
                    Prefs.last10(from) == Prefs.last10(Prefs.target(context))
                ) {
                    val msg = body.removePrefix(Command.REPLY_PREFIX).trim()
                    lastReply.value = msg
                    History.add(context, "⬅️ $msg")
                }
            }
            Prefs.RECEIVER -> handleCommand(context, from, body)
        }
    }

    private fun handleCommand(c: Context, from: String, body: String) {
        val cmd = Command.parse(body) ?: return // not a gate text, ignore
        if (!Prefs.isAllowed(c, from)) {
            History.add(c, "🚫 Ignored gate text from unknown number $from")
            return
        }
        val reply = { text: String -> Phone.sendSms(c, from, "${Command.REPLY_PREFIX} $text") }

        val code = Prefs.code(c)
        if (code.isEmpty() || cmd.code != code) {
            History.add(c, "🚫 Wrong code from $from")
            reply("❌ Wrong code. Gate not opened.")
            return
        }

        // Same text twice within 15 s (carrier duplicates) → run once
        val now = System.currentTimeMillis()
        if (body == lastBody && now - lastRun < 15_000) return
        lastBody = body
        lastRun = now

        if (cmd.status) {
            reply(GateService.statusText(c))
            History.add(c, "ℹ️ Status sent to $from")
            return
        }
        val gate = cmd.gate ?: return

        // 250 m rule: only open when the sender's phone is near the gate
        val home = Prefs.gateLocation(c)
        if (Prefs.requireNearby(c) && home != null) {
            if (cmd.lat == null || cmd.lon == null) {
                History.add(c, "🚫 ${gate.title}: no location in the text")
                reply("❌ No location in your text (turn on location / 'Send my location'). Not opened.")
                return
            }
            val d = Phone.distanceMeters(cmd.lat, cmd.lon, home.first, home.second)
            val limit = Prefs.radius(c)
            if (d > limit) {
                History.add(c, "🚫 ${gate.title}: sender ${d.toInt()} m away (limit $limit m)")
                reply("❌ You're ${fmt(d)} from the gate (limit $limit m). Not opened.")
                return
            }
        }

        val svc = GateService.instance
        if (svc == null) {
            History.add(c, "❌ ${gate.title}: Accessibility for Gate Remote is off")
            reply("❌ Gate phone setup issue: Accessibility for Gate Remote is off.")
            return
        }
        History.add(c, "➡️ ${gate.title} requested by $from")
        svc.openGate(gate, from)
    }

    private fun fmt(m: Float) = if (m >= 1000) "%.1f km".format(m / 1000) else "${m.toInt()} m"
}
