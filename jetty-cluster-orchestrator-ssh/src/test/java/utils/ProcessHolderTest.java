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

package utils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.util.ProcessHolder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ProcessHolderTest {
    @Test
    public void testHolder() throws Exception {
        Process process = startBlockingProcess();
        ProcessHolder processHolder = ProcessHolder.from(process);

        boolean alive = processHolder.isAlive();
        assertThat(alive, is(true));

        processHolder.destroy();

        alive = processHolder.isAlive();
        assertThat(alive, is(false));
    }

    private static Process startBlockingProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                jvm(), "-classpath", System.getProperty("java.class.path"), MainForProcessHolder.class.getName());
        pb.inheritIO();
        return pb.start();
    }

    private static String jvm() {
        Path javaHome = Paths.get(System.getProperties().getProperty("java.home"));
        boolean windows = System.getProperty("os.name").startsWith("Win");
        return javaHome.resolve("bin").resolve(windows ? "java.exe" : "java").toString();
    }
}
