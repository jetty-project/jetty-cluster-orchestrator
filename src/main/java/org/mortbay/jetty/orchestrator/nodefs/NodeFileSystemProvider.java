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
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sshd.client.SshClient;

/**
 * URI format is:
 * <code>jco:[hostid]{!/[path]}</code>
 */
public class NodeFileSystemProvider extends FileSystemProvider
{
    public static final String SCHEME = "jco";
    public static final String IS_WINDOWS_ENV = "windows";
    public static final String SFTP_HOST_ENV = "host";
    public static final String SFTP_PORT_ENV = "port";
    public static final String SFTP_USERNAME_ENV = "username";
    public static final String SFTP_PASSWORD_ENV = "password";

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
        throw new UnsupportedOperationException();
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
            boolean windows = (Boolean)env.get(IS_WINDOWS_ENV);
            String sftpHost = (String)env.get(SFTP_HOST_ENV);
            Integer sftpPort = (Integer)env.get(SFTP_PORT_ENV);
            String sftpUsername = (String)env.get(SFTP_USERNAME_ENV);
            char[] sftpPassword = (char[])env.get(SFTP_PASSWORD_ENV);
            SshClient sshClient = (SshClient)env.get(SshClient.class.getName());
            String hostId = extractHostId(uri);
            if (fileSystems.containsKey(hostId))
                throw new FileSystemAlreadyExistsException("FileSystem already exists: " + hostId);

            NodeFileSystem fileSystem = new NodeFileSystem(this, sshClient, hostId, extractPath(uri), windows, sftpHost, sftpPort, sftpUsername, sftpPassword);
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

    void remove(String hostId)
    {
        synchronized (fileSystems)
        {
            fileSystems.remove(hostId);
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

    private static String extractPath(URI uri)
    {
        String nodeId = uri.getSchemeSpecificPart();
        int i = nodeId.indexOf("!/");
        if (i == -1)
            return "";
        return nodeId.substring(i + 1);
    }

    private static String extractPathAsString(URI uri)
    {
        String nodeId = uri.getSchemeSpecificPart();
        int i = nodeId.indexOf("!/");
        if (i == -1)
            return ".";
        return nodeId.substring(i + 1);
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
            return fileSystem.getPath(extractPathAsString(uri));
        }
    }

    @Override
    public String getScheme()
    {
        return SCHEME;
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
        return path.getFileSystem().provider().newInputStream(path, options);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
    {
        return path.getFileSystem().provider().newByteChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        if (!(dir instanceof NodePath))
            throw new ProviderMismatchException("Path is not NodePath: " + dir.getClass());
        NodeFileSystem delegateFileSystem = (NodeFileSystem)dir.getFileSystem();
        Path path = delegateFileSystem.delegatePath((NodePath) dir);
        return delegateFileSystem.delegateProvider().newDirectoryStream(path, filter);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException
    {
        return path.getFileSystem().provider().readAttributes(path, type, options);
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
        int[] masks = new int[modes.length];
        for (int i = 0; i < modes.length; i++)
        {
            AccessMode mode = modes[i];
            int mask = ACCESS_MODES_MASKS.get(mode);
            masks[i] = mask;
        }

//        NodeFileAttributes attributes = readAttributes(path, NodeFileAttributes.class);
//        for (FilePermission permission : attributes.getLstat().getPermissions())
//        {
//            if (masks.length == 0) // existence check
//                return;
//            for (int mask : masks)
//            {
//                if (permission.isIn(mask))
//                    return;
//            }
//        }
        throw new IOException("Access check failed");
    }
}
