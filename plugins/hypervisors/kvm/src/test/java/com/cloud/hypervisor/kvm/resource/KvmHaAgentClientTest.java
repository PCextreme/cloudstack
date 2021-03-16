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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@RunWith(MockitoJUnitRunner.class)
public class KvmHaAgentClientTest {

    private static final String AGENT_ADDRESS = "kvm-agent.domain.name";
    private static final int ERROR_CODE = -1;
    private HostVO agent = Mockito.mock(HostVO.class);
    private KvmHaAgentClient kvmHaAgentClient = Mockito.spy(new KvmHaAgentClient(agent));
    private static final int DEFAULT_PORT = 8080;
    private static final String PRIVATE_IP_ADDRESS = "1.2.3.4";

    private static final String JSON_STRING_EXAMPLE_3VMs = "{\"count\": 3, \"virtualmachines\": [\"r-123-VM\", \"v-134-VM\", \"s-111-VM\"]}";
    private static final int EXPECTED_RUNNING_VMS_EXAMPLE_3VMs = 3;
    private static final String JSON_STRING_EXAMPLE_0VMs = "{\"count\": 0, \"virtualmachines\": []}";
    private static final int EXPECTED_RUNNING_VMS_EXAMPLE_0VMs = 0;

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

    private CloseableHttpResponse mockResponse(int httpStatusCode) throws IOException {
        String jsonString = "{\"count\": 3, \"virtualmachines\": [\"r-123-VM\", \"v-134-VM\", \"s-111-VM\"]}";
        StatusLine statusLine = Mockito.mock(StatusLine.class);
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        HttpEntity httpEntity = Mockito.mock(HttpEntity.class);
        InputStream in = IOUtils.toInputStream(jsonString, StandardCharsets.UTF_8);
        BufferedReader buffer = Mockito.spy(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));

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

        String expectedUrl = String.format("http://%s:%d", PRIVATE_IP_ADDRESS, DEFAULT_PORT);
        Mockito.doReturn(mockResponse(HttpStatus.SC_OK)).when(kvmHaAgentClient).executeHttpRequest(expectedUrl);

        JsonObject jObject = new JsonParser().parse(jsonStringExample).getAsJsonObject();
        Mockito.doReturn(jObject).when(kvmHaAgentClient).processHttpResponseIntoJson(Mockito.any(HttpResponse.class));

        int result = kvmHaAgentClient.countRunningVmsOnAgent();
        Assert.assertEquals(expectedListedVms, result);
    }

}
