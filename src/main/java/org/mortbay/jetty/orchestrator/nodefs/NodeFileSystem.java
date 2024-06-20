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
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider;
import org.apache.sshd.sftp.common.SftpConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NodeFileSystem extends FileSystem
{
    private static final Logger LOG = LoggerFactory.getLogger(NodeFileSystem.class);
    static final String PATH_SEPARATOR = "/";

    private final NodeFileSystemProvider provider;
    private final String hostId;
    private final boolean windows;
    private final NodePath homePath;
    private final NodePath cwdPath;
    private final FileSystem delegate;

    NodeFileSystem(NodeFileSystemProvider provider, SshClient sshClient, String hostId, String cwd, boolean windows, String sftpHost, int sftpPort, String sftpUsername, char[] sftpPassword)
    {
        this.provider = provider;
        this.hostId = hostId;
        this.windows = windows;

        SftpFileSystemProvider sftpFileSystemProvider = new SftpFileSystemProvider(sshClient);


        URI uri = SftpFileSystemProvider.createFileSystemURI(sftpHost, sftpPort, sftpUsername, sftpPassword == null || sftpPassword.length == 0 ? null : new String(sftpPassword));

        try
        {
            //String userAuth = SftpFileSystemProvider.encodeCredentials(sftpUsername, sftpPassword == null || sftpPassword.length == 0 ? null : new String(sftpPassword));
            //URI uri = new URI(SftpConstants.SFTP_SUBSYSTEM_NAME, userAuth, sftpHost, sftpPort, cwd, null, null);
            delegate = sftpFileSystemProvider.newFileSystem(uri, Collections.emptyMap());
            this.homePath = new NodePath(this, null, NodePath.toSegments(delegate.getPath(".").toAbsolutePath().normalize().toString()));
            this.cwdPath = new NodePath(this, homePath, NodePath.toSegments(cwd));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public Path delegatePath(NodePath path)
    {
        if (!path.isAbsolute())
            return delegate.getPath(cwdPath.resolve(path).toAbsolutePath().toString());
        else
            return delegate.getPath(path.toAbsolutePath().toString());
    }

    boolean isWindows()
    {
        return windows;
    }

    String getHostId()
    {
        return hostId;
    }

    @Override
    public FileSystemProvider provider()
    {
        return provider;
    }

    public FileSystemProvider delegateProvider()
    {
        return delegate.provider();
    }

    @Override
    public void close() throws IOException
    {
        delegate.close();
    }

    @Override
    public boolean isOpen()
    {
        return delegate.isOpen();
    }

    @Override
    public boolean isReadOnly()
    {
        return delegate.isReadOnly();
    }

    @Override
    public String getSeparator()
    {
        return delegate.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories()
    {
        return Collections.singleton(new NodePath(this, null, Collections.emptyList()));
    }

    @Override
    public Iterable<FileStore> getFileStores()
    {
        return delegate.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews()
    {
        return delegate.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more)
    {
        boolean absolute = first.startsWith(PATH_SEPARATOR);
        List<String> segments = new ArrayList<>(NodePath.toSegments(first));
        for (String s : more)
            segments.addAll(NodePath.toSegments(s));
        return getPath(absolute, segments);
    }

    Path getPath(boolean absolute, List<String> segments)
    {
        return cwdPath.resolve(absolute, segments);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern)
    {
        return delegate.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService()
    {
        return delegate.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException
    {
        return delegate.newWatchService();
    }

    @Override
    public String toString()
    {
        return "NodeFileSystem{" +
            "hostId='" + hostId + '\'' +
            '}';
    }
}
