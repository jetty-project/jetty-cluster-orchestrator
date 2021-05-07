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

import java.util.ArrayList;
import java.util.List;

import org.mortbay.jetty.orchestrator.util.IOUtil;

public class Closer implements AutoCloseable
{
    private final List<AutoCloseable> closeables = new ArrayList<>();

    public <T extends AutoCloseable> T register(T t)
    {
        closeables.add(t);
        return t;
    }

    @Override
    public void close() throws Exception
    {
        IOUtil.close(closeables.toArray(new AutoCloseable[0]));
    }
}
