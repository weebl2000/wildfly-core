/*
* JBoss, Home of Professional Open Source.
* Copyright 2011, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.as.server.operations;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.operations.common.ProcessReloadHandler;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.controller.descriptions.ServerDescriptions;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceName;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class ServerProcessReloadHandler extends ProcessReloadHandler<RunningModeControl> {

    private static final AttributeDefinition USE_CURRENT_SERVER_CONFIG = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.USE_CURRENT_SERVER_CONFIG, ModelType.BOOLEAN, true)
            .setAlternatives(ModelDescriptionConstants.SERVER_CONFIG)
            .setDefaultValue(new ModelNode(true))
            .build();

    private static final AttributeDefinition SERVER_CONFIG = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.SERVER_CONFIG, ModelType.STRING, true)
            .setAlternatives(ModelDescriptionConstants.USE_CURRENT_SERVER_CONFIG)
            .build();

    private static final AttributeDefinition[] ATTRIBUTES = new AttributeDefinition[] {ADMIN_ONLY, USE_CURRENT_SERVER_CONFIG, SERVER_CONFIG};

    public static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, ServerDescriptions.getResourceDescriptionResolver("server"))
                                                                .setParameters(ATTRIBUTES)
                                                                .setRuntimeOnly()
                                                                .build();

    private final ServerEnvironment environment;
    public ServerProcessReloadHandler(ServiceName rootService, RunningModeControl runningModeControl,
            ControlledProcessState processState, ServerEnvironment environment) {
        super(rootService, runningModeControl, processState);
        this.environment = environment;
    }

    @Override
    protected ProcessReloadHandler.ReloadContext<RunningModeControl> initializeReloadContext(final OperationContext context, final ModelNode operation) throws OperationFailedException {
        final boolean unmanaged = context.getProcessType() != ProcessType.DOMAIN_SERVER; // make sure that the params are ignored for managed servers
        final boolean adminOnly = unmanaged && ADMIN_ONLY.resolveModelAttribute(context, operation).asBoolean(false);
        final boolean useCurrentConfig = unmanaged && USE_CURRENT_SERVER_CONFIG.resolveModelAttribute(context, operation).asBoolean(true);
        final String serverConfig = unmanaged && operation.hasDefined(SERVER_CONFIG.getName()) ? SERVER_CONFIG.resolveModelAttribute(context, operation).asString() : null;

        if (operation.hasDefined(USE_CURRENT_SERVER_CONFIG.getName()) && serverConfig != null) {
            throw ServerLogger.ROOT_LOGGER.cannotBothHaveFalseUseCurrentConfigAndServerConfig();
        }
        if (serverConfig != null && !environment.getServerConfigurationFile().checkCanFindNewBootFile(serverConfig)) {
            throw ServerLogger.ROOT_LOGGER.serverConfigForReloadNotFound(serverConfig);
        }
        return new ReloadContext<RunningModeControl>() {

            @Override
            public void reloadInitiated(RunningModeControl runningModeControl) {
            }

            @Override
            public void doReload(RunningModeControl runningModeControl) {
                runningModeControl.setRunningMode(adminOnly ? RunningMode.ADMIN_ONLY : RunningMode.NORMAL);
                runningModeControl.setReloaded();
                runningModeControl.setUseCurrentConfig(useCurrentConfig);
                runningModeControl.setNewBootFileName(serverConfig);
            }
        };
    }
}
