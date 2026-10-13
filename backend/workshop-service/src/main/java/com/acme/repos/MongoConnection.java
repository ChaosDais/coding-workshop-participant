package com.acme.repos;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Resolves the MongoDB deployment from the MONGO_* variables the deploy scripts give each Lambda. */
public final class MongoConnection {
    static final String DEFAULT_DATABASE = "workshop";

    private MongoConnection() {
    }

    public static String connectionString(Map<String, String> env) {
        String host = valueOrDefault(env, "MONGO_HOST", "localhost");
        String port = valueOrDefault(env, "MONGO_PORT", "27017");
        String user = valueOrDefault(env, "MONGO_USER", "");
        String password = valueOrDefault(env, "MONGO_PASS", "");

        // The workshop users are created in the admin database, both locally and on DocumentDB.
        String auth = user.isEmpty() ? "" : encode(user) + ":" + encode(password) + "@";
        // Fail well inside CloudFront's 30 second origin timeout so an unreachable database is reported as JSON.
        StringBuilder options = new StringBuilder("serverSelectionTimeoutMS=5000");
        if (!user.isEmpty()) {
            options.append("&authSource=admin");
        }
        // DocumentDB needs TLS and does not support retryable writes; the local MongoDB runs without TLS.
        if (!"true".equals(env.get("IS_LOCAL"))) {
            options.append("&tls=true&tlsAllowInvalidCertificates=true&retryWrites=false");
        }

        return String.format("mongodb://%s%s:%s/?%s", auth, host, port, options);
    }

    public static String databaseName(Map<String, String> env) {
        return valueOrDefault(env, "MONGO_NAME", DEFAULT_DATABASE);
    }

    private static String valueOrDefault(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
