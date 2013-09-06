/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 ForgeRock AS. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.openidm.filter;

import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.Context;
import org.forgerock.json.resource.ForbiddenException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResultHandler;
import org.forgerock.json.resource.SecurityContext;
import org.forgerock.json.resource.ServerContext;
import org.forgerock.json.resource.SingletonResourceProvider;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.json.resource.servlet.HttpContext;
import org.forgerock.json.resource.servlet.HttpServletContextFactory;
import org.forgerock.json.resource.servlet.SecurityContextFactory;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.jaspi.config.AuthenticationConfig;
import org.forgerock.openidm.jaspi.modules.AuthHelper;
import org.forgerock.openidm.router.RouteService;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Auth Filter
 * 
 * @author Jamie Nelson
 * @author aegloff
 * @author ckienle
 */

@Component(name = AuthFilter.PID, immediate = true, policy = ConfigurationPolicy.IGNORE)
@Service({HttpServletContextFactory.class, SingletonResourceProvider.class})
@Properties({
    @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
    @Property(name = Constants.SERVICE_DESCRIPTION, value = "OpenIDM Authentication Filter Service"),
    @Property(name = ServerConstants.ROUTER_PREFIX, value = "/authentication")
})
public class AuthFilter implements HttpServletContextFactory, SingletonResourceProvider {

    public static final String PID = "org.forgerock.openidm.reauthentication";

    /**
     * Setup logging for the {@link AuthFilter}.
     */
    private final static Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    /** Re-authentication password header. */
    public static final String HEADER_REAUTH_PASSWORD = "X-OpenIDM-Reauth-Password";

    private String queryId;
    private String queryOnResource;

    /** The authentication module to delegate to.*/
    private AuthHelper authHelper;

    @Reference(
            name = "AuthenticationConfig",
            referenceInterface = AuthenticationConfig.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MANDATORY_UNARY,
            bind = "bindAuthenticationConfig",
            unbind = "unBindAuthenticationConfig"
    )
    private JsonValue config;
    private void bindAuthenticationConfig(AuthenticationConfig authenticationConfig) {
        config = authenticationConfig.getConfig();
    }
    private void unBindAuthenticationConfig(AuthenticationConfig authenticationConfig) {
        config = null;
    }

    /**
     * Activates this component.
     *
     * @param context The COmponentContext
     */
    @Activate
    protected synchronized void activate(ComponentContext context) throws ResourceException {
        logger.info("Activating Auth Filter with configuration {}", context.getProperties());
        setConfig(config);
    }

    private void setConfig(JsonValue config) throws ResourceException {
        queryId = config.get("queryId").defaultTo("credential-query").asString();
        queryOnResource = config.get("queryOnResource").defaultTo("managed/user").asString();

        JsonValue properties = config.get("propertyMapping");
        String userIdProperty = properties.get("userId").asString();
        String userCredentialProperty = properties.get("userCredential").asString();
        String userRolesProperty = properties.get("userRoles").asString();
        List<String> defaultRoles = config.get("defaultUserRoles").asList(String.class);

        authHelper = new AuthHelper(cryptoService, repositoryRoute.createServerContext(), userIdProperty,
                userCredentialProperty, userRolesProperty, defaultRoles);
    }

    @Override
    public Context createContext(HttpServletRequest request) throws ResourceException {

        SecurityContextFactory securityContextFactory = SecurityContextFactory.getHttpServletContextFactory();
        SecurityContext securityContext = securityContextFactory.createContext(request);
        String authcid = securityContext.getAuthenticationId();

        if (StringUtils.isEmpty(authcid)) {
            logger.warn("Rejecting invocation as required context to allow invocation not populated");
            throw ResourceException.getException(ResourceException.UNAVAILABLE,
                    "Rejecting invocation as required context to allow invocation not populated");
        }
        return securityContext;
    }

    // ----- Declarative Service Implementation

    @Reference(target = "("+ServerConstants.ROUTER_PREFIX+"=/repo/*)")
    RouteService repositoryRoute;

    @Reference(policy = ReferencePolicy.DYNAMIC)
    CryptoService cryptoService;


    // ----- Implementation of SingletonResourceProvider interface

    /**
     * Action support, including reauthenticate action {@inheritDoc}
     */
    @Override
    public void actionInstance(ServerContext context, ActionRequest request,
            ResultHandler<JsonValue> handler) {
        try {
            if ("reauthenticate".equalsIgnoreCase(request.getAction())) {
                if (context.containsContext(HttpContext.class)
                        && context.containsContext(SecurityContext.class)) {
                    String authcid =
                            context.asContext(SecurityContext.class).getAuthenticationId();
                    HttpContext httpContext = context.asContext(HttpContext.class);
                    String password = httpContext.getHeaderAsString(HEADER_REAUTH_PASSWORD);
                    if (StringUtils.isBlank(authcid) || StringUtils.isBlank(password)) {
                        logger.debug("Failed authentication, missing or empty headers");
                        handler.handleError(new ForbiddenException(
                                "Failed authentication, missing or empty headers"));
                        return;
                    }
                    if (!authHelper.authenticate(queryId, queryOnResource, authcid, password, null)) {
                        //TODO Handle message
                        handler.handleError(new ForbiddenException("Reauthentication failed", new AuthException(authcid)));
                    }
                }
            } else {
                handler.handleError(new BadRequestException("Action " + request.getAction()
                        + " on authentication service not supported"));
            }
        } catch (Exception e) {
            handler.handleError(new InternalServerErrorException(e));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void patchInstance(ServerContext context, PatchRequest request,
            ResultHandler<Resource> handler) {
        final ResourceException e = new NotSupportedException("Patch operations are not supported");
        handler.handleError(e);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readInstance(ServerContext context, ReadRequest request,
            ResultHandler<Resource> handler) {
        final ResourceException e = new NotSupportedException("Read operations are not supported");
        handler.handleError(e);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateInstance(ServerContext context, UpdateRequest request,
            ResultHandler<Resource> handler) {
        final ResourceException e =
                new NotSupportedException("Update operations are not supported");
        handler.handleError(e);
    }
}
