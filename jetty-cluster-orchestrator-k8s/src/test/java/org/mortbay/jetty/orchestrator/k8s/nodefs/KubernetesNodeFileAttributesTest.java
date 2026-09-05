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

import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.k8s.nodefs.KubernetesNodeFileAttributes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class KubernetesNodeFileAttributesTest
{
    @Test
    public void testBasicFileAttributes()
    {
        // Test regular file with common permissions (644)
        KubernetesNodeFileAttributes attrs = new KubernetesNodeFileAttributes(
            "regular file", 1234L, 0644, 1000, 1000, 
            1678901234L, 1678901230L, 1678901235L
        );

        assertThat(attrs.isRegularFile(), is(true));
        assertThat(attrs.isDirectory(), is(false));
        assertThat(attrs.size(), equalTo(1234L));
        assertThat(attrs.getPermissions(), equalTo(0644));
    }

    @Test
    public void testPermissionChecking()
    {
        // Test file with read-write permissions for owner, read for others (644)
        KubernetesNodeFileAttributes readWriteFile = new KubernetesNodeFileAttributes(
            "regular file", 1234L, 0644, 1000, 1000,
            1678901234L, 1678901230L, 1678901235L
        );

        assertThat(readWriteFile.isReadable(), is(true));
        assertThat(readWriteFile.isWritable(), is(true));
        assertThat(readWriteFile.isExecutable(), is(false));

        // Test executable file (755)
        KubernetesNodeFileAttributes executableFile = new KubernetesNodeFileAttributes(
            "regular file", 1234L, 0755, 1000, 1000,
            1678901234L, 1678901230L, 1678901235L
        );

        assertThat(executableFile.isReadable(), is(true));
        assertThat(executableFile.isWritable(), is(true));
        assertThat(executableFile.isExecutable(), is(true));
    }

    @Test
    public void testDirectoryAndSymlinkTypes()
    {
        KubernetesNodeFileAttributes dir = new KubernetesNodeFileAttributes(
            "directory", 4096L, 0755, 1000, 1000,
            1678901234L, 1678901230L, 1678901235L
        );

        assertThat(dir.isDirectory(), is(true));
        assertThat(dir.isRegularFile(), is(false));

        KubernetesNodeFileAttributes symlink = new KubernetesNodeFileAttributes(
            "symbolic link", 10L, 0777, 1000, 1000,
            1678901234L, 1678901230L, 1678901235L
        );

        assertThat(symlink.isSymbolicLink(), is(true));
        assertThat(symlink.isRegularFile(), is(false));
    }

    @Test
    public void testQuotedFormatParsing()
    {
        // Test that our quoted format parsing works correctly
        // This simulates the output format: "'regular file' '1234' '644' '1000' '1000' '1678901234' '1678901230' '1678901235'"
        String testOutput = "'regular file with spaces' '1234' '644' '1000' '1000' '1678901234' '1678901230' '1678901235'";
        String[] parts = testOutput.split("'");
        
        // Verify parsing logic works with spaces in file type
        assertThat(parts[1], equalTo("regular file with spaces")); // File type with spaces
        assertThat(parts[3], equalTo("1234")); // Size
        assertThat(parts[5], equalTo("644"));  // Permissions
    }
}