package com.glossary.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GlossaryToolService {

    private static final Logger logger = LoggerFactory.getLogger(GlossaryToolService.class);

    @Value("${glossary.file.path:glossary/tpch-glossary.yml}")
    private String glossaryFilePath;

    private Map<String, Object> glossary;

    @PostConstruct
    public void loadGlossary() {
        try {
            Yaml yaml = new Yaml();
            InputStream is;
            if (Files.exists(Paths.get(glossaryFilePath))) {
                is = Files.newInputStream(Paths.get(glossaryFilePath));
            } else {
                is = getClass().getClassLoader().getResourceAsStream("tpch-glossary.yml");
            }
            if (is == null) throw new RuntimeException("Glossary file not found: " + glossaryFilePath);
            glossary = yaml.load(is);
            logger.info("Loaded business glossary from {}", glossaryFilePath);
        } catch (Exception e) {
            logger.error("Failed to load glossary: {}", e.getMessage());
            glossary = new HashMap<>();
        }
    }

    @Tool(name = "list_entities",
          description = "List all business entities (tables) in the data catalog with their descriptions. " +
                        "Call this first to discover what data is available before writing any SQL.")
    public Object listEntities() {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities = (List<Map<String, Object>>) glossary.get("entities");
            if (entities == null) return Map.of("error", "No entities found in glossary");

            List<Map<String, Object>> result = entities.stream()
                .map(e -> Map.<String, Object>of(
                    "name", e.get("name"),
                    "table", e.get("table"),
                    "description", e.getOrDefault("description", "")
                ))
                .collect(Collectors.toList());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> schemas = (List<Map<String, Object>>) glossary.get("schemas");

            return Map.of(
                "entities", result,
                "schemas", schemas != null ? schemas : List.of(),
                "hint", "Use get_entity_context(name) to get column details and business rules for any entity."
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "get_entity_context",
          description = "Get full business context for a specific entity: column descriptions, allowed values, " +
                        "business rules, and join paths. Use this before writing SQL involving that entity. " +
                        "Example: get_entity_context('orders') before querying order completion rate.")
    public Object getEntityContext(String entityName) {
        if (entityName == null || entityName.isBlank())
            return Map.of("error", "entityName is required");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities = (List<Map<String, Object>>) glossary.get("entities");
            if (entities == null) return Map.of("error", "No entities in glossary");

            Optional<Map<String, Object>> match = entities.stream()
                .filter(e -> entityName.equalsIgnoreCase((String) e.get("name"))
                          || entityName.equalsIgnoreCase((String) e.get("table")))
                .findFirst();

            if (match.isEmpty())
                return Map.of("error", "Entity not found: " + entityName,
                              "available", entities.stream().map(e -> e.get("name")).collect(Collectors.toList()));

            Map<String, Object> entity = match.get();

            // Also include relevant metrics and join paths
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allMetrics = (List<Map<String, Object>>) glossary.get("metrics");
            List<Map<String, Object>> relevantMetrics = allMetrics == null ? List.of() :
                allMetrics.stream()
                    .filter(m -> entityName.equalsIgnoreCase((String) m.get("table")))
                    .collect(Collectors.toList());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> joinPaths = (List<Map<String, Object>>) glossary.get("join_paths");
            List<Map<String, Object>> relevantJoins = joinPaths == null ? List.of() :
                joinPaths.stream()
                    .filter(j -> ((String) j.get("path")).toLowerCase().contains(entityName.toLowerCase()))
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>(entity);
            if (!relevantMetrics.isEmpty()) result.put("metrics", relevantMetrics);
            if (!relevantJoins.isEmpty()) result.put("join_paths", relevantJoins);

            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "search_glossary",
          description = "Search the business glossary for a term, concept, or column name. " +
                        "Use this when you encounter an unfamiliar term or need to understand a business concept. " +
                        "Examples: search_glossary('on-time'), search_glossary('pending'), search_glossary('high priority')")
    public Object searchGlossary(String term) {
        if (term == null || term.isBlank())
            return Map.of("error", "Search term is required");
        try {
            String lowerTerm = term.toLowerCase();
            List<Map<String, Object>> matches = new ArrayList<>();

            // Search entities and their columns
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entities = (List<Map<String, Object>>) glossary.get("entities");
            if (entities != null) {
                for (Map<String, Object> entity : entities) {
                    String entityName = (String) entity.get("name");
                    String entityDesc = String.valueOf(entity.getOrDefault("description", "")).toLowerCase();
                    String entityNote = String.valueOf(entity.getOrDefault("business_note", "")).toLowerCase();

                    if (entityName.toLowerCase().contains(lowerTerm) || entityDesc.contains(lowerTerm) || entityNote.contains(lowerTerm)) {
                        matches.add(Map.of("type", "entity", "name", entityName,
                                           "description", entity.getOrDefault("description", ""),
                                           "business_note", entity.getOrDefault("business_note", "")));
                    }

                    // Search columns
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> columns = (List<Map<String, Object>>) entity.get("columns");
                    if (columns != null) {
                        for (Map<String, Object> col : columns) {
                            String colName = String.valueOf(col.getOrDefault("name", "")).toLowerCase();
                            String colDesc = String.valueOf(col.getOrDefault("description", "")).toLowerCase();
                            String colNote = String.valueOf(col.getOrDefault("business_note", "")).toLowerCase();
                            String valuesStr = col.containsKey("values") ? col.get("values").toString().toLowerCase() : "";

                            if (colName.contains(lowerTerm) || colDesc.contains(lowerTerm)
                                    || colNote.contains(lowerTerm) || valuesStr.contains(lowerTerm)) {
                                Map<String, Object> colMatch = new LinkedHashMap<>();
                                colMatch.put("type", "column");
                                colMatch.put("entity", entityName);
                                colMatch.put("column", col.get("name"));
                                colMatch.put("description", col.getOrDefault("description", ""));
                                if (col.containsKey("values")) colMatch.put("values", col.get("values"));
                                if (col.containsKey("business_note")) colMatch.put("business_note", col.get("business_note"));
                                matches.add(colMatch);
                            }
                        }
                    }
                }
            }

            // Search metrics
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metrics = (List<Map<String, Object>>) glossary.get("metrics");
            if (metrics != null) {
                for (Map<String, Object> metric : metrics) {
                    String mName = String.valueOf(metric.getOrDefault("name", "")).toLowerCase();
                    String mDesc = String.valueOf(metric.getOrDefault("description", "")).toLowerCase();
                    String mNote = String.valueOf(metric.getOrDefault("business_note", "")).toLowerCase();
                    if (mName.contains(lowerTerm) || mDesc.contains(lowerTerm) || mNote.contains(lowerTerm)) {
                        matches.add(Map.of("type", "metric", "name", metric.get("name"),
                                           "description", metric.getOrDefault("description", ""),
                                           "formula", metric.getOrDefault("formula", ""),
                                           "business_note", metric.getOrDefault("business_note", "")));
                    }
                }
            }

            if (matches.isEmpty())
                return Map.of("matches", List.of(), "hint", "No results for '" + term + "'. Try list_entities() to see all available entities.");

            return Map.of("term", term, "matches", matches);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "get_metric_definition",
          description = "Get the SQL formula and business definition for a specific metric. " +
                        "Use this to understand how to calculate a business metric correctly before writing SQL. " +
                        "Example: get_metric_definition('order_completion_rate')")
    public Object getMetricDefinition(String metricName) {
        if (metricName == null || metricName.isBlank())
            return Map.of("error", "metricName is required");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metrics = (List<Map<String, Object>>) glossary.get("metrics");
            if (metrics == null) return Map.of("error", "No metrics in glossary");

            Optional<Map<String, Object>> match = metrics.stream()
                .filter(m -> metricName.equalsIgnoreCase((String) m.get("name")))
                .findFirst();

            if (match.isEmpty())
                return Map.of("error", "Metric not found: " + metricName,
                              "available", metrics.stream().map(m -> m.get("name")).collect(Collectors.toList()));

            return match.get();
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
