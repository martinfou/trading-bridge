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
        this.whitelistPairs = List.copyOf(whitelistPairs);
        this.templates = Map.copyOf(templates);
    }

    public static TemplateRegistry loadDefault() throws IOException {
        try (InputStream in = TemplateRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            List<String> pairs = readStringList(root.get("whitelistPairs"));
            Map<String, TemplateEntry> map = new LinkedHashMap<>();
            JsonNode templatesNode = root.get("templates");
            templatesNode.fields().forEachRemaining(field -> {
                String id = field.getKey();
                JsonNode node = field.getValue();
                map.put(id, new TemplateEntry(
                    id,
                    node.get("name").asText(),
                    node.get("description").asText(),
                    CodegenHandler.valueOf(node.get("codegenHandler").asText()),
                    readStringList(node.get("requiredParams")),
                    readStringList(node.get("optionalParams")),
                    readStringList(node.get("allowedDirections"))
                ));
            });
            return new TemplateRegistry(pairs, map);
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
