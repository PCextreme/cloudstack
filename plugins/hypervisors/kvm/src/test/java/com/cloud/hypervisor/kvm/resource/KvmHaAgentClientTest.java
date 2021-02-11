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
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KvmHaAgentClientTest {

    private static final String AGENT_ADDRESS = "kvm-agent.domain.name";
    private static final int ERROR_CODE = -1;
    private HostVO agent = Mockito.mock(HostVO.class);
    private KvmHaAgentClient kvmHaAgentClient = Mockito.spy(new KvmHaAgentClient(agent));

    private CloseableHttpResponse mockResponse(int httpStatusCode) {
        StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.doReturn(httpStatusCode).when(statusLine).getStatusCode();
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        Mockito.doReturn(statusLine).when(response).getStatusLine();
        Mockito.doReturn(response).when(kvmHaAgentClient).executeHttpRequest(Mockito.anyString());
        return response;
    }

    @Test
    public void isKvmHaAgentHealthyTestAllGood() {
        boolean result = isKvmHaAgentHealthyTests(2, 2);
        Assert.assertTrue(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestVMsDoNotMatchButDoNotReturnFalse() {
        boolean result = isKvmHaAgentHealthyTests(2, 1);
        Assert.assertTrue(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestExpectedRunningVmsButNoneListed() {
        boolean result = isKvmHaAgentHealthyTests(2, 0);
        Assert.assertFalse(result);
    }

    @Test
    public void isKvmHaAgentHealthyTestReceivedErrorCode() {
        boolean result = isKvmHaAgentHealthyTests(2, ERROR_CODE);
        Assert.assertFalse(result);
    }

    private boolean isKvmHaAgentHealthyTests(int expectedNumberOfVms, int vmsRunningOnAgent) {
        Mockito.when(kvmHaAgentClient.countRunningVmsOnAgent()).thenReturn(vmsRunningOnAgent);
        return kvmHaAgentClient.isKvmHaAgentHealthy(expectedNumberOfVms);
    }

}
