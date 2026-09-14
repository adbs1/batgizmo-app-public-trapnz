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

package org.batgizmo.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.batgizmo.app.review.ReviewSession
import org.batgizmo.app.trapnz.CreatedObservation
import org.batgizmo.app.trapnz.TRAP_NZ_WEB_URL
import org.batgizmo.app.trapnz.TrapNzClient
import org.batgizmo.app.trapnz.TrapNzError
import org.batgizmo.app.trapnz.TrapProject
import org.batgizmo.app.trapnz.TrapTerm
import org.batgizmo.app.trapnz.TrapUser
import org.batgizmo.app.trapnz.findHeardType
import org.batgizmo.app.trapnz.findSurveyEvent
import org.batgizmo.app.trapnz.matchSpecies
import org.batgizmo.app.trapnz.mergeTerms
import org.batgizmo.app.trapnz.partitionUploadables
import org.batgizmo.app.trapnz.summariseLocations

private data class UploadOutcome(
    val project: TrapProject,
    val created: List<CreatedObservation>,
    val failures: List<String>,
    val recordingCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrapNzDialog(
    context: Context,
    reviewSession: ReviewSession,
    onDismiss: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences("trapnz", Context.MODE_PRIVATE) }
    val client = remember { TrapNzClient(token = prefs.getString("token", null)) }
    val scope = rememberCoroutineScope()
    val reviewItems by reviewSession.items.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var user by remember { mutableStateOf<TrapUser?>(null) }
    var projects by remember { mutableStateOf<List<TrapProject>>(emptyList()) }
    var selectedProject by remember { mutableStateOf<TrapProject?>(null) }
    var speciesTerms by remember { mutableStateOf<List<TrapTerm>>(emptyList()) }
    var typeTerms by remember { mutableStateOf<List<TrapTerm>>(emptyList()) }
    var surveyTerms by remember { mutableStateOf<List<TrapTerm>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var projectQuery by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<UploadOutcome?>(null) }

    val (eligible, skipped) = remember(reviewItems) {
        partitionUploadables(reviewItems)
    }
    val summaries = remember(eligible) { summariseLocations(eligible) }

    LaunchedEffect(Unit) {
        if (client.signedIn) {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    user = client.me()
                    projects = client.projects()
                }
                val saved = prefs.getString("project", null)
                selectedProject = projects.firstOrNull { it.uuid == saved } ?: projects.firstOrNull()
            } catch (e: Exception) {
                if (e is TrapNzError && e.unauthorized) {
                    client.signOut()
                    prefs.edit().remove("token").apply()
                }
                status = e.message ?: "Sign-in expired"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(selectedProject?.uuid) {
        val project = selectedProject ?: return@LaunchedEffect
        prefs.edit().putString("project", project.uuid).apply()
        if (outcome != null) return@LaunchedEffect
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val projectSpecies = runCatching { client.terms(project.uuid, "monitoring_species") }.getOrDefault(emptyList())
                val allTerms = runCatching { client.terms(project.uuid) }.getOrDefault(emptyList())
                val bait = runCatching { client.terms(project.uuid, "bait_station_species_detected") }.getOrDefault(emptyList())
                typeTerms = runCatching { client.terms(project.uuid, "monitoring_type") }.getOrDefault(emptyList())
                surveyTerms = runCatching { client.terms(project.uuid, "monitoring_survey_event") }.getOrDefault(emptyList())
                speciesTerms = mergeTerms(projectSpecies, allTerms, bait)
            }
        } catch (e: Exception) {
            status = e.message ?: "Could not load project terms"
        } finally {
            busy = false
        }
    }

    val finished = outcome
    if (finished != null) {
        UploadResultsDialog(context, finished, onDismiss)
        return
    }

    val preview = buildString {
        append("${summaries.size} site${if (summaries.size == 1) "" else "s"}, ")
        append("${eligible.size} recording${if (eligible.size == 1) "" else "s"} ready")
        if (skipped.isNotEmpty()) {
            append("\n${skipped.size} skipped")
            val gps = skipped.count { it.reason.name == "NO_GPS" }
            val date = skipped.count { it.reason.name == "NO_DATE" }
            if (gps > 0) append(" ($gps no GPS")
            if (date > 0) append(if (gps > 0) ", $date no date)" else " ($date no date)")
            else if (gps > 0) append(")")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Upload to Trap.NZ") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(preview)
                Spacer(Modifier.height(12.dp))
                if (user == null) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Trap.NZ username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Signed in as ${user!!.displayName}")
                    TextButton(onClick = {
                        client.signOut()
                        user = null
                        prefs.edit().remove("token").apply()
                    }) { Text("Sign out") }
                    Spacer(Modifier.height(8.dp))
                    val matchingProjects = remember(projects, projectQuery, projectMenu) {
                        val q = if (projectMenu) projectQuery.trim() else ""
                        if (q.isEmpty()) projects
                        else projects.filter { it.name.contains(q, ignoreCase = true) }
                    }
                    ExposedDropdownMenuBox(
                        expanded = projectMenu,
                        onExpandedChange = { expanded ->
                            projectMenu = expanded
                            if (expanded) projectQuery = ""
                        }
                    ) {
                        OutlinedTextField(
                            value = if (projectMenu) projectQuery
                                else selectedProject?.name.orEmpty(),
                            onValueChange = { value ->
                                projectQuery = value
                                if (!projectMenu) projectMenu = true
                            },
                            readOnly = false,
                            singleLine = true,
                            label = { Text("Project") },
                            placeholder = { Text("Search projects") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectMenu)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = projectMenu,
                            onDismissRequest = { projectMenu = false }
                        ) {
                            if (matchingProjects.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No matching projects") },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                for (project in matchingProjects) {
                                    DropdownMenuItem(
                                        text = { Text(project.name) },
                                        onClick = {
                                            selectedProject = project
                                            projectQuery = ""
                                            projectMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status)
                }
            }
        },
        confirmButton = {
            if (user == null) {
                TextButton(
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        status = "Signing in…"
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    client.login(username.trim(), password)
                                    user = client.me()
                                    projects = client.projects()
                                }
                                prefs.edit()
                                    .putString("token", client.token)
                                    .putString("username", username.trim())
                                    .apply()
                                password = ""
                                selectedProject = projects.firstOrNull { it.uuid == prefs.getString("project", null) }
                                    ?: projects.firstOrNull()
                                status = if (projects.isEmpty()) "No writable projects" else ""
                            } catch (e: Exception) {
                                status = e.message ?: "Sign-in failed"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Signing in…" else "Sign in") }
            } else {
                TextButton(
                    enabled = !busy && selectedProject != null && summaries.isNotEmpty(),
                    onClick = {
                        val project = selectedProject ?: return@TextButton
                        busy = true
                        status = "Uploading…"
                        scope.launch {
                            try {
                                val uploadedKeys = mutableListOf<String>()
                                val created = mutableListOf<CreatedObservation>()
                                val failures = mutableListOf<String>()
                                withContext(Dispatchers.IO) {
                                    val type = findHeardType(typeTerms) ?: typeTerms.firstOrNull()
                                        ?: throw TrapNzError("This project has no acoustic / heard observation type")
                                    val survey = findSurveyEvent(surveyTerms)
                                    for ((i, summary) in summaries.withIndex()) {
                                        withContext(Dispatchers.Main) {
                                            status = "Uploading ${i + 1} of ${summaries.size}…"
                                        }
                                        val species = matchSpecies(speciesTerms, summary.speciesNames)
                                        if (species.isEmpty()) {
                                            failures.add("${summary.gridKey}: no Bat species term")
                                            continue
                                        }
                                        val body = client.buildObservation(
                                            summary, project, species, type, user?.displayName, survey
                                        )
                                        val label = "${summary.count} recording${if (summary.count == 1) "" else "s"}"
                                        try {
                                            val record = client.createObservation(body, label)
                                            if (record.uuid.isBlank() && record.nid == null) {
                                                failures.add("${summary.gridKey}: no record id returned")
                                                continue
                                            }
                                            created.add(record)
                                            uploadedKeys.addAll(summary.itemKeys)
                                        } catch (e: Exception) {
                                            failures.add("${summary.gridKey}: ${e.message}")
                                        }
                                    }
                                }
                                reviewSession.markUploaded(uploadedKeys)
                                outcome = UploadOutcome(
                                    project = project,
                                    created = created,
                                    failures = failures,
                                    recordingCount = uploadedKeys.size
                                )
                            } catch (e: Exception) {
                                if (e is TrapNzError && e.unauthorized) {
                                    client.signOut()
                                    user = null
                                    prefs.edit().remove("token").apply()
                                }
                                status = e.message ?: "Upload failed"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Working…" else "Upload") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun UploadResultsDialog(
    context: Context,
    outcome: UploadOutcome,
    onDismiss: () -> Unit
) {
    val ok = outcome.created.size
    val headline = when {
        ok > 0 && outcome.failures.isEmpty() ->
            "Uploaded $ok observation${if (ok == 1) "" else "s"} (${outcome.recordingCount} recording${if (outcome.recordingCount == 1) "" else "s"}) to ${outcome.project.name}."
        ok > 0 ->
            "Uploaded $ok observation${if (ok == 1) "" else "s"}. ${outcome.failures.size} failed."
        else ->
            "Nothing was uploaded."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload results") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(headline)
                val projectUrl = outcome.project.webUrl
                if (ok > 0 && projectUrl != null) {
                    TextButton(onClick = { openTrapNz(context, projectUrl) }) {
                        Text("View project on Trap.NZ")
                    }
                } else if (ok > 0) {
                    TextButton(onClick = { openTrapNz(context, TRAP_NZ_WEB_URL) }) {
                        Text("Open Trap.NZ")
                    }
                }
                if (outcome.created.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Records")
                    for ((i, record) in outcome.created.withIndex()) {
                        val url = record.webUrl
                        if (url != null) {
                            TextButton(onClick = { openTrapNz(context, url) }) {
                                Text("Record ${i + 1}: ${record.label}")
                            }
                        } else {
                            Text("Record ${i + 1}: ${record.label}")
                        }
                    }
                }
                if (outcome.failures.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Failed")
                    for (failure in outcome.failures.take(8)) {
                        Text(failure)
                    }
                }
            }
        },
        confirmButton = {
            val firstUrl = outcome.created.firstNotNullOfOrNull { it.webUrl } ?: outcome.project.webUrl
            if (firstUrl != null) {
                TextButton(onClick = { openTrapNz(context, firstUrl) }) {
                    Text("Open records")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun openTrapNz(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
