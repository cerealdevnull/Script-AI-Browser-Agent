package com.cereal.script.sample

import com.cereal.sdk.ScriptConfiguration
import com.cereal.sdk.ScriptConfigurationItem
import com.cereal.sdk.models.proxy.Proxy

interface SampleConfiguration : ScriptConfiguration {

    @ScriptConfigurationItem(
        keyName = "NullableStringKey",
        name = "Nullable String",
        description = "Text field allowing empty. This string is marked as nullable and is optional for the user to fill in."
    )
    fun nullableStringValue(): String?

    @ScriptConfigurationItem(
        keyName = "BooleanKey",
        name = "Boolean",
        description = "This is a boolean field showing a switch the user can click to enable."
    )
    fun booleanValue(): Boolean

    @ScriptConfigurationItem(
        keyName = "IntegerKey",
        name = "Integer",
        description = "This is an integer field where you can only enter numbers."
    )
    fun integerValue(): Int

    @ScriptConfigurationItem(
        keyName = "FloatingKey",
        name = "Float",
        description = "This is a float field where you can only enter numbers and a separator."
    )
    fun floatValue(): Float

    @ScriptConfigurationItem(
        keyName = "DoubleKey",
        name = "Double",
        description = "This is a double field where you can only enter numbers and a separator."
    )
    fun doubleValue(): Double

    @ScriptConfigurationItem(
        keyName = "Proxies",
        name = "Proxies",
        description = "Select or upload proxies based on template, one proxy per task."
    )
    fun proxyValue(): Proxy

    @ScriptConfigurationItem(
        keyName = "UsernameKey",
        name = "Username",
        description = "Select or upload a list with usernames, one per task.",
        valuePerTask = true
    )
    fun usernameValue(): String

    @ScriptConfigurationItem(
        keyName = "PasswordKey",
        name = "Password",
        description = "Select or upload a list with password, one per task.",
        valuePerTask = true
    )
    fun passwordValue(): String
}
