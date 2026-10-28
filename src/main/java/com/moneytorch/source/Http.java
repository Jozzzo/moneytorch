package com.moneytorch.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class Http {
    private static final String USER_AGENT = "moneyTorch/0.1 (personal price tracker)";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;

    public Http(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode getJson(String url) throws IOException {
        return mapper.readTree(getBytes(url, "application/json"));
    }

    public byte[] getBytes(String url, String accept) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + url, e);
        }
    }
}
