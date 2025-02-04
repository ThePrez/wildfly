/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.clustering.web.undertow.session;

import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.servlet.ServletContext;

import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.web.container.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.web.session.ImmutableSession;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.SessionManagerConfiguration;
import org.wildfly.clustering.web.session.SessionManagerFactory;
import org.wildfly.clustering.web.undertow.IdentifierFactoryAdapter;
import org.wildfly.security.manager.WildFlySecurityManager;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionListeners;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.ThreadSetupHandler;

/**
 * Factory for creating a {@link DistributableSessionManager}.
 * @author Paul Ferraro
 */
public class DistributableSessionManagerFactory implements io.undertow.servlet.api.SessionManagerFactory {
    private static final String ALLOW_ORPHAN_SESSION_PROPERTY = "jboss.web.allow-orphan-session";

    private final SessionManagerFactory<ServletContext, Map<String, Object>, Batch> factory;
    private final SessionManagerFactoryConfiguration config;
    private final SessionListeners listeners = new SessionListeners();

    public DistributableSessionManagerFactory(SessionManagerFactory<ServletContext, Map<String, Object>, Batch> factory, SessionManagerFactoryConfiguration config) {
        this.factory = factory;
        this.config = config;
    }

    @Override
    public io.undertow.server.session.SessionManager createSessionManager(final Deployment deployment) {
        DeploymentInfo info = deployment.getDeploymentInfo();
        boolean statisticsEnabled = info.getMetricsCollector() != null;
        RecordableInactiveSessionStatistics inactiveSessionStatistics = statisticsEnabled ? new DistributableInactiveSessionStatistics() : null;
        Supplier<String> factory = new IdentifierFactoryAdapter(info.getSessionIdGenerator());
        Consumer<ImmutableSession> expirationListener = new UndertowSessionExpirationListener(deployment, this.listeners, inactiveSessionStatistics);
        SessionManagerConfiguration<ServletContext> configuration = new SessionManagerConfiguration<>() {
            @Override
            public ServletContext getServletContext() {
                return deployment.getServletContext();
            }

            @Override
            public Supplier<String> getIdentifierFactory() {
                return factory;
            }

            @Override
            public Consumer<ImmutableSession> getExpirationListener() {
                return expirationListener;
            }

            @Override
            public Duration getTimeout() {
                return Duration.ofMinutes(this.getServletContext().getSessionTimeout());
            }
        };
        SessionManager<Map<String, Object>, Batch> manager = this.factory.createSessionManager(configuration);
        Batcher<Batch> batcher = manager.getBatcher();
        info.addThreadSetupAction(new ThreadSetupHandler() {
            @Override
            public <T, C> Action<T, C> create(Action<T, C> action) {
                return new Action<>() {
                    @Override
                    public T call(HttpServerExchange exchange, C context) throws Exception {
                        Batch batch = batcher.suspendBatch();
                        try (BatchContext ctx = batcher.resumeBatch(batch)) {
                            return action.call(exchange, context);
                        }
                    }
                };
            }
        });
        SessionListeners listeners = this.listeners;
        RecordableSessionManagerStatistics statistics = (inactiveSessionStatistics != null) ? new DistributableSessionManagerStatistics(manager, inactiveSessionStatistics, this.config.getMaxActiveSessions()) : null;
        io.undertow.server.session.SessionManager result = new DistributableSessionManager(new DistributableSessionManagerConfiguration() {
            @Override
            public String getDeploymentName() {
                return info.getDeploymentName();
            }

            @Override
            public SessionManager<Map<String, Object>, Batch> getSessionManager() {
                return manager;
            }

            @Override
            public SessionListeners getSessionListeners() {
                return listeners;
            }

            @Override
            public RecordableSessionManagerStatistics getStatistics() {
                return statistics;
            }

            @Override
            public boolean isOrphanSessionAllowed() {
                // TODO Configure via DeploymentInfo
                return WildFlySecurityManager.doUnchecked(new PrivilegedAction<Boolean>() {
                    @Override
                    public Boolean run() {
                        return Boolean.getBoolean(ALLOW_ORPHAN_SESSION_PROPERTY);
                    }
                });
            }
        });
        result.setDefaultSessionTimeout((int) this.config.getDefaultSessionTimeout().getSeconds());
        return result;
    }
}
