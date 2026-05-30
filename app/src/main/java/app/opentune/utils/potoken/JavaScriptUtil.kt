/*
 * Copyright (C) 2025 OpenTune
 * Adapted from NewPipe (TeamNewPipe/NewPipe), which is licensed GPL-3.0.
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils.potoken

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)

    val challengeData: JSONArray = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        val descrambled = descramble(scrambled.getString(1))
        JSONArray(descrambled)
    } else {
        scrambled.getJSONArray(0)
    }

    val messageId = challengeData.optString(0)
    val interpreterHash = challengeData.optString(3)
    val program = challengeData.optString(4)
    val globalName = challengeData.optString(5)
    val clientExperimentsStateBlob = challengeData.optString(7)

    val innerArray1 = challengeData.optJSONArray(1)
    val safeScriptValue = innerArray1?.let { arr ->
        (0 until arr.length()).map { arr.opt(it) }.firstOrNull { it is String } as String?
    }

    val innerArray2 = challengeData.optJSONArray(2)
    val trustedResourceUrlValue = innerArray2?.let { arr ->
        (0 until arr.length()).map { arr.opt(it) }.firstOrNull { it is String } as String?
    }

    return JSONObject().apply {
        put("messageId", messageId)
        put("interpreterJavascript", JSONObject().apply {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", safeScriptValue)
            put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trustedResourceUrlValue)
        })
        put("interpreterHash", interpreterHash)
        put("program", program)
        put("globalName", globalName)
        put("clientExperimentsStateBlob", clientExperimentsStateBlob)
    }.toString()
}

fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val arr = JSONArray(rawIntegrityTokenData)
    return base64ToU8(arr.getString(0)) to arr.getLong(1)
}

fun stringToU8(identifier: String): String =
    newUint8Array(identifier.toByteArray(Charsets.UTF_8))

fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",")
        .map { it.trim().toUByte().toByte() }
        .toByteArray()
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
}

private fun descramble(scrambled: String): String =
    base64ToByteArray(scrambled)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String =
    newUint8Array(base64ToByteArray(base64))

private fun newUint8Array(bytes: ByteArray): String =
    "new Uint8Array([" + bytes.joinToString(",") { it.toUByte().toString() } + "])"

private fun base64ToByteArray(base64: String): ByteArray {
    val normalized = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return Base64.decode(normalized, Base64.DEFAULT)
}
