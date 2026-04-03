package com.mcp.datalakehouse.tool;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KafkaToolService {

    private static final Logger log = LoggerFactory.getLogger(KafkaToolService.class);

    @Value("${kafka.bootstrap.servers:localhost:9092}")
    private String bootstrapServers;

    private Properties adminProps() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        return p;
    }

    private Properties consumerProps(String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        return p;
    }

    @Tool(name = "list_kafka_topics",
          description = "List all Kafka topics in the cluster. Shows topic names and partition counts. " +
                        "Use this to discover what streaming data is available.")
    public Object listKafkaTopics() {
        try (AdminClient admin = AdminClient.create(adminProps())) {
            ListTopicsResult result = admin.listTopics(new ListTopicsOptions().listInternal(false));
            Map<String, TopicDescription> descriptions = admin.describeTopics(result.names().get()).allTopicNames().get();

            List<Map<String, Object>> topics = descriptions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("topic", e.getKey());
                    t.put("partitions", e.getValue().partitions().size());
                    return t;
                })
                .collect(Collectors.toList());

            return Map.of("topics", topics, "count", topics.size());
        } catch (Exception e) {
            log.error("Error listing Kafka topics: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "get_kafka_topic_stats",
          description = "Get statistics for a Kafka topic: total message count (end offset), " +
                        "partition details, and approximate messages per second if available. " +
                        "Useful to see how many records have been streamed so far.")
    public Object getKafkaTopicStats(String topic) {
        if (topic == null || topic.isBlank()) return Map.of("error", "topic is required");
        try (AdminClient admin = AdminClient.create(adminProps())) {
            // Get partition info
            Map<String, TopicDescription> desc = admin.describeTopics(List.of(topic)).allTopicNames().get();
            if (!desc.containsKey(topic)) return Map.of("error", "Topic not found: " + topic);

            TopicDescription topicDesc = desc.get(topic);
            List<TopicPartition> partitions = topicDesc.partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .collect(Collectors.toList());

            // Get end offsets (= total messages produced)
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps("mcp-stats-" + System.currentTimeMillis()))) {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
                Map<TopicPartition, Long> beginOffsets = consumer.beginningOffsets(partitions);

                long totalMessages = endOffsets.values().stream().mapToLong(Long::longValue).sum();
                long availableMessages = 0;
                for (TopicPartition tp : partitions) {
                    availableMessages += endOffsets.getOrDefault(tp, 0L) - beginOffsets.getOrDefault(tp, 0L);
                }

                List<Map<String, Object>> partitionStats = partitions.stream()
                    .map(tp -> {
                        Map<String, Object> ps = new LinkedHashMap<>();
                        ps.put("partition", tp.partition());
                        ps.put("begin_offset", beginOffsets.getOrDefault(tp, 0L));
                        ps.put("end_offset", endOffsets.getOrDefault(tp, 0L));
                        ps.put("message_count", endOffsets.getOrDefault(tp, 0L) - beginOffsets.getOrDefault(tp, 0L));
                        return ps;
                    })
                    .collect(Collectors.toList());

                return Map.of(
                    "topic", topic,
                    "total_messages_produced", totalMessages,
                    "available_messages", availableMessages,
                    "partition_count", partitions.size(),
                    "partitions", partitionStats
                );
            }
        } catch (Exception e) {
            log.error("Error getting topic stats for {}: {}", topic, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "get_recent_kafka_messages",
          description = "Peek at the most recent messages from a Kafka topic. Returns up to 'limit' messages " +
                        "from the end of the topic. Use this to inspect what raw streaming data looks like " +
                        "before it lands in Iceberg. Default limit is 5, max is 20.")
    public Object getRecentKafkaMessages(String topic, Integer limit) {
        if (topic == null || topic.isBlank()) return Map.of("error", "topic is required");
        int maxMessages = Math.min(limit != null ? limit : 5, 20);

        try (AdminClient admin = AdminClient.create(adminProps())) {
            Map<String, TopicDescription> desc = admin.describeTopics(List.of(topic)).allTopicNames().get();
            if (!desc.containsKey(topic)) return Map.of("error", "Topic not found: " + topic);

            List<TopicPartition> partitions = desc.get(topic).partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .collect(Collectors.toList());

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps("mcp-peek-" + System.currentTimeMillis()))) {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

                // Seek to (end - maxMessages) for each partition
                Map<TopicPartition, Long> seekOffsets = new HashMap<>();
                for (TopicPartition tp : partitions) {
                    long end = endOffsets.getOrDefault(tp, 0L);
                    seekOffsets.put(tp, Math.max(0, end - maxMessages));
                }

                consumer.assign(partitions);
                seekOffsets.forEach(consumer::seek);

                List<Map<String, Object>> messages = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 5000;

                while (messages.size() < maxMessages && System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> record : records) {
                        if (messages.size() >= maxMessages) break;
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("partition", record.partition());
                        msg.put("offset", record.offset());
                        msg.put("key", record.key());
                        msg.put("value", record.value());
                        msg.put("timestamp", record.timestamp());
                        messages.add(msg);
                    }
                    if (records.isEmpty()) break;
                }

                // Return most recent first
                Collections.reverse(messages);
                return Map.of("topic", topic, "messages", messages, "count", messages.size());
            }
        } catch (Exception e) {
            log.error("Error reading messages from {}: {}", topic, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "get_kafka_consumer_lag",
          description = "Get consumer lag for all consumer groups on a topic. " +
                        "Shows how far behind each consumer group is from the latest messages. " +
                        "Useful to verify the Iceberg sink connector is keeping up with the stream.")
    public Object getKafkaConsumerLag(String topic) {
        if (topic == null || topic.isBlank()) return Map.of("error", "topic is required");
        try (AdminClient admin = AdminClient.create(adminProps())) {
            // List all consumer groups
            List<String> groupIds = admin.listConsumerGroups().all().get().stream()
                .map(ConsumerGroupListing::groupId)
                .collect(Collectors.toList());

            if (groupIds.isEmpty()) return Map.of("topic", topic, "consumer_groups", List.of(),
                                                   "note", "No consumer groups found");

            List<Map<String, Object>> groupStats = new ArrayList<>();
            for (String groupId : groupIds) {
                try {
                    Map<TopicPartition, OffsetAndMetadata> committed = admin
                        .listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata().get();

                    // Filter to requested topic
                    Map<TopicPartition, OffsetAndMetadata> topicOffsets = committed.entrySet().stream()
                        .filter(e -> e.getKey().topic().equals(topic))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                    if (topicOffsets.isEmpty()) continue;

                    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps("mcp-lag-check"))) {
                        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicOffsets.keySet());
                        long totalLag = 0;
                        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : topicOffsets.entrySet()) {
                            long end = endOffsets.getOrDefault(entry.getKey(), 0L);
                            long committed2 = entry.getValue().offset();
                            totalLag += Math.max(0, end - committed2);
                        }
                        groupStats.add(Map.of("group_id", groupId, "total_lag", totalLag));
                    }
                } catch (Exception ignored) { /* skip groups with no access */ }
            }

            return Map.of("topic", topic, "consumer_groups", groupStats);
        } catch (Exception e) {
            log.error("Error getting consumer lag for {}: {}", topic, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
