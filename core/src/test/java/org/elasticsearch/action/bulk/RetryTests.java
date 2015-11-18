package org.elasticsearch.action.bulk;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.rest.NoOpClient;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.*;

public class RetryTests extends ESTestCase {
    // no need to wait fof a long time in tests
    private static final TimeValue DELAY = TimeValue.timeValueMillis(1L);
    private static final int CALLS_TO_FAIL = 5;

    private MockBulkClient bulkClient;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        this.bulkClient = new MockBulkClient(getTestName(), CALLS_TO_FAIL);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        this.bulkClient.close();
    }

    private BulkRequest createBulkRequest() {
        BulkRequest request = new BulkRequest();
        request.add(new UpdateRequest("shop", "products", "1"));
        request.add(new UpdateRequest("shop", "products", "2"));
        request.add(new UpdateRequest("shop", "products", "3"));
        request.add(new UpdateRequest("shop", "products", "4"));
        request.add(new UpdateRequest("shop", "products", "5"));
        return request;
    }

    public void testSyncRetryBacksOff() throws Exception {
        BackoffPolicy backoff = BackoffPolicy.constantBackoff(DELAY, CALLS_TO_FAIL);

        BulkRequest bulkRequest = createBulkRequest();
        BulkResponse response = Retry
                .on(EsRejectedExecutionException.class)
                .policy(backoff)
                .withSyncBackoff(bulkClient, bulkRequest);

        assertFalse(response.hasFailures());
        assertThat(response.getItems().length, equalTo(bulkRequest.numberOfActions()));
    }

    public void testSyncRetryFailsAfterBackoff() throws Exception {
        BackoffPolicy backoff = BackoffPolicy.constantBackoff(DELAY, CALLS_TO_FAIL - 1);

        BulkRequest bulkRequest = createBulkRequest();
        BulkResponse response = Retry
                .on(EsRejectedExecutionException.class)
                .policy(backoff)
                .withSyncBackoff(bulkClient, bulkRequest);

        assertTrue(response.hasFailures());
        assertThat(response.getItems().length, equalTo(bulkRequest.numberOfActions()));
    }

    public void testAsyncRetryBacksOff() throws Exception {
        BackoffPolicy backoff = BackoffPolicy.constantBackoff(DELAY, CALLS_TO_FAIL);
        AssertingListener listener = new AssertingListener();

        BulkRequest bulkRequest = createBulkRequest();
        Retry.on(EsRejectedExecutionException.class)
                .policy(backoff)
                .withAsyncBackoff(bulkClient, bulkRequest, listener);

        listener.awaitCallbacksCalled();
        listener.assertOnResponseCalled();
        listener.assertResponseWithoutFailures();
        listener.assertResponseWithNumberOfItems(bulkRequest.numberOfActions());
        listener.assertOnFailureNeverCalled();
    }

    public void testAsyncRetryFailsAfterBacksOff() throws Exception {
        BackoffPolicy backoff = BackoffPolicy.constantBackoff(DELAY, CALLS_TO_FAIL - 1);
        AssertingListener listener = new AssertingListener();

        BulkRequest bulkRequest = createBulkRequest();
        Retry.on(EsRejectedExecutionException.class)
                .policy(backoff)
                .withAsyncBackoff(bulkClient, bulkRequest, listener);

        listener.awaitCallbacksCalled();

        listener.assertOnResponseCalled();
        listener.assertResponseWithFailures();
        listener.assertResponseWithNumberOfItems(bulkRequest.numberOfActions());
        listener.assertOnFailureNeverCalled();
    }

    private static class AssertingListener implements ActionListener<BulkResponse> {
        private final CountDownLatch latch;
        private int countOnResponseCalled = 0;
        private Throwable lastFailure;
        private BulkResponse response;

        private AssertingListener() {
            latch = new CountDownLatch(1);
        }

        public void awaitCallbacksCalled() throws InterruptedException {
            latch.await();
        }

        @Override
        public void onResponse(BulkResponse bulkItemResponses) {
            latch.countDown();
            this.response = bulkItemResponses;
            countOnResponseCalled++;
        }

        @Override
        public void onFailure(Throwable e) {
            latch.countDown();
            this.lastFailure = e;
        }

        public void assertOnResponseCalled() {
            assertThat(countOnResponseCalled, equalTo(1));
        }

        public void assertResponseWithNumberOfItems(int numItems) {
            assertThat(response.getItems().length, equalTo(numItems));
        }

        public void assertResponseWithoutFailures() {
            assertThat(response, notNullValue());
            assertFalse("Response should not have failures", response.hasFailures());
        }

        public void assertResponseWithFailures() {
            assertThat(response, notNullValue());
            assertTrue("Response should have failures", response.hasFailures());
        }

        public void assertOnFailureNeverCalled() {
            assertThat(lastFailure, nullValue());
        }
    }

    private static class MockBulkClient extends NoOpClient {
        private int numberOfCallsToFail;

        private MockBulkClient(String testName, int numberOfCallsToFail) {
            super(testName);
            this.numberOfCallsToFail = numberOfCallsToFail;
        }

        @Override
        public ActionFuture<BulkResponse> bulk(BulkRequest request) {
            PlainActionFuture<BulkResponse> responseFuture = new PlainActionFuture<>();
            bulk(request, responseFuture);
            return responseFuture;
        }

        @Override
        public void bulk(BulkRequest request, ActionListener<BulkResponse> listener) {
            // do everything synchronously, that's fine for a test
            boolean shouldFail = numberOfCallsToFail > 0;
            numberOfCallsToFail--;

            BulkItemResponse[] itemResponses = new BulkItemResponse[request.requests().size()];
            // if we have to fail, we need to fail at least once "reliably", the rest can be random
            int itemToFail = randomInt(request.requests().size() - 1);
            for (int idx = 0; idx < request.requests().size(); idx++) {
                if (shouldFail && (randomBoolean() || idx == itemToFail)) {
                    itemResponses[idx] = failedResponse();
                } else {
                    itemResponses[idx] = successfulResponse();
                }
            }
            listener.onResponse(new BulkResponse(itemResponses, 1000L));
        }

        private BulkItemResponse successfulResponse() {
            return new BulkItemResponse(1, "update", new DeleteResponse());
        }

        private BulkItemResponse failedResponse() {
            return new BulkItemResponse(1, "update", new BulkItemResponse.Failure("test", "test", "1", new EsRejectedExecutionException("pool full")));
        }
    }
}
