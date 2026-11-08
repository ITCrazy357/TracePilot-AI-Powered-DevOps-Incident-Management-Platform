package com.tracepilot.github;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

// Bound memory even without Content-Length. Completing after the full body lets
// the API client apply one deadline to the complete HTTP exchange.
final class LimitedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int maxBytes;
    private Flow.Subscription subscription;
    private long receivedBytes;

    LimitedResponseBody(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            receivedBytes += buffer.remaining();
        }
        if (receivedBytes > maxBytes) {
            subscription.cancel();
            delegate.onError(new IOException("GitHub response exceeds the configured limit."));
            return;
        }
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }
}
