/**
 * The purpose of this package is to make local testing as painless as possible. It also
 * provides a each model to completely test the lifecycle CRUDL for a resource in unit testing.
 * This makes debugging integration issues easy inside your IDE of choice. For making actual service
 * calls we use AWS cli's named profiles model using roles. This mimics the type of credentials
 * you handlers will receive during actual calls.
 *
 * For named profiles setup please read
 * <a href="https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-role.html">Named Profiles</a>.
 *
 * The recommended approach to is as shown below
 * <p>
 * <b>Sample example of AWS CLI setup</b>
 *
 * <pre>
 *     ~/.aws/credentials
 *     ...
 *     [<b>cfn-assume-role</b>]
 *     aws_access_key_id=[YOUR_ACCESS_KEY_ID]
 *     aws_secret_access_key=[YOUR_SECRET_ACCESS_KEY]
 *     ...
 *
 *     ~/.aws/config
 *     [profile <b>cfn-integration</b>]
 *     role_arn = arn:aws:iam::0123456789012:role/cfn-integration
 *     source_profile = cfn-assume-role
 *</pre>
 *
 * <b>How to setup IAM managed policies, user and role credentials for above setup</b>
 *
 * <ol>
 *     <li><u>Create a Managed Policy for the user</u>
 *         Here the credentials section has an user credentials that is provided with only sts:assumeRole
 *         permission. Here is the policy that is associated with cfn-assume-role user in the account.
 *
 *         <pre>
 *         {
 *             "Version": "2012-10-17",
 *             "Statement": [
 *                 {
 *                     "Sid": "VisualEditor0",
 *                     "Effect": "Allow",
 *                     "Action": "sts:AssumeRole",
 *                     "Resource": "*"
 *                 }
 *             ]
 *         }
 *         </pre>
 *     </li>
 *     <li><u>Create a Managed Policy for the services you are testing with</u>
 *             This is needed to test all integration for CRUD+L needed for logs. You can always narrow it down further.
 *             Recommended approach is to define the above policies as customer managed policies in IAM in the account and
 *             associate with the role and users as appropriate. This is an example policy to test CloudWatch LogGroup
 *             and KMS integration
 *
 *          <pre>
 *             {
 *                 "Version": "2012-10-17",
 *                 "Statement": [
 *                     {
 *                         "Sid": "VisualEditor0",
 *                         "Effect": "Allow",
 *                         "Action": [
 *                             "kms:*",
 *                             "[INSERT_YOUR_SERVICE]:*"
 *                         ],
 *                         "Resource": "*"
 *                     }
 *                 ]
 *             }
 *          </pre>
 *      </li>
 *      <li><u>Create a user cfn-assume-role with Managed Policy create in (1)</u>
 *             Download the access_key, secret_key for this user and add it to the credentials file under
 *             cfn-assume-role
 *      </li>
 *      <li><u>Create cfn-integration role with the reference to the managed policy we created above.</u></li>
 *      <li><u>Update your poml.xml</u>
 *             Here is how to use this for unit testing. First add the dependency to you maven <u>pom.xml</u>
 *
 *             <pre>{@code
 *                 <!-- for sts support to assume role setup above -->
 *                 <dependency>
 *                     <groupId>software.amazon.awssdk</groupId>
 *                     <artifactId>sts</artifactId>
 *                     <version>2.10.91</version>
 *                     <scope>test</scope>
 *                 </dependency>
 *
 *                <!-- for kms key handling support -->
 *                <dependency>
 *                     <groupId>software.amazon.awssdk</groupId>
 *                     <artifactId>kms</artifactId>
 *                     <version>2.10.91</version>
 *                </dependency>
 *
 *                 <dependency>
 *                     <groupId>software.amazon.cloudformation.test</groupId>
 *                     <artifactId>cloudformation-cli-java-plugin-testing-support</artifactId>
 *                     <version>1.0-SNAPSHOT</version>
 *                     <scope>test</scope>
 *                 </dependency>
 *            }</pre>
 *       </li>
 * </ol>
 *
 * <b>How to use it?</b>
 * <p>
 * Sample code illustrating how to use this setup with KMS. To make scheduling the key for delete in case of abort to
 * testing the key is aliased using the alias name {@link software.amazon.cloudformation.test.KMSKeyEnabledServiceIntegrationTestBase#KEY_ALIAS}
 * The test when it runs to completion will automatically move the KMS key for delete. If test is rerun
 * the KMS key will be made active again for the duration of he test run and disable and scheduled to be deleted.
 * Regardless of how many times we run these tests there is only one key with the alias managed in the account.
 *
 * To ensure that this test does not run for build environments we Enable is using system properties using
 * {@link org.junit.jupiter.api.condition.EnabledIfSystemProperty}. To run the same shown below with maven we would
 * use
 *
 * <pre>
 *     mvn -Ddesktop=true test
 * </pre>
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
 *     &#064;TestMethodOrder(MethodOrderer.OrderAnnotation.class) // we order the tests to follows C to U to D
 *     <b>&#064;ExtendWith(InjectProfileCredentials.class)</b> // extend with profile based credentials
 *     &#064;EnabledIfSystemProperty(named = "desktop", matches = "true")
 *     &#064;TestInstance(TestInstance.Lifecycle.PER_CLASS) // IMP PER_CLASS
 *     public class LifecycleTest extends KMSKeyEnabledServiceIntegrationTestBase {
 *
 *         //
 *         // At the annotation software.amazon.cloudformation.test.annotations.InjectSessionCredentials to the
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
 *
 * @see software.amazon.cloudformation.test.KMSKeyEnabledServiceIntegrationTestBase
 * @see software.amazon.cloudformation.test.AbstractLifecycleTestBase
 */
package software.amazon.cloudformation.test;
