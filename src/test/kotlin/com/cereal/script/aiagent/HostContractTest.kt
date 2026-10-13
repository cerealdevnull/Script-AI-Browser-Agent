package com.cereal.script.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals

class HostContractTest {
    // The host's TaskExecutor.newInstance calls `constructors.first().call()`: a constructor with parameters fails there,
    // even when they all have defaults ("Callable expects 5 arguments, but 0 were provided").
    @Test
    fun `the script has only a no-arg constructor`() {
        assertEquals(listOf(0), AiAgentScript::class.java.constructors.map { it.parameterCount })
        AiAgentScript::class.constructors.first().call()
    }
}
