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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

/**
 * NIO FileSystem backed by a Kubernetes pod (Fabric8 client).
 * Registered at pod launch time so that {@code ReportUtil.download()} can
 * resolve {@code jco:} URIs without any SSH/SFTP involvement.
 */
class KubernetesNodeFileSystem extends NodeFileSystem
{
    private final NodeFileSystemProvider provider;
    private final KubernetesClient client;
    private final String namespace;
    private final String podName;
    private final String hostId;
    private final NodePath homePath;
    private final NodePath cwdPath;
    private volatile boolean closed;

    KubernetesNodeFileSystem(NodeFileSystemProvider provider, KubernetesClient client,
                             String namespace, String podName, String podHome,
                             String hostId, List<String> cwd)
    {
        this.provider = provider;
        this.client = client;
        this.namespace = namespace;
        this.podName = podName;
        this.hostId = hostId;
        this.homePath = new NodePath(this, null, NodePath.toSegments(podHome));
        this.cwdPath = new NodePath(this, homePath, cwd);
    }

    @Override
    String getHostId()
    {
        return hostId;
    }

    @Override
    boolean isWindows()
    {
        return false;
    }

    @Override
    InputStream newInputStream(NodePath path, OpenOption... options) throws IOException
    {
        String abs = absolutePathString(path);
        return client.pods().inNamespace(namespace).withName(podName).file(abs).read();
    }

    @Override
    SeekableByteChannel newByteChannel(NodePath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
    {
        byte[] data;
        try (InputStream is = newInputStream(path))
        {
            data = is.readAllBytes();
        }
        catch (IOException e)
        {
            throw new IOException("Unable to open byte channel for path: " + path, e);
        }

        return new SeekableByteChannel()
        {
            private long position;

            @Override public void close() {}
            @Override public boolean isOpen() { return true; }
            @Override public long position() { return position; }

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

    @Override
    DirectoryStream<Path> newDirectoryStream(NodePath dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        String abs = absolutePathString(dir);
        String output;
        try
        {
            output = podRunAndCollect("ls", "-1a", abs);
        }
        catch (IOException e)
        {
            throw new IOException("Unable to open directory stream for path: " + dir, e);
        }

        List<Path> filteredPaths = new ArrayList<>();
        for (String name : output.split("\n"))
        {
            name = name.trim();
            if (name.isEmpty() || name.equals(".") || name.equals(".."))
                continue;
            Path resolved = dir.resolve(name);
            if (filter.accept(resolved))
                filteredPaths.add(resolved);
        }

        return new DirectoryStream<>()
        {
            @Override
            public Iterator<Path> iterator()
            {
                return new Iterator<>()
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

            @Override public void close() {}
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    <A extends BasicFileAttributes> A readAttributes(NodePath path, Class<A> type, LinkOption... options) throws IOException
    {
        String abs = absolutePathString(path);
        String output;
        try
        {
            output = podRunAndCollect("stat", "--format=%F %s %Y", abs).trim();
        }
        catch (IOException e)
        {
            throw new IOException("Error reading attributes of path: " + path, e);
        }

        // "%F %s %Y" -> e.g. "regular file 1234 1678901234"
        // %F may contain a space ("regular file"), so split from the right.
        int lastSpace = output.lastIndexOf(' ');
        int secondLastSpace = output.lastIndexOf(' ', lastSpace - 1);
        if (lastSpace < 0 || secondLastSpace < 0)
            throw new IOException("Unexpected stat output for " + abs + ": " + output);

        String fileType = output.substring(0, secondLastSpace);
        long size = Long.parseLong(output.substring(secondLastSpace + 1, lastSpace));
        long mtimeSeconds = Long.parseLong(output.substring(lastSpace + 1));
        boolean directory = "directory".equals(fileType);
        boolean regularFile = "regular file".equals(fileType);

        BasicFileAttributes result = new BasicFileAttributes()
        {
            @Override
            public FileTime lastModifiedTime()
            {
                return FileTime.from(mtimeSeconds, TimeUnit.SECONDS);
            }
            @Override
            public FileTime lastAccessTime()
            {
                return lastModifiedTime();
            }
            @Override
            public FileTime creationTime()
            {
                return lastModifiedTime();
            }
            @Override
            public boolean isRegularFile()
            {
                return regularFile;
            }
            @Override
            public boolean isDirectory()
            {
                return directory;
            }
            @Override
            public boolean isSymbolicLink()
            {
                return "symbolic link".equals(fileType);
            }
            @Override
            public boolean isOther()
            {
                return !directory && !regularFile && !isSymbolicLink();
            }
            @Override
            public long size()
            {
                return size;
            }
            @Override
            public Object fileKey()
            {
                return null;
            }
        };

        return (A)result;
    }

    @Override
    public Path getPath(String first, String... more)
    {
        boolean absolute = first.startsWith(SFTPNodeFileSystem.PATH_SEPARATOR);
        List<String> segments = new ArrayList<>(NodePath.toSegments(first));
        for (String s : more)
            segments.addAll(NodePath.toSegments(s));
        return getPath(absolute, segments);
    }

    @Override
    Path getPath(boolean absolute, List<String> segments)
    {
        return cwdPath.resolve(absolute, segments);
    }

    @Override
    public FileSystemProvider provider()
    {
        return provider;
    }

    @Override
    public void close()
    {
        provider.remove(hostId);
        closed = true;
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
        return SFTPNodeFileSystem.PATH_SEPARATOR;
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

    private String absolutePathString(NodePath path)
    {
        return path.toAbsolutePath().toString();
    }

    /** Runs a command inside the pod and returns stdout. Uses 30-second timeout. */
    private String podRunAndCollect(String... command) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Uses Fabric8 pod exec API (String[] command, not a shell string — no injection risk)
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
            .writingOutput(out)
            .exec(command))
        {
            watch.exitCode().get(30, TimeUnit.SECONDS);
            return out.toString(StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            if (e instanceof IOException)
                throw (IOException)e;
            throw new IOException("Pod command failed: " + Arrays.toString(command), e);
        }
    }

    @Override
    public String toString()
    {
        return "KubernetesNodeFileSystem{hostId='" + hostId + "', pod='" + podName + "'}";
    }
}
