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

import com.cloud.host.HostVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicStatusLine;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(MockitoJUnitRunner.class)
public class KvmHaAgentClientTest {

    private static final int ERROR_CODE = -1;
    private HostVO agent = Mockito.mock(HostVO.class);
    private KvmHaAgentClient kvmHaAgentClient = Mockito.spy(new KvmHaAgentClient(agent));
    private static final int DEFAULT_PORT = 8080;
    private static final String PRIVATE_IP_ADDRESS = "1.2.3.4";
    private static final String JSON_STRING_EXAMPLE_3VMs = "{\"count\": 3, \"virtualmachines\": [\"r-123-VM\", \"v-134-VM\", \"s-111-VM\"]}";
    private static final int EXPECTED_RUNNING_VMS_EXAMPLE_3VMs = 3;
    private static final String JSON_STRING_EXAMPLE_0VMs = "{\"count\": 0, \"virtualmachines\": []}";
    private static final int EXPECTED_RUNNING_VMS_EXAMPLE_0VMs = 0;
    private static final String EXPECTED_URL = String.format("http://%s:%d", PRIVATE_IP_ADDRESS, DEFAULT_PORT);
    private static final HttpRequestBase HTTP_REQUEST_BASE = new HttpGet(EXPECTED_URL);
    private static final int MAX_REQUEST_RETRIES = 2;

    @Mock
    HttpClient client;

    @Mock
    HttpResponse httpResponse;

    @Test
    public void isKvmHaAgentHealthyTestAllGood() {
        boolean result = isKvmHaAgentHealthyTests(EXPECTED_RUNNING_VMS_EXAMPLE_3VMs, EXPECTED_RUNNING_VMS_EXAMPLE_3VMs);
        Assert.assertTrue(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestVMsDoNotMatchButDoNotReturnFalse() {
        boolean result = isKvmHaAgentHealthyTests(EXPECTED_RUNNING_VMS_EXAMPLE_3VMs, 1);
        Assert.assertTrue(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestExpectedRunningVmsButNoneListed() {
        boolean result = isKvmHaAgentHealthyTests(EXPECTED_RUNNING_VMS_EXAMPLE_3VMs, 0);
        Assert.assertFalse(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestReceivedErrorCode() {
        boolean result = isKvmHaAgentHealthyTests(EXPECTED_RUNNING_VMS_EXAMPLE_3VMs, ERROR_CODE);
        Assert.assertFalse(result);
    }

    private boolean isKvmHaAgentHealthyTests(int expectedNumberOfVms, int vmsRunningOnAgent) {
        Mockito.when(kvmHaAgentClient.countRunningVmsOnAgent()).thenReturn(vmsRunningOnAgent);
        return kvmHaAgentClient.isKvmHaAgentHealthy(expectedNumberOfVms);
    }

    @Test
    public void processHttpResponseIntoJsonTestNull() {
        JsonObject responseJson = kvmHaAgentClient.processHttpResponseIntoJson(null);
        Assert.assertNull(responseJson);
    }

//    /**
//     * Processes the response of request GET System ID as a JSON object.<br>
//     *
//     * Json example: {"count": 3, "virtualmachines": ["r-123-VM", "v-134-VM", "s-111-VM"]}<br><br>
//     *
//     * Note: this method can return NULL JsonObject in case HttpResponse is NULL.
//     */
//    protected JsonObject processHttpResponseIntoJson(HttpResponse response) {
//        InputStream in;
//        String jsonString;
//        if (response == null) {
//            return null;
//        }
//        try {
//            in = response.getEntity().getContent();
//            BufferedReader streamReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
//            jsonString = streamReader.readLine();
//        } catch (UnsupportedOperationException | IOException e) {
//            throw new CloudRuntimeException("Failed to process response", e);
//        }
//
//        return new JsonParser().parse(jsonString).getAsJsonObject();
//    }

    @Test
    public void processHttpResponseIntoJsonTest() throws IOException {
        CloseableHttpResponse mockedResponse = mockResponse(HttpStatus.SC_OK);
        JsonObject responseJson = kvmHaAgentClient.processHttpResponseIntoJson(mockedResponse);
//        Assert.assertNull(responseJson);
    }

    private CloseableHttpResponse mockResponse(int httpStatusCode) throws IOException {
        BasicStatusLine basicStatusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1000, 123), httpStatusCode, "Status");
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        Mockito.when(response.getStatusLine()).thenReturn(basicStatusLine);
        HttpEntity httpEntity = null;
        Mockito.when(response.getEntity()).thenReturn(httpEntity);
        InputStream in = IOUtils.toInputStream(JSON_STRING_EXAMPLE_3VMs, StandardCharsets.UTF_8);
        return response;
    }

    @Test
    public void countRunningVmsOnAgentTest() throws IOException {
        prepareAndRunCountRunningVmsOnAgent(JSON_STRING_EXAMPLE_3VMs, EXPECTED_RUNNING_VMS_EXAMPLE_3VMs);
    }

    @Test
    public void countRunningVmsOnAgentTestBlankNoVmsListed() throws IOException {
        prepareAndRunCountRunningVmsOnAgent(JSON_STRING_EXAMPLE_0VMs, EXPECTED_RUNNING_VMS_EXAMPLE_0VMs);
    }

    private void prepareAndRunCountRunningVmsOnAgent(String jsonStringExample, int expectedListedVms) throws IOException {
        Mockito.when(agent.getPrivateIpAddress()).thenReturn(PRIVATE_IP_ADDRESS);
        Mockito.doReturn(mockResponse(HttpStatus.SC_OK)).when(kvmHaAgentClient).executeHttpRequest(EXPECTED_URL);

        JsonObject jObject = new JsonParser().parse(jsonStringExample).getAsJsonObject();
        Mockito.doReturn(jObject).when(kvmHaAgentClient).processHttpResponseIntoJson(Mockito.any(HttpResponse.class));

        int result = kvmHaAgentClient.countRunningVmsOnAgent();
        Assert.assertEquals(expectedListedVms, result);
    }

    @Test
    public void retryHttpRequestTest() throws IOException {
        kvmHaAgentClient.retryHttpRequest(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Mockito.verify(client, Mockito.times(1)).execute(Mockito.any());
        Mockito.verify(kvmHaAgentClient, Mockito.times(1)).retryUntilGetsHttpResponse(Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    @Test
    public void retryHttpRequestTestNullResponse() throws IOException {
        Mockito.doReturn(null).when(kvmHaAgentClient).retryUntilGetsHttpResponse(Mockito.anyString(), Mockito.any(), Mockito.any());
        HttpResponse response = kvmHaAgentClient.retryHttpRequest(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Assert.assertNull(response);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retryHttpRequestTestForbidden() throws IOException {
        prepareAndRunRetryHttpRequestTest(HttpStatus.SC_FORBIDDEN);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retryHttpRequestTestMultipleChoices() throws IOException {
        prepareAndRunRetryHttpRequestTest(HttpStatus.SC_MULTIPLE_CHOICES);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retryHttpRequestTestProcessing() throws IOException {
        prepareAndRunRetryHttpRequestTest(HttpStatus.SC_PROCESSING);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retryHttpRequestTestTimeout() throws IOException {
        prepareAndRunRetryHttpRequestTest(HttpStatus.SC_GATEWAY_TIMEOUT);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retryHttpRequestTestVersionNotSupported() throws IOException {
        prepareAndRunRetryHttpRequestTest(HttpStatus.SC_HTTP_VERSION_NOT_SUPPORTED);
    }

    private void prepareAndRunRetryHttpRequestTest(int scMultipleChoices) throws IOException {
        HttpResponse mockedResponse = mockResponse(scMultipleChoices);
        Mockito.doReturn(mockedResponse).when(kvmHaAgentClient).retryUntilGetsHttpResponse(Mockito.anyString(), Mockito.any(), Mockito.any());
        kvmHaAgentClient.retryHttpRequest(EXPECTED_URL, HTTP_REQUEST_BASE, client);
    }

    @Test
    public void retryHttpRequestTestHttpOk() throws IOException {
        HttpResponse mockedResponse = mockResponse(HttpStatus.SC_OK);
        Mockito.doReturn(mockedResponse).when(kvmHaAgentClient).retryUntilGetsHttpResponse(Mockito.anyString(), Mockito.any(), Mockito.any());
        HttpResponse result = kvmHaAgentClient.retryHttpRequest(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Mockito.verify(kvmHaAgentClient, Mockito.times(1)).retryUntilGetsHttpResponse(Mockito.anyString(), Mockito.any(), Mockito.any());
        Assert.assertEquals(mockedResponse, result);
    }

    @Test
    public void retryUntilGetsHttpResponseTestOneIOException() throws IOException {
        Mockito.when(client.execute(HTTP_REQUEST_BASE)).thenThrow(IOException.class).thenReturn(mockResponse(HttpStatus.SC_OK));
        kvmHaAgentClient.retryUntilGetsHttpResponse(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Mockito.verify(client, Mockito.times(MAX_REQUEST_RETRIES)).execute(Mockito.any());
    }

    @Test
    public void retryUntilGetsHttpResponseTestTwoIOException() throws IOException {
        Mockito.when(client.execute(HTTP_REQUEST_BASE)).thenThrow(IOException.class).thenThrow(IOException.class);
        kvmHaAgentClient.retryUntilGetsHttpResponse(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Mockito.verify(client, Mockito.times(MAX_REQUEST_RETRIES)).execute(Mockito.any());
    }

    @Test
    public void retryHttpRequestTestTwoIOException() throws IOException {
        Mockito.when(client.execute(HTTP_REQUEST_BASE)).thenThrow(IOException.class).thenThrow(IOException.class);
        kvmHaAgentClient.retryHttpRequest(EXPECTED_URL, HTTP_REQUEST_BASE, client);
        Mockito.verify(client, Mockito.times(MAX_REQUEST_RETRIES)).execute(Mockito.any());
    }

}
