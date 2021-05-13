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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

public class TestSshServer implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TestSshServer.class);

    private GenericContainer sshContainer;

    public TestSshServer() throws Exception
    {
        this(Files.createTempDirectory(TestSshServer.class.getName()).toString());
    }

    public TestSshServer(String homeDir) throws Exception
    {
        init(homeDir);
    }

    public int getPort()
    {
        return sshContainer.getMappedPort(2222);
    }

    public int getRemoteForwardPort()
    {
        return sshContainer.getMappedPort(2223);
    }

    public String getHost()
    {
        return sshContainer.getContainerIpAddress();
    }

    public String getUser()
    {
        return "jetty";
    }

    public String getPassword()
    {
        return "tropical_ale";
    }

    private void init(String homePath) throws Exception
    {
        LOGGER.debug("init with homePath: {}", homePath);
        Map<String, String> env = new HashMap<>();

//        docker run -d \
//        --name=openssh-server \
//        --hostname=openssh-server `#optional` \
//        -e PUID=1000 \
//        -e PGID=1000 \
//        -e TZ=Europe/London \
//        -e PUBLIC_KEY=yourpublickey `#optional` \
//        -e PUBLIC_KEY_FILE=/path/to/file `#optional` \
//        -e PUBLIC_KEY_DIR=/path/to/directory/containing/_only_/pubkeys `#optional` \
//        -e SUDO_ACCESS=false `#optional` \
//        -e PASSWORD_ACCESS=false `#optional` \
//        -e USER_PASSWORD=password `#optional` \
//        -e USER_PASSWORD_FILE=/path/to/file `#optional` \
//        -e USER_NAME=linuxserver.io `#optional` \
//        -p 2222:2222 \
//        -v /path/to/appdata/config:/config \
//        --restart unless-stopped \
//        ghcr.io/linuxserver/openssh-server

        env.put("PUID", "1000");
        env.put("PGID", "1000");
        env.put("TZ", "Australia/Brisbane"); // better to use a sunny place
        env.put("PASSWORD_ACCESS", "true");
        env.put("USER_PASSWORD", getPassword());
        env.put("USER_NAME", getUser());
        sshContainer = new GenericContainer("jetty-project:jetty-orchestrator-ssh-test") //"ghcr.io/linuxserver/openssh-server:version-8.4_p1-r3")
            .withEnv(env)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("sshd.sshcontainer")))
            .withExposedPorts(2222);
        if (homePath != null)
        {
            sshContainer.withFileSystemBind(homePath, "/config", BindMode.READ_WRITE);
        }
        sshContainer.withFileSystemBind("src/test/resources/sshd_config/custom-cont-init.d",
                                        "/config/custom-cont-init.d",
                                        BindMode.READ_WRITE);
        long start = System.currentTimeMillis();
        LOGGER.info("Starting sshd container");
        sshContainer.start();
        LOGGER.info("End Starting sshd container in {} ms", System.currentTimeMillis() - start);
    }

    @Override
    public void close() throws Exception
    {
        sshContainer.stop();
    }
}
