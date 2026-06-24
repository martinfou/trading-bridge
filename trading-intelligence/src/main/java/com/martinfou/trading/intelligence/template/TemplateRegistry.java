package com.martinfou.trading.intelligence.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads {@code template-registry.json} — fixed T1–T8 catalogue (Epic 22.3). */
public final class TemplateRegistry {

    public enum CodegenHandler {
        THIN_PROP,
        DELEGATE_LORB,
        DELEGATE_GAP_FADE,
        NO_TRADE
    }

    public record TemplateEntry(
        String id,
        String name,
        String description,
        CodegenHandler codegenHandler,
        List<String> requiredParams,
        List<String> optionalParams,
        List<String> allowedDirections
    ) {}

    private static final String RESOURCE = "/template-registry.json";

    private final List<String> whitelistPairs;
    private final Map<String, TemplateEntry> templates;

    public TemplateRegistry(List<String> whitelistPairs, Map<String, TemplateEntry> templates) {
        if (whitelistPairs == null) {
            this.whitelistPairs = List.of();
        } else {
            List<String> cleanPairs = new java.util.ArrayList<>();
            for (String p : whitelistPairs) {
                if (p != null) {
                    cleanPairs.add(p);
                }
            }
            this.whitelistPairs = List.copyOf(cleanPairs);
        }

        if (templates == null) {
            this.templates = Map.of();
        } else {
            Map<String, TemplateEntry> cleanMap = new LinkedHashMap<>();
            for (Map.Entry<String, TemplateEntry> entry : templates.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    cleanMap.put(entry.getKey(), entry.getValue());
                }
            }
            this.templates = Map.copyOf(cleanMap);
        }
    }

    public static TemplateRegistry loadDefault() throws IOException {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + RESOURCE);
            }
            return load(in);
        }
    }

    public static TemplateRegistry load(InputStream in) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(in);
        if (root == null) {
            throw new IOException("Empty registry JSON");
        }
        List<String> pairs = readStringList(root.get("whitelistPairs"));
        Map<String, TemplateEntry> map = new LinkedHashMap<>();
        JsonNode templatesNode = root.get("templates");
        if (templatesNode == null || !templatesNode.isObject()) {
            throw new IOException("Missing templates node in registry");
        }
        var fields = templatesNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String id = field.getKey();
            JsonNode node = field.getValue();
            map.put(id, new TemplateEntry(
                id,
                node.path("name").asText(""),
                node.path("description").asText(""),
                parseCodegenHandler(node),
                readStringList(node.path("requiredParams")),
                readStringList(node.path("optionalParams")),
                readStringList(node.path("allowedDirections"))
            ));
        }
        return new TemplateRegistry(pairs, map);
    }

    private static CodegenHandler parseCodegenHandler(JsonNode node) {
        String val = node.path("codegenHandler").asText("NO_TRADE");
        try {
            return CodegenHandler.valueOf(val.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CodegenHandler.NO_TRADE;
        }
    }

    public List<String> whitelistPairs() {
        return whitelistPairs;
    }

    public Set<String> templateIds() {
        return templates.keySet();
    }

    public Optional<TemplateEntry> find(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    public TemplateEntry require(String templateId) {
        TemplateEntry entry = templates.get(templateId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown template: " + templateId);
        }
        return entry;
    }

    public RiskBudgetEnvelope defaultEnvelope() {
        return RiskBudgetEnvelope.defaults(whitelistPairs);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var list = new java.util.ArrayList<String>(node.size());
        node.forEach(item -> list.add(item.asText()));
        return List.copyOf(list);
    }
}
