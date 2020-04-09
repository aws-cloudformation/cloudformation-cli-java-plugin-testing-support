/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * Annotation that can used on method or constructor parameters to inject AWS
 * named profiles to be used for testing purposes. This ensures that we make
 * actual AWS service API calls and tests whether the resource handlers work as
 * designed. Here is the code on how to use {@link InjectProfileCredentials} and
 * {@link InjectSessionCredentials} with a junit5 test framework.
 *
 * <pre><code>
 *      &#064;TestMethodOrder(MethodOrderer.OrderAnnotation.class) // we order the tests to follow C to U to D
 *      &#064;ExtendWith(InjectProfileCredentials.class)
 *      &#064;EnabledIfSystemProperty(named = "desktop", matches = "true")
 *      &#064;TestInstance(TestInstance.Lifecycle.PER_CLASS) // IMP PER_CLASS
 *      public class LifecycleTest extends AbstractTestBase {
 *
 *      public LifecycleTest(&#064;InjectSessionCredentials(profile = "cfn-logs-integration") AWSCredentials credentials) {
 *           super(credentials);
 *      }
 * </code></pre>
 *
 *  To make the tests run only on our desktop/laptop use system properties to
 *  control how you run these test as they rely on local box credentials. For our regular
 *  pipelines we don't run these tests as the system properties are not enabled.
 *
 *  For Named Profiles see https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-profiles.html
 *  we recommend using roles for session credentials which mimic the way you integrate with CFN
 *
 * <pre>
 *     ~/.aws/credentials
 *
 *     [user]
 *     aws_access_key_id=[YOUR_ACCESS_KEY]
 *     aws_secret_access_key=[YOUR_SECRET_KEY]
 *
 *     ~/.aws/config
 *
 *     [profile kinesis]
 *     role_arn = arn:aws:iam::123456789012:role/kinesis
 *     source_profile = user
 *     region = us-west-2
 * </pre>
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
@Tag("AWSSessionCredentials")
public @interface InjectSessionCredentials {
    String profile();

    String homeDir() default "";
}
