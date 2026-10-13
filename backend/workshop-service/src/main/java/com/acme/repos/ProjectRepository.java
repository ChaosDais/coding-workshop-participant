package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Stores each project as one document in the "projects" collection, with milestones embedded. */
public class ProjectRepository implements ProjectStore {
    static final String COLLECTION = "projects";

    private final MongoCollection<Document> collection;

    /** The caller owns the client; create it once and reuse it across Lambda invocations. */
    public ProjectRepository(MongoClient client, String databaseName) {
        this.collection = client.getDatabase(databaseName).getCollection(COLLECTION);
    }

    /** Builds a client for a MongoDB Atlas connection string such as mongodb+srv://user:pass@cluster/... */
    public static MongoClient createClient(String connectionString) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .serverApi(ServerApi.builder().version(ServerApiVersion.V1).build())
                .build());
    }

    /** Fails with MongoWriteException when a project with the same id already exists. */
    @Override
    public void insert(Project project) {
        collection.insertOne(toDocument(project));
    }

    @Override
    public Optional<Project> findById(String id) {
        Document document = collection.find(Filters.eq("_id", id)).first();
        return Optional.ofNullable(document).map(ProjectRepository::fromDocument);
    }

    @Override
    public List<Project> findAll() {
        List<Project> projects = new ArrayList<>();
        for (Document document : collection.find().sort(Sorts.ascending("startDate", "name"))) {
            projects.add(fromDocument(document));
        }
        return projects;
    }

    /** Replaces the stored project including its milestones; returns false when the id does not exist. */
    @Override
    public boolean replace(Project project) {
        return collection.replaceOne(Filters.eq("_id", project.id()), toDocument(project)).getMatchedCount() > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return collection.deleteOne(Filters.eq("_id", id)).getDeletedCount() > 0;
    }

    static Document toDocument(Project project) {
        return new Document("_id", project.id())
                .append("name", project.name())
                .append("startDate", toDate(project.startDate()))
                .append("endDate", toDate(project.endDate()))
                .append("progressTime", project.progressTime())
                .append("manager", project.manager())
                .append("budget", new Decimal128(project.budget()))
                .append("status", project.status().name())
                .append("milestones", project.milestones().stream().map(ProjectRepository::toDocument).toList());
    }

    private static Document toDocument(Milestone milestone) {
        return new Document("id", milestone.id())
                .append("name", milestone.name())
                .append("startDate", toDate(milestone.startDate()))
                .append("endDate", toDate(milestone.endDate()))
                .append("progressTime", milestone.progressTime())
                .append("assignedEmployees", milestone.assignedEmployees())
                .append("status", milestone.status().name());
    }

    static Project fromDocument(Document document) {
        List<Document> milestones = document.getList("milestones", Document.class, List.of());
        return new Project(
                document.getString("_id"),
                document.getString("name"),
                toLocalDate(document.getDate("startDate")),
                toLocalDate(document.getDate("endDate")),
                document.get("progressTime", Number.class).doubleValue(),
                document.getString("manager"),
                document.get("budget", Decimal128.class).bigDecimalValue(),
                CompletionStatus.valueOf(document.getString("status")),
                milestones.stream().map(ProjectRepository::milestoneFromDocument).toList());
    }

    private static Milestone milestoneFromDocument(Document document) {
        return new Milestone(
                document.getString("id"),
                document.getString("name"),
                toLocalDate(document.getDate("startDate")),
                toLocalDate(document.getDate("endDate")),
                document.get("progressTime", Number.class).doubleValue(),
                document.getList("assignedEmployees", String.class, List.of()),
                CompletionStatus.valueOf(document.getString("status")));
    }

    /** Dates are stored as BSON dates at UTC midnight so they sort and compare correctly in queries. */
    private static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
