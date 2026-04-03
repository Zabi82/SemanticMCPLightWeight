package com.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TpchKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(TpchKafkaProducer.class);

    @Value("${trino.host}") private String trinoHost;
    @Value("${trino.port}") private int trinoPort;
    @Value("${trino.catalog}") private String catalog;
    @Value("${trino.schema}") private String schema;
    @Value("${producer.batch.size:10}") private int batchSize;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    // In-memory dataset loaded once from Trino
    private final List<Map<String, Object>> allOrders = new ArrayList<>();
    private final Map<Long, List<Map<String, Object>>> lineitemsByOrder = new LinkedHashMap<>();

    // Streaming cursor — cycles back to 0 when exhausted
    private final AtomicInteger cursor = new AtomicInteger(0);
    // Monotonically increasing key offset to avoid duplicate IDs on wrap-around
    private final AtomicLong keyOffset = new AtomicLong(0);
    private final AtomicBoolean referenceDataPublished = new AtomicBoolean(false);
    private final AtomicBoolean dataLoaded = new AtomicBoolean(false);

    // Reference tables streamed once — kept for potential manual use
    private static final List<String> REFERENCE_TABLES = List.of("customer", "supplier", "nation", "region", "part");

    public TpchKafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void init() {
        log.info("Loading TPC-H data from Trino {}:{}", trinoHost, trinoPort);
        try {
            loadOrdersAndLineitems();
            dataLoaded.set(true);
            log.info("Loaded {} orders with their lineitems", allOrders.size());
        } catch (Exception e) {
            log.error("Failed to load TPC-H data: {}", e.getMessage(), e);
        }
    }

    // Reference tables are pre-populated in streaming_db via init SQL (Trino CTAS).
    // The producer only streams orders and lineitems continuously.
    // This method is kept as a no-op placeholder in case manual re-seeding is needed.
    @Scheduled(initialDelay = 15_000, fixedDelay = Long.MAX_VALUE)
    public void publishReferenceDataOnce() {
        if (!dataLoaded.get() || referenceDataPublished.get()) return;
        log.info("Reference tables (customer, supplier, nation, region, part, partsupp) are pre-loaded " +
                 "into streaming_db via Trino init SQL. No Kafka publishing needed for reference data.");
        referenceDataPublished.set(true);
    }

    // ── Orders + lineitems: continuous streaming ──────────────────────────────

    @Scheduled(fixedDelayString = "${producer.interval.ms:5000}", initialDelay = 20_000)
    public void publishNextBatch() {
        if (!dataLoaded.get() || allOrders.isEmpty()) return;

        int start = cursor.get();
        int end = Math.min(start + batchSize, allOrders.size());

        for (int i = start; i < end; i++) {
            Map<String, Object> order = new LinkedHashMap<>(allOrders.get(i));
            long originalKey = toLong(order.get("orderkey"));

            // Generate a new unique key by adding an offset based on how many full cycles we've done
            long newKey = originalKey + keyOffset.get();
            order.put("orderkey", newKey);

            // Rewrite dates to today
            LocalDate today = LocalDate.now();
            order.put("orderdate", today.toString());

            publishRecord("tpch.orders", String.valueOf(newKey), order);

            // Publish associated lineitems with matching new orderkey
            List<Map<String, Object>> items = lineitemsByOrder.getOrDefault(originalKey, List.of());
            for (Map<String, Object> item : items) {
                Map<String, Object> lineitem = new LinkedHashMap<>(item);
                lineitem.put("orderkey", newKey);

                // Shift dates relative to today
                int shipOffset = 1 + (int)(Math.random() * 10);
                int commitOffset = 7 + (int)(Math.random() * 7);
                int receiptOffset = shipOffset + 1 + (int)(Math.random() * 3);
                lineitem.put("shipdate", today.plusDays(shipOffset).toString());
                lineitem.put("commitdate", today.plusDays(commitOffset).toString());
                lineitem.put("receiptdate", today.plusDays(receiptOffset).toString());

                String lineitemKey = newKey + "-" + lineitem.get("linenumber");
                publishRecord("tpch.lineitems", lineitemKey, lineitem);
            }
        }

        log.info("Published batch [{}-{}] of {} orders", start, end - 1, allOrders.size());

        if (end >= allOrders.size()) {
            // Wrap around — bump key offset by max orderkey to ensure uniqueness
            long maxKey = allOrders.stream()
                .mapToLong(o -> toLong(o.get("orderkey")))
                .max().orElse(100_000L);
            keyOffset.addAndGet(maxKey + 1);
            cursor.set(0);
            log.info("Wrapped around. New key offset: {}", keyOffset.get());
        } else {
            cursor.set(end);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void loadOrdersAndLineitems() throws Exception {
        // Load orders
        String orderSql = "SELECT orderkey, custkey, orderstatus, totalprice, orderdate, orderpriority, clerk, shippriority, comment FROM "
                + catalog + "." + schema + ".orders ORDER BY orderkey";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(orderSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) cols.add(meta.getColumnName(i));
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols.size(); i++) row.put(cols.get(i - 1), rs.getObject(i));
                allOrders.add(row);
            }
        }

        // Load lineitems indexed by orderkey
        String lineitemSql = "SELECT orderkey, partkey, suppkey, linenumber, quantity, extendedprice, discount, tax, "
                + "returnflag, linestatus, shipdate, commitdate, receiptdate, shipinstruct, shipmode, comment FROM "
                + catalog + "." + schema + ".lineitem ORDER BY orderkey, linenumber";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(lineitemSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) cols.add(meta.getColumnName(i));
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols.size(); i++) row.put(cols.get(i - 1), rs.getObject(i));
                long orderKey = toLong(row.get("orderkey"));
                lineitemsByOrder.computeIfAbsent(orderKey, k -> new ArrayList<>()).add(row);
            }
        }
    }

    private void publishRecord(String topic, String key, Map<String, Object> record) {
        try {
            String json = mapper.writeValueAsString(record);
            kafkaTemplate.send(topic, key, json);
        } catch (Exception e) {
            log.error("Failed to publish to {}: {}", topic, e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "admin");
        return DriverManager.getConnection(
            String.format("jdbc:trino://%s:%d/%s/%s", trinoHost, trinoPort, catalog, schema), props);
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }
}
