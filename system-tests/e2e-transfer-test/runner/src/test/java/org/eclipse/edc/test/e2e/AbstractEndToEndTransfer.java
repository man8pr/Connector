/*
 *  Copyright (c) 2022 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.test.e2e;

import org.eclipse.edc.connector.policy.spi.PolicyDefinition;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AndConstraint;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.policy.model.PolicyType;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.HttpDataAddress;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static java.time.Duration.ofDays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.connector.transfer.spi.types.TransferProcessStates.COMPLETED;
import static org.eclipse.edc.spi.CoreConstants.EDC_NAMESPACE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public abstract class AbstractEndToEndTransfer {

    protected static final Participant CONSUMER = new Participant("consumer", "urn:connector:consumer");
    protected static final Participant PROVIDER = new Participant("provider", "urn:connector:provider");
    private static final Object CONTRACT_EXPIRY_EVALUATION_KEY = EDC_NAMESPACE + "inForceDate";
    protected final Duration timeout = Duration.ofSeconds(60);

    @Test
    void httpPullDataTransfer() {
        registerDataPlanes();
        var assetId = UUID.randomUUID().toString();
        createResourcesOnProvider(assetId, noConstraintPolicy(), UUID.randomUUID().toString(), httpDataAddressProperties());

        var catalog = CONSUMER.getCatalog(PROVIDER);

        var contractOffer = catalog
                .getContractOffers()
                .stream()
                .filter(o -> o.getAssetId().equals(assetId))
                .findFirst()
                .get();
        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, contractOffer);

        var dataRequestId = UUID.randomUUID().toString();
        var transferProcessId = CONSUMER.dataRequest(dataRequestId, contractAgreementId, assetId, PROVIDER, sync());

        await().atMost(timeout).untilAsserted(() -> {
            var state = CONSUMER.getTransferProcessState(transferProcessId);
            assertThat(state).isEqualTo(COMPLETED.name());
        });

        // retrieve the data reference
        var edr = CONSUMER.getDataReference(dataRequestId);

        // pull the data without query parameter
        await().atMost(timeout).untilAsserted(() -> CONSUMER.pullData(edr, Map.of(), equalTo("some information")));

        // pull the data with additional query parameter
        var msg = UUID.randomUUID().toString();
        await().atMost(timeout).untilAsserted(() -> CONSUMER.pullData(edr, Map.of("message", msg), equalTo(msg)));
    }

    /**
     * This test is disabled because rejection messages are not processed correctly in IDS. The Policy that is attached to the contract definition
     * contains a validity period, that will be violated, so we expect the transfer request to be rejected with a 409 CONFLICT.
     * Once that case is handled properly by the DSP, we can re-enable the test and add a proper assertion
     */
    @Test
    @Disabled
    void httpPull_withExpiredContract_fixedInForcePeriod() {
        var assetId = "test-asset-id";
        var now = Instant.now();
        // contract was valid from t-10d to t-5d, so "now" it is expired
        createResourcesOnProvider(assetId, createInForcePolicy(Operator.GEQ, now.minus(ofDays(10)), Operator.LEQ, now.minus(ofDays(5))), "test-definition-id", httpDataAddressProperties());

        var catalog = CONSUMER.getCatalog(PROVIDER);
        assertThat(catalog.getContractOffers()).hasSize(1);

        var offer = catalog.getContractOffers().get(0);
        assertThat(offer.getAssetId()).isEqualTo(assetId);

        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, offer);

        var dataRequestId = "test-data-request-id";
        var transferProcessId = CONSUMER.dataRequest(dataRequestId, contractAgreementId, assetId, PROVIDER, sync());

        await().atMost(timeout).untilAsserted(() -> {
            //todo: assert transfer request rejection
        });
    }

    /**
     * This test is disabled because rejection messages are not processed correctly in IDS. The Policy that is attached to the contract definition
     * contains a validity period, that will be violated, so we expect the transfer request to be rejected with a 409 CONFLICT.
     * Once that case is handled properly by the DSP, we can re-enable the test and add a proper assertion
     */
    @Test
    @Disabled
    void httpPull_withExpiredContract_durationInForcePeriod() {
        var assetId = "test-asset-id";
        var now = Instant.now();
        // contract was valid from t-10d to t-5d, so "now" it is expired
        createResourcesOnProvider(assetId, createInForcePolicy(Operator.GEQ, now.minus(ofDays(10)), Operator.LEQ, "contractAgreement+2s"), "test-definition-id", httpDataAddressProperties());

        var catalog = CONSUMER.getCatalog(PROVIDER);
        assertThat(catalog.getContractOffers()).hasSize(1);

        var offer = catalog.getContractOffers().get(0);
        assertThat(offer.getAssetId()).isEqualTo(assetId);

        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, offer);

        var dataRequestId = "test-data-request-id";
        var transferProcessId = CONSUMER.dataRequest(dataRequestId, contractAgreementId, assetId, PROVIDER, sync());

        await().atMost(timeout).untilAsserted(() -> {
            //todo: assert transfer request rejection
        });
    }

    @Test
    void httpPullDataTransferProvisioner() {
        registerDataPlanes();
        var assetId = UUID.randomUUID().toString();
        createResourcesOnProvider(assetId, noConstraintPolicy(), UUID.randomUUID().toString(), Map.of(
                "name", "transfer-test",
                "baseUrl", PROVIDER.backendService() + "/api/provider/data",
                "type", "HttpProvision",
                "proxyQueryParams", "true"
        ));

        var catalog = CONSUMER.getCatalog(PROVIDER);

        var contractOffer = catalog
                .getContractOffers()
                .stream()
                .filter(o -> o.getAssetId().equals(assetId))
                .findFirst()
                .get();
        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, contractOffer);

        var dataRequestId = UUID.randomUUID().toString();
        var transferProcessId = CONSUMER.dataRequest(dataRequestId, contractAgreementId, assetId, PROVIDER, sync());

        await().atMost(timeout).untilAsserted(() -> {
            var state = CONSUMER.getTransferProcessState(transferProcessId);
            assertThat(state).isEqualTo(COMPLETED.name());
        });

        var edr = CONSUMER.getDataReference(dataRequestId);
        await().atMost(timeout).untilAsserted(() -> CONSUMER.pullData(edr, Map.of(), equalTo("some information")));
    }

    @Test
    void httpPushDataTransfer() {
        registerDataPlanes();
        var assetId = UUID.randomUUID().toString();
        createResourcesOnProvider(assetId, noConstraintPolicy(), UUID.randomUUID().toString(), httpDataAddressProperties());

        var catalog = CONSUMER.getCatalog(PROVIDER);

        var contractOffer = catalog
                .getContractOffers()
                .stream()
                .filter(o -> o.getAssetId().equals(assetId))
                .findFirst()
                .get();
        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, contractOffer);

        var destination = HttpDataAddress.Builder.newInstance()
                .baseUrl(CONSUMER.backendService() + "/api/consumer/store")
                .build();
        var transferProcessId = CONSUMER.dataRequest(UUID.randomUUID().toString(), contractAgreementId, assetId, PROVIDER, destination);

        await().atMost(timeout).untilAsserted(() -> {
            var state = CONSUMER.getTransferProcessState(transferProcessId);
            assertThat(state).isEqualTo(COMPLETED.name());
        });

        await().atMost(timeout).untilAsserted(() -> {
            given()
                    .baseUri(CONSUMER.backendService().toString())
                    .when()
                    .get("/api/consumer/data")
                    .then()
                    .statusCode(anyOf(is(200), is(204)))
                    .body(is(notNullValue()));
        });
    }

    @Test
    @DisplayName("Provider pushes data to Consumer, Provider needs to authenticate the data request through an oauth2 server")
    void httpPushDataTransfer_oauth2Provisioning() {
        registerDataPlanes();
        var assetId = UUID.randomUUID().toString();
        createResourcesOnProvider(assetId, noConstraintPolicy(), UUID.randomUUID().toString(), httpDataAddressOauth2Properties());

        var catalog = CONSUMER.getCatalog(PROVIDER);

        var contractOffer = catalog
                .getContractOffers()
                .stream()
                .filter(o -> o.getAssetId().equals(assetId))
                .findFirst()
                .get();
        var contractAgreementId = CONSUMER.negotiateContract(PROVIDER, contractOffer);

        var destination = HttpDataAddress.Builder.newInstance()
                .baseUrl(CONSUMER.backendService() + "/api/consumer/store")
                .build();
        var transferProcessId = CONSUMER.dataRequest(UUID.randomUUID().toString(), contractAgreementId, assetId, PROVIDER, destination);

        await().atMost(timeout).untilAsserted(() -> {
            var state = CONSUMER.getTransferProcessState(transferProcessId);
            assertThat(state).isEqualTo(COMPLETED.name());
        });

        await().atMost(timeout).untilAsserted(() -> {
            given()
                    .baseUri(CONSUMER.backendService().toString())
                    .when()
                    .get("/api/consumer/data")
                    .then()
                    .statusCode(anyOf(is(200), is(204)))
                    .body(is(notNullValue()));
        });
    }

    @NotNull
    private Map<String, String> httpDataAddressOauth2Properties() {
        return Map.of(
                "name", "transfer-test",
                "baseUrl", PROVIDER.backendService() + "/api/provider/oauth2data",
                "type", "HttpData",
                "proxyQueryParams", "true",
                "oauth2:clientId", "clientId",
                "oauth2:clientSecretKey", "provision-oauth-secret",
                "oauth2:tokenUrl", PROVIDER.backendService() + "/api/oauth2/token"
        );
    }

    @NotNull
    private Map<String, String> httpDataAddressProperties() {
        return Map.of(
                "name", "transfer-test",
                "baseUrl", PROVIDER.backendService() + "/api/provider/data",
                "type", "HttpData",
                "proxyQueryParams", "true"
        );
    }

    private void registerDataPlanes() {
        PROVIDER.registerDataPlane();
        CONSUMER.registerDataPlane();
    }

    private void createResourcesOnProvider(String assetId, PolicyDefinition contractPolicy, String definitionId, Map<String, String> dataAddressProperties) {
        PROVIDER.createAsset(assetId, dataAddressProperties);
        var accessPolicy = noConstraintPolicy();
        PROVIDER.createPolicy(accessPolicy);
        PROVIDER.createPolicy(contractPolicy);
        PROVIDER.createContractDefinition(assetId, definitionId, accessPolicy.getUid(), contractPolicy.getUid(), 31536000L);
    }

    private DataAddress sync() {
        return DataAddress.Builder.newInstance().type("HttpProxy").build();
    }

    private PolicyDefinition noConstraintPolicy() {
        return PolicyDefinition.Builder.newInstance()
                .policy(Policy.Builder.newInstance()
                        .permission(Permission.Builder.newInstance()
                                .action(Action.Builder.newInstance().type("USE").build())
                                .build())
                        .type(PolicyType.SET)
                        .build())
                .build();
    }

    private PolicyDefinition createInForcePolicy(Operator operatorStart, Object startDate, Operator operatorEnd, Object endDate) {
        var fixedInForceTimeConstraint = AndConstraint.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(CONTRACT_EXPIRY_EVALUATION_KEY))
                        .operator(operatorStart)
                        .rightExpression(new LiteralExpression(startDate.toString()))
                        .build())
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(CONTRACT_EXPIRY_EVALUATION_KEY))
                        .operator(operatorEnd)
                        .rightExpression(new LiteralExpression(endDate.toString()))
                        .build())
                .build();
        var permission = Permission.Builder.newInstance()
                .action(Action.Builder.newInstance().type("USE").build())
                .constraint(fixedInForceTimeConstraint).build();

        var p = Policy.Builder.newInstance()
                .permission(permission)
                .build();

        return PolicyDefinition.Builder.newInstance()
                .policy(p)
                .id("in-force-policy")
                .build();
    }
}
