package com.atlas.schemagate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Proves the AT-2 gate actually fails, not just that it is present (Brief 1's own requirement):
 * one red test for a backward-incompatible schema change, one red test for an unapproved enum
 * change, plus the green paths (compatible change; enum change with an approval entry).
 */
class SchemaCompatibilityGateTest {

    @Test
    void compatibleChangePasses() throws IOException, URISyntaxException {
        SchemaCompatibilityGate.GateResult result = SchemaCompatibilityGate.run(
                fixture("baseline"), fixture("compatible"), null);

        assertThat(result.passed()).isTrue();
        assertThat(result.checkedSchemaCount()).isEqualTo(1);
    }

    @Test
    void backwardIncompatibleChangeFailsTheGate() throws IOException, URISyntaxException {
        SchemaCompatibilityGate.GateResult result = SchemaCompatibilityGate.run(
                fixture("baseline"), fixture("incompatible"), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("backward-incompatible"));
    }

    @Test
    void unapprovedEnumSymbolChangeFailsTheGate() throws IOException, URISyntaxException {
        SchemaCompatibilityGate.GateResult result = SchemaCompatibilityGate.run(
                fixture("enum-change-unapproved/baseline"), fixture("enum-change-unapproved/current"), null);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("without an approval entry"));
    }

    @Test
    void approvedEnumSymbolChangePasses() throws IOException, URISyntaxException {
        SchemaCompatibilityGate.GateResult result = SchemaCompatibilityGate.run(
                fixture("enum-change-approved/baseline"),
                fixture("enum-change-approved/current"),
                fixture("enum-change-approved/enum-approvals.txt"));

        assertThat(result.passed()).isTrue();
    }

    private static Path fixture(String relative) throws URISyntaxException {
        Path fixturesRoot = Paths.get(
                SchemaCompatibilityGateTest.class.getResource("/fixtures").toURI());
        return fixturesRoot.resolve(relative);
    }
}
