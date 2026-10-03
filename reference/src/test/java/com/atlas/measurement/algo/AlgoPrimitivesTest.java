package com.atlas.measurement.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.Test;

class AlgoPrimitivesTest {
    @Test
    void splitMix64MatchesPublishedReferenceOutputsForSeedZero() {
        SplitMix64 g = new SplitMix64(0L);
        assertThat(Long.toHexString(g.nextLong())).isEqualTo("e220a8397b1dcdaf");
        assertThat(Long.toHexString(g.nextLong())).isEqualTo("6e789e6aa1b965f4");
        assertThat(Long.toHexString(g.nextLong())).isEqualTo("6c45d188009454f");
    }

    @Test
    void windowSeedXorsTheGoldenConstant() {
        assertThat(Long.toHexString(SplitMix64.forWindow(0).nextLong()))
                .isEqualTo(Long.toHexString(new SplitMix64(0x9E3779B97F4A7C15L).nextLong()));
    }

    @Test
    void boundedIntIsInRangeAndUsesRejectionThreshold() {
        SplitMix64 g = SplitMix64.forWindow(7);
        for (int i = 0; i < 1000; i++) {
            assertThat(g.nextInt(7)).isBetween(0, 6);
        }
        assertThat(Long.remainderUnsigned(-3L, 3L)).isEqualTo(1L); // 2^64 mod 3
    }

    @Test
    void lowerMedianBreaksTiesByIndex() {
        assertThat(Median.lowerMedianPosition(new double[] {3, 1, 2, 1})).isEqualTo(3);
        assertThat(Median.lowerMedianPosition(new double[] {9, 8, 7, 6, 5, 4})).isEqualTo(3);
        assertThat(Median.lowerMedian(new double[] {5})).isEqualTo(5);
    }

    @Test
    void kahanBeatsNaiveSummation() {
        double[] v = {1e16, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1e16};
        double naive = 0;
        Kahan k = new Kahan();
        for (double x : v) {
            naive += x;
            k.add(x);
        }
        assertThat(Math.abs(k.sum() - 10)).isLessThan(Math.abs(naive - 10));
    }

    @Test
    void jacobiReconstructsTheMatrixAndOrdersEigenvalues() {
        double[][] a = {{4, 1, 0.5}, {1, 3, 0.2}, {0.5, 0.2, 2}};
        Jacobi3.Result r = Jacobi3.solve(a);
        assertThat(r.values[0]).isGreaterThanOrEqualTo(r.values[1]);
        assertThat(r.values[1]).isGreaterThanOrEqualTo(r.values[2]);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += r.values[k] * r.vectors[k][i] * r.vectors[k][j];
                }
                assertThat(s).isCloseTo(a[i][j], offset(1e-10));
            }
        }
        for (double[] e : r.vectors) {
            int big = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(e[i]) > Math.abs(e[big])) {
                    big = i;
                }
            }
            assertThat(e[big]).isPositive();
        }
    }

    @Test
    void jacobiTiesKeepIndexOrderAndZeroMatrixIsStable() {
        Jacobi3.Result r = Jacobi3.solve(new double[][] {{2, 0, 0}, {0, 2, 0}, {0, 0, 1}});
        assertThat(r.values).containsExactly(2, 2, 1);
        assertThat(r.vectors[0]).containsExactly(1, 0, 0);
        assertThat(r.vectors[1]).containsExactly(0, 1, 0);
        assertThat(Jacobi3.solve(new double[3][3]).sweeps).isZero();
    }
}
