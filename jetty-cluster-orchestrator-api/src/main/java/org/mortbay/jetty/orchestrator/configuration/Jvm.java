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

package org.mortbay.jetty.orchestrator.configuration;

import java.io.Serializable;
import java.nio.file.FileSystem;
import java.util.Arrays;
import java.util.List;

import org.mortbay.jetty.orchestrator.util.FilenameSupplier;

public class Jvm implements Serializable
{
    private final FilenameSupplier executableSupplier;
    private final List<String> opts;

    public Jvm(FilenameSupplier executableSupplier, String... opts)
    {
        this.executableSupplier = executableSupplier;
        this.opts = Arrays.asList(opts);
    }

    public String executable(FileSystem fileSystem, String hostname)
    {
        return executableSupplier.get(fileSystem, hostname);
    }

    public List<String> getOpts()
    {
        return opts;
    }
}
