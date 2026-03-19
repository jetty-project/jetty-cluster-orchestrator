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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class NodePathTest
{
    @Test
    public void testAbsolutePaths()
    {
        NodePath root1 = new NodePath(null, null, Collections.emptyList());
        assertThat(root1.isAbsolute(), is(true));
        assertThat(root1.toString(), is("/"));
        NodePath root2 = new NodePath(null, null, Arrays.asList("a", "b", "c"));
        assertThat(root2.isAbsolute(), is(true));
        assertThat(root2.toString(), is("/a/b/c"));
    }

    @Test
    public void testToAbsolutePath()
    {
        NodePath root = new NodePath(null, null, Arrays.asList("a", "b", "c"));
        assertThat(root.isAbsolute(), is(true));
        assertThat(root.toAbsolutePath().isAbsolute(), is(true));
        assertThat(root.toString(), is("/a/b/c"));
        assertThat(root.toAbsolutePath().toString(), is("/a/b/c"));

        NodePath child = root.resolve("d");
        assertThat(child.isAbsolute(), is(false));
        assertThat(child.toAbsolutePath().isAbsolute(), is(true));
        assertThat(child.toString(), is("d"));
        assertThat(child.toAbsolutePath().toString(), is("/a/b/c/d"));
    }

    @Test
    public void testResolve()
    {
        NodePath root = new NodePath(null, null, Arrays.asList("a", "b", "c"));

        NodePath child1 = root.resolve("d/e/f");
        assertThat(child1.isAbsolute(), is(false));
        assertThat(child1.toString(), is("d/e/f"));
        assertThat(child1.toAbsolutePath().toString(), is("/a/b/c/d/e/f"));

        Path child2 = root.resolve(child1);
        assertThat(child2.isAbsolute(), is(false));
        assertThat(child2.toString(), is("d/e/f"));
        assertThat(child2.toAbsolutePath().toString(), is("/a/b/c/d/e/f"));
    }

    @Test
    public void testResolveResolve()
    {
        NodePath root = new NodePath(null, null, Arrays.asList("a", "b", "c"));

        NodePath child1 = root.resolve("d/e/f");
        assertThat(child1.isAbsolute(), is(false));
        assertThat(child1.toString(), is("d/e/f"));
        assertThat(child1.toAbsolutePath().toString(), is("/a/b/c/d/e/f"));

        Path child11 = child1.resolve("g/h/i");
        assertThat(child11.isAbsolute(), is(false));
        assertThat(child11.toString(), is("g/h/i"));
        assertThat(child11.toAbsolutePath().toString(), is("/a/b/c/d/e/f/g/h/i"));

        Path child12 = child1.resolve(child11);
        assertThat(child12.isAbsolute(), is(false));
        assertThat(child12.toString(), is("g/h/i"));
        assertThat(child12.toAbsolutePath().toString(), is("/a/b/c/d/e/f/g/h/i"));
    }

    @Test
    public void testRelativize()
    {
        NodePath absolute1 = new NodePath(null, null, Arrays.asList("a", "b", "c"));
        NodePath absolute2 = new NodePath(null, null, Arrays.asList("a", "b", "c", "d", "e", "f"));
        NodePath relative1 = absolute1.resolve("d/e/f");

        Path child1 = absolute1.relativize(absolute2);
        assertThat(child1.isAbsolute(), is(false));
        assertThat(child1.toString(), is("d/e/f"));
        assertThat(child1.toAbsolutePath().toString(), is("/a/b/c/d/e/f"));

        Path child2 = absolute1.relativize(relative1);
        assertThat(child2.isAbsolute(), is(false));
        assertThat(child2.toString(), is("d/e/f"));
        assertThat(child2.toAbsolutePath().toString(), is("/a/b/c/d/e/f"));
    }
}
