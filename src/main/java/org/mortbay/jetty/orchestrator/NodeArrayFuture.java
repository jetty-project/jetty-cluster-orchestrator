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

package org.mortbay.jetty.orchestrator;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class NodeArrayFuture
{
    private final List<CompletableFuture<Object>> futures;

    NodeArrayFuture(List<CompletableFuture<Object>> futures)
    {
        this.futures = futures;
    }

    public void get() throws ExecutionException, InterruptedException
    {
        for (CompletableFuture<Object> future : futures)
        {
            future.get();
        }
    }
}