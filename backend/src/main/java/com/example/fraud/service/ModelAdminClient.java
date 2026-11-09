package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class ModelAdminClient {
    private final RestTemplate restTemplate;
    private final String modelBaseUrl;

    public ModelAdminClient(@Qualifier("modelAdminRestTemplate") RestTemplate restTemplate,
                            @Value("${MODEL_BASE_URL:http://127.0.0.1:8000}") String modelBaseUrl) {
        this.restTemplate = restTemplate;
        String base = modelBaseUrl.endsWith("/")
                ? modelBaseUrl.substring(0, modelBaseUrl.length() - 1) : modelBaseUrl;
        if (!isLoopbackHttpOrigin(base)) {
            throw new IllegalArgumentException("MODEL_BASE_URL must be a loopback HTTP(S) origin");
        }
        this.modelBaseUrl = base;
    }

    private boolean isLoopbackHttpOrigin(String value) {
        try {
            URI uri = new URI(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                    && (uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= 65535))
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> train(String modelVersion, String dataPath) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("modelVersion", modelVersion);
        request.put("dataPath", dataPath);
        request.put("sourceSystem", "IEEE_CIS");
        Map<String, Object> response;
        try {
            ResponseEntity<Map> entity = restTemplate.postForEntity(
                    modelBaseUrl + "/internal/v2/train", request, Map.class);
            response = entity.getBody();
        } catch (RestClientException exception) {
            throw ApiException.modelServiceUnavailable();
        }
        if (response == null) {
            throw ApiException.invalidModelResponse();
        }
        return response;
    }
}
