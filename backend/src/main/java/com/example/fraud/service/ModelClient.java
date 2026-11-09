package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.ModelHealthResponse;
import com.example.fraud.api.ModelScoreRequest;
import com.example.fraud.api.ModelScoreResponse;
import com.example.fraud.domain.RiskLevel;
import com.example.fraud.domain.ScoreRecord;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class ModelClient {
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern RULE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final RestTemplate restTemplate;
    private final String modelBaseUrl;

    public ModelClient(@Qualifier("modelRestTemplate") RestTemplate modelRestTemplate,
                       @Value("${MODEL_BASE_URL:http://127.0.0.1:8000}") String modelBaseUrl) {
        this.restTemplate = modelRestTemplate;
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
            String host = uri.getHost();
            boolean validPort = uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= 65535);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && ("127.0.0.1".equals(host) || "localhost".equals(host))
                    && validPort && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    public ModelHealthResponse health() {
        ModelHealthResponse response;
        try {
            ResponseEntity<ModelHealthResponse> entity = restTemplate.getForEntity(
                    modelBaseUrl + "/health", ModelHealthResponse.class);
            response = entity.getBody();
        } catch (RestClientException exception) {
            if (isUnreadableResponse(exception)) {
                throw ApiException.invalidModelResponse();
            }
            throw ApiException.modelUnavailable();
        }
        if (response == null || !"UP".equals(response.getStatus())
                || !"fraud-model".equals(response.getService())
                || !validVersion(response.getModelVersion())
                || !"DEMO_RULE".equals(response.getModelType())
                || !validFraction(response.getThreshold())
                || !validLabel(response.getLabelSemantics())) {
            throw ApiException.invalidModelResponse();
        }
        return response;
    }

    public ScoreRecord score(ModelScoreRequest request) {
        ModelScoreResponse response;
        try {
            ResponseEntity<ModelScoreResponse> entity = restTemplate.postForEntity(
                    modelBaseUrl + "/internal/v1/score", request, ModelScoreResponse.class);
            response = entity.getBody();
        } catch (RestClientException exception) {
            if (isUnreadableResponse(exception)) {
                throw ApiException.invalidModelResponse();
            }
            throw ApiException.modelUnavailable();
        }
        if (response == null || !request.getOrderId().equals(response.getOrderId())
                || !validVersion(response.getModelVersion())
                || !"DEMO_RULE".equals(response.getModelType())
                || !validFraction(response.getScore())
                || !validFraction(response.getThreshold())
                || response.getRiskLevel() == null
                || !RiskLevel.forScore(response.getScore()).name().equals(response.getRiskLevel())
                || response.getRuleCodes() == null || response.getRuleCodes().size() > 100
                || !validCodes(response)
                || response.getScoredAt() == null
                || !validLabel(response.getLabelSemantics())) {
            throw ApiException.invalidModelResponse();
        }
        return new ScoreRecord(0, response.getOrderId(), response.getScore(),
                RiskLevel.valueOf(response.getRiskLevel()), response.getModelVersion(),
                response.getModelType(), response.getThreshold(), response.getRuleCodes(),
                response.getScoredAt());
    }

    private boolean isUnreadableResponse(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpMessageNotReadableException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private boolean validCodes(ModelScoreResponse response) {
        for (String code : response.getRuleCodes()) {
            if (code == null || !RULE_CODE.matcher(code).matches()) {
                return false;
            }
        }
        return true;
    }

    private boolean validVersion(String value) {
        return value != null && VERSION.matcher(value).matches();
    }

    private boolean validFraction(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0
                && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private boolean validLabel(String value) {
        return "RISK_SCORE_NOT_GROUND_TRUTH".equals(value);
    }
}
