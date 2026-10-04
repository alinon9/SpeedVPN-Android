package com.speedvpn.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SpeedVerificationStore {
    private const val PREFS = "speed_verification"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 20

    fun save(context: Context, result: SpeedVerificationResult) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching {
            JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        }.getOrElse { JSONArray() }

        val next = JSONArray()
        next.put(toJson(result))
        for (index in 0 until current.length().coerceAtMost(MAX_HISTORY - 1)) {
            current.optJSONObject(index)?.let { next.put(it) }
        }
        prefs.edit().putString(KEY_HISTORY, next.toString()).apply()
    }

    fun loadRecent(context: Context, limit: Int = 8): List<SpeedVerificationResult> {
        val array = runCatching {
            JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]")
            )
        }.getOrElse { JSONArray() }

        return buildList {
            for (index in 0 until array.length().coerceAtMost(limit)) {
                array.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }
    }

    private fun toJson(result: SpeedVerificationResult) = JSONObject().apply {
        put("startedAtMillis", result.startedAtMillis)
        put("durationMs", result.durationMs)
        put("overallStatus", result.overallStatus.name)
        put("note", result.note ?: JSONObject.NULL)
        put("download", metricToJson(result.download))
        put("upload", metricToJson(result.upload))
    }

    private fun metricToJson(metric: SpeedMetricVerification) = JSONObject().apply {
        put("planKbps", metric.planKbps ?: JSONObject.NULL)
        put("baselineBps", metric.baselineBps ?: JSONObject.NULL)
        put("vpnBps", metric.vpnBps ?: JSONObject.NULL)
        put("status", metric.status.name)
        put("accuracyPercent", metric.accuracyPercent ?: JSONObject.NULL)
        put("reason", metric.reason ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): SpeedVerificationResult =
        SpeedVerificationResult(
            startedAtMillis = json.optLong("startedAtMillis"),
            durationMs = json.optLong("durationMs"),
            download = metricFromJson(json.optJSONObject("download")),
            upload = metricFromJson(json.optJSONObject("upload")),
            overallStatus = runCatching {
                SpeedVerificationStatus.valueOf(json.optString("overallStatus"))
            }.getOrDefault(SpeedVerificationStatus.NOT_VERIFIABLE),
            note = json.optString("note").takeIf { it.isNotBlank() && it != "null" },
        )

    private fun metricFromJson(json: JSONObject?): SpeedMetricVerification {
        if (json == null) {
            return SpeedMetricVerification(
                planKbps = null,
                baselineBps = null,
                vpnBps = null,
                status = SpeedVerificationStatus.NOT_VERIFIABLE,
                accuracyPercent = null,
                reason = "سجل قديم أو غير مكتمل.",
            )
        }

        return SpeedMetricVerification(
            planKbps = json.optLongOrNull("planKbps"),
            baselineBps = json.optLongOrNull("baselineBps"),
            vpnBps = json.optLongOrNull("vpnBps"),
            status = runCatching {
                SpeedVerificationStatus.valueOf(json.optString("status"))
            }.getOrDefault(SpeedVerificationStatus.NOT_VERIFIABLE),
            accuracyPercent = json.optDoubleOrNull("accuracyPercent"),
            reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)
}
