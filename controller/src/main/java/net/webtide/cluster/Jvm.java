package net.webtide.cluster;

public class Jvm
{
    private final String home;
    private final String version;
    private final String vendor;

    public Jvm(String home, String version)
    {
        this(home, version, "unspecified", true);
    }

    public Jvm(String home, String version, String vendor, boolean valid)
    {
        this.home = home;
        this.version = version;
        this.vendor = vendor;
    }

    public String getHome()
    {
        return home;
    }

    public String getVendor()
    {
        return vendor;
    }

    public String getVersion()
    {
        return version;
    }

    @Override
    public String toString()
    {
        return "Jvm{" +
            "home='" + home + '\'' +
            ", version='" + version + '\'' +
            ", vendor='" + vendor + '\'' +
            '}';
    }
}
