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

package org.mortbay.jetty.orchestrator.k8s.nodefs;

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
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystem;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.nodefs.NodePath;

/**
 * NIO FileSystem backed by a Kubernetes pod (Fabric8 client).
 * Registered at pod launch time so that {@code ReportUtil.download()} can
 * resolve {@code jco:} URIs without any SSH/SFTP involvement.
 */
public class KubernetesNodeFileSystem extends NodeFileSystem
{
    static final String PATH_SEPARATOR = "/";
    
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
    public String getHostId()
    {
        return hostId;
    }

    @Override
    public boolean isWindows()
    {
        return false;
    }

    @Override
    public InputStream newInputStream(NodePath path, OpenOption... options) throws IOException
    {
        String abs = absolutePathString(path);
        return client.pods().inNamespace(namespace).withName(podName).file(abs).read();
    }

    @Override
    public SeekableByteChannel newByteChannel(NodePath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
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
    public DirectoryStream<Path> newDirectoryStream(NodePath dir, DirectoryStream.Filter<? super Path> filter) throws IOException
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
    public <A extends BasicFileAttributes> A readAttributes(NodePath path, Class<A> type, LinkOption... options) throws IOException
    {
        Objects.requireNonNull(type);
        if (!type.equals(BasicFileAttributes.class) && !type.equals(KubernetesNodeFileAttributes.class))
            throw new UnsupportedOperationException("Unsupported attribute type: " + type);

        String abs = absolutePathString(path);
        String output;
        try
        {
            // Enhanced stat format with quotes for robust parsing
            // Format: '%F' '%s' '%a' '%u' '%g' '%Y' '%X' '%Z'
            output = podRunAndCollect("stat", "--format='%F' '%s' '%a' '%u' '%g' '%Y' '%X' '%Z'", abs).trim();
        }
        catch (IOException e)
        {
            throw new IOException("Error reading attributes of path: " + path, e);
        }

        // Parse quoted stat output: "'%F' '%s' '%a' '%u' '%g' '%Y' '%X' '%Z'"
        // e.g. "'regular file' '1234' '755' '1000' '1000' '1678901234' '1678901230' '1678901235'"
        // Split on single quotes and extract every other element (skip empty strings between quotes)
        String[] parts = output.split("'");
        if (parts.length < 15) // Should have 16 parts: empty + 8 quoted fields + 7 separators
            throw new IOException("Unexpected stat output format for " + abs + ": " + output);

        // Extract quoted fields (at indices 1, 3, 5, 7, 9, 11, 13, 15)
        String fileType = parts[1];
        String sizeStr = parts[3];
        String permissionsStr = parts[5];
        String userIdStr = parts[7];
        String groupIdStr = parts[9];
        String mtimeStr = parts[11];
        String atimeStr = parts[13];
        String ctimeStr = parts[15];

        try
        {
            long size = Long.parseLong(sizeStr);
            int permissions = Integer.parseInt(permissionsStr, 8); // Parse as octal
            int userId = Integer.parseInt(userIdStr);
            int groupId = Integer.parseInt(groupIdStr);
            long mtimeSeconds = Long.parseLong(mtimeStr);
            long atimeSeconds = Long.parseLong(atimeStr);
            long ctimeSeconds = Long.parseLong(ctimeStr);

            KubernetesNodeFileAttributes result = new KubernetesNodeFileAttributes(
                fileType, size, permissions, userId, groupId, 
                mtimeSeconds, atimeSeconds, ctimeSeconds);

            return (A)result;
        }
        catch (NumberFormatException e)
        {
            throw new IOException("Failed to parse stat output for " + abs + ": " + output, e);
        }
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

    @Override
    public Path getPath(boolean absolute, List<String> segments)
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
