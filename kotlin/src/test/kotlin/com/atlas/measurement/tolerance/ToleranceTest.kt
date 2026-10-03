package com.atlas.measurement.tolerance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToleranceTest {
    private val toleranceDir = Paths.get("..", "tolerance")
    private val smoke: JsonNode = ObjectMapper().readTree(Files.readAllBytes(toleranceDir.resolve("smoke-cases.json")))
    private val boundaryDir = Paths.get("..", "vectors", "boundary", "1.0.0")
    private val boundary: JsonNode = ObjectMapper().readTree(Files.readAllBytes(boundaryDir.resolve("boundary-cases.json")))
    private val base = ToleranceProfile.load(toleranceDir.resolve("tolerance-profile.json"))
    private val over = ToleranceProfile.parse(smoke.get("overrideProfile"))

    @Test
    fun profileIsVersion100WithTheFourRows() {
        assertEquals("1.0.0", base.version)
        assertEquals(ToleranceRow("agree", 0.02, 0.02, 2.0, 0.10), base.row("agree", 1))
        assertEquals(ToleranceRow("accuracyGate", 0.01, 0.02, null, null), base.row("accuracyGate", 1))
        assertThrows(IllegalArgumentException::class.java) { base.row("nope", 1) }
    }

    @Test
    fun smokeTolCases() {
        val eps = smoke.get("tolEpsilon").asDouble()
        for (c in smoke.get("tol")) {
            val p = if (c.get("profile").asText() == "base") base else over
            val row = p.row(c.get("row").asText(), c.get("major").asInt())
            val t = Tolerance.tol(c.get("ref").asDouble(), c.get("sigmaServer").asDouble(), c.get("sigmaClient").asDouble(), row)
            assertTrue(abs(t - c.get("tol").asDouble()) <= eps, c.get("id").asText())
        }
    }

    @Test
    fun smokeWithinCases() {
        for (c in smoke.get("within")) {
            assertEquals(c.get("within").asBoolean(), Tolerance.withinTolerance(c.get("diff").asDouble(), c.get("tol").asDouble()), c.get("id").asText())
        }
    }

    @Test
    fun smokeClassifyCases() {
        for (c in smoke.get("classify")) {
            val dims = c.get("dims").map {
                DimensionPair(it.get("server").asDouble(), it.get("sigmaServer").asDouble(), it.get("client").asDouble(), it.get("sigmaClient").asDouble())
            }
            assertEquals(c.get("outcome").asText(), Tolerance.classify(base, c.get("major").asInt(), dims).name, c.get("id").asText())
        }
    }

    @Test
    fun invalidInputsAreErrors() {
        val row = base.row("agree", 1)
        assertThrows(IllegalArgumentException::class.java) { Tolerance.tol(1.0, -0.1, 0.0, row) }
        assertThrows(IllegalArgumentException::class.java) { Tolerance.tol(Double.NaN, 0.0, 0.0, row) }
        assertThrows(IllegalArgumentException::class.java) { Tolerance.tol(1.0, 0.0, Double.POSITIVE_INFINITY, row) }
    }

    @Test
    fun unitsRoundHalfUp() {
        assertEquals(2000L, Tolerance.units(0.020004))
        assertEquals(2001L, Tolerance.units(0.020006))
    }

    @Test
    fun boundaryTolCases() {
        val eps = boundary.get("tolEpsilon").asDouble()
        for (c in boundary.get("threshold")) {
            val row = base.row(c.get("row").asText(), c.get("major").asInt())
            val t = Tolerance.tol(c.get("ref").asDouble(), c.get("sigmaServer").asDouble(), c.get("sigmaClient").asDouble(), row)
            assertTrue(abs(t - c.get("tol").asDouble()) <= eps, c.get("id").asText())
            assertEquals(c.get("within").asBoolean(), Tolerance.withinTolerance(c.get("diff").asDouble(), t), c.get("id").asText())
        }
    }

    @Test
    fun boundaryWithinCases() {
        for (c in boundary.get("within")) {
            assertEquals(c.get("within").asBoolean(), Tolerance.withinTolerance(c.get("diff").asDouble(), c.get("tol").asDouble()), c.get("id").asText())
        }
    }

    @Test
    fun boundaryClassifyCases() {
        for (c in boundary.get("classify")) {
            val dims = c.get("dims").map {
                DimensionPair(it.get("server").asDouble(), it.get("sigmaServer").asDouble(), it.get("client").asDouble(), it.get("sigmaClient").asDouble())
            }
            assertEquals(c.get("outcome").asText(), Tolerance.classify(base, c.get("major").asInt(), dims).name, c.get("id").asText())
        }
    }

    /** A number, or the string NaN / Infinity / -Infinity (the boundary vectors' encoding of a non-finite value). */
    private fun number(n: JsonNode): Double = if (n.isTextual) n.asText().toDouble() else n.asDouble()

    @Test
    fun invalidInputVectorsAreErrors() {
        var count = 0
        for (c in boundary.get("invalid")) {
            val id = c.get("id").asText()
            assertEquals("ERROR", c.get("expect").asText(), id)
            count++
            when (c.get("op").asText()) {
                "classify" -> {
                    val dims = c.get("dims").map {
                        DimensionPair(number(it.get("server")), number(it.get("sigmaServer")), number(it.get("client")), number(it.get("sigmaClient")))
                    }
                    assertThrows(IllegalArgumentException::class.java, { Tolerance.classify(base, c.get("major").asInt(), dims) }, id)
                }
                "within" ->
                    assertThrows(IllegalArgumentException::class.java, { Tolerance.withinTolerance(number(c.get("diff")), number(c.get("tol"))) }, id)
                else -> {
                    val row = base.row(c.get("row").asText(), c.get("major").asInt())
                    assertThrows(IllegalArgumentException::class.java, {
                        Tolerance.tol(number(c.get("ref")), number(c.get("sigmaServer")), number(c.get("sigmaClient")), row)
                    }, id)
                }
            }
        }
        assertTrue(count >= 30)
    }

    @Test
    fun aNonFiniteClientValueIsNeverAgree() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300)) {
            assertThrows(IllegalArgumentException::class.java) { Tolerance.classify(base, 1, listOf(DimensionPair(0.6, 0.01, bad, 0.01))) }
            assertThrows(IllegalArgumentException::class.java) { Tolerance.units(bad) }
        }
    }
}
