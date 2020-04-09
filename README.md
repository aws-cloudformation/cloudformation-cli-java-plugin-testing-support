## AWS CloudFormation Java Plugin Test Framework

This provides an easier foundation for testing handlers for CRUD along with integated support for KMS. Developers 
can easily write sequence of CRUD lifecycle test with expectations and will be tested. There is also a mock based 
test based for local unit testing.

The framework leverages support for [Named Profiles](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-profiles.html) that allows 
developers to test using roles and credentials, to test the exact way in which they expect to work inside CFN for their handlers. Here is the
sample for now this can be used for injecting credentials using the role based profile specified. 

**Sample AWS Named Profile Setup**

 ~/.aws/credentials
 ...
 **cfn-assume-role**
 aws_access_key_id=[YOUR_ACCESS_KEY_ID]
 aws_secret_access_key=[YOUR_SECRET_ACCESS_KEY]
 ...
 
 ~/.aws/config
 [profilei **cfn-integration**]
 role_arn = arn:aws:iam::<AWS_ACCOUNT_ID>:role/<ROLE_NAME>
 source_profile = *cfn-assume-role*

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
            associate with the role and users as appropriate. This is an example policy to test CloudWatch LogGroup
            and KMS integration

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
     <li><u>Create a user cfn-assume-role with Managed Policy create in (1)</u>
            Download the access_key, secret_key for this user and add it to the credentials file under
            cfn-assume-role
     </li>
     <li><u>Create cfn-integration role with the con</u></li>
     <li><u>Update your poml.xml</u>
            Here is how to use this for unit testing. First add the dependency to you maven <u>pom.xml</u>

            <pre>{@code
                <!-- for sts support to assume role setup above -->
                <dependency>
                    <groupId>software.amazon.awssdk</groupId>
                    <artifactId>sts</artifactId>
                    <version>2.10.91</version>
                    <scope>test</scope>
                </dependency>

               <!-- for kms key handling support -->
               <dependency>
                    <groupId>software.amazon.awssdk</groupId>
                    <artifactId>kms</artifactId>
                    <version>2.10.91</version>
               </dependency>

                <dependency>
                    <groupId>software.amazon.cloudformation.test</groupId>
                    <artifactId>cloudformation-cli-java-plugin-testing-support</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <scope>test</scope>
                </dependency>
           }</pre>
      </li>
</ol>

<b>How to use it?</b>
<p>
Sample code illustrating how to use this setup with KMS. To make scheduling the key for delete in case of abort to
testing the key is aliased using the alias name [KEY_ALIAS](src/software/amazon/cloudformation/test/KMSKeyEnabledServiceIntegrationTestBase.java)
The test when it runs to completion will automatically move the KMS key for delete. If test is rerun
the KMS key will be made active again for the duration of he test run and disable and scheduled to be deleted.
Regardless of how many times we run these tests there is only one key with the alias managed in the account.

To ensure that this test does not run for build environments like Travis etc. we enable is using system properties using
{@link org.junit.jupiter.api.condition.EnabledIfSystemProperty}. To run the test with maven we would
use

```
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
            model.setKMSKey(kmsKeyArn);
            ProgressEvent&lt;ResourceModel, CallbackContext&gt; event = new UpdateHandler()
                .handleRequest(getProxy(), createRequest(model, current), null, getLoggerProxy());
            assertThat(event.isSuccess()).isTrue();
            model = event.getResourceModel();
        }
        ...
        ...
    }
```

**See Also**

software.amazon.cloudformation.test.KMSKeyEnabledServiceIntegrationTestBase
software.amazon.cloudformation.test.AbstractLifecycleTestBase


## License

This project is licensed under the Apache-2.0 License.
