package software.amazon.cloudformation.test;

import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

import java.time.Duration;

/**
 * Abstract base class that does some of the mocking and proxy setup that you need for
 * testing each of your handlers consistently in order for CRUD + L
 */
@lombok.Getter
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractLifecycleTestBase {

    private final LoggerProxy loggerProxy = new LoggerProxy() {{
        addLogPublisher(new LogPublisher(m -> m) {
            private final Logger logger = LoggerFactory.getLogger(getClass().getSimpleName());
            @Override
            protected void publishMessage(String message) {
                logger.info(message);
            }
        });
    }};
    private final AwsSessionCredentials sessionCredentials;
    private final AmazonWebServicesClientProxy proxy;

    protected AbstractLifecycleTestBase(final AwsSessionCredentials credentials) {
        this(credentials, DelayFactory.CONSTANT_DEFAULT_DELAY_FACTORY);
    }

    protected AbstractLifecycleTestBase(final AwsSessionCredentials sessionCredentials,
                                        final DelayFactory factory) {
        this.sessionCredentials = sessionCredentials;
        proxy = new AmazonWebServicesClientProxy(
            loggerProxy,
            new Credentials(
                sessionCredentials.accessKeyId(),
                sessionCredentials.secretAccessKey(),
                sessionCredentials.sessionToken()),
            () -> Duration.ofMinutes(15).toMillis(),
            factory);
    }

    protected <T> ResourceHandlerRequest<T> createRequest(T model) {
        return createRequest(model, null);
    }

    protected <T> ResourceHandlerRequest<T> createRequest(T model,
                                                          T current) {
        return ResourceHandlerRequest.<T>builder().desiredResourceState(model)
            .previousResourceState(current).build();
    }


}
