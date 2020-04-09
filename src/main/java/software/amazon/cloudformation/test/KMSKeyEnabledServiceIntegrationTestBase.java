package software.amazon.cloudformation.test;

import com.amazonaws.annotation.NotThreadSafe;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.AliasListEntry;
import software.amazon.awssdk.services.kms.model.CancelKeyDeletionRequest;
import software.amazon.awssdk.services.kms.model.CreateAliasRequest;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.DependencyTimeoutException;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.DescribeKeyResponse;
import software.amazon.awssdk.services.kms.model.EnableKeyRequest;
import software.amazon.awssdk.services.kms.model.GetKeyPolicyRequest;
import software.amazon.awssdk.services.kms.model.GetKeyPolicyResponse;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.ListAliasesRequest;
import software.amazon.awssdk.services.kms.model.ListAliasesResponse;
import software.amazon.awssdk.services.kms.model.NotFoundException;
import software.amazon.awssdk.services.kms.model.PutKeyPolicyRequest;
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest;
import software.amazon.cloudformation.proxy.CallChain;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.StdCallbackContext;
import software.amazon.cloudformation.proxy.delay.Constant;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Abstract base class we can derive from that adds support for a KMS key and has helpers methods
 * to update Key policy for accessing the key for service principal
 *
 * <pre>
 * <code>
 *     package software.amazon.logs.loggroup;
 *
 *     import org.junit.jupiter.api.*;
 *     import static org.assertj.core.api.Assertions.assertThat;
 *
 *     import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
 *     import software.amazon.cloudformation.proxy.*;
 *     import software.amazon.cloudformation.test.*;
 *
 *     &#064;TestMethodOrder(MethodOrderer.OrderAnnotation.class) // we order the tests to follow C to U to D
 *     <b>&#064;ExtendWith(InjectProfileCredentials.class)</b> // extend with profile based credentials
 *     &#064;EnabledIfSystemProperty(named = "desktop", matches = "true")
 *     &#064;TestInstance(TestInstance.Lifecycle.PER_CLASS) // IMP PER_CLASS
 *     public class LifecycleTest extends KMSKeyEnabledServiceIntegrationTestBase {
 *
 *         //
 *         // At the annotation software.amazon.cloudformation.test.InjectSessionCredentials to the
 *         // constructor. This will inject the role's credentials
 *         //
 *         public LifecycleTest(<b>&#064;InjectSessionCredentials(profile = "cfn-integration")</b> AwsSessionCredentials awsCredentials) {
 *              super(awsCredentials, ((apiCall, provided) -&gt; override));
 *         }
 *         ...
 *         ...
 *         &#064;Order(300)
 *         &#064;Test
 *         void addValidKMS() {
 *             final ResourceModel current = ResourceModel.builder().arn(model.getArn())
 *                 .logGroupName(model.getLogGroupName()).retentionInDays(model.getRetentionInDays()).build();
 *             <b>// Access a KMS key. The test ensures to only create one key and recycles despite any number of runs</b>
 *             <b>String kmsKeyId = getKmsKeyId();</b>
 *             <b>String kmsKeyArn = getKmsKeyArn();</b>
 *             <b>// Add your service to use KMS key</b>
 *             addServiceAccess("logs", kmsKeyId);
 *             model.setKMSKey(kmsKeyArn);
 *             ProgressEvent&lt;ResourceModel, CallbackContext&gt; event = new UpdateHandler()
 *                 .handleRequest(getProxy(), createRequest(model, current), null, getLoggerProxy());
 *             assertThat(event.isSuccess()).isTrue();
 *             model = event.getResourceModel();
 *         }
 *         ...
 *         ...
 *     }
 * </code>
 * </pre>
 */
@NotThreadSafe
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class KMSKeyEnabledServiceIntegrationTestBase extends AbstractLifecycleTestBase {

    //
    // This is make this fail for the alias on purpose
    //
    //protected final String KEY_ALIAS = "kms-key-TEST-TO-DELETE-" + KMSKeyEnabledServiceIntegrationTest.class.getSimpleName();
    protected final String KEY_ALIAS = "alias/kms-key-TEST-TO-DELETE-" + KMSKeyEnabledServiceIntegrationTestBase.class.getSimpleName();
    private final KmsClient kmsClient;
    private String kmsKeyArn;
    private String kmsKeyId;
    private String aliasArn;
    private final CallChain.Initiator<KmsClient, String, StdCallbackContext> serviceInvoker;
    protected KMSKeyEnabledServiceIntegrationTestBase(AwsSessionCredentials sessionCredentials, DelayFactory factory) {
        super(sessionCredentials, ((apiCall, provided) -> {
            //
            // for KMS alone override to wait until 1.5 minutes
            //
            if (apiCall.startsWith("kms")) {
                return Constant.of().delay(Duration.ofSeconds(5)).timeout(Duration.ofSeconds(60)).build();
            }
            //
            // for others skip delegate
            //
            return factory.getDelay(apiCall, provided);
        }));
        kmsClient = KmsClient.builder().build();
        serviceInvoker = getProxy().newInitiator(() -> kmsClient, KEY_ALIAS, new StdCallbackContext());
    }

    @AfterAll
    public void deleteKmsKey() {
        if (kmsKeyArn != null) {
            serviceInvoker
                .translateToServiceRequest(ign -> ScheduleKeyDeletionRequest.builder()
                    .keyId(kmsKeyId)
                    .pendingWindowInDays(7) // this is the minimum
                    .build())
                .makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::scheduleKeyDeletion))
                .success();
        }
    }

    protected String getKmsKeyArn() {
        if (kmsKeyArn == null) {
            discoverIfAlreadyExistsWithAlias();
            if (kmsKeyId != null) {
                describeAndEnableKey();
            }
            else {
                createAndSetKmsKey();
            }
        }
        return kmsKeyArn;
    }

    protected String getAliasArn() {
        getKmsKeyArn();
        return aliasArn;
    }

    protected String getKmsKeyId() {
        getKmsKeyArn();
        return kmsKeyId;
    }

    private final static int CONSECUTIVE_ATTEMPTS = 6;
    <RequestT, ResponseT, ModelT> CallChain.Callback<RequestT, ResponseT, KmsClient, ModelT, StdCallbackContext, Boolean>
        stabilizeCurry(final String trackingName,
                       CallChain.Callback<RequestT, ResponseT, KmsClient, ModelT, StdCallbackContext, Boolean> func) {
        return stabilizeCurry(trackingName, func, CONSECUTIVE_ATTEMPTS);
    }


    <RequestT, ResponseT, ModelT> CallChain.Callback<RequestT, ResponseT, KmsClient, ModelT, StdCallbackContext, Boolean>
        stabilizeCurry(final String trackingName,
                       CallChain.Callback<RequestT, ResponseT, KmsClient, ModelT, StdCallbackContext, Boolean> func,
                       final int consecutiveAttempts) {
        final String attempts = trackingName + ".kms.attempts";
        return (request, response, client, model, context) -> {
            Integer attempt = context.attempts(attempts);
            boolean stabilized = attempt > consecutiveAttempts;
            if (!stabilized) {
                if (func.invoke(request, response, client, model, context)) {
                    attempt++;
                    stabilized = attempt > consecutiveAttempts;
                    context.attempts(attempts, attempt);
                }
                else {
                    context.attempts(attempts, 1);
                }
            }
            return stabilized;
        };
    }

    void describeAndEnableKey() {
        //
        // We need to account for KMS propagation delays. So each step stabilizes returns true
        // when 4 consecutive attempts to describe and check succeeds to reliably converge.
        //
        final String trackingKeyName = "describeAndEnableKey";
        final DescribeKeyRequest describeKeyRequest = DescribeKeyRequest.builder().keyId(kmsKeyId).build();
        final DescribeKeyResponse[] finalDescribeKeyResponse = new DescribeKeyResponse[1];

        ProgressEvent<String, StdCallbackContext> result =  serviceInvoker.initiate(trackingKeyName).
            translateToServiceRequest(ign -> describeKeyRequest).
            makeServiceCall((r, c) -> {
               DescribeKeyResponse response = c.injectCredentialsAndInvokeV2(r, c.client()::describeKey);
               kmsKeyArn = response.keyMetadata().arn();
               return response;
            }).
            stabilize(stabilizeCurry(
                trackingKeyName + ":stabilize:describeKey",
                (request, response, client, model, context) -> {
                    if (finalDescribeKeyResponse[0] == null) {
                        finalDescribeKeyResponse[0] = response;
                        //
                        // No deletion date, then we are enabled and alive
                        //
                        return response.keyMetadata().deletionDate() == null;
                    }
                    finalDescribeKeyResponse[0] = client.injectCredentialsAndInvokeV2(
                        DescribeKeyRequest.builder().keyId(kmsKeyId).build(),
                        client.client()::describeKey
                    );
                    //
                    // No deletion date, then we are enabled and alive
                    //
                    return finalDescribeKeyResponse[0].keyMetadata().deletionDate() == null;
                })).
            success();

        if (result.isSuccess()) {
            return;
        }

        serviceInvoker.
            translateToServiceRequest(ign -> CancelKeyDeletionRequest.builder().keyId(kmsKeyId).build()).
            makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::cancelKeyDeletion)).
            stabilize(stabilizeCurry(
                trackingKeyName + ":stabilize:CancelKey",
                (request, response, client, model, context) -> {
                    DescribeKeyResponse response_ = client.injectCredentialsAndInvokeV2(
                        DescribeKeyRequest.builder().keyId(kmsKeyId).build(),
                        client.client()::describeKey
                    );
                    return response_.keyMetadata().deletionDate() == null;
                }
            )).
            progress().
            then(evt ->
                serviceInvoker
                    .translateToServiceRequest(ign -> EnableKeyRequest.builder().keyId(kmsKeyId).build())
                    .makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::enableKey))
                    .stabilize(stabilizeCurry(
                        trackingKeyName + ":stabilize:EnableKey",
                        (request, response, client, model, context) -> {
                            DescribeKeyResponse response_ =
                                client.injectCredentialsAndInvokeV2(
                                    describeKeyRequest, client.client()::describeKey);
                            return response_.keyMetadata().enabled();
                        })).
                    success());
    }

    void discoverIfAlreadyExistsWithAlias() {
        ListAliasesResponse aliases = ListAliasesResponse.builder().build();
        do {
            ProgressEvent<ListAliasesResponse, StdCallbackContext> result =
                serviceInvoker.rebindModel(aliases).
                    translateToServiceRequest(m -> ListAliasesRequest.builder().marker(m.nextMarker()).build()).
                    makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::listAliases)).
                    done(response_ -> ProgressEvent.success(response_, serviceInvoker.getCallbackContext()));
            if (!result.isSuccess()) {
                throw new RuntimeException("Error retrieving key aliases " + result.getMessage());
            }
            aliases = result.getResourceModel();
            AliasListEntry entry = aliases.aliases().stream().filter(e -> e.aliasName().equals(KEY_ALIAS)).findFirst()
                .orElse(null);
            if (entry != null) {
                kmsKeyId = entry.targetKeyId();
                aliasArn = entry.aliasArn();
                break;
            }
            if (aliases.nextMarker() == null) {
                break;
            }
        } while (kmsKeyId == null);
    }

    void createAndSetKmsKey() {
        ProgressEvent<String, StdCallbackContext> result =
            serviceInvoker.
                initiate("kms:CreateKey").
                translateToServiceRequest(m -> CreateKeyRequest.builder().keyUsage(KeyUsageType.ENCRYPT_DECRYPT).build()).
                backoffDelay(Constant.of().delay(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(5 * 10)).build()).
                makeServiceCall((r, c) -> {
                    CreateKeyResponse response = c.injectCredentialsAndInvokeV2(r, c.client()::createKey);
                    kmsKeyId = response.keyMetadata().keyId();
                    kmsKeyArn = response.keyMetadata().arn();
                    return response;
                }).
                stabilize(
                    stabilizeCurry(
                        "kms:CreateKey:stabilize",
                        (request, response, client, model, context) -> {
                            try {
                                DescribeKeyRequest req = DescribeKeyRequest.builder().keyId(kmsKeyId).build();
                                client.injectCredentialsAndInvokeV2(req, client.client()::describeKey);
                            } catch (NotFoundException | DependencyTimeoutException e) {
                                return false;
                            }
                            return true;
                        }
                    )
                ).
                progress()
            .then(event ->
                serviceInvoker.
                    initiate("kms:CreateAlias").
                    translateToServiceRequest(ign -> CreateAliasRequest.builder().aliasName(KEY_ALIAS).targetKeyId(kmsKeyId).build()).
                    makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createAlias)).
                    stabilize(
                        stabilizeCurry(
                            "kms:CreateAlias:stabilize",
                            (request, response, client, model, context) -> {
                                ListAliasesResponse res = client.injectCredentialsAndInvokeV2(
                                    ListAliasesRequest.builder().keyId(kmsKeyId).build(),
                                    client.client()::listAliases
                                );
                                return res.aliases().get(0).aliasName().equals(KEY_ALIAS);
                            }
                        )).
                    progress()
            ).then(event ->
                serviceInvoker.
                    translateToServiceRequest(ign -> ListAliasesRequest.builder().keyId(kmsKeyId).build()).
                    makeServiceCall((r, c) -> {
                        ListAliasesResponse response = c.injectCredentialsAndInvokeV2(r, c.client()::listAliases);
                        aliasArn = response.aliases().get(0).aliasArn();
                        return response;
                    })
                    .success()
            );

        if (!result.isSuccess()) {
            throw new RuntimeException("Could not create KMS Key " + result.getMessage());
        }

    }

    protected void addServiceAccess(String serviceName, String kmsKeyId) {
        addServiceAccess(serviceName, kmsKeyId, Region.US_EAST_2);
    }

    private static final String SERVICE_ACCESS_STATEMENT =
        " {\n" +
        "   \"Effect\": \"Allow\",\n" +
        "   \"Principal\": { \"Service\": \"%1$s.%2$s.amazonaws.com\" },\n" +
        "   \"Action\": [ \n" +
        "     \"kms:Encrypt*\",\n" +
        "     \"kms:Decrypt*\",\n" +
        "     \"kms:ReEncrypt*\",\n" +
        "     \"kms:GenerateDataKey*\",\n" +
        "     \"kms:Describe*\"\n" +
        "   ],\n" +
        "   \"Resource\": \"*\"\n" +
        " }";
    private final static ObjectMapper mapper = new ObjectMapper();
    protected void addServiceAccess(String serviceName, String kmsKeyId, Region region) {
        final CallChain.Initiator<KmsClient, ObjectNode, StdCallbackContext> initiator =
            this.serviceInvoker.rebindModel(mapper.createObjectNode());

        final ObjectNode statement = getServiceStatement(serviceName, region);
        getKeyPolicyAddServiceAccess(
            initiator, serviceName, region, "addServiceAccess",
            //
            // Check is service is not present in the policy
            //
            getKeyPolicyResponse -> !containsService(getKeyPolicyResponse.policy(), statement)).
            onSuccess(event -> {
                //
                // Add service statement
                //
                ObjectNode node = event.getResourceModel();
                JsonNode statements = node.at("/Statement");
                ArrayNode statementArray = (ArrayNode) statements;
                statementArray.add(statement);
                putInServicePolicy(event, initiator, "addServiceAccess");
                return event;
            });
    }

    protected void removeServiceAccess(String serviceName, String kmsKeyId, Region region) {
        final CallChain.Initiator<KmsClient, ObjectNode, StdCallbackContext> initiator =
            this.serviceInvoker.rebindModel(mapper.createObjectNode());
        final ObjectNode statement = getServiceStatement(serviceName, region);
        getKeyPolicyAddServiceAccess(
            initiator, serviceName, region,"removeServiceAccess",
            //
            // Check if the service was included in the policy
            //
            getKeyPolicyResponse -> containsService(getKeyPolicyResponse.policy(), statement))
        .onSuccess(event -> {
                ObjectNode node = event.getResourceModel();
                JsonNode statements = node.at("/Statement");
                ArrayNode statementArray = (ArrayNode) statements;
                ArrayNode replaced = mapper.createArrayNode();
                for (JsonNode each: statementArray) {
                    if (!each.equals(statement)) {
                        replaced.add(each);
                    }
                }
                node.replace("Statement", replaced);
                putInServicePolicy(ProgressEvent.success(node, event.getCallbackContext()), initiator,
                    "kms:PutKeyPolicy:removeServiceAccess");
                return ProgressEvent.success(node, initiator.getCallbackContext());
        });
    }

    private void putInServicePolicy(
        final ProgressEvent<ObjectNode, StdCallbackContext> policyEvent,
        final CallChain.Initiator<KmsClient, ObjectNode, StdCallbackContext> initiator,
        final String callGraph) {
        final ObjectNode policy = policyEvent.getResourceModel();
        final String inline = policy.toString();

        ProgressEvent<String, StdCallbackContext> progress = initiator.
            rebindModel(inline).
            initiate(callGraph).
            translateToServiceRequest(policyDoc -> PutKeyPolicyRequest.builder().
               keyId(kmsKeyId).
               policyName("default").
               policy(policyDoc).
               build()).
            makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::putKeyPolicy)).
            stabilize(
                stabilizeCurry(
                    callGraph + ":putInServicePolicy:stabilize",
                    (request, response, client, model, context) -> {
                        GetKeyPolicyRequest getKeyPolicyRequest = GetKeyPolicyRequest.builder()
                            .keyId(kmsKeyId)
                            .policyName("default")
                            .build();
                        GetKeyPolicyResponse getKeyPolicyResponse =
                            client.injectCredentialsAndInvokeV2(getKeyPolicyRequest, client.client()::getKeyPolicy);
                        ObjectNode retried = mapNode(getKeyPolicyResponse.policy());
                        return retried.equals(policy);
                    }
                )
            ).
            success();

        if (!progress.isSuccess()) {
            throw new RuntimeException("Error adding service credentials to policy " + progress.getMessage());
        }
    }

    private ProgressEvent<ObjectNode, StdCallbackContext> getKeyPolicyAddServiceAccess(
        CallChain.Initiator<KmsClient, ObjectNode, StdCallbackContext> initiator,
        final String serviceName,
        final Region region,
        final String callGraph,
        final Predicate<GetKeyPolicyResponse> check) {
        final GetKeyPolicyResponse[] finalGetKeyReponse = new GetKeyPolicyResponse[1];
        return initiator.
            initiate(callGraph).
            translateToServiceRequest(ign -> GetKeyPolicyRequest.builder().keyId(kmsKeyId).policyName("default").build()).
            makeServiceCall((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::getKeyPolicy)).
            stabilize(
                stabilizeCurry(
                    callGraph + ":stabilize",
                    (request, response, client, model, context) -> {
                        if (finalGetKeyReponse[0] == null) {
                            finalGetKeyReponse[0] = response;
                            return check.test(response);
                        }
                        finalGetKeyReponse[0] =
                            client.injectCredentialsAndInvokeV2(
                                GetKeyPolicyRequest.builder().keyId(kmsKeyId).policyName("default")
                                    .build(),
                                client.client()::getKeyPolicy
                            );
                        return check.test(finalGetKeyReponse[0]);
                    },
                    3
                )
            ).
            done(ignored ->
                ProgressEvent.success(mapNode(finalGetKeyReponse[0].policy()),
                    initiator.getCallbackContext())
            );
    }

    boolean containsService(String policy, ObjectNode serviceStatement) {
        ObjectNode node = mapNode(policy);
        JsonNode statements = node.at("/Statement");
        if (statements == null || statements instanceof MissingNode) {
            throw new RuntimeException("policy was malformed " + policy);
        }
        ArrayNode statementArray = (ArrayNode) statements;
        for (JsonNode each: statementArray) {
            ObjectNode svc = (ObjectNode) each;
            if (serviceStatement.equals(svc)) {
                //
                // Already present, return success
                //
                return true;
            }
        }
        return false;
    }

    ObjectNode mapNode(String policy) {
        try {
            return (ObjectNode) mapper.readTree(policy);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error processing policy " + e.getMessage(), e);
        }
    }

    ObjectNode getServiceStatement(String serviceName, Region region) {
        final String statement = String.format(SERVICE_ACCESS_STATEMENT, serviceName, region.id());
        return mapNode(statement);
    }

}
