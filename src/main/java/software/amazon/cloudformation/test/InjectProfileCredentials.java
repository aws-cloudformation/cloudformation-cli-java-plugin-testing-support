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

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;

public class InjectProfileCredentials implements ParameterResolver {

    private final ExtensionContext.Namespace namespace = ExtensionContext.Namespace.create("AWS.Session.Credentials");

    private final String homeDir;

    public InjectProfileCredentials() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            userHome = System.getenv("HOME");
        }
        if (userHome == null) {
            throw new RuntimeException("Can not determine user HOME directory");
        }
        this.homeDir = userHome;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
        boolean canResolve = parameterContext.isAnnotated(InjectSessionCredentials.class);
        Class<?> type = parameterContext.getParameter().getType();
        if (AwsCredentials.class.isAssignableFrom(type)) {
            return canResolve;
        }
        throw new ParameterResolutionException("Found annotation " + InjectSessionCredentials.class.getName() + " on parameter "
            + parameterContext.getParameter().getName() + " But type isn't supported" + type);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
        return parameterContext.findAnnotation(InjectSessionCredentials.class).map(anno -> {
            Class<?> type = parameterContext.getParameter().getType();
            String dir = anno.homeDir() == null || anno.homeDir().isEmpty() ? this.homeDir : anno.homeDir();
            String profile = anno.profile();
            if (profile.isEmpty()) {
                throw new ParameterResolutionException("Specify a profile name to use for "
                    + parameterContext.getParameter().getName() + " for test " + extensionContext.getRequiredTestInstance());
            }
            return getSessionCredentials(dir, profile, parameterContext, extensionContext);
        }).orElseThrow(
            () -> new ParameterResolutionException("Can not handle injecting parameter " + parameterContext.getParameter()));
    }

    private AwsSessionCredentials
        getSessionCredentials(String dir, String profileName, ParameterContext paramCxt, ExtensionContext extnCxt)
            throws ParameterResolutionException {
        FileSystem fs = FileSystems.getDefault();
        return (AwsSessionCredentials) extnCxt.getStore(namespace).getOrComputeIfAbsent("aws.session.credentials", (key) -> {
            AwsCredentialsProvider provider = (AwsCredentialsProvider) extnCxt.getStore(namespace).getOrComputeIfAbsent(
                "session.provider",
                (key1) -> {
                    ProfileFile file =  ProfileFile.aggregator().addFile(
                        ProfileFile.builder().content(fs.getPath(dir, ".aws/config"))
                            .type(ProfileFile.Type.CONFIGURATION).build()
                    ).addFile(
                        ProfileFile.builder().content(fs.getPath(dir, ".aws/credentials"))
                            .type(ProfileFile.Type.CREDENTIALS).build()
                    ).build();

                    Profile profile = file.profile(profileName)
                        .orElseThrow(() -> new RuntimeException("Can not find aws profile" + profileName));
                    return profile.property("role_arn")
                        .flatMap(arn -> createRoleArnProvider(arn, profile, file))
                        .orElseThrow(() -> new RuntimeException("Can not load role_arn, source_profile properties on profile " + profileName));
                }

            );
            return (AwsSessionCredentials) provider.resolveCredentials();
        });
    }

    private Optional<AwsCredentialsProvider> createRoleArnProvider(final String arn,
                                                                   final Profile profile,
                                                                   final ProfileFile file) {

        return profile.property("source_profile")
            .flatMap(src ->
                file.profile(src).flatMap(
                    p -> p.property("aws_access_key_id")
                        .flatMap(access_key -> p.property("aws_secret_access_key")
                            .map(secret_key -> {
                                String region =
                                    profile.property("region").orElseGet(() ->
                                        p.property("region").orElse(null));
                                StsClientBuilder builder = StsClient.builder();
                                builder.credentialsProvider(
                                    StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(access_key, secret_key)));
                                if (region != null) {
                                    builder.region(Region.of(region));
                                }
                                final StsClient sts = builder.build();
                                return (AwsCredentialsProvider) () -> {
                                    Credentials credentials = sts.assumeRole(
                                        AssumeRoleRequest.builder()
                                            .roleArn(arn)
                                            .roleSessionName(UUID.randomUUID().toString())
                                            .build()
                                    ).credentials();
                                    return AwsSessionCredentials.create(
                                        credentials.accessKeyId(),
                                        credentials.secretAccessKey(),
                                        credentials.sessionToken());
                                };
                            }))));
    }

}
