/**
 * Copyright 2013 Matteo Caprari
 *
 *   Licensed under the Apache License, Version 2.0 (the "License"); 
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package httpfailover;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.GuardedBy;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Iterator;
import java.util.List;

/**
 * Extends {@link DefaultHttpClient} adding methods that allow to retry the same request
 * on multiple hosts.
 *
 * The retry logic is controlled by a {@link FailoverRetryHandler}.
 * If no handler is provided, {@link DefaultFailoverRetryHandler} is used.
 *
 * Please note that a request on a host may be retried multiple times, befoire hopping
 * to the next host in the list. This behaviour is controlled by the {@link org.apache.http.client.HttpRequestRetryHandler}
 * configured in {@link @DefaultHttpClient}.
 */
@SuppressWarnings("deprecation")
public class FailoverHttpClient extends DefaultHttpClient {

    private final Log log = LogFactory.getLog(getClass());

    /** the multi-target retry handler **/
    @GuardedBy("this")
    private FailoverRetryHandler multiTargetRetryHandler = null;

    private CloseableHttpClient mClient;

    /**
     * Creates a new HTTP client from parameters and a connection manager.
     *
     * @param params    the parameters
     * @param conman    the connection manager
     */
    public FailoverHttpClient(
            final ClientConnectionManager conman,
            final HttpParams params) {
        super(conman, params);
    }

    public FailoverHttpClient(
            final ClientConnectionManager conman) {
        super(conman, null);
    }


    public FailoverHttpClient(final HttpParams params) {
        super(null, params);
    }


    public FailoverHttpClient() {
        super(null, null);
    }
    
    
    public FailoverHttpClient(CloseableHttpClient aClient) {
        mClient = aClient;
    }


    public synchronized CloseableHttpClient getClient(){
        return mClient;
    }
    

    public synchronized FailoverRetryHandler getMultiTargetRetryHandler() {
        if (multiTargetRetryHandler == null) {
            multiTargetRetryHandler = new DefaultFailoverRetryHandler();
        }
        return multiTargetRetryHandler;
    }

    /**
     * Set a handler for determining if an HttpRequest should fail over a different host
     * @param handler the handler
     */
    public synchronized void setMultiTargetRetryHandler(FailoverRetryHandler handler) {
        this.multiTargetRetryHandler = handler;
    }

    /**
     * Tries to execute the request on all targets.
     * Each target failure is evaluated using the multiTargetRetryHandler.
     *
     * In case of non-retriable failure, the last exception is thrown.
     *
     * @param targets   the candidate target hosts for the request.
     *                  The request is executed on each hosts until one succeeds.
     * @param request   the request to execute
     * @return
     * @throws IOException
     * @throws ClientProtocolException
     */
    public HttpResponse execute(List<HttpHost> targets, HttpRequest request)
            throws IOException, ClientProtocolException {
        return execute(targets, request, (HttpContext) null);
    }

    /**
     * Tries to execute the request on all targets.
     * Each target failure is evaluated using the multiTargetRetryHandler.
     *
     * In case of non-retriable failure, the last exception is thrown.
     *
     * @param targets   the candidate target hosts for the request.
     *                  The request is executed on each hosts until one succeeds.
     * @param request   the request to execute
     * @param context the request-specific execution context,
     *                  or <code>null</code> to use a default context
     * @return
     * @throws IOException
     * @throws ClientProtocolException
     */
    public HttpResponse execute(List<HttpHost> targets, HttpRequest request, HttpContext context)
            throws IOException, ClientProtocolException {

        FailoverRetryHandler retryHandler = getMultiTargetRetryHandler();
        int executionCount = 1;
        while(true) {
            try {
                return executeMulti(targets, request, context);
            }
            catch(IOException ex) {
                if (executionCount >= retryHandler.getRetryCount()) {
                    throw  ex;
                }
                logRetry(ex);
            }
            executionCount++;
        }
    }

    /**
     * Executes a request using the default context and processes the
     * response using the given response handler. All target hosts are tried until one succeeds.
     *
     * @param request   the request to execute
     * @param responseHandler the response handler
     *
     * @return  the response object as generated by the response handler.
     * @throws IOException in case of a problem or the connection was aborted
     * @throws ClientProtocolException in case of an http protocol error
     */
    public <T> T execute(List<HttpHost> targets, HttpRequest request, ResponseHandler<? extends T> responseHandler)
            throws IOException, ClientProtocolException {
        return execute(targets, request, responseHandler, null);
    }

    /**
     * Executes a request using the default context and processes the
     * response using the given response handler.
     *
     * @param targets   the candidate target hosts for the request.
     *                  The request is executed on each hosts until one succeeds.
     * @param request   the request to execute
     * @param responseHandler the response handler
     * @param context   the context to use for the execution, or
     *                  <code>null</code> to use the default context
     *
     * @return  the response object as generated by the response handler.
     * @throws IOException in case of a problem or the connection was aborted
     * @throws ClientProtocolException in case of an http protocol error
     */
    public <T> T execute(List<HttpHost> targets, HttpRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context)
            throws IOException, ClientProtocolException {


        HttpResponse response = execute(targets, request, context);
        T result;
        try {
            result = responseHandler.handleResponse(response);
        } catch (Exception t) {
            HttpEntity entity = response.getEntity();
            try {
                EntityUtils.consume(entity);
            } catch (Exception t2) {
                // Log this exception. The original exception is more
                // important and will be thrown to the caller.
                this.log.warn("Error consuming content after an exception.", t2);
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new UndeclaredThrowableException(t);
        }

        // Handling the response was successful. Ensure that the content has
        // been fully consumed.
        HttpEntity entity = response.getEntity();
        EntityUtils.consumeQuietly(entity);

        return result;
    }

    private HttpResponse executeMulti(List<HttpHost> targets, HttpRequest request, HttpContext context)
            throws IOException, ClientProtocolException {

        if (context == null) {
            context = createHttpContext();
        }

        if (targets == null || targets.size() == 0) {
            throw new IllegalArgumentException("targets parameter may not be null or empty");
        }
        Iterator<HttpHost> iterator = targets.iterator();

        FailoverRetryHandler retryHandler = getMultiTargetRetryHandler();

        // note that at the last item in the iterator
        // the loop terminates either with return or throw
        while (true) {
            try {
                return execute(iterator.next(), request, context);
            }
            catch(IOException ex) {
                if(!iterator.hasNext() || !retryHandler.tryNextHost(ex, context)) {
                    throw ex;
                }
                logRetry(ex);
            }
        }
    }

    private void logRetry(IOException ex) {
        if (this.log.isWarnEnabled()) {
            this.log.warn("I/O exception ("+ ex.getClass().getName() +
                    ") caught when processing request: "
                    + ex.getMessage());
        }
        if (this.log.isDebugEnabled()) {
            this.log.debug(ex.getMessage(), ex);
        }
        this.log.info("Trying request on next target");
    }
    
    
    @Override
    public void close() {
        try {
            if (mClient != null) {
                mClient.close();
            } else {
                super.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public CloseableHttpResponse execute(HttpHost target, HttpRequest request, HttpContext context) throws IOException {
        if (mClient != null) {
            return mClient.execute(target, request, context);
        } else {
            return super.execute(target, request, context);
        }
    }

    @Override
    public CloseableHttpResponse execute(HttpUriRequest request, HttpContext context) throws IOException {
        if (mClient != null) {
            return mClient.execute(request, context);
        } else {
            return super.execute(request, context);
        }
    }

    @Override
    public CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
        if (mClient != null) {
            return mClient.execute(request);
        } else {
            return super.execute(request);
        }

    }

    @Override
    public CloseableHttpResponse execute(HttpHost target, HttpRequest request) throws IOException {
        if (mClient != null) {
            return mClient.execute(target, request);
        } else {
            return super.execute(target, request);
        }
    }

    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler) throws IOException {
        if (mClient != null) {
            return mClient.execute(request, responseHandler);
        } else {
            return super.execute(request, responseHandler);
        }
    }

    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException {
        if (mClient != null) {
            return mClient.execute(request, responseHandler, context);
        } else {
            return super.execute(request, responseHandler, context);
        }
    }

    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler) throws IOException {
        if (mClient != null) {
            return mClient.execute(target, request, responseHandler);
        } else {
            return super.execute(target, request, responseHandler);
        }
    }

    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException {
        if (mClient != null) {
            return mClient.execute(target, request, responseHandler, context);
        } else {
            return super.execute(target, request, responseHandler, context);
        }
    }

}
