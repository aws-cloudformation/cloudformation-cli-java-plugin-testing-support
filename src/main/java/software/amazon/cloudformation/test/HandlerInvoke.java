package software.amazon.cloudformation.test;

import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.proxy.StdCallbackContext;

@FunctionalInterface
public interface HandlerInvoke<ModelT, CallbackT extends StdCallbackContext> {
    ProgressEvent<ModelT, CallbackT> handleRequest(
        final AmazonWebServicesClientProxy proxy,
        final ResourceHandlerRequest<ModelT> request,
        final CallbackT callbackContext,
        final Logger logger);
}
