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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NodeFileSystem extends FileSystem
{
    private static final Logger LOG = LoggerFactory.getLogger(NodeFileSystem.class);
    static final String PATH_SEPARATOR = "/";

    private final NodeFileSystemProvider provider;
    private final String hostId;
    private final boolean windows;
    private final FileSystem delegate;

    NodeFileSystem(NodeFileSystemProvider provider, SshClient sshClient, String hostId, List<String> cwd, boolean windows)
    {
        this.provider = provider;
        this.hostId = hostId;
        this.windows = windows;

        SftpFileSystemProvider sftpFileSystemProvider = new SftpFileSystemProvider(sshClient);
        URI uri = SftpFileSystemProvider.createFileSystemURI("localhost", 22, "lorban", null);

        try
        {
            delegate = sftpFileSystemProvider.newFileSystem(uri, Collections.emptyMap());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
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
        return delegate.getRootDirectories();
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
        return delegate.getPath(first, more);
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
