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

package org.apache.druid.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.AlertBuilder;
import org.apache.druid.query.QueryException;
import org.apache.druid.query.QueryInterruptedException;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.QueryResource;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filter that verifies that authorization checks were applied to an HTTP request, before sending a response.
 * <p>
 * This filter is intended to help catch missing authorization checks arising from bugs/design omissions.
 */
public class PreResponseAuthorizationCheckFilter implements Filter
{
  private static final EmittingLogger log = new EmittingLogger(PreResponseAuthorizationCheckFilter.class);

  private final List<Authenticator> authenticators;
  private final ObjectMapper jsonMapper;

  public PreResponseAuthorizationCheckFilter(
      List<Authenticator> authenticators,
      ObjectMapper jsonMapper
  )
  {
    this.authenticators = authenticators;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void init(FilterConfig filterConfig)
  {
  }

  @Override
  public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException
  {
    final HttpServletResponse response = (HttpServletResponse) servletResponse;
    final HttpServletRequest request = (HttpServletRequest) servletRequest;

    if (servletRequest.getAttribute(AuthConfig.DRUID_AUTHENTICATION_RESULT) == null) {
      handleUnauthenticatedRequest(response);
      return;
    }

    filterChain.doFilter(servletRequest, servletResponse);

    Boolean authInfoChecked = (Boolean) servletRequest.getAttribute(AuthConfig.DRUID_AUTHORIZATION_CHECKED);
    if (authInfoChecked == null && statusShouldBeHidden(response.getStatus())) {
      // Note: rather than throwing an exception here, it would be nice to blank out the original response
      // since the request didn't have any authorization checks performed. However, this breaks proxying
      // (e.g. OverlordServletProxy), so this is not implemented for now.
      handleAuthorizationCheckError(
          StringUtils.format(
              "Request did not have an authorization check performed, original response status [%s].",
              response.getStatus()
          ),
          request,
          response
      );
    }

    if (authInfoChecked != null && !authInfoChecked && response.getStatus() != HttpServletResponse.SC_FORBIDDEN) {
      handleAuthorizationCheckError(
          "Request's authorization check failed but status code was not 403.",
          request,
          response
      );
    }
  }

  @Override
  public void destroy()
  {
  }

  private void handleUnauthenticatedRequest(
      final HttpServletResponse response
  ) throws IOException
  {
    // Since this is the last filter in the chain, some previous authentication filter
    // should have placed an authentication result in the request.
    // If not, send an authentication challenge.
    Set<String> supportedAuthSchemes = new HashSet<>();
    for (Authenticator authenticator : authenticators) {
      String challengeHeader = authenticator.getAuthChallengeHeader();
      if (challengeHeader != null) {
        supportedAuthSchemes.add(challengeHeader);
      }
    }
    for (String authScheme : supportedAuthSchemes) {
      response.addHeader("WWW-Authenticate", authScheme);
    }
    QueryInterruptedException unauthorizedError = new QueryInterruptedException(
        QueryException.UNAUTHORIZED_ERROR_CODE,
        null,
        null,
        DruidNode.getDefaultHost()
    );
    unauthorizedError.setStackTrace(new StackTraceElement[0]);
    OutputStream out = response.getOutputStream();
    sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, jsonMapper.writeValueAsString(unauthorizedError), out);
    out.close();
  }

  private void handleAuthorizationCheckError(
      String errorMsg,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse
  )
  {
    // Send out an alert so there's a centralized collection point for seeing errors of this nature
    AlertBuilder builder = log.makeAlert(errorMsg)
        .addData("uri", servletRequest.getRequestURI())
        .addData("method", servletRequest.getMethod())
        .addData("remoteAddr", servletRequest.getRemoteAddr());

    // Omit the host name if it just repeats the IP address.
    String remoteHost = servletRequest.getRemoteHost();
    if (remoteHost != null && !remoteHost.equals(servletRequest.getRemoteAddr())) {
      builder.addData("remoteHost", remoteHost);
    }

    // Omit the query ID if there is no ID.
    final String queryId = servletResponse.getHeader(QueryResource.QUERY_ID_RESPONSE_HEADER);
    if (queryId != null) {
      builder.addData("queryId", queryId);
    }
    builder.emit();

    if (!servletResponse.isCommitted()) {
      try {
        servletResponse.reset();
        servletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static boolean statusShouldBeHidden(int status)
  {
    // Hide any 200s because a 200 response could contain stuff that we don't want seen, so we hide that.  It's also
    // possible that errors can leak information, but that's something we cannot truly fix here.  We choose to let
    // those error messages through because this filter values giving the user good feedback instead.
    return status / 100 == 2;
  }

  public static void sendJsonError(HttpServletResponse resp, int error, String errorJson, OutputStream outputStream)
  {
    resp.setStatus(error);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    try {
      outputStream.write(errorJson.getBytes(StandardCharsets.UTF_8));
    }
    catch (IOException ioe) {
      log.error("Can't get writer from HTTP response.");
    }
  }
}
