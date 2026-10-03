package com.atlas.measurement.tolerance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of reference/.../Tolerance.java. Normative text: tolerance/README.md.
// Thresholds come only from tolerance-profile.json (never constants in code).

/** One effective profile row. `sigmaK == null`: no sigma term; `sigmaCapM == null`: sigma term uncapped. */
data class ToleranceRow(
    val name: String,
    val floorM: Double,
    val relFrac: Double,
    val sigmaK: Double?,
    val sigmaCapM: Double?,
)

enum class Outcome { AGREE, MINOR_DIFF, MAJOR_DIFF }

/** One dimension of an associated pair. */
data class DimensionPair(val server: Double, val sigmaServer: Double, val client: Double, val sigmaClient: Double)

/** A parsed `tolerance-profile.json`. */
class ToleranceProfile private constructor(
    val version: String,
    private val rows: Map<String, ToleranceRow>,
    private val overrides: JsonNode,
) {
    /** Effective row: field-wise override of the base row for [algorithmMajor] (absent fields inherit). */
    fun row(name: String, algorithmMajor: Int): ToleranceRow {
        val base = rows[name] ?: throw IllegalArgumentException("unknown tolerance row: $name")
        val o = overrides.path(algorithmMajor.toString()).path(name)
        if (o.isMissingNode) return base
        return ToleranceRow(
            name,
            if (o.has("floorM")) o.get("floorM").asDouble() else base.floorM,
            if (o.has("relFrac")) o.get("relFrac").asDouble() else base.relFrac,
            if (o.has("sigmaK")) nullableDouble(o.get("sigmaK")) else base.sigmaK,
            if (o.has("sigmaCapM")) nullableDouble(o.get("sigmaCapM")) else base.sigmaCapM,
        )
    }

    companion object {
        private val mapper = ObjectMapper()

        fun load(file: Path): ToleranceProfile = parse(mapper.readTree(Files.readAllBytes(file)))

        /**
         * A released profile by version, read from the artifact's own resources (tolerance/<version>/tolerance-profile.json).
         * Every released version stays bundled, so vectors released against 1.0.0 keep replaying after a retune ships 1.1.0.
         */
        fun bundled(version: String): ToleranceProfile {
            require(Regex("\\d+\\.\\d+\\.\\d+").matches(version)) { "not a profile version: $version" }
            val stream = ToleranceProfile::class.java.getResourceAsStream("/tolerance/$version/tolerance-profile.json")
                ?: throw IllegalArgumentException("no bundled tolerance profile $version")
            val profile = stream.use { parse(mapper.readTree(it)) }
            check(profile.version == version) { "profile folder $version holds version ${profile.version}" }
            return profile
        }

        /** The versions bundled in this artifact, oldest first (from tolerance/manifest.json). */
        fun bundledVersions(): List<String> {
            val stream = ToleranceProfile::class.java.getResourceAsStream("/tolerance/manifest.json")
                ?: throw IllegalStateException("missing tolerance/manifest.json")
            return stream.use { mapper.readTree(it) }.required("toleranceProfileVersions").map { it.asText() }
        }

        fun parse(root: JsonNode): ToleranceProfile {
            val rows = LinkedHashMap<String, ToleranceRow>()
            for (r in root.required("rows")) {
                val row = ToleranceRow(
                    r.required("name").asText(),
                    r.required("floorM").asDouble(),
                    r.required("relFrac").asDouble(),
                    nullableDouble(r.required("sigmaK")),
                    nullableDouble(r.required("sigmaCapM")),
                )
                rows[row.name] = row
            }
            return ToleranceProfile(root.required("version").asText(), rows, root.required("overrides"))
        }

        private fun nullableDouble(n: JsonNode): Double? = if (n.isNull) null else n.asDouble()
    }
}

object Tolerance {
    /** Largest metre value the tolerance function accepts (the schema caps metrics at 9999.99999 m). */
    const val MAX_ABS_METRES: Double = 1.0e6

    /** `max(floorM, relFrac*|ref|, min(sigmaK*combinedSigma, sigmaCapM))`; sigma term omitted if `sigmaK` is null. */
    fun tol(ref: Double, sigmaServer: Double, sigmaClient: Double, row: ToleranceRow): Double {
        requireFinite(ref, "ref")
        require(abs(ref) <= MAX_ABS_METRES) { "ref must be at most $MAX_ABS_METRES in magnitude" }
        requireSigma(sigmaServer, "sigmaServer")
        requireSigma(sigmaClient, "sigmaClient")
        var t = max(row.floorM, row.relFrac * abs(ref))
        if (row.sigmaK != null) {
            var sigmaTerm = row.sigmaK * sqrt(sigmaServer * sigmaServer + sigmaClient * sigmaClient)
            if (row.sigmaCapM != null) sigmaTerm = min(sigmaTerm, row.sigmaCapM)
            t = max(t, sigmaTerm)
        }
        return t
    }

    /**
     * Integer units of 1e-5 m: `floor(x * 1e5 + 0.5)` in double arithmetic, identical in every port. Non-finite input
     * and |x| above [MAX_ABS_METRES] are errors, never a silent 0 or a saturated value.
     */
    fun units(metres: Double): Long {
        require(metres.isFinite() && abs(metres) <= MAX_ABS_METRES) { "metres must be finite and at most $MAX_ABS_METRES in magnitude" }
        return floor(metres * 1e5 + 0.5).toLong()
    }

    /** `diff <= tol` compared in metres rounded to 1e-5. */
    fun withinTolerance(diff: Double, tol: Double): Boolean = units(diff) <= units(tol)

    fun classify(profile: ToleranceProfile, algorithmMajor: Int, dims: List<DimensionPair>): Outcome {
        require(dims.isNotEmpty()) { "no dimensions to classify" }
        if (allWithin(profile.row("agree", algorithmMajor), dims)) return Outcome.AGREE
        if (allWithin(profile.row("minor", algorithmMajor), dims)) return Outcome.MINOR_DIFF
        return Outcome.MAJOR_DIFF
    }

    private fun allWithin(row: ToleranceRow, dims: List<DimensionPair>): Boolean =
        dims.all { d ->
            requireFinite(d.client, "client")
            withinTolerance(abs(d.client - d.server), tol(d.server, d.sigmaServer, d.sigmaClient, row))
        }

    private fun requireFinite(v: Double, name: String) = require(v.isFinite()) { "$name must be finite" }

    private fun requireSigma(v: Double, name: String) {
        requireFinite(v, name)
        require(v >= 0) { "$name must be >= 0" }
    }
}
