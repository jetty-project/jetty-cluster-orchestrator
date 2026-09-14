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

package org.mortbay.jetty.orchestrator.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FilenameSupplierTest {
    @Test
    public void testMavenVersion() {
        assertTrue(mavenVersion("17").matches("17"));
        assertFalse(mavenVersion("17.0").matches("17"));
        assertTrue(mavenVersion("17").matches("17.0"));
        assertTrue(mavenVersion("17.0").matches("17.0"));
        assertFalse(mavenVersion("17.0.2").matches("17.0"));
        assertTrue(mavenVersion("17").matches("17.0.2"));
        assertTrue(mavenVersion("17.0").matches("17.0.2"));
        assertTrue(mavenVersion("17.0.2").matches("17.0.2"));
        assertTrue(mavenVersion("17").matches("17.0.2.build1"));
        assertTrue(mavenVersion("17.0").matches("17.0.2.build1"));
        assertTrue(mavenVersion("17.0.2").matches("17.0.2.build1"));
        assertTrue(mavenVersion("17").matches("17.0.2-SNAPSHOT"));
        assertTrue(mavenVersion("17.0").matches("17.0.2-SNAPSHOT"));
        assertTrue(mavenVersion("17.0.2").matches("17.0.2-SNAPSHOT"));

        assertFalse(mavenVersion("18").matches("17"));
        assertFalse(mavenVersion("18").matches("17.0"));
        assertFalse(mavenVersion("18").matches("17.0.2"));
        assertFalse(mavenVersion("18").matches("17.0.2.build1"));
        assertFalse(mavenVersion("18").matches("17.0.2-SNAPSHOT"));

        assertTrue(mavenVersion("[17").matches("17.0.2"));
        assertTrue(mavenVersion("[17.0").matches("17.0.2"));
        assertTrue(mavenVersion("[17.0.2").matches("17.0.2"));
        assertTrue(mavenVersion("[17").matches("18.1.0"));
        assertTrue(mavenVersion("[17.0").matches("18.1.0"));
        assertTrue(mavenVersion("[17.0.2").matches("18.1.0"));
    }

    private static FilenameSupplier.MavenToolchains.MavenVersion mavenVersion(String v) {
        return new FilenameSupplier.MavenToolchains.MavenVersion(v);
    }
}
