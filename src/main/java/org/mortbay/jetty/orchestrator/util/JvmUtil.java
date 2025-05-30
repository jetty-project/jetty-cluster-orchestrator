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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.mortbay.jetty.orchestrator.configuration.Jvm;

public class JvmUtil
{
    public static Jvm currentJvm(String... opts)
    {
        return new Jvm((fileSystem, hostname) ->
        {
            Path javaExec = JvmUtil.findCurrentJavaExecutable();
            if (javaExec == null)
                throw new IllegalStateException("Cannot find executable java command of current JVM");
            return javaExec.toAbsolutePath().toString();
        }, opts);
    }

    public static Path findCurrentJavaExecutable()
    {
        String javaHome = System.getProperty("java.home");
        Path javaHomePath = Paths.get(javaHome);
        return findJavaExecutable(javaHomePath);
    }

    public static Path findJavaExecutable(Path javaHomePath)
    {
        Path javaExec = javaHomePath.resolve("bin").resolve("java"); // *nix
        if (!Files.isExecutable(javaExec))
            javaExec = javaHomePath.resolve("Contents").resolve("Home").resolve("bin").resolve("java"); // OSX
        if (!Files.isExecutable(javaExec))
            javaExec = javaHomePath.resolve("bin").resolve("java.exe"); // Windows
        if (!Files.isExecutable(javaExec))
            return null;
        return javaExec;
    }
}
