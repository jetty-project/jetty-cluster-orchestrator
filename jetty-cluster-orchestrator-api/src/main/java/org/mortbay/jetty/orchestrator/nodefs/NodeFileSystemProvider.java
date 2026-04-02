//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.mortbay.jetty.orchestrator.nodefs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * URI format is:
 * <code>jco:[hostid]{!/[path]}</code>
 */
public class NodeFileSystemProvider extends FileSystemProvider
{
    public static final String PREFIX = "jco";
    public static final String IS_WINDOWS_ENV_PROPERTY = "windows";
    
    // Kubernetes environment property keys
    public static final String K8S_NAMESPACE_ENV_PROPERTY = "namespace";
    public static final String K8S_POD_NAME_ENV_PROPERTY = "podName";
    public static final String K8S_POD_HOME_ENV_PROPERTY = "podHome";
    
    private static final Map<AccessMode, Integer> ACCESS_MODES_MASKS = new EnumMap<>(AccessMode.class);
    static
    {
        ACCESS_MODES_MASKS.put(AccessMode.EXECUTE, 0111); // Yes, octal.
        ACCESS_MODES_MASKS.put(AccessMode.WRITE, 0222); // Yes, octal.
        ACCESS_MODES_MASKS.put(AccessMode.READ, 0444); // Yes, octal.
    }

    private final Map<String, NodeFileSystem> fileSystems = new HashMap<>();

    public NodeFileSystemProvider()
    {
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options)
    {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs)
    {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path)
    {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options)
    {
        if (Objects.requireNonNull(type) == BasicFileAttributeView.class)
        {
            BasicFileAttributeView basicFileAttributeView = new BasicFileAttributeView()
            {
                @Override
                public String name()
                {
                    return "basic";
                }

                @Override
                public BasicFileAttributes readAttributes() throws IOException
                {
                    return ((NodeFileSystem)path.getFileSystem()).readAttributes((NodePath)path, BasicFileAttributes.class, options);
                }

                @Override
                public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime)
                {
                    throw new UnsupportedOperationException();
                }
            };
            @SuppressWarnings("unchecked")
            V v = (V)basicFileAttributeView;
            return v;
        }
        return null;
    }

    @Override
    public FileStore getFileStore(Path path)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env)
    {
        synchronized (fileSystems)
        {
            String hostId = extractHostId(uri);
            if (fileSystems.containsKey(hostId))
                throw new FileSystemAlreadyExistsException("FileSystem already exists: " + hostId);

            NodeFileSystem fileSystem = null;
            
            // Discover and try all available filesystem factories
            ServiceLoader<NodeFileSystemFactory> loader = ServiceLoader.load(NodeFileSystemFactory.class);
            List<NodeFileSystemFactory> factories = new ArrayList<>();
            for (NodeFileSystemFactory factory : loader)
            {
                factories.add(factory);
            }
            
            // Sort by priority (highest first)
            factories.sort(Comparator.comparingInt(NodeFileSystemFactory::getPriority).reversed());
            
            for (NodeFileSystemFactory factory : factories)
            {
                if (factory.canHandle(env))
                {
                    try
                    {
                        fileSystem = factory.createFileSystem(this, uri, env);
                        break;
                    }
                    catch (IOException e)
                    {
                        throw new IllegalArgumentException("Failed to create filesystem", e);
                    }
                }
            }
            
            if (fileSystem == null)
            {
                throw new IllegalArgumentException("No filesystem factory found that can handle the provided environment");
            }
            fileSystems.put(hostId, fileSystem);
            return fileSystem;
        }
    }

    @Override
    public FileSystem getFileSystem(URI uri)
    {
        synchronized (fileSystems)
        {
            String hostId = extractHostId(uri);
            NodeFileSystem fileSystem = fileSystems.get(hostId);
            if (fileSystem == null)
                throw new FileSystemNotFoundException(uri.toString());
            return fileSystem;
        }
    }

    public void remove(String hostId)
    {
        synchronized (fileSystems)
        {
            fileSystems.remove(hostId); // closing is done by the caller
        }
    }

    private static String extractHostId(URI uri)
    {
        String nodeId = uri.getSchemeSpecificPart();
        int i = nodeId.indexOf("!/");
        if (i >= 0)
            return nodeId.substring(0, i);
        return nodeId;
    }

    private static List<String> extractPath(URI uri)
    {
        String nodeId = uri.getSchemeSpecificPart();
        int i = nodeId.indexOf("!/");
        if (i == -1)
            return Collections.emptyList();
        return NodePath.toSegments(nodeId.substring(i + 1));
    }

    @Override
    public Path getPath(URI uri)
    {
        synchronized (fileSystems)
        {
            String hostId = extractHostId(uri);
            NodeFileSystem fileSystem = fileSystems.get(hostId);
            if (fileSystem == null)
                throw new FileSystemNotFoundException(uri.toString());
            return fileSystem.getPath(false, extractPath(uri));
        }
    }

    @Override
    public String getScheme()
    {
        return PREFIX;
    }

    @Override
    public boolean isHidden(Path path)
    {
        return false;
    }

    @Override
    public boolean isSameFile(Path path, Path path2)
    {
        return path.toAbsolutePath().equals(path2.toAbsolutePath());
    }

    @Override
    public void move(Path source, Path target, CopyOption... options)
    {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException
    {
        if (!(path instanceof NodePath))
            throw new ProviderMismatchException();
        return ((NodeFileSystem)path.getFileSystem()).newInputStream((NodePath)path, options);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
    {
        if (!(path instanceof NodePath))
            throw new ProviderMismatchException();
        return ((NodeFileSystem)path.getFileSystem()).newByteChannel((NodePath)path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        if (!(dir instanceof NodePath))
            throw new ProviderMismatchException();
        return ((NodeFileSystem)dir.getFileSystem()).newDirectoryStream((NodePath)dir, filter);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException
    {
        return ((NodeFileSystem)path.getFileSystem()).readAttributes((NodePath)path, type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
    {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException
    {
        NodePath nodePath = toNodePath(path);
        NodeFileSystem nodeFileSystem = (NodeFileSystem) nodePath.getFileSystem();
        nodeFileSystem.checkAccess(nodePath, modes);
    }
    
    private NodePath toNodePath(Path path)
    {
        if (!(path instanceof NodePath))
            throw new ProviderMismatchException();
        return (NodePath) path;
    }
}
