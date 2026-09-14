/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.batgizmo.app.trapnz

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

const val TRAP_NZ_API_URL = "https://trap.nz/api2"
const val TRAP_NZ_WEB_URL = "https://trap.nz"
const val TRAP_NZ_CLIENT_ID = "appclient"

fun trapNzNodeUrl(nid: Int): String = "$TRAP_NZ_WEB_URL/node/$nid"

fun jsonNodeId(o: JSONObject): Int? {
    val nid = o.optJSONObject("meta")?.optInt("nid", 0) ?: 0
    return nid.takeIf { it > 0 }
}

fun parseCreatedObservation(o: JSONObject, label: String = ""): CreatedObservation =
    CreatedObservation(
        uuid = o.optString("uuid"),
        nid = jsonNodeId(o),
        label = label
    )

class TrapNzError(message: String, val status: Int = 0) : IOException(message) {
    val unauthorized: Boolean get() = status == 401 || status == 403
}

data class TrapUser(
    val uuid: String,
    val username: String,
    val commonName: String,
    val email: String?
) {
    val displayName: String get() = commonName.ifBlank { username }
}

data class TrapProject(
    val uuid: String,
    val name: String,
    val canWrite: Boolean,
    val nid: Int? = null
) {
    val webUrl: String? get() = nid?.let { trapNzNodeUrl(it) }
}

data class CreatedObservation(
    val uuid: String,
    val nid: Int?,
    val label: String
) {
    val webUrl: String? get() = nid?.let { trapNzNodeUrl(it) }
}

data class TrapTerm(
    val tid: Int,
    val name: String,
    val uuid: String? = null,
    val vocabulary: String? = null
)

class TrapNzClient(
    var token: String? = null,
    private val baseUrl: String = TRAP_NZ_API_URL
) {
    val signedIn: Boolean get() = token != null

    fun signOut() {
        token = null
    }

    fun login(username: String, password: String) {
        val body = JSONObject()
            .put("grant_type", "password")
            .put("scope", "default")
            .put("username", username)
            .put("password", password)
            .put("client_id", TRAP_NZ_CLIENT_ID)
        val data = request("/oauth2/token", method = "POST", json = body, auth = false)
        val access = data.optString("access_token")
        if (access.isBlank()) throw TrapNzError("No access token returned")
        token = access
    }

    fun me(): TrapUser {
        val o = request("/users/me")
        return TrapUser(
            uuid = o.optString("uuid"),
            username = o.optString("username"),
            commonName = o.optString("common_name"),
            email = o.optString("email").ifBlank { null }
        )
    }

    fun projects(): List<TrapProject> {
        val rows = requestArray("/projects?my_membership_state=active")
        return rows.mapNotNull { o ->
            val uuid = o.optString("uuid")
            if (uuid.isBlank()) return@mapNotNull null
            val access = o.optJSONObject("my_access")
            val canWrite = access == null ||
                access.optBoolean("trapper") ||
                access.optBoolean("advanced_trapper") ||
                access.optBoolean("manager") ||
                access.optBoolean("administrator")
            TrapProject(uuid, o.optString("name", uuid), canWrite, jsonNodeId(o))
        }.filter { it.canWrite }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun terms(projectUuid: String, vocabulary: String? = null): List<TrapTerm> {
        val suffix = if (vocabulary != null) "?vocabulary=${urlEncode(vocabulary)}" else ""
        return asTermList(requestAny("/terms/project/$projectUuid$suffix"))
    }

    fun createObservation(body: JSONObject, label: String = ""): CreatedObservation {
        return try {
            parseCreatedObservation(request("/adHocObservations", method = "POST", json = body), label)
        } catch (err: TrapNzError) {
            if (!body.has("survey_event") || (err.status != 400 && err.status != 422)) throw err
            val createBody = JSONObject(body.toString())
            createBody.remove("survey_event")
            val created = request("/adHocObservations", method = "POST", json = createBody)
            val parsed = parseCreatedObservation(created, label)
            if (parsed.uuid.isNotBlank()) {
                try {
                    request(
                        "/adHocObservations/${parsed.uuid}",
                        method = "PATCH",
                        json = JSONObject().put("survey_event", body.getJSONObject("survey_event"))
                    )
                } catch (_: Exception) {
                }
            }
            parsed
        }
    }

    fun buildObservation(
        summary: LocationSummary,
        project: TrapProject,
        species: List<TrapTerm>,
        observationType: TrapTerm,
        recordedBy: String?,
        surveyEvent: TrapTerm?
    ): JSONObject {
        val speciesLabel = species.joinToString("; ") { it.name }
        val speciesArr = JSONArray()
        for (term in species) speciesArr.put(JSONObject().put("tid", term.tid))
        val body = JSONObject()
            .put("date", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(summary.startEpochMs)))
            .put("observation_type", JSONObject().put("tid", observationType.tid))
            .put("species", speciesArr)
            .put("project", JSONObject().put("uuid", project.uuid).put("name", project.name))
            .put(
                "location",
                JSONObject()
                    .put("type", "Point")
                    .put(
                        "coordinates",
                        JSONArray().put(summary.location.lon).put(summary.location.lat)
                    )
            )
            .put("notes", observationNotes(summary, speciesLabel))
        if (!recordedBy.isNullOrBlank()) body.put("recorded_by", recordedBy)
        if (surveyEvent != null) body.put("survey_event", JSONObject().put("tid", surveyEvent.tid))
        return body
    }

    private fun requestArray(path: String): List<JSONObject> {
        val any = requestAny(path)
        return when (any) {
            is JSONArray -> (0 until any.length()).mapNotNull { any.optJSONObject(it) }
            is JSONObject -> listOf(any)
            else -> emptyList()
        }
    }

    private fun requestAny(path: String, method: String = "GET", json: JSONObject? = null, auth: Boolean = true): Any {
        val text = rawRequest(path, method, json, auth)
        if (text.isBlank()) return JSONObject()
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    private fun request(
        path: String,
        method: String = "GET",
        json: JSONObject? = null,
        auth: Boolean = true
    ): JSONObject {
        val any = requestAny(path, method, json, auth)
        return any as? JSONObject ?: JSONObject()
    }

    private fun rawRequest(
        path: String,
        method: String,
        json: JSONObject?,
        auth: Boolean
    ): String {
        val payload = json?.toString()?.toByteArray(Charsets.UTF_8)
        var url = URL("$baseUrl$path")
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                if (auth) {
                    val t = token ?: throw TrapNzError("Not signed in", 401)
                    setRequestProperty("Authorization", "Bearer $t")
                }
            }
            try {
                if (payload != null) {
                    conn.outputStream.use { it.write(payload) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw TrapNzError(formatApiError("", code), code)
                    val next = URL(url, location)
                    if (!allowedRedirectHost(next.host)) {
                        throw TrapNzError("Unexpected redirect to ${next.host}", code)
                    }
                    url = next
                    return@repeat
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    throw TrapNzError(formatApiError(text, code), code)
                }
                return text
            } finally {
                conn.disconnect()
            }
        }
        throw TrapNzError("Too many redirects from Trap.NZ")
    }

    private fun allowedRedirectHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "trap.nz" || h.endsWith(".trap.nz")
    }

    private fun asTermList(data: Any): List<TrapTerm> {
        val rows: List<JSONObject> = when (data) {
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            is JSONObject -> {
                val nested = listOf("data", "items", "terms", "list", "results")
                    .firstNotNullOfOrNull { key -> data.optJSONArray(key) }
                if (nested != null) (0 until nested.length()).mapNotNull { nested.optJSONObject(it) }
                else emptyList()
            }
            else -> emptyList()
        }
        return rows.mapNotNull { o ->
            val tid = o.optInt("tid", o.optInt("id", o.optInt("term_id", 0)))
            if (tid <= 0) return@mapNotNull null
            val name = when {
                o.optString("name").isNotBlank() -> o.optString("name")
                o.optString("label").isNotBlank() -> o.optString("label")
                else -> o.optJSONObject("name")?.optString("en").orEmpty()
            }
            TrapTerm(
                tid = tid,
                name = name,
                uuid = o.optString("uuid").ifBlank { null },
                vocabulary = o.optString("vocabulary").ifBlank { null }
            )
        }
    }

    private fun formatApiError(text: String, status: Int): String {
        if (text.isBlank()) {
            return when (status) {
                307, 308 -> "Trap.NZ redirected the request (HTTP $status)"
                401, 403 -> "Wrong username or password"
                else -> "Trap.NZ error HTTP $status"
            }
        }
        return try {
            val o = JSONObject(text)
            sequenceOf("error_description", "detail", "message", "error", "title")
                .map { o.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: text
        } catch (_: Exception) {
            text
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
