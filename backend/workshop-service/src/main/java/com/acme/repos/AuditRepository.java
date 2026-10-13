package com.acme.repos;

import com.acme.models.AuditEntry;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Stores each audit entry as one document in the "audit_entries" collection. */
public class AuditRepository implements AuditStore {
    static final String COLLECTION = "audit_entries";

    private final MongoCollection<Document> collection;

    /** The caller owns the client; create it once and reuse it across Lambda invocations. */
    public AuditRepository(MongoClient client, String databaseName) {
        this.collection = client.getDatabase(databaseName).getCollection(COLLECTION);
    }

    @Override
    public void insert(AuditEntry entry) {
        collection.insertOne(toDocument(entry));
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        for (Document document : collection.find().sort(Sorts.descending("timestamp")).limit(limit)) {
            entries.add(fromDocument(document));
        }
        return entries;
    }

    static Document toDocument(AuditEntry entry) {
        return new Document("_id", entry.id())
                .append("action", entry.action())
                .append("actor", entry.actor())
                .append("details", new Document(entry.details()))
                .append("timestamp", Date.from(entry.timestamp()));
    }

    static AuditEntry fromDocument(Document document) {
        return new AuditEntry(
                document.getString("_id"),
                document.getString("action"),
                document.getString("actor"),
                document.get("details", new Document()),
                document.getDate("timestamp").toInstant());
    }
}
