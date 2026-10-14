package com.acme.http;

import com.acme.models.CompletionStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** JSON reading and writing for requests and responses; dates use YYYY-MM-DD. */
public final class Json {
    private static final Gson GSON = new GsonBuilder()
            // Responses are JSON, not HTML, so characters like ' stay readable instead of becoming \u0027.
            .disableHtmlEscaping()
            .registerTypeAdapter(LocalDate.class, new LocalDateAdapter().nullSafe())
            .registerTypeAdapter(CompletionStatus.class, (JsonDeserializer<CompletionStatus>) (json, type, context) ->
                    parseStatus(json.getAsString()))
            .create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    /** Parses a request body that must be a JSON object. */
    static JsonObject parseObject(String text) {
        JsonElement json;
        try {
            json = GSON.fromJson(text, JsonElement.class);
        } catch (JsonParseException e) {
            throw new ApiException(400, "Request body is not valid JSON");
        }
        if (json == null || !json.isJsonObject()) {
            throw new ApiException(400, "Request body must be a JSON object");
        }
        return json.getAsJsonObject();
    }

    /** Builds a model from JSON; validation failures in the model become 400 responses. */
    static <T> T convert(JsonObject json, Class<T> type) {
        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException e) {
            // A number field holding text, e.g. "salary": "lots"; its parser message is not meant for clients.
            if (e instanceof NumberFormatException || e.getCause() instanceof NumberFormatException) {
                throw new ApiException(400, "Request body has a field of the wrong type");
            }
            // Gson wraps exceptions thrown by the record constructors, so the validation message is in the cause.
            if (e.getCause() instanceof IllegalArgumentException cause) {
                throw new ApiException(400, cause.getMessage());
            }
            if (e instanceof IllegalArgumentException) {
                throw new ApiException(400, e.getMessage());
            }
            if (e instanceof JsonParseException || e instanceof IllegalStateException) {
                throw new ApiException(400, "Request body has a field of the wrong type");
            }
            throw e;
        }
    }

    private static CompletionStatus parseStatus(String value) {
        try {
            return CompletionStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be one of TODO, IN_PROGRESS, COMPLETE");
        }
    }

    private static class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            String value = in.nextString();
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Dates must use the format YYYY-MM-DD: " + value);
            }
        }
    }
}
