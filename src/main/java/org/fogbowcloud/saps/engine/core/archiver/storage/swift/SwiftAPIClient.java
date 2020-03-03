package org.fogbowcloud.saps.engine.core.archiver.storage.swift;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.fogbowcloud.saps.engine.core.util.ProcessUtil;
import org.fogbowcloud.saps.engine.utils.SapsPropertiesConstants;
import org.fogbowcloud.saps.engine.utils.SapsPropertiesUtil;

public class SwiftAPIClient {

    // Properties
    private Properties properties;

    // Core Attributes
    private String projectId;
    private String userId;
    private String userPassword;
    private String tokenAuthUrl;
    private String swiftUrl;
    private String token;

    public static final Logger LOGGER = Logger.getLogger(SwiftAPIClient.class);

    public SwiftAPIClient(Properties properties) throws SwiftPermanentStorageException {
        if (!checkProperties(properties))
            throw new SwiftPermanentStorageException("Error on validate the file. Missing properties for start Swift API Client.");

        this.properties = properties;

        projectId = properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_PROJECT_ID);
        userId = properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_USER_ID);
        userPassword = properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_PASSWORD);
        tokenAuthUrl = properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_AUTH_URL);
        swiftUrl = properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_SWIFT_URL);

        handleTokenUpdate(Executors.newScheduledThreadPool(1));

        LOGGER.info("Waiting to get token...");
        while (token == null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean checkProperties(Properties properties) {
        String[] propertiesSet = {
                SapsPropertiesConstants.FOGBOW_KEYSTONEV3_PROJECT_ID,
                SapsPropertiesConstants.FOGBOW_KEYSTONEV3_USER_ID,
                SapsPropertiesConstants.FOGBOW_KEYSTONEV3_PASSWORD,
                SapsPropertiesConstants.FOGBOW_KEYSTONEV3_AUTH_URL,
                SapsPropertiesConstants.FOGBOW_KEYSTONEV3_SWIFT_URL
        };

        return SapsPropertiesUtil.checkProperties(properties, propertiesSet);
    }

    public void createContainer(String containerName) {
        // TODO: test JUnit
        LOGGER.debug("Creating container " + containerName);
        ProcessBuilder builder = new ProcessBuilder("swift", "--os-auth-token", token, "--os-storage-url", swiftUrl,
                "post", containerName);

        LOGGER.debug("Executing command " + builder.command());

        try {
            Process p = builder.start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error while creating container " + containerName, e);
        }
    }

    public void uploadFile(String containerName, File file, String pseudFolder) throws Exception {
        String completeFileName = pseudFolder + File.separator + file.getName();

        LOGGER.debug("Uploading " + completeFileName + " to " + containerName);
        ProcessBuilder builder = new ProcessBuilder("swift", "--os-auth-token", token, "--os-storage-url", swiftUrl,
                "upload", containerName, file.getAbsolutePath(), "--object-name", completeFileName);
        try {
            Process p = builder.start();
            p.waitFor();

            if (p.exitValue() != 0) {
                throw new Exception("process_output=" + p.exitValue());
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error while uploading file " + completeFileName + " to container " + containerName, e);
        }
    }

    public void deleteFile(String containerName, String filePath) {
        LOGGER.debug("Deleting " + filePath + " from " + containerName);
        ProcessBuilder builder = new ProcessBuilder("swift", "--os-auth-token", token, "--os-storage-url", swiftUrl,
                "delete", containerName, filePath);

        try {
            Process p = builder.start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error while deleting " + filePath + " from container " + containerName, e);
        }

        LOGGER.debug(filePath + " file deleted successfully from " + containerName);
    }

    public List<String> listFilesWithPrefix(String containerName, String prefix) {
        LOGGER.info("Listing files in container " + containerName + " with prefix " + prefix);
        ProcessBuilder builder = new ProcessBuilder("swift", "--os-auth-token", token, "--os-storage-url",
                swiftUrl, "list", "-p", prefix, containerName);
        LOGGER.debug("Executing command " + builder.command());

        Process p;
        String output;

        try {
            p = builder.start();
            p.waitFor();

            output = ProcessUtil.getOutput(p);

            return getOutputLinesIntoList(output);
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error while listing files from " + containerName);
            return new ArrayList<String>();
        }
    }

    private List<String> getOutputLinesIntoList(String fileNames) throws IOException {
        List<String> fileNamesList = new ArrayList<String>();

        String[] lines = fileNames.split(System.getProperty("line.separator"));

        for (int i = 0; i < lines.length; i++) {
            fileNamesList.add(lines[i]);
        }

        return fileNamesList;
    }

    protected String generateToken() {

        try {
            ProcessBuilder builder = new ProcessBuilder("bash",
                    properties.get(SapsPropertiesConstants.FOGBOW_CLI_PATH) + File.separator + "bin/fogbow-cli",
                    "token", "--create", "-DprojectId=" + projectId, "-DuserId=" + userId, "-Dpassword=" + userPassword,
                    "-DauthUrl=" + tokenAuthUrl, "--type", "openstack");

            LOGGER.debug("Executing command " + builder.command());

            Process p = builder.start();
            p.waitFor();

            return ProcessUtil.getOutput(p);
        } catch (Throwable e) {
            LOGGER.error("Error while generating keystone token", e);
        }

        return null;
    }

    protected void handleTokenUpdate(ScheduledExecutorService handleTokenUpdateExecutor) {
        LOGGER.debug("Turning on handle token update.");

        handleTokenUpdateExecutor.scheduleWithFixedDelay(() -> setToken(generateToken()), 0, Integer.parseInt(properties.getProperty(SapsPropertiesConstants.FOGBOW_KEYSTONEV3_UPDATE_PERIOD)),
                TimeUnit.MILLISECONDS);
    }

    protected void setToken(String token) {
        LOGGER.debug("Setting token to " + token);
        this.token = token;
    }

    List<String> listFiles(String containerName, String dirPath) throws IOException {
        String url = this.swiftUrl + "/" + containerName + "?path=" + dirPath;
        HttpClient client = HttpClients.createDefault();
        HttpGet httpget = new HttpGet(url);
        httpget.addHeader("X-Auth-Token", token);
        HttpResponse response = client.execute(httpget);
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            throw new IOException("The request to list files on object storage was failed: " + EntityUtils.toString(response.getEntity()));
        }
        return Arrays.asList(EntityUtils.toString(response.getEntity()).split("\n"));
    }
}
