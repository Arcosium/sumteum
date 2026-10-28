package com.arka.sumteum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/** Gleo AI voice calls (component "service" in assets/schema.json) and the callback broadcast. */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val (status, message, data) = Hub.gleo(action)
        val response = JSONObject().put("functionName", action).put("status", status).put("message", message)
        data?.let { response.put("data", it) }
        Log.i("Gleo", response.toString())
        ctx.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .putExtra("packageName", ctx.packageName)
            .putExtra("response", response.toString()))
    }
}
