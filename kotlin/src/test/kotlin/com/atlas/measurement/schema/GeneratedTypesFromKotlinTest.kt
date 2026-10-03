package com.atlas.measurement.schema

import com.atlas.measurement.schema.v1.LiveMeasurement
import com.atlas.measurement.schema.v1.StrictMapper
import com.fasterxml.jackson.core.JsonProcessingException
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The Kotlin artifact exposes the generated JVM 11 types: they are used from Kotlin exactly as they are. */
class GeneratedTypesFromKotlinTest {
    private val mapper = StrictMapper.create()
    private val examples = Paths.get("..", "json-schema", "v1", "examples")
    private val negative = Paths.get("..", "vectors", "negative", "1.0.0")

    @Test
    fun exampleDeserializes() {
        val m = mapper.readValue(Files.readString(examples.resolve("live-measurement-object-box.json")), LiveMeasurement::class.java)
        assertEquals(LiveMeasurement.Mode.OBJECT_BOX, m.mode)
        assertEquals(0.602, m.dimensions.lengthM.value)
    }

    @Test
    fun unknownFieldIsRejected() {
        val bad = Files.readString(negative.resolve("unknown-field-top-level.json"))
        assertThrows(JsonProcessingException::class.java) { mapper.readValue(bad, LiveMeasurement::class.java) }
    }

    @Test
    fun jsonDeeperThanEightIsRejected() {
        val bad = Files.readString(negative.resolve("depth-9.json"))
        assertThrows(JsonProcessingException::class.java) { mapper.readValue(bad, LiveMeasurement::class.java) }
    }

    @Test
    fun parserDifferentialsAndOverflowingNumbersAreRejected() {
        // S7/S8: duplicate keys, a second document, an overflowing number or an oversized string never reach the types
        for (id in listOf("duplicate-key-top-level", "duplicate-key-hides-a-bad-value", "trailing-content-second-document", "value-1e999-overflows-a-double",
            "yaw-1e999", "string-over-4096-characters")) {
            val bad = Files.readString(negative.resolve("$id.json"))
            assertThrows(java.io.IOException::class.java, { mapper.readValue(bad, LiveMeasurement::class.java) }, id)
        }
    }

    @Test
    fun geometryIsPartOfTheGeneratedTypes() {
        val m = mapper.readValue(Files.readString(examples.resolve("live-measurement-plane-distance.json")), LiveMeasurement::class.java)
        assertEquals(listOf(0.0, 1.0, 0.0), m.geometry.plane.normalWorld)
    }
}
