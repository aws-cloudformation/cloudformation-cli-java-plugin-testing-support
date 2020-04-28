package software.amazon.cloudformation.test;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.cloudformation.Action;
import software.amazon.cloudformation.exceptions.ResourceNotFoundException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.DelayFactory;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.StdCallbackContext;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@NotThreadSafe
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class CRUDLifecycleTestBase<ResourceModelT, CallbackT extends StdCallbackContext> extends KMSKeyEnabledServiceIntegrationTestBase {

    public CRUDLifecycleTestBase(AwsSessionCredentials sessionCredentials, DelayFactory factory) {
        super(sessionCredentials, factory);
    }

    @lombok.EqualsAndHashCode
    @lombok.ToString
    public final class ModelStepExpectation {
        private final Action action;
        private final OperationStatus status;
        private final HandlerErrorCode errorCode;
        private final Supplier<ResourceModelT> model;
        private ResourceModelT resolvedModel;
        public ModelStepExpectation(Action action,
                                    OperationStatus status,
                                    HandlerErrorCode errorCode,
                                    Supplier<ResourceModelT> model) {
            this.action = action;
            this.status = status;
            this.errorCode = errorCode;
            this.model = model;
        }

        public ResourceModelT getModel() {
            if (resolvedModel == null) {
                resolvedModel = model.get();
            }
            return resolvedModel;
        }

     }

    /**
     * Provides a dynamic set of resource models that will used to seed the Create+, Read+, Update+
     * and Delete cycles to test
     */
    @lombok.AllArgsConstructor
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public final class ResourceModels {
        private final List<ModelStepExpectation> modelSteps;
        private final String group;
        private final Map<Action, HandlerInvoke<ResourceModelT, CallbackT>> invokers;
    }

    protected Builder newStepBuilder() {
        return new Builder();
    }

    public final class Builder {
        private final List<ModelStepExpectation> modelSteps = new ArrayList<>(10);
        private String group = "default";
        private final Map<Action, HandlerInvoke<ResourceModelT, CallbackT>> invokers = new HashMap<>(handlers());

        public Builder() {}

        public Builder create(ResourceModelT model) {
            return create(() -> model);
        }

        public Builder create(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.CREATE, OperationStatus.SUCCESS, null, model));
        }

        public Builder createFail(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.CREATE, OperationStatus.FAILED, null, model));
        }

        public Builder createFail(ResourceModelT model) {
            return createFail(() -> model);
        }

        public Builder update(ResourceModelT model) {
            return update(() -> model);
        }

        public Builder update(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.UPDATE, OperationStatus.SUCCESS, null, model));
        }

        public Builder updateFail(ResourceModelT model) {
            return updateFail(() -> model);
        }

        public Builder updateFail(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.UPDATE, OperationStatus.FAILED, null, model));
        }

        public ResourceModels delete(ResourceModelT model) {
            return delete(() -> model);
        }

        public ResourceModels delete(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.DELETE, OperationStatus.SUCCESS, null, model)).build();
        }

        public Builder deleteFailed(ResourceModelT model) {
            return deleteFailed(() -> model);
        }

        public Builder deleteFailed(Supplier<ResourceModelT> model) {
            return add(new ModelStepExpectation(
                Action.DELETE, OperationStatus.FAILED, null, model));
        }

        private Builder add(ModelStepExpectation modelStep) {
            this.modelSteps.add(modelStep);
            return this;
        }

        public Builder group(String grp) {
            this.group = grp;
            return this;
        }

        public Builder action(Action action, HandlerInvoke<ResourceModelT, CallbackT> invoker) {
            this.invokers.put(action, invoker);
            return this;
        }

        public ResourceModels build() {
            return new ResourceModels(modelSteps, group, invokers);
        }
    }

    protected abstract List<ResourceModels> testSeed();

    protected abstract Map<Action, HandlerInvoke<ResourceModelT, CallbackT>> handlers();

    protected abstract CallbackT context();

    @TestFactory
    final List<DynamicTest> CRUD_Tests() {
        List<DynamicTest> tests_ = Objects.requireNonNull(testSeed()).stream()
            .flatMap(models -> {
                final Map<Action, HandlerInvoke<ResourceModelT, CallbackT>> handlers = models.getInvokers();
                final List<ModelStepExpectation> expectations = models.getModelSteps();
                final ModelStepExpectation first = expectations.get(0);
                final CallbackT context = null;
                final AmazonWebServicesClientProxy proxy = getProxy();
                final LoggerProxy logger = getLoggerProxy();

                final List<DynamicTest> tests = new ArrayList<>(expectations.size());
                // READ first should fail
                tests.add(
                    DynamicTest.dynamicTest(
                        models.getGroup() + "_read_before_create_fails", () -> {
                            try {
                                ProgressEvent<ResourceModelT, CallbackT> event =
                                    handlers.get(Action.READ).handleRequest(
                                        proxy,
                                        createRequest(first.model.get()),
                                        context,
                                        logger
                                    );
                                assertThat(event).isNotNull();
                                assertThat(event.isFailed()).isTrue();
                            } catch (ResourceNotFoundException e) {
                                // expected
                                e.printStackTrace();
                            }
                        }
                    ));

                int testOrderNumber = 1;
                final AtomicReference<ResourceModelT> currentState = new AtomicReference<>(null);
                final AtomicReference<ProgressEvent<ResourceModelT, CallbackT>>
                    lastRun = new AtomicReference<>(null);
                for (ModelStepExpectation step: models.getModelSteps()) {
                    tests.add(
                        DynamicTest.dynamicTest(
                            models.getGroup() + "_on_" + step.action + "_expected_" + step.status +
                                "_" + testOrderNumber, () -> {
                                ProgressEvent<ResourceModelT, CallbackT> event =
                                    handlers.get(step.action).handleRequest(
                                        proxy,
                                        createRequest(step.getModel(), currentState.get()),
                                        context,
                                        logger
                                    );

                                assertThat(event).isNotNull();
                                assertThat(event.getStatus()).isEqualTo(step.status);

                                lastRun.set(event);
                                if (event.isSuccess()) {
                                    currentState.set(event.getResourceModel());
                                }
                            }
                        )
                    );
                    //
                    // https://junit.org/junit5/docs/5.0.2/api/org/junit/jupiter/api/DynamicTest.html. This is verifying
                    // that reading the state after Create(C)/Update(U) operation is consistent with the desired state that we
                    // sent in. If the C/U is expected to fail, then we check to see that the previous desired state is
                    // indeed equal. The other thing this forces is it ensures that C/U handlers do delegate to Read(R)
                    // handlers post application of configuration. This prevents the set of bugs like missing primary
                    // identifiers etc. that R always provides ensuring all read-only attributes are being communicated
                    // post a C/U operation.
                    //
                    if (step.action != Action.DELETE) {
                        tests.add(
                            //
                            // Read the state back and ensure they coincide. The the action failed
                            // the current state is not updated and it should still be the same
                            //
                            DynamicTest.dynamicTest(
                                models.getGroup() + "_read_after_" + step.action + "_with_status_"
                                    + step.status + "_" + testOrderNumber, () -> {
                                    ProgressEvent<ResourceModelT, CallbackT> event =
                                        handlers.get(Action.READ).handleRequest(
                                            proxy,
                                            createRequest(step.getModel()),
                                            context,
                                            logger
                                        );
                                    assertThat(event).isNotNull();
                                    assertThat(event.isSuccess()).isTrue();
                                    assertThat(event.getResourceModel()).isEqualTo(currentState.get());
                                }
                            ));
                    }
                    ++testOrderNumber;
                }
                return tests.stream();
            }).collect(Collectors.toList());
        return tests_;
    }


}
