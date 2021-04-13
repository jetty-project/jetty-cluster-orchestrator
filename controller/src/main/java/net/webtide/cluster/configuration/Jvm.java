package net.webtide.cluster.configuration;

import java.io.Serializable;

public class Jvm implements Serializable
{
    private final String home;

    public Jvm(String home)
    {
        this.home = home;
    }

    public String getHome()
    {
        return home;
    }

    @Override
    public String toString()
    {
        return "Jvm{" +
            "home='" + home + '\'' +
            '}';
    }
}
