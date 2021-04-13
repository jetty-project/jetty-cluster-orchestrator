package net.webtide.cluster.configuration;

import net.webtide.cluster.JvmSettings;
import net.webtide.cluster.RemoteNodeLauncher;

public interface RemotingConfiguration
{
    JvmSettings jvmSettings();
    RemoteNodeLauncher remoteNodeLauncher();
}
