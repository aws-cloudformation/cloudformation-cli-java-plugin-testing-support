package software.amazon.cloudformation.test;

import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.ProxyClient;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

@lombok.Getter
public class AbstractMockTestBase<CLIENT extends SdkClient> {

    protected final AwsSessionCredentials awsSessionCredential;
    protected final AwsCredentialsProvider v2CredentialsProvider;
    protected final AwsRequestOverrideConfiguration configuration;
    protected final LoggerProxy loggerProxy;
    protected final CLIENT serviceClient;
    protected final Supplier<Long> awsLambdaRuntime = () -> Duration.ofMinutes(15).toMillis();
    protected final AmazonWebServicesClientProxy proxy;
    protected final Credentials mockCredentials =
        new Credentials("mockAccessId", "mockSecretKey", "mockSessionToken");
    protected AbstractMockTestBase(Class<CLIENT> service) {
        serviceClient = Mockito.mock(service);
        loggerProxy = Mockito.mock(LoggerProxy.class);
        awsSessionCredential = AwsSessionCredentials.create(mockCredentials.getAccessKeyId(),
            mockCredentials.getSecretAccessKey(), mockCredentials.getSessionToken());
        v2CredentialsProvider = StaticCredentialsProvider.create(awsSessionCredential);
        configuration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider)
            .build();
        proxy = new AmazonWebServicesClientProxy(
            loggerProxy,
            mockCredentials,
            awsLambdaRuntime
        ) {
            @Override
            public <ClientT> ProxyClient<ClientT> newProxy(@Nonnull Supplier<ClientT> client) {
                return new ProxyClient<ClientT>() {
                    @Override
                    public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                        ResponseT injectCredentialsAndInvokeV2(RequestT request,
                                                               Function<RequestT, ResponseT> requestFunction) {
                        return proxy.injectCredentialsAndInvokeV2(request, requestFunction);
                    }

                    @Override
                    public <RequestT extends AwsRequest, ResponseT extends AwsResponse> CompletableFuture<ResponseT>
                        injectCredentialsAndInvokeV2Async(RequestT request, Function<RequestT, CompletableFuture<ResponseT>> requestFunction) {
                        return proxy.injectCredentialsAndInvokeV2Async(request, requestFunction);
                    }

                    @Override
                    public <RequestT extends AwsRequest, ResponseT extends AwsResponse, IterableT extends SdkIterable<ResponseT>>
                        IterableT
                        injectCredentialsAndInvokeIterableV2(RequestT request, Function<RequestT, IterableT> requestFunction) {
                        return proxy.injectCredentialsAndInvokeIterableV2(request, requestFunction);
                    }

                    @SuppressWarnings("unchecked")
                    @Override
                    public ClientT client() {
                        return (ClientT) serviceClient;
                    }
                };
            }
        };
    }

    protected static <T extends AwsServiceException, EB extends AwsServiceException.Builder>
        T
        make(final EB builder,
             final int status,
             final String message,
             final Class<T> type)
    {
        return type.cast(builder
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorMessage(message)
                    .errorCode(String.valueOf(status))
                    .sdkHttpResponse(
                        new SdkHttpResponse() {
                            @Override
                            public Optional<String> statusText() {
                                return Optional.empty();
                            }

                            @Override
                            public int statusCode() {
                                return status;
                            }

                            @Override
                            public Map<String, List<String>> headers() {
                                return Collections.emptyMap();
                            }

                            @Override
                            public Builder toBuilder() {
                                return null;
                            }
                        }
                    )
                    .build()
            )
            .build());
    }

    protected static
        <T extends AwsRequest>
        ArgumentMatcher<T> argCmp(final T incoming) {
        return incoming::equalsBySdkFields;
    }
}
