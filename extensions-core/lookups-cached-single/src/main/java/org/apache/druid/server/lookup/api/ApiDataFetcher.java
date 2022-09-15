/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.lookup.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

import org.json.*;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.server.lookup.AricDataFetcher;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

public class ApiDataFetcher implements AricDataFetcher<String, String> {
  static {
    NullHandling.initializeForTests();
  }

  private static final Logger LOGGER = new Logger(ApiDataFetcher.class);
  private static final int DEFAULT_RETRY_COUNT = 3;
  private static final long DEFAULT_RETRY_INTERVAL = 100;
  private static final long DEFAULT_CONNECT_TIMEOUT = 1000;
  private static final long DEFAULT_CONNECTION_REQUEST_TIMEOUT = 1000;
  private static final long DEFAULT_RESPONSE_TIMEOUT = 1000;
  private static final int DEFAULT_MAX_TOTAL = 200;
  private static final int DEFAULT_MAX_PER_ROUTE = 200;

  @JsonProperty
  private final String fetchUri;
  @JsonProperty
  private final String accessToken;
  @JsonProperty
  private final int retryCount;
  @JsonProperty
  private final long retryInterval;
  @JsonProperty
  private final long connectTimeout;
  @JsonProperty
  private final long connectionRequestTimeout;
  @JsonProperty
  private final long responseTimeout;
  @JsonProperty
  private final int maxTotal;
  @JsonProperty
  private final int maxPerRoute;

  private CloseableHttpClient httpclient;

  ApiDataFetcher(
      @JsonProperty("fetchUri") String fetchUriParam,
      @JsonProperty("accessToken") String accessTokenParam,
      @JsonProperty("retryCount") @Nullable Integer retryCountParam,
      @JsonProperty("retryInterval") @Nullable Long retryIntervalParam,
      @JsonProperty("connectTimeout") @Nullable Long connectTimeoutParam,
      @JsonProperty("connectionRequestTimeout") @Nullable Long connectionRequestTimeoutParam,
      @JsonProperty("responseTimeout") @Nullable Long responseTimeoutParam,
      @JsonProperty("maxTotal") @Nullable Integer maxTotalParam,
      @JsonProperty("maxPerRoute") @Nullable Integer maxPerRouteParam
  ) {
    this.fetchUri = Preconditions.checkNotNull(fetchUriParam, "fetchUri");
    this.accessToken = Preconditions.checkNotNull(accessTokenParam, "accessToken");
    this.retryCount = retryCountParam == null
        ? DEFAULT_RETRY_COUNT : retryCountParam;
    this.retryInterval = retryIntervalParam == null
        ? DEFAULT_RETRY_INTERVAL : retryIntervalParam;
    this.connectTimeout = connectTimeoutParam == null
        ? DEFAULT_CONNECT_TIMEOUT : connectTimeoutParam;
    this.connectionRequestTimeout = connectionRequestTimeoutParam == null
        ? DEFAULT_CONNECTION_REQUEST_TIMEOUT : connectionRequestTimeoutParam;
    this.responseTimeout = responseTimeoutParam == null
        ? DEFAULT_RESPONSE_TIMEOUT : responseTimeoutParam;
    this.maxTotal = maxTotalParam == null
        ? DEFAULT_MAX_TOTAL : maxTotalParam;
    this.maxPerRoute = maxPerRouteParam == null
        ? DEFAULT_MAX_PER_ROUTE : maxPerRouteParam;

    this.httpclient = HttpClients
        .custom()
        .setRetryStrategy(getHttpRequestRetryStrategy())
        .setDefaultRequestConfig(getRequestConfig())
        .setConnectionManager(getConnectionManager())
        .build();

  }

  @Override
  public String fetch(final String key) {
    try {
      HttpPost httpPost = new HttpPost(fetchUri);
      httpPost.addHeader("Content-Type", "text");
      httpPost.addHeader("Access-Token", accessToken);
      httpPost.setEntity(new StringEntity(StringUtils.format("\"%s\"", key)));
      CloseableHttpResponse response = httpclient.execute(httpPost);
      HttpEntity entity = response.getEntity();

      //Both the HttpEntity and CloseableHttpResponse are consumed/closed before returning
      //This ensures that no entity or responses are left open potentially waisting resources
      String value = EntityUtils.toString(entity);
      EntityUtils.consume(entity);
      response.close();

      //A HTTP status code which is not 200 indicates a error has occured
      if (response.getCode() != 200) {
        throw new Exception(
            StringUtils.format(
                "ApiDataFetcher received an incompatible HTTP Status Code: %d Response: %s",
                response.getCode(),
                value));
      }
      return NullHandling.nullToEmptyIfNeeded(value);
    } catch (Exception e) {
      LOGGER.error(e, "Unable to fetch response");
      throw new RuntimeException(e);
    }
  }

  @Override
  public Map<String, String> fetchBatch(final List<String> keysBatch) {
    try {
      JSONObject tokens = new JSONObject();
      tokens.put("id", keysBatch);

      HttpPost httpPost = new HttpPost(fetchUri);

      httpPost.setHeader("Accept", "application/json");
      httpPost.setHeader("Content-type", "application/json");
      httpPost.addHeader("Access-Token", accessToken);

      StringEntity body = new StringEntity(tokens.toString());
      httpPost.setEntity(body);

      CloseableHttpResponse response = httpclient.execute(httpPost);
      HttpEntity entity = response.getEntity();
      response.close();

      String value = EntityUtils.toString(entity);

      //A HTTP status code which is not 200 indicates a error has occured
      if (response.getCode() != 200) {
        throw new Exception(
                StringUtils.format(
                        "ApiDataFetcher received an incompatible HTTP Status Code: %d Response: %s",
                        response.getCode(),
                        value));
      }
      Map<String, String> batchResults = new HashMap<>();

      JSONArray valuesBatch = new JSONArray(value);
      for (int i = 0; i < valuesBatch.length(); i++) {
        batchResults.put(keysBatch.get(i),valuesBatch.getString(i));
      }
      return batchResults;
    } catch (Exception e) {
      LOGGER.error(e, "Unable to fetch response");
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<String> reverseFetchKeys(final String value) {
    throw new UnsupportedOperationException("Cannot reverse fetch keys");
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ApiDataFetcher)) {
      return false;
    }

    ApiDataFetcher that = (ApiDataFetcher) o;

    if (!fetchUri.equals(that.fetchUri)) {
      return false;
    }

    return accessToken.equals(that.accessToken);

  }

  @Override
  public int hashCode() {
    return Objects.hash(fetchUri, accessToken);
  }

  @Override
  public String toString() {
    return "ApiDataFetcher{"
        +
        "fetchUri='"
        + fetchUri
        + '\''
        +
        ", accessToken='"
        + accessToken
        + '\''
        +
        '}';
  }

  private HttpRequestRetryStrategy getHttpRequestRetryStrategy() {
    HttpRequestRetryStrategy httpRequestRetryStrategy = new HttpRequestRetryStrategy() {
      @Override
      public boolean retryRequest(HttpRequest httpRequest, IOException e, int i, HttpContext httpContext) {
        return i <= retryCount;
      }

      @Override
      public boolean retryRequest(HttpResponse httpResponse, int i, HttpContext httpContext) {
        return false;
      }

      @Override
      public TimeValue getRetryInterval(HttpResponse httpResponse, int i, HttpContext httpContext) {
        return TimeValue.ofMilliseconds(retryInterval);
      }
    };
    return httpRequestRetryStrategy;
  }

  private RequestConfig getRequestConfig() {
    RequestConfig requestConfig = RequestConfig
        .custom()
        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout))
        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout))
        .build();
    return requestConfig;
  }

  private PoolingHttpClientConnectionManager getConnectionManager() {
    PoolingHttpClientConnectionManager connectionManager =
        new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(maxTotal);
    connectionManager.setDefaultMaxPerRoute(maxPerRoute);
    return connectionManager;
  }

}
