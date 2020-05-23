## AWS CloudFormation Java Plugin Test Framework

This provides an easier foundation for testing handlers for CRUD along with integated support for KMS. Developers
can easily write sequence of CRUD lifecycle test with expectations and will be tested. There is also a mock based
test based for local unit testing.

The framework leverages support for [Named Profiles](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-profiles.html) that allows
developers to test using roles and credentials, to test the exact way in which they expect to work inside CloudFormation for their handlers. Here is the
sample for now this can be used for injecting credentials using the role based profile specified.

**Sample AWS Named Profile Setup**

```
 ~/.aws/credentials
 ...
 [cfn-assume-role]
 aws_access_key_id=[YOUR_ACCESS_KEY_ID]
 aws_secret_access_key=[YOUR_SECRET_ACCESS_KEY]
 ...

 ~/.aws/config
 [profile cfn-integration]
 role_arn = arn:aws:iam::<AWS_ACCOUNT_ID>:role/<ROLE_NAME>
 source_profile = cfn-assume-role
```

**Using the named profile for testing**

<b>How to setup IAM managed policies, user and role credentials for above setup</b>

<ol>
    <li><u>Create a Managed Policy for the user</u>
        Here the credentials section has an user credentials that is provided with only sts:assumeRole
        permission. Here is the policy that is associated with cfn-assume-role user in the account.
        <pre>
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Sid": "VisualEditor0",
                    "Effect": "Allow",
                    "Action": "sts:AssumeRole",
                    "Resource": "*"
                }
            ]
        }
        </pre>
    </li>
    <li><u>Create a Managed Policy for the services you are testing with</u>
            This is needed to test all integration for CRUD+L needed for logs. You can always narrow it down further.
            Recommended approach is to define the above policies as customer managed policies in IAM in the account and
            associate with the role and users as appropriate. This is an example policy to test, replace
            [INSERT_YOUR_SERVICE] with the service you are integrating with and need KMS integration. E.g.
            use _logs_ as the service name for integrating with CloudWatch Logs service.
         <pre>
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Sid": "VisualEditor0",
                        "Effect": "Allow",
                        "Action": [
                            "kms:*",
                            "[INSERT_YOUR_SERVICE]:*"
                        ],
                        "Resource": "*"
                    }
                ]
            }
         </pre>
     </li>
     <li><u>Create a user cfn-assume-role with Managed Policy create in Step (1)</u>
            Download the access_key, secret_key for this user and add it to the credentials file under
            cfn-assume-role
     </li>
     <li><u>Create cfn-integration role with the reference to the managed policy we created above.</u></li>
     <li><u>Update your poml.xml</u>
            Here is how to use this for unit testing. First add the dependency to you maven <u>pom.xml</u>
            <pre><code>
                &lt;!-- for sts support to assume role setup above --&gt;
                &lt;dependency&gt;
                    &lt;groupId&gt;software.amazon.awssdk&lt;/groupId&gt;
                    &lt;artifactId&gt;sts&lt;/artifactId&gt;
                    &lt;version&gt;2.10.91&lt;/version&gt;
                    &lt;scope&gt;test&lt;/scope&gt;
                &lt;/dependency&gt;
               &lt;!-- for kms key handling support --&gt;
               &lt;dependency&gt;
                    &lt;groupId&gt;software.amazon.awssdk&lt;/groupId&gt;
                    &lt;artifactId&gt;kms&lt;/artifactId&gt;
                    &lt;version&gt;2.10.91&lt;/version&gt;
               &lt;/dependency&gt;
                &lt;dependency&gt;
                    &lt;groupId&gt;software.amazon.cloudformation.test&lt;/groupId&gt;
                    &lt;artifactId&gt;cloudformation-cli-java-plugin-testing-support&lt;/artifactId&gt;
                    &lt;version&gt;1.0.0&lt;/version&gt;
                    &lt;scope&gt;test&lt;/scope&gt;
                &lt;/dependency&gt;
           </code></pre>
      </li>
</ol>

<b>How to use it?</b>
<p>
Sample code illustrating how to use this setup with KMS. To make scheduling the key for delete in case of abort to
testing the key is aliased using the alias name [KEY_ALIAS](src/main/java/software/amazon/cloudformation/test/KMSKeyEnabledServiceIntegrationTestBase.java)
The test when it runs to completion will automatically move the KMS key for delete. If test is rerun
the KMS key will be made active again for the duration of he test run and disable and scheduled to be deleted.
Regardless of how many times we run these tests there is only one key with the alias managed in the account.

To ensure that this test does not run for build environments like Travis etc. we enable is using system properties using
{@link org.junit.jupiter.api.condition.EnabledIfSystemProperty}. To run the test with maven we would
use

```sh
mvn -Ddesktop=true test
```

to run the test code shown below

```java

    package software.amazon.logs.loggroup;

    import org.junit.jupiter.api.*;
    import static org.assertj.core.api.Assertions.assertThat;

    import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
    import software.amazon.cloudformation.proxy.*;
    import software.amazon.cloudformation.test.*;

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class) // we order the tests to follows C to U to D
    @ExtendWith(InjectProfileCredentials.class) // extend with profile based credentials
    @EnabledIfSystemProperty(named = "desktop", matches = "true")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS) // IMP PER_CLASS
    public class LifecycleTest extends KMSKeyEnabledServiceIntegrationTestBase {

        //
        // At the annotation software.amazon.cloudformation.test.annotations.InjectSessionCredentials to the
        // constructor. This will inject the role's credentials
        //
        public LifecycleTest(@InjectSessionCredentials(profile = "cfn-integration") AwsSessionCredentials awsCredentials) {
             super(awsCredentials, ((apiCall, provided) -&gt; override));
        }
        ...
        ...
        @Order(300)
        @Test
        void addValidKMS() {
            final ResourceModel current = ResourceModel.builder().arn(model.getArn())
                .logGroupName(model.getLogGroupName()).retentionInDays(model.getRetentionInDays()).build();
            // Access a KMS key. The test ensures to only create one key and recycles despite any number of runs
            String kmsKeyId = getKmsKeyId();
            String kmsKeyArn = getKmsKeyArn();
            // Add your service to use KMS key
            addServiceAccess("logs", kmsKeyId);
            model.setKMSKeyArn(kmsKeyArn);
            ProgressEvent<ResourceModel, CallbackContext> event = new UpdateHandler()
                .handleRequest(getProxy(), createRequest(model, current), null, getLoggerProxy());
            assertThat(event.isSuccess()).isTrue();
            model = event.getResourceModel();
        }
        ...
        ...
    }
```

**Example Lifecycle Testing with KMS Support**

```java
@ExtendWith(InjectProfileCredentials.class)
@EnabledIfSystemProperty(named = "desktop", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CRUDLifecycleTest extends CRUDLifecycleTestBase<ResourceModel, CallbackContext> {

    private final ReadHandler readHandler = new ReadHandler();
    private final DeleteHandler deleteHandler = new DeleteHandler();
    private final UpdateHandler updateHandler = new UpdateHandler();
    private final CreateHandler createHandler = new CreateHandler();
    private final Map<Action, HandlerInvoke<ResourceModel, CallbackContext>> handlers =
        ImmutableMap.<Action, HandlerInvoke<ResourceModel, CallbackContext>>builder()
            .put(Action.CREATE, createHandler::handleRequest)
            .put(Action.READ, readHandler::handleRequest)
            .put(Action.UPDATE, updateHandler::handleRequest)
            .put(Action.DELETE, deleteHandler::handleRequest)
        .build();

    private final String logGroupName = "logGroup-TEST-DELETE-" + UUID.randomUUID().toString();

    static final Delay override = Constant.of().delay(Duration.ofSeconds(1))
        .timeout(Duration.ofSeconds(10)).build();
    public CRUDLifecycleTest(@InjectSessionCredentials(profile = "cfn-integ") AwsSessionCredentials sessionCredentials) {
        super(sessionCredentials, ((apiCall, provided) -> override));
    }

    @Override
    protected List<ResourceModels> testSeed() {
        final String kmsKeyId = getKmsKeyId();
        final String kmsKeyArn = getKmsKeyArn();
        List<ResourceModels> testGroups = new ArrayList<>(1);

        testGroups.add(
            newStepBuilder()
                .group("simple_normal")
                .create(ResourceModel.builder().logGroupName(logGroupName).build())
                .update(ResourceModel.builder().logGroupName(logGroupName).retentionInDays(7).build())
                .delete(ResourceModel.builder().logGroupName(logGroupName).build()));

        testGroups.add(
            newStepBuilder()
                .group("complex_with_failed_kms")
                .create(ResourceModel.builder().logGroupName(logGroupName).build())
                .createFail(ResourceModel.builder().logGroupName(logGroupName).build())
                .updateFail(ResourceModel.builder().logGroupName(logGroupName).retentionInDays(10).build())
                .update(ResourceModel.builder().logGroupName(logGroupName).retentionInDays(7).build())
                .updateFail(
                    ResourceModel.builder().logGroupName(logGroupName).retentionInDays(7)
                        .kMSKey("kmsKeyDoesNotExist").build())
                //
                // can not access logs service
                //
                .updateFail(() -> {
                    removeServiceAccess("logs", kmsKeyId, Region.US_EAST_2);
                    return ResourceModel.builder().logGroupName(logGroupName).retentionInDays(7)
                        .kMSKey(kmsKeyArn).build();
                })
                .update(() -> {
                    addServiceAccess("logs", kmsKeyId, Region.US_EAST_2);
                    return ResourceModel.builder().logGroupName(logGroupName).retentionInDays(7)
                        .kMSKey(kmsKeyArn).build();
                })
                .delete(ResourceModel.builder().logGroupName(logGroupName).build()));
        return testGroups;
    }

    @Override
    protected Map<Action, HandlerInvoke<ResourceModel, CallbackContext>> handlers() {
        return handlers;
    }

    @Override
    protected CallbackContext context() {
        return new CallbackContext();
    }
}

```

**See Also**

*Lifecycle Testing*

These classes use the annotation and named profiles described above to make testing against live AWS services easy.

[software.amazon.cloudformation.test.KMSKeyEnabledServiceIntegrationTestBase](src/main/java/software/amazon/cloudformation/test/KMSKeyEnabledServiceIntegrationTestBase.java)
[software.amazon.cloudformation.test.AbstractLifecycleTestBase](src/main/java/software/amazon/cloudformation/test/AbstractLifecycleTestBase.java)

*Local Unit Testing*

This class provides the step plumbing

[software.amazon.cloudformation.test.AbstractMockTestBase](src/main/java/software/amazon/cloudformation/test/AbstractMockTestBase.java)

## License

This project is licensed under the Apache-2.0 License.
