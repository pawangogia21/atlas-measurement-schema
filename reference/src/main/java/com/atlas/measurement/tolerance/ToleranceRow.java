package com.atlas.measurement.tolerance;

/** One effective profile row. {@code sigmaK == null}: no sigma term; {@code sigmaCapM == null}: sigma term uncapped. */
public final class ToleranceRow {
    public final String name;
    public final double floorM;
    public final double relFrac;
    public final Double sigmaK;
    public final Double sigmaCapM;

    public ToleranceRow(String name, double floorM, double relFrac, Double sigmaK, Double sigmaCapM) {
        this.name = name;
        this.floorM = floorM;
        this.relFrac = relFrac;
        this.sigmaK = sigmaK;
        this.sigmaCapM = sigmaCapM;
    }
}
