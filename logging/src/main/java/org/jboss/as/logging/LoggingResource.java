/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
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

package org.jboss.as.logging;

import static org.jboss.as.logging.LogFileResourceDefinition.LOG_FILE;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.PlaceholderResource;
import org.jboss.as.controller.registry.PlaceholderResource.PlaceholderResourceEntry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.registry.ResourceFilter;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathResourceDefinition;
import org.jboss.as.logging.logging.LoggingLogger;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class LoggingResource implements Resource {
    private static final List<String> FILE_RESOURCE_NAMES = Arrays.asList(
            FileHandlerResourceDefinition.FILE_HANDLER,
            PeriodicHandlerResourceDefinition.PERIODIC_ROTATING_FILE_HANDLER,
            PeriodicSizeRotatingHandlerResourceDefinition.PERIODIC_SIZE_ROTATING_FILE_HANDLER,
            SizeRotatingHandlerResourceDefinition.SIZE_ROTATING_FILE_HANDLER
    );

    private final PathManager pathManager;
    private final Resource delegate;

    public LoggingResource(final PathManager pathManager) {
        this(Resource.Factory.create(), pathManager);
    }

    public LoggingResource(final Resource delegate, final PathManager pathManager) {
        assert pathManager != null : "PathManager cannot be null";
        this.delegate = delegate;
        this.pathManager = pathManager;
    }

    @Override
    public ModelNode getModel() {
        return delegate.getModel();
    }

    @Override
    public void writeModel(final ModelNode newModel) {
        delegate.writeModel(newModel);
    }

    @Override
    public boolean isModelDefined() {
        return delegate.isModelDefined();
    }

    @Override
    public boolean hasChild(final PathElement element) {
        if (LOG_FILE.equals(element.getKey())) {
            return hasReadableFile(element.getValue());
        }
        return delegate.hasChild(element);
    }

    @Override
    public Resource getChild(final PathElement element) {
        if (LOG_FILE.equals(element.getKey())) {
            if (hasReadableFile(element.getValue())) {
                return PlaceholderResource.INSTANCE;
            }
            return null;
        }
        return delegate.getChild(element);
    }

    @Override
    public Resource requireChild(final PathElement element) {
        if (LOG_FILE.equals(element.getKey())) {
            if (hasReadableFile(element.getValue())) {
                return PlaceholderResource.INSTANCE;
            }
            throw new NoSuchResourceException(element);
        }
        return delegate.requireChild(element);
    }

    @Override
    public boolean hasChildren(final String childType) {
        if (LOG_FILE.equals(childType)) {
            final String logDir = pathManager.getPathEntry(ServerEnvironment.SERVER_LOG_DIR).resolvePath();
            if (logDir != null) {
                final Path dir = Paths.get(logDir);
                final Collection<String> validFileNames = findValidFileNames(getFileHandlersModel());
                // If there's at least one child, then we have children
                for (String name : validFileNames) {
                    final Path file = dir.resolve(name);
                    // First just check if the file exist and if it's readable, if not we need to walk the tree and
                    // look for possibly rotated files.
                    if (Files.exists(file) && Files.isReadable(file)) {
                        return true;
                    } else {
                        // Used an AtomicBoolean as it's mutable
                        final AtomicBoolean found = new AtomicBoolean(false);
                        try {
                            // Walk the tree and look for the first possible match
                            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                                    final Path relativeFile = dir.relativize(file);
                                    final String resourceName = relativeFile.toString();
                                    // Check if the file may be a rotated file
                                    if ((resourceName.equals(name) || resourceName.startsWith(name)) && Files.isReadable(file)) {
                                        found.set(true);
                                        return FileVisitResult.TERMINATE;
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                            if (found.get()) {
                                return true;
                            }
                        } catch (IOException e) {
                            LoggingLogger.ROOT_LOGGER.errorDeterminingChildrenExist(e, childType);
                        }
                    }
                }
            }
            return false;
        }
        return delegate.hasChildren(childType);
    }

    @Override
    public Resource navigate(final PathAddress address) {
        if (address.size() > 0 && LOG_FILE.equals(address.getElement(0).getKey())) {
            if (address.size() > 1) {
                throw new NoSuchResourceException(address.getElement(1));
            }
            return PlaceholderResource.INSTANCE;
        }
        return delegate.navigate(address);
    }

    @Override
    public Set<String> getChildTypes() {
        final Set<String> result = new LinkedHashSet<>(delegate.getChildTypes());
        result.add(LOG_FILE);
        return result;
    }

    @Override
    public Set<String> getChildrenNames(final String childType) {
        if (LOG_FILE.equals(childType)) {
            final String logDir = pathManager.getPathEntry(ServerEnvironment.SERVER_LOG_DIR).resolvePath();
            try {
                final Set<Path> validPaths = findFiles(logDir, getFileHandlersModel(), true);
                final Set<String> result = new LinkedHashSet<>();
                for (Path p : validPaths) {
                    result.add(p.toString());
                }
                return result;
            } catch (IOException e) {
                LoggingLogger.ROOT_LOGGER.errorProcessingLogDirectory(logDir);
            }
        }
        return delegate.getChildrenNames(childType);
    }

    @Override
    public Set<ResourceEntry> getChildren(final String childType) {
        if (LOG_FILE.equals(childType)) {
            final Set<String> names = getChildrenNames(childType);
            final Set<ResourceEntry> result = new LinkedHashSet<>(names.size());
            for (String name : names) {
                result.add(new PlaceholderResourceEntry(LOG_FILE, name));
            }
            return result;
        }
        return delegate.getChildren(childType);
    }

    @Override
    public void registerChild(final PathElement address, final Resource resource) {
        final String type = address.getKey();
        if (LOG_FILE.equals(type)) {
            throw LoggingLogger.ROOT_LOGGER.cannotRegisterResourceOfType(type);
        }
        delegate.registerChild(address, resource);
    }

    @Override
    public void registerChild(PathElement address, int index, Resource resource) {
        final String type = address.getKey();
        if (LOG_FILE.equals(type)) {
            throw LoggingLogger.ROOT_LOGGER.cannotRegisterResourceOfType(type);
        }
        delegate.registerChild(address, index, resource);
    }

    @Override
    public Resource removeChild(final PathElement address) {
        final String type = address.getKey();
        if (LOG_FILE.equals(type)) {
            throw LoggingLogger.ROOT_LOGGER.cannotRemoveResourceOfType(type);
        }
        return delegate.removeChild(address);
    }

    @Override
    public boolean isRuntime() {
        return delegate.isRuntime();
    }

    @Override
    public boolean isProxy() {
        return delegate.isProxy();
    }

    @Override
    public Set<String> getOrderedChildTypes() {
        return Collections.emptySet();
    }

    @Override
    public Resource clone() {
        return new LoggingResource(delegate.clone(), pathManager);
    }

    private boolean hasReadableFile(final String fileName) {
        final String logDir = pathManager.getPathEntry(ServerEnvironment.SERVER_LOG_DIR).resolvePath();
        if (logDir != null) {
            final Path dir = Paths.get(logDir);
            final Collection<String> validFileNames = findValidFileNames(getFileHandlersModel());
            for (String name : validFileNames) {
                // Check if the file name is a valid file name or a possibly rotated valid file name
                if ((fileName.equals(name) || fileName.startsWith(name))) {
                    final Path file = dir.resolve(fileName);
                    return Files.exists(file) && Files.isReadable(file);
                }
            }
        }
        return false;
    }

    private ModelNode getFileHandlersModel() {
        return Tools.readModel(delegate, -1, FileHandlerResourceFilter.INSTANCE);
    }


    /**
     * Finds all the files in the {@code jboss.server.log.dir} that are defined on a known file handler. Files in
     * subdirectories are also returned.
     *
     * @param logDir     the log directory to look fr files
     * @param model      the model used to resolve the file handlers
     * @param relativize {@code true} to return a set of paths relative to the log directory
     *
     * @return a list of paths or an empty list if no files were found
     */
    static Set<Path> findFiles(final String logDir, final ModelNode model, final boolean relativize) throws IOException {
        if (logDir == null) {
            return Collections.emptySet();
        }
        // Get the list of valid file names
        final Collection<String> validFileNames = findValidFileNames(model);
        final Set<Path> logFiles = new TreeSet<>();
        final Path dir = Paths.get(logDir);
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            boolean first = true;

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                if (first || relativize) {
                    first = false;
                    return FileVisitResult.CONTINUE;
                }
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final Path relativeFile = dir.relativize(file);
                final String resourceName = relativeFile.toString();
                // Check each valid file name, rotated files will just start with the name
                for (String name : validFileNames) {
                    if ((resourceName.equals(name) || resourceName.startsWith(name)) && Files.isReadable(file)) {
                        if (relativize) {
                            logFiles.add(relativeFile);
                        } else {
                            logFiles.add(file);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return logFiles;
    }

    private static Collection<String> findValidFileNames(final ModelNode model) {
        final Collection<String> names = new ArrayList<>();
        // Get all the file names from the model
        for (Property resource : model.asPropertyList()) {
            final String name = resource.getName();
            if (FILE_RESOURCE_NAMES.contains(name)) {
                for (Property handlerResource : resource.getValue().asPropertyList()) {
                    final ModelNode handlerModel = handlerResource.getValue();
                    // This should always exist, but better to be safe
                    if (handlerModel.hasDefined(CommonAttributes.FILE.getName())) {
                        final ModelNode fileModel = handlerModel.get(CommonAttributes.FILE.getName());
                        // Only allow from the jboss.server.log.dir
                        if (fileModel.hasDefined(PathResourceDefinition.RELATIVE_TO.getName())
                                && ServerEnvironment.SERVER_LOG_DIR.equals(fileModel.get(PathResourceDefinition.RELATIVE_TO.getName()).asString())
                                && fileModel.hasDefined(PathResourceDefinition.PATH.getName())) {
                            names.add(fileModel.get(PathResourceDefinition.PATH.getName()).asString());
                        }
                    }
                }
            }
        }
        return names;
    }

    private static class FileHandlerResourceFilter implements ResourceFilter {

        static final FileHandlerResourceFilter INSTANCE = new FileHandlerResourceFilter();

        @Override
        public boolean accepts(final PathAddress address, final Resource resource) {
            final PathElement last = address.getLastElement();
            return last == null || FILE_RESOURCE_NAMES.contains(last.getKey());
        }
    }
}
