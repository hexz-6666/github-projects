package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.ModelScoreRequest;
import com.example.fraud.domain.FeatureRecord;
import com.example.fraud.domain.ScoreRecord;
import com.example.fraud.store.FraudStore;
import org.springframework.stereotype.Service;

@Service
public class ScoreService {
    private final FraudStore store;
    private final ModelClient modelClient;

    public ScoreService(FraudStore store, ModelClient modelClient) {
        this.store = store;
        this.modelClient = modelClient;
    }

    public ScoreRecord score(String orderId) {
        store.findOrder(orderId).orElseThrow(ApiException::orderNotFound);
        FeatureRecord feature = store.findFeature(orderId).orElseThrow(ApiException::orderNotFound);
        ScoreRecord modelScore = modelClient.score(new ModelScoreRequest(feature));
        return store.saveScore(feature, modelScore);
    }
}
