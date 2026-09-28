package com.atlas.events.v1.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/**
 * Shared in-memory serialize/deserialize helper for the per-schema unit tests. Deliberately not
 * shipped in the published jar (test scope only): no domain logic belongs in this artifact.
 */
public final class AvroRoundTrip {

    private AvroRoundTrip() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends SpecificRecord> T roundTrip(T record) throws IOException {
        SpecificDatumWriter<T> writer = new SpecificDatumWriter<>((Class<T>) record.getClass());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(record, encoder);
        encoder.flush();

        SpecificDatumReader<T> reader = new SpecificDatumReader<>((Class<T>) record.getClass());
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
        return reader.read(null, decoder);
    }
}
