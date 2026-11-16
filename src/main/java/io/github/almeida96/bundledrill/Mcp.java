package io.github.almeida96.bundledrill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** A stdio MCP session bound to one report loaded by the operator. */
public final class Mcp {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String LATEST = "2025-11-25";
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int PAGE_LIMIT = 100;
    private static final List<String> TOOLS = List.of("restore_summary", "list_restore_points", "list_bundles", "bundle_prerequisites");
    private final JsonNode report;
    private final ArrayNode inventoryRows = JSON.createArrayNode();
    private boolean negotiated;
    private boolean ready;

    private Mcp(JsonNode report) {
        if (report.path("formatVersion").asInt() != 1 || !Set.of("PASS", "FAIL", "DEGRADED").contains(report.path("status").asText())
                || !"PASS".equals(report.path("fsck").asText()) || !report.path("points").isArray() || !report.path("bundles").isArray())
            throw invalid("Expected a BundleDrill version 1 report");
        for (JsonNode row : report.get("bundles")) {
            if (!row.path("prerequisites").isArray() || !row.path("missing").isArray()) throw invalid("Invalid bundle evidence");
            ObjectNode compact = inventoryRows.addObject();
            copyFields(row, compact, "index", "name", "sha256", "bytes", "state", "appliedOrder");
            compact.put("missingCount", row.get("missing").size());
        }
        this.report = report;
    }

    public static void serve(JsonNode report, InputStream input, OutputStream output) throws IOException {
        var session = new Mcp(report);
        var stream = new BufferedInputStream(input);
        byte[] frame = new byte[MAX_FRAME];
        int used = 0;
        boolean oversized = false;
        int next;
        while ((next = stream.read()) != -1) {
            if (next == '\n') {
                session.respond(frame, used, oversized, output);
                used = 0;
                oversized = false;
            } else if (used < MAX_FRAME) {
                frame[used++] = (byte) next;
            } else {
                oversized = true;
            }
        }
        if (used != 0 || oversized) session.respond(frame, used, oversized, output);
    }

    private void respond(byte[] frame, int used, boolean oversized, OutputStream output) throws IOException {
        ObjectNode reply;
        if (oversized) {
            reply = error(null, -32700, "Message exceeds 1 MiB");
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frame, 0, used)).toString();
                JsonNode request = JSON.readTree(text);
                reply = request == null || request.isMissingNode()
                        ? error(null, -32700, "Empty JSON message") : handle(request);
            } catch (JsonProcessingException | CharacterCodingException invalid) {
                reply = error(null, -32700, "Invalid UTF-8 JSON message");
            }
        }
        if (reply == null) return;
        byte[] bytes = JSON.writeValueAsBytes(reply);
        if (bytes.length > MAX_FRAME) {
            bytes = JSON.writeValueAsBytes(error(reply.get("id"), -32603, "Result exceeds 1 MiB. Request a smaller page."));
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    private ObjectNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").textValue())
                || !request.path("method").isTextual()
                || (id != null && !(id.isIntegralNumber() || id.isTextual()))
                || (id != null && id.toString().length() > 1024)) {
            return error(null, -32600, "Invalid JSON-RPC request");
        }
        String method = request.path("method").textValue();
        if (id == null) {
            if ("notifications/initialized".equals(method) && negotiated) ready = true;
            return null;
        }
        JsonNode params = request.has("params") ? request.get("params") : JSON.createObjectNode();
        if (!params.isObject()) return error(id, -32602, "params must be an object");
        try {
            if ("ping".equals(method)) return success(id, JSON.createObjectNode());
            if ("initialize".equals(method)) {
                if (negotiated) return error(id, -32600, "Session already initialized");
                String version = string(params, "protocolVersion");
                if (!params.path("capabilities").isObject()) throw invalid("Missing client capabilities");
                string(params.path("clientInfo"), "name");
                string(params.path("clientInfo"), "version");
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-06-18".equals(version) ? version : LATEST);
                result.putObject("serverInfo").put("name", "bundledrill").put("version", "0.1.0");
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                negotiated = true;
                return success(id, result);
            }
            if (!ready) return error(id, -32600, "Complete initialization first");
            if ("tools/list".equals(method)) {
                fields(params, Set.of("_meta"));
                return success(id, inventory());
            }
            if (!"tools/call".equals(method)) return error(id, -32601, "Method not found");
            fields(params, Set.of("name", "arguments", "_meta"));
            JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
            if (!args.isObject()) throw invalid("arguments must be an object");
            String name = string(params, "name");
            if (!TOOLS.contains(name)) return error(id, -32602, "Unknown tool");
            ObjectNode data;
            try {
                data = call(name, args);
            } catch (IllegalArgumentException e) {
                ObjectNode failure = JSON.createObjectNode().put("isError", true);
                failure.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
                return success(id, failure);
            }
            ObjectNode result = JSON.createObjectNode().put("isError", false);
            result.set("structuredContent", data);
            result.putArray("content").addObject().put("type", "text").put("text", data.toString());
            return success(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode call(String name, JsonNode args) {
        ObjectNode result = JSON.createObjectNode();
        if ("restore_summary".equals(name)) {
            fields(args, Set.of());
            copyFields(report, result, "status", "fsck", "gitVersion", "objectFormat");
            int recovered = 0, applied = 0;
            for (JsonNode p : report.get("points")) if ("RECOVERED".equals(p.path("state").asText())) recovered++;
            for (JsonNode b : inventoryRows) if ("APPLIED".equals(b.path("state").asText())) applied++;
            return result.put("recovered", recovered).put("points", report.get("points").size())
                    .put("applied", applied).put("bundles", inventoryRows.size());
        }
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(args, "limit", 25, 1, PAGE_LIMIT);
        if ("bundle_prerequisites".equals(name)) {
            fields(args, Set.of("index", "offset", "limit"));
            int index = integer(args, "index", -1, 1, 64);
            for (JsonNode row : report.get("bundles")) if (row.path("index").asInt() == index) {
                Set<String> missing = new java.util.HashSet<>();
                row.get("missing").forEach(n -> missing.add(n.asText()));
                ArrayNode deps = JSON.createArrayNode();
                row.get("prerequisites").forEach(n -> deps.addObject().put("commit", n.asText()).put("missing", missing.contains(n.asText())));
                return page(result.put("index", index), "prerequisites", deps, n -> true, offset, limit);
            }
            throw invalid("Unknown bundle index");
        }
        fields(args, Set.of("state", "offset", "limit"));
        boolean points = name.equals("list_restore_points");
        String state = option(args, "state", points ? Set.of("RECOVERED", "MISSING") : Set.of("APPLIED", "BLOCKED", "ABSENT"), null);
        return page(result, points ? "points" : "bundles", points ? report.get("points") : inventoryRows,
                n -> matches(n, "state", state), offset, limit);
    }

    private static boolean matches(JsonNode row, String key, String expected) {
        return expected == null || expected.equals(row.path(key).textValue());
    }

    private static String option(JsonNode args, String key, Set<String> allowed, String fallback) {
        if (!args.has(key)) return fallback;
        String value = string(args, key);
        if (!allowed.contains(value)) throw invalid("Unknown value for " + key);
        return value;
    }

    private static ObjectNode page(ObjectNode result, String key, JsonNode rows, Predicate<JsonNode> filter,
                                   int offset, int limit) {
        ArrayNode items = result.putArray(key);
        int total = 0;
        for (JsonNode row : rows) {
            if (filter.test(row) && total++ >= offset && items.size() < limit) items.add(row);
        }
        return pagination(result, offset, items.size(), total);
    }

    private static ObjectNode pagination(ObjectNode result, int offset, int count, int total) {
        result.put("offset", offset).put("total", total);
        if (offset < total - count) result.put("nextOffset", offset + count);
        else result.putNull("nextOffset");
        return result;
    }

    private static ObjectNode inventory() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (String name : TOOLS) {
            ObjectNode tool = list.addObject().put("name", name);
            tool.put("description", switch (name) {
                case "restore_summary" -> "Read the cold restore verdict and counts from the loaded report.";
                case "list_restore_points" -> "List pinned branches, exact commits, recovery states and tree IDs.";
                case "list_bundles" -> "List bundle checksums, replay order and missing prerequisite counts.";
                default -> "Show prerequisite commit IDs for a bundle and whether each was missing.";
            });
            tool.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false)
                    .put("idempotentHint", true).put("openWorldHint", false);
            tool.putObject("outputSchema").put("type", "object");
            ObjectNode schema = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
            ObjectNode props = schema.putObject("properties");
            if ("restore_summary".equals(name)) continue;
            props.putObject("offset").put("type","integer").put("minimum",0);
            props.putObject("limit").put("type","integer").put("minimum",1).put("maximum",PAGE_LIMIT);
            if ("bundle_prerequisites".equals(name)) {
                props.putObject("index").put("type","integer").put("minimum",1).put("maximum",64);
                schema.putArray("required").add("index");
            } else {
                boolean points = "list_restore_points".equals(name);
                ArrayNode states = props.putObject("state").put("type","string").putArray("enum")
                        .add(points ? "RECOVERED" : "APPLIED").add(points ? "MISSING" : "BLOCKED");
                if (!points) states.add("ABSENT");
            }
        }
        return result;
    }

    private static void copyFields(JsonNode source, ObjectNode destination, String... keys) {
        for (String key : keys) if (source.has(key)) destination.set(key, source.get(key));
    }

    private static String string(JsonNode params, String key) {
        JsonNode value = params.path(key);
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("Expected a nonempty string for " + key);
        return value.textValue();
    }

    private static int integer(JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid(key + " must be an integer between " + min + " and " + max);
        return value.intValue();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw invalid("Unexpected parameter");
        });
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode reply = JSON.createObjectNode().put("jsonrpc", "2.0");
        return reply.set("id", id);
    }

    private static ObjectNode success(JsonNode id, ObjectNode data) {
        return envelope(id).set("result", data);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode reply = envelope(id);
        reply.putObject("error").put("code", code).put("message", message);
        return reply;
    }
}
