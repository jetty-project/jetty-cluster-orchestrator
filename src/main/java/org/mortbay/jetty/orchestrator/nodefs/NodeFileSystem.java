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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class NodeFileSystem extends FileSystem
{
    private static final Logger LOG = LoggerFactory.getLogger(NodeFileSystem.class);
    static final String PATH_SEPARATOR = "/";

    private final NodeFileSystemProvider provider;
    private final SFTPClient sftpClient;
    private final String hostId;
    private final NodePath homePath;
    private final NodePath cwdPath;
    private volatile boolean closed;

    NodeFileSystem(NodeFileSystemProvider provider, SFTPClient sftpClient, String hostId, List<String> cwd)
    {
        this.provider = provider;
        this.sftpClient = sftpClient;
        this.hostId = hostId;
        try
        {
            this.homePath = new NodePath(this, null, NodePath.toSegments(sftpClient.canonicalize(".")));
            this.cwdPath = new NodePath(this, homePath, cwd);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    String getHostId()
    {
        return hostId;
    }

    SeekableByteChannel newByteChannel(NodePath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
    {
        byte[] data;
        try
        {
            InMemoryFile inMemoryFile = new InMemoryFile();
            sftpClient.get(homePath.relativize(path).toString(), inMemoryFile);
            data = inMemoryFile.getOutputStream().toByteArray();
        }
        catch (IOException e)
        {
            throw new IOException("Unable to open byte channel for path: " + path, e);
        }

        return new SeekableByteChannel()
        {
            private long position;

            @Override
            public void close()
            {
            }

            @Override
            public boolean isOpen()
            {
                return true;
            }

            @Override
            public long position()
            {
                return position;
            }

            @Override
            public SeekableByteChannel position(long newPosition)
            {
                position = newPosition;
                return this;
            }

            @Override
            public int read(ByteBuffer dst)
            {
                int l = (int)Math.min(dst.remaining(), size() - position);
                dst.put(data, (int)position, l);
                position += l;
                return l;
            }

            @Override
            public long size()
            {
                return data.length;
            }

            @Override
            public SeekableByteChannel truncate(long size)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public int write(ByteBuffer src)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    DirectoryStream<Path> newDirectoryStream(NodePath dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        List<Path> filteredPaths = new ArrayList<>();
        try
        {
            List<RemoteResourceInfo> content = sftpClient.ls(homePath.relativize(dir).toString());
            for (RemoteResourceInfo remoteResourceInfo : content)
            {
                Path resolved = dir.resolve(remoteResourceInfo.getName());
                if (filter.accept(resolved))
                    filteredPaths.add(resolved);
            }
        }
        catch (IOException e)
        {
            throw new IOException("Unable to open directory stream for path: " + dir, e);
        }

        return new DirectoryStream<Path>()
        {
            @Override
            public Iterator<Path> iterator()
            {
                return new Iterator<Path>()
                {
                    private final Iterator<Path> delegate = filteredPaths.iterator();

                    @Override
                    public boolean hasNext()
                    {
                        return delegate.hasNext();
                    }

                    @Override
                    public Path next()
                    {
                        return delegate.next();
                    }

                    @Override
                    public void remove()
                    {
                        throw new UnsupportedOperationException();
                    }
                };
            }
            @Override
            public void close()
            {
            }
        };
    }

    InputStream newInputStream(NodePath path, OpenOption... options) throws IOException
    {
        String sftpPath = homePath.relativize(path).toString();
        long fileSize;
        try
        {
            fileSize = sftpClient.lstat(sftpPath).getSize();
        }
        catch (IOException e)
        {
            throw new IOException("Unable to open input stream for path: " + path, e);
        }
        if (fileSize > 1024 * 1024)
        {
            // use piping if file to download is > 1MB
            PipingFile pipingFile = new PipingFile();
            Thread t = new Thread(() ->
            {
                try
                {
                    sftpClient.get(sftpPath, pipingFile);
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Error copying " + sftpPath + " over sftp", e);
                }
                finally
                {
                    IOUtil.close(pipingFile.getOutputStream());
                }
            });
            t.setDaemon(true);
            t.start();
            return pipingFile.getInputStream();
        }
        else
        {
            InMemoryFile inMemoryFile = new InMemoryFile();
            sftpClient.get(sftpPath, inMemoryFile);
            byte[] data = inMemoryFile.getOutputStream().toByteArray();
            return new ByteArrayInputStream(data);
        }
    }

    <A extends BasicFileAttributes> A readAttributes(NodePath path, Class<A> type, LinkOption... options) throws IOException
    {
        if (!type.equals(BasicFileAttributes.class) && !type.equals(NodeFileAttributes.class))
            throw new UnsupportedOperationException();

        String sftpPath = homePath.relativize(path).toString();
        try
        {
            FileAttributes lstat = sftpClient.lstat(sftpPath);
            NodeFileAttributes nodeFileAttributes = new NodeFileAttributes(lstat);
            return (A)nodeFileAttributes;
        }
        catch (IOException e)
        {
            throw new IOException("Error reading attributes of path: " + path, e);
        }
    }

    @Override
    public FileSystemProvider provider()
    {
        return provider;
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            sftpClient.close();
        }
        finally
        {
            provider.remove(hostId);
            closed = true;
        }
    }

    @Override
    public boolean isOpen()
    {
        return !closed;
    }

    @Override
    public boolean isReadOnly()
    {
        return true;
    }

    @Override
    public String getSeparator()
    {
        return PATH_SEPARATOR;
    }

    @Override
    public Iterable<Path> getRootDirectories()
    {
        return Collections.singleton(new NodePath(this, null, Collections.emptyList()));
    }

    @Override
    public Iterable<FileStore> getFileStores()
    {
        return Collections.emptySet();
    }

    @Override
    public Set<String> supportedFileAttributeViews()
    {
        return Collections.emptySet();
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
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        return "NodeFileSystem{" +
            "hostId='" + hostId + '\'' +
            '}';
    }
}
