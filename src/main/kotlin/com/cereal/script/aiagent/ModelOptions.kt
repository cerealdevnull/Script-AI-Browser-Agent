package com.cereal.script.aiagent

import com.cereal.sdk.FieldOption
import com.cereal.sdk.FieldOptions
import com.cereal.sdk.statemodifier.ScriptConfig

/**
 * The Model field's dropdown, now always empty: the model comes from the client's Settings → AI, and the field is
 * unused until L4 (#133) removes it. The jar build restores this class's compiler original (the host needs its Kotlin
 * metadata for objectInstance), so it may reference only names the shrinking keeps: [loadModelOptions].
 */
object ModelOptions : FieldOptions {
    override val dependsOn = setOf("provider", "apiKey", "baseUrl")

    override suspend fun load(config: ScriptConfig): List<FieldOption> = loadModelOptions(config)
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun loadModelOptions(config: ScriptConfig): List<FieldOption> = emptyList()
