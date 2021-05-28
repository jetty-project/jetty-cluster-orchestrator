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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

class NodePath implements Path
{
    private final NodeFileSystem fileSystem;
    private final NodePath basePath;
    private final List<String> pathSegments;

    NodePath(NodeFileSystem fileSystem, NodePath basePath, List<String> pathSegments)
    {
        this.fileSystem = fileSystem;
        this.basePath = basePath;
        this.pathSegments = pathSegments;
    }

    List<String> getPathSegments()
    {
        return pathSegments;
    }

    static List<String> toSegments(String path)
    {
        if (path.equals(NodeFileSystem.PATH_SEPARATOR))
            return Collections.singletonList(NodeFileSystem.PATH_SEPARATOR);
        String[] segments = path.split(NodeFileSystem.PATH_SEPARATOR);
        return Arrays.stream(segments).filter(s -> !"".equals(s)).collect(Collectors.toList());
    }

    @Override
    public Iterator<Path> iterator()
    {
        return new Iterator<Path>()
        {
            private int i = 0;

            @Override
            public boolean hasNext()
            {
                return (i < getNameCount());
            }

            @Override
            public Path next()
            {
                if (i < getNameCount())
                {
                    Path result = getName(i);
                    i++;
                    return result;
                }
                else
                {
                    throw new NoSuchElementException();
                }
            }
        };
    }

    @Override
    public File toFile()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NodePath resolve(String other)
    {
        boolean absolute = other.startsWith(NodeFileSystem.PATH_SEPARATOR);
        return resolve(absolute, toSegments(other));
    }

    NodePath resolve(boolean absolute, List<String> segments)
    {
        NodePath basePath = absolute ? null : this;
        return new NodePath(fileSystem, basePath, segments);
    }

    @Override
    public FileSystem getFileSystem()
    {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute()
    {
        return basePath == null;
    }

    @Override
    public Path getRoot()
    {
        return new NodePath(fileSystem, null, Collections.emptyList());
    }

    @Override
    public Path getFileName()
    {
        if (pathSegments.isEmpty())
            return null;
        return this;
    }

    @Override
    public Path getParent()
    {
        if (getNameCount() == 0)
            return this;
        return getName(getNameCount() - 1);
    }

    @Override
    public int getNameCount()
    {
        return toAbsolutePath().pathSegments.size();
    }

    @Override
    public Path getName(int index)
    {
        if (index >= pathSegments.size())
            throw new IllegalArgumentException("index " + index + " too big for " + this);
        List<String> parentSegments = new ArrayList<>(pathSegments);
        for (int i = 0; i < index; i++)
            parentSegments.remove(parentSegments.size() - 1);
        return new NodePath(fileSystem, basePath, parentSegments);
    }

    @Override
    public Path resolve(Path other)
    {
        if (other.isAbsolute())
            return other;
        if (other.getNameCount() == 0)
            return this;
        return new NodePath(fileSystem, this, toSegments(other.toString()));
    }

    @Override
    public URI toUri()
    {
        if (!isAbsolute())
            return toAbsolutePath().toUri();
        return URI.create(fileSystem.provider().getScheme() + ":" + fileSystem.getHostId() + "!/" + toAbsolutePath());
    }

    @Override
    public NodePath toAbsolutePath()
    {
        if (isAbsolute())
            return this;
        NodePath absoluteBasePath = basePath.toAbsolutePath();
        List<String> segments = new ArrayList<>();
        segments.addAll(absoluteBasePath.pathSegments);
        segments.addAll(pathSegments);
        return new NodePath(fileSystem, null, segments);
    }

    @Override
    public Path toRealPath(LinkOption... options)
    {
        return this;
    }

    @Override
    public Path resolveSibling(Path other)
    {
        return getParent().resolve(other);
    }

    @Override
    public Path resolveSibling(String other)
    {
        return getParent().resolve(other);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean startsWith(String other)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean startsWith(Path other)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean endsWith(String other)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean endsWith(Path other)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public Path normalize()
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public Path relativize(Path other)
    {
        String thisAbsolute = toAbsolutePath().toString();
        String otherAbsolute = other.toAbsolutePath().toString();

        int idx = otherAbsolute.indexOf(thisAbsolute);
        if (idx == -1)
            throw new IllegalArgumentException();

        List<String> segments = toSegments(otherAbsolute.substring(idx + thisAbsolute.length()));
        return new NodePath(fileSystem, this, segments);
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Path other)
    {
        NodePath otherNodePath = (NodePath)other;
        return toString().compareTo(otherNodePath.toString());
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodePath other = (NodePath)o;
        return fileSystem == other.fileSystem && Objects.equals(basePath, other.basePath) && Objects.equals(pathSegments, other.pathSegments);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(basePath);
        result = 31 * result + pathSegments.hashCode();
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        boolean windows = fileSystem != null && fileSystem.isWindows();
        char separator = windows ? '\\' : '/';
        if (isAbsolute() && !windows)
            sb.append(separator);
        for (int i = 0; i < pathSegments.size(); i++)
        {
            String segment = pathSegments.get(i);
            sb.append(segment);
            if (i < pathSegments.size() - 1)
                sb.append(separator);
        }
        return sb.toString();
    }
}
