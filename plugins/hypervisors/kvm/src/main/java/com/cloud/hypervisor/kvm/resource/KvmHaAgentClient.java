/*
 * Copyright 2021 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloud.hypervisor.kvm.resource;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.Host;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.kvm.ha.KVMHAConfig;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * This class provides a client that checks Agent status via a webserver.
 *
 * The additional webserver exposes a simple JSON API which returns a list
 * of Virtual Machines that are running on that host according to libvirt.
 *
 * This way, KVM HA can verify, via libvirt, VMs status with a HTTP-call
 * to this simple webserver and determine if the host is actually down
 * or if it is just the Java Agent which has crashed.
 */
public class KvmHaAgentClient {

    @Inject
    private ClusterDao clusterDao;
    @Inject
    private VMInstanceDao vmInstanceDao;

    private static final Logger LOGGER = Logger.getLogger(KvmHaAgentClient.class);
    private final static int WAIT_FOR_REQUEST_RETRY = 2;
    private final static String VM_COUNT = "count";
    private final static int ERROR_CODE = -1;
    private final static String EXPECTED_HTTP_STATUS = "2XX";
    private static final int MAX_REQUEST_RETRIES = 2;
    private Host agent;

    /**
     * Instantiates a webclient that checks, via a webserver running on the KVM host, the VMs running
     */
    public KvmHaAgentClient(Host agent) {
        this.agent = agent;
    }

    /**
     *  Returns the number of VMs running on the KVM host according to libvirt.
     */
    protected int countRunningVmsOnAgent() {
        String url = String.format("http://%s:%d", agent.getPrivateIpAddress(), getKvmHaMicroservicePortValue());
        HttpResponse response = executeHttpRequest(url);

        if (response == null)
            return ERROR_CODE;

        JsonObject responseInJson = processHttpResponseIntoJson(response);
        if (responseInJson == null) {
            return ERROR_CODE;
        }

        return responseInJson.get(VM_COUNT).getAsInt();
    }

    protected int getKvmHaMicroservicePortValue() {
        Integer haAgentPort = KVMHAConfig.KVM_HA_WEBSERVICE_PORT.value();
        if (haAgentPort == null) {
            ClusterVO cluster = clusterDao.findById(agent.getClusterId());
            LOGGER.warn(String.format("Using default kvm.ha.webservice.port: %s as it was set to NULL for cluster [id: %d, name: %s].", KVMHAConfig.KVM_HA_WEBSERVICE_PORT.defaultValue(), cluster.getId(), cluster.getName()));
            haAgentPort = Integer.parseInt(KVMHAConfig.KVM_HA_WEBSERVICE_PORT.defaultValue());
        }
        return haAgentPort;
    }

    /**
     * Checks if the KVM HA Webservice is enabled or not; if disabled then CloudStack ignores HA validation via the webservice.
     */
    public boolean isKvmHaWebserviceEnabled() {
        return KVMHAConfig.IS_KVM_HA_WEBSERVICE_ENABLED.value();
    }

    /**
     *  Returns true in case of the expected number of VMs matches with the VMs running on the KVM host according to Libvirt. <br><br>
     *
     *  IF: <br>
     *  (i) expected VMs running but listed 0 VMs: returns false as could not find VMs running but it expected at least one VM running, fencing/recovering host would avoid downtime to VMs in this case.<br>
     *  (ii) amount of listed VMs is different than expected: return true and print WARN messages so Admins can look closely to what is happening on the host
     */
    public boolean isKvmHaAgentHealthy(int expectedNumberOfVms) {
        int numberOfVmsOnAgent = countRunningVmsOnAgent();

        if (numberOfVmsOnAgent < 0) {
            LOGGER.error(String.format("KVM HA Agent health check failed, either the KVM Agent [%s] is unreachable or Libvirt validation failed", agent));
            return false;
        }
        if (expectedNumberOfVms == numberOfVmsOnAgent) {
            return true;
        }
        if (numberOfVmsOnAgent == 0) {
            // Return false as could not find VMs running but it expected at least one VM running, fencing/recovering host would avoid downtime to VMs in this case.
            LOGGER.warn(String.format("KVM HA Agent [%s] could not find running VMs; it was expected to list %d running VMs.", agent, expectedNumberOfVms));
            return false;
        }
        // In order to have a less "aggressive" health-check, the KvmHaAgentClient will not return false; fencing/recovering could bring downtime to existing VMs
        // Additionally, the inconsistency can also be due to jobs in progress to migrate/stop/start VMs
        // Either way, WARN messages should be presented to Admins so they can look closely to what is happening on the host
        LOGGER.warn(String.format("KVM HA Agent [%s] listed %d running VMs; however, it was expected %d running VMs.", agent, numberOfVmsOnAgent, expectedNumberOfVms));
        return true;
    }

    /**
     * Executes a GET request for the given URL address.
     */
    protected HttpResponse executeHttpRequest(String url) {
        HttpGet httpReq = prepareHttpRequestForUrl(url);
        if (httpReq == null) {
            return null;
        }

        HttpClient client = HttpClientBuilder.create().build();
        HttpResponse response = null;
        try {
            response = client.execute(httpReq);
        } catch (IOException e) {
            if (MAX_REQUEST_RETRIES == 0) {
                LOGGER.warn(String.format("Failed to execute HTTP %s request [URL: %s] due to exception %s.", httpReq.getMethod(), url, e), e);
                return null;
            }
            retryHttpRequest(url, httpReq, client);
        }
        return response;
    }

    @Nullable
    private HttpGet prepareHttpRequestForUrl(String url) {
        HttpGet httpReq = null;
        try {
            URIBuilder builder = new URIBuilder(url);
            httpReq = new HttpGet(builder.build());
        } catch (URISyntaxException e) {
            LOGGER.error(String.format("Failed to create URI for GET request [URL: %s] due to exception.", url), e);
            return null;
        }
        return httpReq;
    }

    /**
     * Re-executes the HTTP GET request until it gets a response or it reaches the maximum request retries {@link #MAX_REQUEST_RETRIES}
     */
    protected HttpResponse retryHttpRequest(String url, HttpRequestBase httpReq, HttpClient client) {
        LOGGER.warn(String.format("Failed to execute HTTP %s request [URL: %s]. Executing the request again.", httpReq.getMethod(), url));
        HttpResponse response = retryUntilGetsHttpResponse(url, httpReq, client);

        if (response == null) {
            LOGGER.error(String.format("Failed to execute HTTP %s request [URL: %s].", httpReq.getMethod(), url));
            return response;
        }

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode < HttpStatus.SC_OK || statusCode >= HttpStatus.SC_MULTIPLE_CHOICES) {
            throw new CloudRuntimeException(
                    String.format("Failed to get VMs information with a %s request to URL '%s'. The expected HTTP status code is '%s' but it got '%s'.", HttpGet.METHOD_NAME, url,
                            EXPECTED_HTTP_STATUS, statusCode));
        }

        LOGGER.debug(String.format("Successfully executed HTTP %s request [URL: %s].", httpReq.getMethod(), url));
        return response;
    }

    protected HttpResponse retryUntilGetsHttpResponse(String url, HttpRequestBase httpReq, HttpClient client) {
        for (int attempt = 1; attempt < MAX_REQUEST_RETRIES + 1; attempt++) {
            try {
                TimeUnit.SECONDS.sleep(WAIT_FOR_REQUEST_RETRY);
                LOGGER.debug(String.format("Retry HTTP %s request [URL: %s], attempt %d/%d.", httpReq.getMethod(), url, attempt, MAX_REQUEST_RETRIES));
                return client.execute(httpReq);
            } catch (IOException | InterruptedException e) {
                if (attempt == MAX_REQUEST_RETRIES) {
                    LOGGER.error(
                            String.format("Failed to execute HTTP %s request retry attempt %d/%d [URL: %s] due to exception %s", httpReq.getMethod(), attempt, MAX_REQUEST_RETRIES,
                                    url, e));
                } else {
                    LOGGER.error(
                            String.format("Failed to execute HTTP %s request retry attempt %d/%d [URL: %s] due to exception %s", httpReq.getMethod(), attempt, MAX_REQUEST_RETRIES,
                                    url, e));
                }
            }
        }
        return null;
    }

    /**
     * Processes the response of request GET System ID as a JSON object.<br>
     * Json example: {"count": 3, "virtualmachines": ["r-123-VM", "v-134-VM", "s-111-VM"]}<br><br>
     *
     * Note: this method can return NULL JsonObject in case HttpResponse is NULL.
     */
    protected JsonObject processHttpResponseIntoJson(HttpResponse response) {
        InputStream in;
        String jsonString;
        if (response == null) {
            return null;
        }
        try {
            in = response.getEntity().getContent();
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            jsonString = streamReader.readLine();
        } catch (UnsupportedOperationException | IOException e) {
            throw new CloudRuntimeException("Failed to process response", e);
        }

        return new JsonParser().parse(jsonString).getAsJsonObject();
    }
}
