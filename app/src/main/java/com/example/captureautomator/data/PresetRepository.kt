package com.example.captureautomator.data

import android.content.Context
import com.example.captureautomator.core.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

object Codec {
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    fun export(preset: Preset): String = gson.toJson(preset)
    fun parsePreset(json: String): Preset {
        require(json.length <= 2_000_000) { "Preset file too large" }
        val obj = JsonParser.parseString(json).asJsonObject
        require(obj.get("schemaVersion")?.asInt == 1) { "Unsupported preset schema" }
        require(obj.has("steps") && obj.has("apps") && obj.has("name")) { "Incomplete preset" }
        val preset = gson.fromJson(obj, Preset::class.java)
        requireNotNull(preset)
        val issues = PresetValidation.errors(preset)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        return preset
    }
}

class PresetRepository(context: Context) {
    private val dao = PresetDatabase.instance(context).dao()
    val presets: Flow<List<PresetRow>> = dao.observePresets()
    val executions: Flow<List<ExecutionRow>> = dao.observeExecutions()

    suspend fun save(preset: Preset) = withContext(Dispatchers.IO) {
        val issues = PresetValidation.errors(preset)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        dao.savePreset(PresetRow(preset.id, preset.name, preset.folder, Codec.export(preset),
            preset.createdAtEpochMs, System.currentTimeMillis()))
    }

    suspend fun load(id: String): Preset? = withContext(Dispatchers.IO) {
        dao.getPreset(id)?.json?.let(Codec::parsePreset)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.deletePreset(id) }

    suspend fun duplicate(id: String) {
        val old = load(id) ?: return
        val time = System.currentTimeMillis()
        save(old.copy(id = UUID.randomUUID().toString(), name = old.name + " (copy)",
            createdAtEpochMs = time, modifiedAtEpochMs = time))
    }

    suspend fun importJson(json: String): Preset {
        val parsed = Codec.parsePreset(json)
        val imported = parsed.copy(id = UUID.randomUUID().toString(),
            name = parsed.name + " (imported)", modifiedAtEpochMs = System.currentTimeMillis(),
            steps = parsed.steps.map {
                val safe = CameraPolicy.safeImportedStep(it)
                it.copy(id = UUID.randomUUID().toString(), needsReview = !safe,
                    classificationUncertain = if (safe) false else it.classificationUncertain)
            })
        save(imported)
        return imported
    }

    suspend fun saveReport(report: ExecutionReport) = withContext(Dispatchers.IO) {
        dao.saveExecution(ExecutionRow(report.id, report.presetId, report.presetName,
            report.outcome.name, report.startedAt, Codec.gson.toJson(report)))
    }
}
