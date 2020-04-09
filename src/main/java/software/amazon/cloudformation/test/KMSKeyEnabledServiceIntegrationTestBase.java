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
import software.amazon.awssdk.services.kms.model.EnableKeyRequest;
import software.amazon.awssdk.services.kms.model.GetKeyPolicyRequest;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.ListAliasesRequest;
import software.amazon.awssdk.services.kms.model.ListAliasesResponse;
import software.amazon.awssdk.services.kms.model.NotFoundException;
import software.amazon.awssdk.services.kms.model.PutKeyPolicyRequest;
import software.amazon.awssdk.services.kms.model.ScheduleKeyDeletionRequest;
import software.amazon.cloudformation.proxy.CallChain;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.StdCallbackContext;
import software.amazon.cloudformation.proxy.delay.Constant;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

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
    private final CallChain.Initiator<KmsClient, String, StdCallbackContext> initiator;
    protected KMSKeyEnabledServiceIntegrationTestBase(AwsSessionCredentials credentials) {
        this(credentials, DelayFactory.CONSTANT_DEFAULT_DELAY_FACTORY);
    }

    protected KMSKeyEnabledServiceIntegrationTestBase(AwsSessionCredentials sessionCredentials, DelayFactory factory) {
        super(sessionCredentials, factory);
        kmsClient = KmsClient.builder().build();
        initiator = getProxy().newInitiator(() -> kmsClient, KEY_ALIAS, new StdCallbackContext());
    }

    @AfterAll
    public void deleteKmsKey() {
        if (kmsKeyArn != null) {
            initiator.initiate("kms:ScheduleKeyDelete")
                .translate(ign -> ScheduleKeyDeletionRequest.builder()
                    .keyId(kmsKeyId)
                    .pendingWindowInDays(7) // this is the minimum
                    .build())
                .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::scheduleKeyDeletion))
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

    void describeAndEnableKey() {
        //
        // It really success but the Alias response does not return key ARN, it only returns
        // alias arn
        //
        initiator.initiate("kms:DescribeKey")
            .translate(ign -> DescribeKeyRequest.builder().keyId(kmsKeyId).build())
            .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::describeKey))
            .done(resp -> {
                kmsKeyArn = resp.keyMetadata().arn();
                Instant date = resp.keyMetadata().deletionDate();
                if (date != null) {
                    return ProgressEvent.progress(date.toString(), initiator.getCallbackContext());
                }
                return ProgressEvent.success(kmsKeyId, initiator.getCallbackContext());
            }).then(evt ->
                initiator.initiate("kms:CancelKeyDelete")
                    .translate(ign -> CancelKeyDeletionRequest.builder().keyId(kmsKeyId).build())
                    .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::cancelKeyDeletion))
                    .progress()
            ).then(evt ->
                initiator.initiate("kms:EnableKey")
                    .translate(ign -> EnableKeyRequest.builder().keyId(kmsKeyId).build())
                    .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::enableKey))
                    .success());
    }

    void discoverIfAlreadyExistsWithAlias() {
        ListAliasesResponse aliases = ListAliasesResponse.builder().build();
        final BiFunction<CallChain.Initiator<KmsClient, ListAliasesResponse, StdCallbackContext>,
            Integer,
            ProgressEvent<ListAliasesResponse, StdCallbackContext>> invoker =
            (initiator_, iteration) ->
                initiator_
                    .initiate("kms:ListAliases-" + iteration)
                    .translate(m -> ListAliasesRequest.builder().marker(m.nextMarker()).build())
                    .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::listAliases))
                    .done(response_ -> ProgressEvent.success(response_, initiator_.getCallbackContext()));
        int iterationCount = 0;
        do {
            CallChain.Initiator<KmsClient, ListAliasesResponse, StdCallbackContext> initiator =
                this.initiator.rebindModel(aliases);
            ProgressEvent<ListAliasesResponse, StdCallbackContext> result = invoker.apply(initiator, iterationCount);
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
            ++iterationCount;
        } while (kmsKeyId == null);
    }

    void createAndSetKmsKey() {
        ProgressEvent<String, StdCallbackContext> result = initiator.initiate("kms:CreateKey")
            .translate(m -> CreateKeyRequest.builder().keyUsage(KeyUsageType.ENCRYPT_DECRYPT).build())
            .backoff(Constant.of().delay(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(5 * 10)).build())
            .call((r, c) -> {
                CreateKeyResponse response = c.injectCredentialsAndInvokeV2(r, c.client()::createKey);
                kmsKeyId = response.keyMetadata().keyId();
                kmsKeyArn = response.keyMetadata().arn();
                return response;
            })
            .stabilize(
                (request, response, client, model, context_) -> {
                    try {
                        DescribeKeyRequest req = DescribeKeyRequest.builder().keyId(kmsKeyId).build();
                        client.injectCredentialsAndInvokeV2(req, client.client()::describeKey);
                    } catch (NotFoundException | DependencyTimeoutException e) {
                        return false;
                    }
                    return true;
                }
            )
            .progress()
            .then(event ->
                initiator.initiate("kms:CreateAlias")
                    .translate(ign -> CreateAliasRequest.builder().aliasName(KEY_ALIAS).targetKeyId(kmsKeyId).build())
                    .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::createAlias))
                    .progress()
            ).then(event ->
                initiator.initiate("kms:ListAliases:" + kmsKeyId)
                    .translate(ign -> ListAliasesRequest.builder().keyId(kmsKeyId).build())
                    .call((r, c) -> {
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
            this.initiator.rebindModel(mapper.createObjectNode());

        getKeyPolicyAddServiceAccess(initiator, serviceName, region)
            .then(event -> {
                ObjectNode policy = event.getResourceModel();
                String inline = policy.toString();
                ProgressEvent<String, StdCallbackContext> progress = initiator.rebindModel(inline)
                    .initiate("kms:PutKeyPolicy")
                    .translate(policyDoc -> PutKeyPolicyRequest.builder()
                        .keyId(kmsKeyId)
                        .policyName("default")
                        .policy(policyDoc)
                        .build())
                    .backoff(Constant.of().delay(Duration.ofSeconds(10)).timeout(Duration.ofSeconds(5 * 10)).build())
                    .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::putKeyPolicy))
                    .success();
                if (!progress.isSuccess()) {
                    throw new RuntimeException("Error adding service credentials to policy " + progress.getMessage());
                }
                return event;
            });
    }

    private ProgressEvent<ObjectNode, StdCallbackContext> getKeyPolicyAddServiceAccess(
        CallChain.Initiator<KmsClient, ObjectNode, StdCallbackContext> initiator,
        final String serviceName,
        final Region region) {
        final String statement = String.format(SERVICE_ACCESS_STATEMENT, serviceName, region.id());
        return initiator.initiate("kms:GetKeyPolicy")
            .translate(ign -> GetKeyPolicyRequest.builder().keyId(kmsKeyId).policyName("default").build())
            .call((r, c) -> c.injectCredentialsAndInvokeV2(r, c.client()::getKeyPolicy))
            .done(resp -> {
                String policy = resp.policy();
                try {
                    ObjectNode node = (ObjectNode) mapper.readTree(policy);
                    JsonNode statements = node.at("/Statement");
                    if (statements == null || statements instanceof MissingNode) {
                        throw new RuntimeException("policy was malformed " + policy);
                    }
                    ArrayNode statementArray = (ArrayNode) statements;
                    ObjectNode serviceStatement = (ObjectNode) mapper.readTree(statement);
                    for (JsonNode each: statementArray) {
                        ObjectNode svc = (ObjectNode) each;
                        if (serviceStatement.equals(svc)) {
                            //
                            // Already present, return success
                            //
                            return ProgressEvent.success(node, initiator.getCallbackContext());
                        }
                    }
                    //
                    // Else ass the service statement and proceed to next step as progress
                    //
                    statementArray.add(serviceStatement);
                    return ProgressEvent.progress(node, initiator.getCallbackContext());
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("policy was not valid JSON " + policy, e);
                }
            });
    }

}
