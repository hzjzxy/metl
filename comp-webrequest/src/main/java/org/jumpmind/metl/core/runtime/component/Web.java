/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.metl.core.runtime.component;

import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jumpmind.exception.IoException;
import org.jumpmind.metl.core.model.Component;
import org.jumpmind.metl.core.runtime.ControlMessage;
import org.jumpmind.metl.core.runtime.Message;
import org.jumpmind.metl.core.runtime.TextMessage;
import org.jumpmind.metl.core.runtime.flow.ISendMessageCallback;
import org.jumpmind.metl.core.runtime.resource.HttpDirectory;
import org.jumpmind.metl.core.runtime.resource.IResourceRuntime;
import org.jumpmind.util.FormatUtils;

public class Web extends AbstractComponentRuntime {

    public static final String TYPE = "Web";

    public static final String DEFAULT_CHARSET = "UTF-8";

    public static final String RELATIVE_PATH = "relative.path";
    
    public static final String BODY_FROM = "body.from";
    
    public static final String BODY_TEXT = "body.text";
    
    public static final String HTTP_METHOD = "http.method";

    public static final String HTTP_HEADERS = "http.headers";
    
    public static final String HTTP_PARAMETERS = "http.parameters";
    
    public static final String PARAMETER_REPLACEMENT = "parameter.replacement";
    
    public static final String SETTING_ENCODING = "encoding";

        
    String runWhen;
    
    String relativePath;
    
    String httpMethod;
    
    String bodyFrom;
    
    String bodyText;
    
    Map<String,String> httpHeaders;
    
    Map<String,String> httpParameters;
    
    boolean parameterReplacement;
    
    CloseableHttpClient httpClient;
    
    String encoding = "UTF-8";
    
    HttpDirectory httpDirectory;
    
    @Override
    public void start() {
        IResourceRuntime httpResource = getResourceRuntime();
        if (httpResource == null) {
            throw new IllegalStateException("An HTTP resource must be configured");
        }
        httpDirectory = getResourceReference(); 
        Component component = getComponent();
        bodyFrom = component.get(BODY_FROM, "Message");
        bodyText = component.get(BODY_TEXT);
        runWhen = component.get(RUN_WHEN, PER_MESSAGE);
        httpMethod = component.get(HTTP_METHOD);
        if (isBlank(httpMethod)) {
            httpMethod = httpDirectory.getHttpMethod();
        }
        if (isBlank(httpMethod)) {
            throw new IllegalStateException("HTTP Method must be set in Web component or Http resource");
        }
        parameterReplacement = component.getBoolean(PARAMETER_REPLACEMENT, false);
        relativePath = component.get(RELATIVE_PATH);
        httpClient = HttpClients.createDefault();
        encoding = properties.get(SETTING_ENCODING, encoding);
    }
    
    @Override
    public boolean supportsStartupMessages() {
        return true;
    }

    private void resolveHttpHeaderVars(Map<String, String> httpHeaders, Message inputMessage) {
        for (Map.Entry<String, String> hdr : httpHeaders.entrySet()) {
            String newValue = resolveParamsAndHeaders(hdr.getValue(), inputMessage);
            httpHeaders.put(hdr.getKey(), newValue);
        }
    }
    
    @Override
    public void handle(Message inputMessage, ISendMessageCallback callback, boolean unitOfWorkBoundaryReached) {
        if ((PER_UNIT_OF_WORK.equals(runWhen) && inputMessage instanceof ControlMessage)
                || (!PER_UNIT_OF_WORK.equals(runWhen) && !(inputMessage instanceof ControlMessage))) {

            ArrayList<String> inputPayload = new ArrayList<String>();

            HttpDirectory httpDirectory = getResourceReference(); 
            httpHeaders = getHttpHeaderConfigEntries(inputMessage);
            httpParameters = getHttpParameterConfigEntries(inputMessage);
            inputPayload.addAll(getInputPayload(inputMessage));

            if (inputPayload != null) {
                String path = assemblePath(httpDirectory.getUrl());
                for (String requestContent : inputPayload) {
                    getComponentStatistics().incrementNumberEntitiesProcessed(threadNumber);
                    requestContent = replaceParameters(inputMessage, requestContent);
                    HttpRequestBase httpRequest = buildHttpRequest(path, httpHeaders, httpDirectory, requestContent);

                    if (isNotBlank(requestContent)) {
                        info("sending content to %s", path);
                        HttpEntityEnclosingRequestBase encHttpRequest = (HttpEntityEnclosingRequestBase) httpRequest;
                        StringEntity requestEntity;
                        try {
                            requestEntity = new StringEntity(requestContent);
                        } catch (UnsupportedEncodingException ex) {
                            log.error(String.format("Unable to encode content for web request %s",ex.getMessage()));
                            throw new IoException (ex);
                        }
                        encHttpRequest.setEntity(requestEntity);
                        executeRequestAndSendOutputMessage(encHttpRequest, callback);
                    } else {
                        info("getting content from %s", path);
                        executeRequestAndSendOutputMessage(httpRequest, callback);
                    }
                }
            }
        } else if (context.getManipulatedFlow().findStartSteps().contains(context.getFlowStep()) && !PER_UNIT_OF_WORK.equals(runWhen)) {
            warn("This component is configured as a start step but the run when is set to %s.  You might want to switch the run when to %s",
                    runWhen, PER_UNIT_OF_WORK);
        }
    }
	
    private void executeRequestAndSendOutputMessage(HttpRequestBase httpRequest, ISendMessageCallback callback) {
        
        Map<String, Serializable> outputMessageHeaders = new HashMap<String, Serializable>();
        ArrayList<String> outputPayload = new ArrayList<String>();
        CloseableHttpResponse httpResponse=null;
        try {
            httpResponse = httpClient.execute(httpRequest);
            int responseCode = httpResponse.getStatusLine().getStatusCode();           
            if (responseCode / 100 != 2) {
                throw new IoException(String.format("Error calling http method.  HTTP Status %d, HTTP Status Description %s, HTTP Result %s", 
                        responseCode, httpResponse.getStatusLine().getReasonPhrase(), 
                        IOUtils.toString(httpResponse.getEntity().getContent())));
            } else {
                HttpEntity resultEntity = httpResponse.getEntity();
                outputPayload.add(IOUtils.toString(resultEntity.getContent()));
                outputMessageHeaders.putAll(responseHeadersToMap(httpResponse.getAllHeaders()));
                EntityUtils.consume(resultEntity);
            }
        } catch (IOException ex) {
            throw new IoException(String.format("Error calling service %s.  Error: %s", httpRequest.getURI().getPath(), ex.getMessage()));
        } finally {
            try {
            httpResponse.close(); 
            } catch (IOException iox) {
                //close quietly
                log.info(String.format("Unable to close http session %s", iox.getMessage()));
            }
        }                            
        if (outputPayload.size() > 0) {
            callback.sendTextMessage(outputMessageHeaders, outputPayload);
        }        
    }
    
    private Map<String, Serializable> responseHeadersToMap(Header[] headers) {
        Map<String, Serializable> responseHeaders = new HashMap<String, Serializable>();
        
        for (Header header : headers) {
            responseHeaders.put(header.getName(), header.getValue());
        }        
        return responseHeaders;
    }
    
    @Override
    public void stop() {
        try {
            httpClient.close();
        } catch (IOException e) {
            //close quietly
            log.info(String.format("Unable to properly close httpClient connetion.  Reason: %s", e.getMessage()));
        }
    }
    
	private List<String> getInputPayload(Message inputMessage) {
        if (bodyFrom.equals("Message") && inputMessage instanceof TextMessage) {
            return ((TextMessage)inputMessage).getPayload();
        } else {
            List<String> payload = new ArrayList<String>();
            payload.add(bodyText);
            return payload;
        }
	}
	
	private String replaceParameters(Message inputMessage, String requestContent) {
        if (parameterReplacement) {
            resolveHttpHeaderVars(httpHeaders, inputMessage);
            requestContent = resolveParamsAndHeaders(requestContent, inputMessage);
        }
        return requestContent;
	}
	
	protected HttpRequestBase buildHttpRequest(String path,  Map<String, String> headers, HttpDirectory httpDirectory,
	        String requestContent) {        
        HttpRequestBase request=null;
        if (httpMethod.equalsIgnoreCase(HttpDirectory.HTTP_METHOD_GET)) {
            if (isNotBlank(requestContent)) {
                request = new HttpGetWithEntity();
            } else {
                request = new HttpGet();
            }
        } else if (httpMethod.equalsIgnoreCase(HttpDirectory.HTTP_METHOD_PUT)) {
            request = new HttpPut();
        } else if (httpMethod.equalsIgnoreCase(HttpDirectory.HTTP_METHOD_PATCH)) {
            request = new HttpPatch();
        } else if (httpMethod.equalsIgnoreCase(HttpDirectory.HTTP_METHOD_POST)) {
            request = new HttpPost();
        } else if (httpMethod.equalsIgnoreCase(HttpDirectory.HTTP_METHOD_DELETE)) {
            request = new HttpDelete();
        }
        try {
            request.setURI(new URL(path).toURI());
        } catch (MalformedURLException | URISyntaxException ex) {
            throw new IoException(ex);
        }
        if (headers != null) {
            for (String key : headers.keySet()) {
                request.setHeader(key, headers.get(key));
            }
        }
        if (isNotBlank(httpDirectory.getContentType())) {
            request.setHeader("Content-Type", httpDirectory.getContentType());
        }
        RequestConfig.Builder requestConfig = RequestConfig.custom();
        requestConfig.setConnectTimeout(httpDirectory.getTimeout());
        requestConfig.setConnectionRequestTimeout(httpDirectory.getTimeout());
        requestConfig.setSocketTimeout(httpDirectory.getTimeout());
        request.setConfig(requestConfig.build());        
        setAuthIfNeeded(request, httpDirectory);
        
        return request;
    }	
	
    private Map<String, String> getHttpHeaderConfigEntries(Message inputMessage) {
        String headersText = resolveParamsAndHeaders(properties.get(HTTP_HEADERS), inputMessage);
        return parseDelimitedMultiLineParamsToMap(headersText, inputMessage);
    }

    private Map<String, String> getHttpParameterConfigEntries(Message inputMessage) {
        String parametersText = resolveParamsAndHeaders(properties.get(HTTP_PARAMETERS), inputMessage);
        return parseDelimitedMultiLineParamsToMap(parametersText, inputMessage);
    }   
    
    private Map<String, String> parseDelimitedMultiLineParamsToMap(String parametersText, Message inputMessage) {
        Map<String, String> parsedMap = new HashMap<>();
        if (parametersText != null) {
            String[] parameters = parametersText.split("\\r?\\n");
            for (String parameter : parameters) {
                String[] pair = parameter.split(":");
                if (pair != null && pair.length > 1) {
                    parsedMap.put(pair[0], pair[1]);
                }
            }
        }
        return parsedMap;
    }
   
    protected void setAuthIfNeeded(HttpRequestBase request, HttpDirectory httpDirectory) {
        if (HttpDirectory.SECURITY_BASIC.equals(httpDirectory.getSecurity())) {
            String userpassword = String.format("%s:%s", httpDirectory.getUsername(), httpDirectory.getPassword());
            String encodedAuthorization = new String(Base64.encodeBase64(userpassword.getBytes()));
            request.setHeader("Authorization", "Baisc " + encodedAuthorization);
        } else if (HttpDirectory.SECURITY_TOKEN.equals(httpDirectory.getSecurity())) {
            request.setHeader("Authorization","Bearer " + httpDirectory.getToken());
        } else if (HttpDirectory.SECURITY_OAUTH_10.equals(httpDirectory.getSecurity())) {
            //TODO: We should really put this back in
            throw new UnsupportedOperationException("OAuth 1.0 support has been removed from Metl.");
        }
    }    
    
    private String assemblePath(String basePath) {
        Component component = getComponent();
        if (isNotBlank(relativePath)) {
            String path = basePath + FormatUtils.replaceTokens(component.get(RELATIVE_PATH),
                    context.getFlowParameters(), true);
            int parmCount = 0;
            for (Map.Entry<String, String> entry : httpParameters.entrySet()) {
                parmCount++;
                if (parmCount == 1) {
                    path = path + "?"; 
                } else {
                    path = path + "&";
                }
                try {
                    path = path + entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), DEFAULT_CHARSET);
                } catch(UnsupportedEncodingException e) {
                    log.error("Error URL Encoding parameters");
                    throw new RuntimeException(e);
                }
            }
            return path;
        } else {
            return basePath;
        }
    }
    
    private class HttpGetWithEntity extends HttpEntityEnclosingRequestBase {
        public final static String METHOD_NAME = "GET";

        @Override
        public String getMethod() {
            return METHOD_NAME;
        }
    }
}
