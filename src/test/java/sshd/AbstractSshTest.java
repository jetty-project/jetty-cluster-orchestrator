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

package sshd;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSshTest
{
    protected static TestSshServer sshd;

    public static final Logger LOGGER = LoggerFactory.getLogger(AbstractSshTest.class);

    protected static final boolean USE_CI = Boolean.getBoolean("ci");

    @BeforeAll
    public static void setUp() throws Exception
    {
        LOGGER.info("use CI: {}", USE_CI);
        sshd = new TestSshServer();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        sshd.close();
    }
}
