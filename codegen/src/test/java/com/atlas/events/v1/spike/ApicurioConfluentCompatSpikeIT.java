package com.atlas.events.v1.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.events.v1.CaptureSource;
import com.atlas.events.v1.ConfirmingService;
import com.atlas.events.v1.FailureStage;
import com.atlas.events.v1.ModelDeleted;
import com.atlas.events.v1.ModelFailed;
import com.atlas.events.v1.ModelProcessed;
import com.atlas.events.v1.ModelPurged;
import com.atlas.events.v1.ModelUploaded;
import com.atlas.events.v1.ReasonCode;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * AT-2 spike (design section 26.2 gate (a), section 31.11): proves that the standard Confluent
 * Avro serializer/deserializer round-trips every v1 event through a real Kafka topic using
 * Apicurio's Confluent-compatible schema registry API, with <b>no code change</b> beyond the
 * {@code schema.registry.url} — the same client and configuration a consumer would use against a
 * real Confluent Schema Registry. Self-contained via Testcontainers (Brief 1's documented
 * alternative to depending on atlas-platform's compose {@code core} profile).
 *
 * <p>Verdict: see {@code README.md} ("Spike verdict") and {@code AT-2.md}'s "Spike verdict" note.
 */
@Testcontainers
class ApicurioConfluentCompatSpikeIT {

    private static final DockerImageName APICURIO_IMAGE =
            DockerImageName.parse("apicurio/apicurio-registry:3.3.3");

    private static KafkaContainer kafka;
    private static GenericContainer<?> apicurio;
    private static String schemaRegistryUrl;

    @BeforeAll
    static void startContainers() {
        kafka = new KafkaContainer("apache/kafka-native:3.8.0");
        kafka.start();

        // In-memory (H2) SQL storage: no Postgres needed for this self-contained spike
        // (atlas-platform's compose core profile backs Apicurio with Postgres instead; both
        // expose the same Confluent-compatible API at /apis/ccompat).
        apicurio = new GenericContainer<>(APICURIO_IMAGE)
                .withEnv("APICURIO_STORAGE_KIND", "sql")
                .withEnv("APICURIO_STORAGE_SQL_KIND", "h2")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        apicurio.start();

        schemaRegistryUrl = "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080) + "/apis/ccompat/v7";
    }

    @AfterAll
    static void stopContainers() {
        if (apicurio != null) {
            apicurio.stop();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void everyV1EventRoundTripsThroughApicurioConfluentCompatApi() throws Exception {
        assertRoundTrips("atlas.model.uploaded.v1", sampleModelUploaded());
        assertRoundTrips("atlas.model.processed.v1", sampleModelProcessed());
        assertRoundTrips("atlas.model.failed.v1", sampleModelFailed());
        assertRoundTrips("atlas.model.deleted.v1", sampleModelDeleted());
        assertRoundTrips("atlas.model.purged.v1", sampleModelPurged());
    }

    @SuppressWarnings("unchecked")
    private <T extends SpecificRecord> void assertRoundTrips(String topic, T event) throws Exception {
        produce(topic, event);
        T consumed = consumeOne(topic, (Class<T>) event.getClass());
        assertThat(consumed).isEqualTo(event);
    }

    private <T extends SpecificRecord> void produce(String topic, T event) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // The stock Confluent Avro serializer: zero custom code, only the registry URL differs
        // from a real Confluent Schema Registry deployment.
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, true);

        try (KafkaProducer<String, T> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to produce " + event.getClass().getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends SpecificRecord> T consumeOne(String topic, Class<T> type) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "at2-spike-" + topic);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        try (KafkaConsumer<String, T> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, T> records = consumer.poll(Duration.ofSeconds(2));
                if (!records.isEmpty()) {
                    return records.iterator().next().value();
                }
            }
            throw new IllegalStateException("No record consumed from " + topic + " within 30s");
        }
    }

    private ModelUploaded sampleModelUploaded() {
        return ModelUploaded.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setStorageKey("t/default/o/owner-1/m/model-1/raw/model.usdz")
                .setStorageVersionId("v1")
                .setSizeBytes(2048L)
                .setSha256("b".repeat(64))
                .setDetectedFormat("USDZ")
                .setClaimedCaptureSource(CaptureSource.IPHONE_LIDAR)
                .build();
    }

    private ModelProcessed sampleModelProcessed() {
        UUID modelId = UUID.randomUUID();
        return ModelProcessed.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(modelId)
                .setOwnerId("owner-1")
                .setCanonicalStorageKey("t/default/o/owner-1/m/" + modelId + "/canonical/model.glb")
                .setGeometryDocumentId(modelId + ":1")
                .setSpatialArtifactKey("t/default/o/owner-1/m/" + modelId + "/derived/model.bvh")
                .setScaleToMetres(1.0)
                .setScaleSource("DECLARED_METERS_PER_UNIT")
                .setScaleConfidence(0.9)
                .setNeedsConfirmation(false)
                .setAabbSizeXMetres(1.0)
                .setAabbSizeYMetres(1.0)
                .setAabbSizeZMetres(1.0)
                .setPipelineVersion("1")
                .setDurationMs(1000L)
                .setEvidence(List.of("ARKIT_METADATA"))
                .build();
    }

    private ModelFailed sampleModelFailed() {
        return ModelFailed.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setStage(FailureStage.INGESTION)
                .setReasonCode(ReasonCode.MALWARE_DETECTED)
                .setRetryable(false)
                .setMessage("The uploaded file failed a malware scan.")
                .build();
    }

    private ModelDeleted sampleModelDeleted() {
        return ModelDeleted.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .build();
    }

    private ModelPurged sampleModelPurged() {
        return ModelPurged.newBuilder()
                .setEventId(UUID.randomUUID())
                .setOccurredAt(Instant.now())
                .setModelId(UUID.randomUUID())
                .setOwnerId("owner-1")
                .setConfirmingService(ConfirmingService.INGESTION)
                .build();
    }
}
