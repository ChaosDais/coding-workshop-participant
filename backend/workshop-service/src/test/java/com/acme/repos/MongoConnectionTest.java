package com.acme.repos;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoConnectionTest {

    @Test
    void localConnectionUsesNoTls() {
        Map<String, String> env = Map.of("IS_LOCAL", "true", "MONGO_HOST", "172.17.0.1", "MONGO_PORT", "27017",
                "MONGO_NAME", "mongo");

        assertEquals("mongodb://172.17.0.1:27017/?serverSelectionTimeoutMS=5000", MongoConnection.connectionString(env));
        assertEquals("mongo", MongoConnection.databaseName(env));
    }

    @Test
    void credentialsAreEncodedAndAuthenticateAgainstAdmin() {
        Map<String, String> env = Map.of("IS_LOCAL", "true", "MONGO_HOST", "db", "MONGO_USER", "mongo",
                "MONGO_PASS", "p@ss:w/rd 1");

        assertEquals("mongodb://mongo:p%40ss%3Aw%2Frd%201@db:27017/?serverSelectionTimeoutMS=5000&authSource=admin",
                MongoConnection.connectionString(env));
    }

    @Test
    void documentDbConnectionUsesTlsWithoutRetryableWrites() {
        Map<String, String> env = Map.of("IS_LOCAL", "false", "MONGO_HOST", "docdb.example", "MONGO_USER", "u",
                "MONGO_PASS", "p");

        assertEquals("mongodb://u:p@docdb.example:27017/"
                        + "?serverSelectionTimeoutMS=5000&authSource=admin&tls=true&tlsAllowInvalidCertificates=true&retryWrites=false",
                MongoConnection.connectionString(env));
        assertEquals(MongoConnection.DEFAULT_DATABASE, MongoConnection.databaseName(env));
    }
}
