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

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;

import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.common.keyprovider.AbstractResourceKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.shell.InvertedShell;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

public class TestSshServer implements AutoCloseable
{
    private SshServer sshd;

    public TestSshServer() throws Exception
    {
        this(System.getProperty("user.home"));
    }

    public TestSshServer(String homeDir) throws Exception
    {
        KeyPair keyPair;
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(is, "storepwd".toCharArray());
            PublicKey publicKey = keyStore.getCertificate("mykey").getPublicKey();
            PrivateKey privateKey = (PrivateKey)keyStore.getKey("mykey", "storepwd".toCharArray());
            keyPair = new KeyPair(publicKey, privateKey);
        }

        init(keyPair, homeDir);
    }

    public int getPort()
    {
        return sshd.getPort();
    }

    private void init(KeyPair keyPair, String homePath) throws Exception
    {
        sshd = SshServer.setUpDefaultServer();

        // configure server keys
        sshd.setKeyPairProvider(new AbstractResourceKeyPairProvider<Object>() {
            @Override
            public Iterable<KeyPair> loadKeys(SessionContext session)
            {
                return Collections.singleton(keyPair);
            }
        });
        // fully open auth
        sshd.setPublickeyAuthenticator((s, publicKey, serverSession) -> true);
        sshd.setPasswordAuthenticator((username, password, session) -> true);

        // enable TCP port forwarding
        sshd.setForwardingFilter(new AcceptAllForwardingFilter());

        // enable SFTP
        SftpSubsystemFactory factory = new SftpSubsystemFactory()
        {
            @Override
            public Command createSubsystem(ChannelSession channel)
            {
                SftpSubsystem subsystem = new SftpSubsystem(
                    resolveExecutorService(),
                    getUnsupportedAttributePolicy(), getFileSystemAccessor(),
                    getErrorStatusDataHandler())
                {
                    {
                        this.defaultDir = fileSystem.getPath(homePath).toAbsolutePath().normalize();
                    }
                };
                GenericUtils.forEach(getRegisteredListeners(), subsystem::addSftpEventListener);
                return subsystem;
            }
        };
        sshd.setSubsystemFactories(Collections.singletonList(factory));
        sshd.setFileSystemFactory(new NativeFileSystemFactory());

        // execute commands from home folder
        sshd.setCommandFactory(new ProcessShellCommandFactory()
        {
            @Override
            public Command createCommand(ChannelSession channel, String command) throws IOException
            {
                ShellFactory factory = new ProcessShellFactory(command, CommandFactory.split(command))
                {
                    @Override
                    protected InvertedShell createInvertedShell(ChannelSession channel)
                    {
                        return new HomeProcessShell(homePath, resolveEffectiveCommand(channel, getCommand(), getElements()));
                    }
                };
                return factory.createShell(channel);
            }
        });
        sshd.start();
    }

    @Override
    public void close() throws Exception
    {
        sshd.stop();
    }
}
