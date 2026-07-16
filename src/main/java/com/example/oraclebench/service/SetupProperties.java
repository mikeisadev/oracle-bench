package com.example.oraclebench.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the one-click course setup (bound from {@code oracle-bench.setup}
 * in application.yml). Defaults match the verified {@code gvenzl/oracle-free} lab
 * container used in this project.
 */
@Component
@ConfigurationProperties(prefix = "oracle-bench.setup")
public class SetupProperties {

    /** Path to the docker CLI (must be on the JVM's PATH, or an absolute path). */
    private String dockerPath = "docker";
    /** Name of the running Oracle container. */
    private String container = "oracle-corso";
    /** Service name / PDB. */
    private String service = "FREEPDB1";

    private String systemUser = "system";
    private String systemPassword = "Corso2026";
    private String appUser = "corso";
    private String appPassword = "Corso2026";
    private String tunerUser = "tuner";
    private String tunerPassword = "Corso2026#";

    /** How many test rows to load into bench_customers. */
    private int seedRows = 50000;

    /** If true, the setup blocks when HR/SH are not installed (install them manually first). */
    private boolean requireDemoSchemas = true;

    public String getDockerPath() { return dockerPath; }
    public void setDockerPath(String dockerPath) { this.dockerPath = dockerPath; }
    public String getContainer() { return container; }
    public void setContainer(String container) { this.container = container; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getSystemUser() { return systemUser; }
    public void setSystemUser(String systemUser) { this.systemUser = systemUser; }
    public String getSystemPassword() { return systemPassword; }
    public void setSystemPassword(String systemPassword) { this.systemPassword = systemPassword; }
    public String getAppUser() { return appUser; }
    public void setAppUser(String appUser) { this.appUser = appUser; }
    public String getAppPassword() { return appPassword; }
    public void setAppPassword(String appPassword) { this.appPassword = appPassword; }
    public String getTunerUser() { return tunerUser; }
    public void setTunerUser(String tunerUser) { this.tunerUser = tunerUser; }
    public String getTunerPassword() { return tunerPassword; }
    public void setTunerPassword(String tunerPassword) { this.tunerPassword = tunerPassword; }
    public int getSeedRows() { return seedRows; }
    public void setSeedRows(int seedRows) { this.seedRows = seedRows; }
    public boolean isRequireDemoSchemas() { return requireDemoSchemas; }
    public void setRequireDemoSchemas(boolean requireDemoSchemas) { this.requireDemoSchemas = requireDemoSchemas; }
}
