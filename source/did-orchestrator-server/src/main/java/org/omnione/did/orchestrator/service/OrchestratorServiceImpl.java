/*
 * Copyright 2025 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.omnione.did.base.constant.Constant;
import org.omnione.did.base.exception.ErrorCode;
import org.omnione.did.base.exception.OpenDidException;
import org.omnione.did.base.property.BlockchainProperties;
import org.omnione.did.base.property.DatabaseProperties;
import org.omnione.did.base.property.ServicesProperties;
import org.omnione.did.orchestrator.dto.OrchestratorResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.*;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.sql.Connection;

@RequiredArgsConstructor
@Slf4j
@Service
public class OrchestratorServiceImpl implements OrchestratorService{
    private final RestTemplate restTemplate = new RestTemplate();
    private final ServicesProperties servicesProperties;
    private final BlockchainProperties blockChainProperties;
    private final DatabaseProperties databaseProperties;
    private final String JARS_DIR;
    private final Map<String, String> SERVER_JARS;
    private final Map<String, String> SERVER_JARS_FOLDER;
    private final String WALLET_DIR;
    private final String DID_DOC_DIR;
    private final String CLI_TOOL_DIR;
    private final String YAML_FILE_PATH;
    private final String LOGS_PATH;

    @Autowired
    public OrchestratorServiceImpl(ServicesProperties servicesProperties, BlockchainProperties blockChainProperties, DatabaseProperties databaseProperties) {
        this.servicesProperties = servicesProperties;
        this.blockChainProperties = blockChainProperties;
        this.databaseProperties = databaseProperties;
        this.JARS_DIR = System.getProperty("user.dir") + servicesProperties.getJarPath();
        this.YAML_FILE_PATH = System.getProperty("user.dir") + "/configs/application.yml";
        this.SERVER_JARS = initializeServerJars();
        this.SERVER_JARS_FOLDER = initializeServerJarsFolder();
        this.WALLET_DIR = System.getProperty("user.dir") + servicesProperties.getWalletPath();
        this.DID_DOC_DIR = System.getProperty("user.dir") + servicesProperties.getDidDocPath();
        this.CLI_TOOL_DIR = System.getProperty("user.dir") + servicesProperties.getCliToolPath();
        this.LOGS_PATH = System.getProperty("user.dir") + servicesProperties.getLogPath();
    }

    private Map<String, String> initializeServerJars() {
        Map<String, String> serverJars = new HashMap<>();
        servicesProperties.getServer().forEach((key, serverDetail) ->
                serverJars.put(String.valueOf(serverDetail.getPort()), serverDetail.getFile()));
        return serverJars;
    }

    private Map<String, String> initializeServerJarsFolder() {
        Map<String, String> serverJarsFolder = new HashMap<>();
        servicesProperties.getServer().forEach((key, serverDetail) ->
                serverJarsFolder.put(String.valueOf(serverDetail.getPort()), serverDetail.getName()));
        return serverJarsFolder;
    }

    interface FabricStartupCallback {
        void onStartupComplete();
        void onStartupFailed();
    }
    @Override
    public void requestStartupAll() {
        try {
            for (String serverPort : SERVER_JARS.keySet()) {
                if (!isServerRunning(serverPort)) {
                    startServer(serverPort);
                } else {
                    log.debug("Server on port " + serverPort + " is already running. Skipping startup.");
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
    }

    @Override
    public void requestShutdownAll() {
        try {
            for (String serverPort : SERVER_JARS.keySet()) {
                if (isServerRunning(serverPort)) {
                    stopServer(serverPort);
                } else {
                    log.debug("Server on port " + serverPort + " is stop. Skipping shutdown.");
                }
            }
        } catch (InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
    }
    
    @Override
    public OrchestratorResponseDto requestStartup(String port) throws OpenDidException {
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        response.setStatus("Unknown error");
        log.debug("Startup request for port: " + port);
        try {
            response.setStatus(startServer(port));
        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public OrchestratorResponseDto requestShutdown(String port) throws OpenDidException {
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        response.setStatus("Unknown error");
        log.debug("shutdown request for port: " + port);
        try {
            response.setStatus(stopServer(port));
        } catch (InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public OrchestratorResponseDto requestHealthCheck(String port) {
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        response.setStatus("DOWN");
        log.debug("requestHealthCheck for port: " + port);
        if(isServerRunning(port))
            response.setStatus("UP");
        return response;
    }

    @Override
    public OrchestratorResponseDto requestRefresh(String port) {
        String targetUrl = getServerUrl() + port + "/actuator/refresh";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
        ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, requestEntity, String.class);

        String responseBody = response.getBody();
        log.debug("refresh : " + responseBody);
        OrchestratorResponseDto dto = new OrchestratorResponseDto();
        dto.setStatus("SUCCESS");
        return dto;
    }
    @Override
    public OrchestratorResponseDto requestStartupFabric() {
        log.debug("requestStartupFabric");
        String fabricShellPath = System.getProperty("user.dir") + "/shells/Fabric";
        String logFilePath = LOGS_PATH + "/fabric.log";
        
        try {
            ProcessBuilder chmodBuilder = new ProcessBuilder("chmod", "+x", fabricShellPath + "/start.sh");
            chmodBuilder.start().waitFor();

            ProcessBuilder builder = new ProcessBuilder(
                    "sh", "-c", "nohup " + fabricShellPath + "/start.sh " + blockChainProperties.getChannel() + " " + blockChainProperties.getChaincodeName() +
                    " > " + logFilePath + " 2>&1 &"
            );

            builder.directory(new File(fabricShellPath));
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.start();

            watchFabricLogs(logFilePath, new FabricStartupCallback() {
                @Override
                public void onStartupComplete() {
                    log.debug("Hyperledger Fabric is running successfully!");
                }

                @Override
                public void onStartupFailed() {
                    log.error("Fabric startup failed.");
                }
            });

            OrchestratorResponseDto response = requestHealthCheckFabric();
//            if(response.getStatus().equals("UP")){
            // fabric.log 파일삭제
//                File logFile = new File(logFilePath);
//                if (logFile.exists()) {
//                    boolean deleted = logFile.delete();
//                    if (deleted) {
//                        log.debug("Fabric log file deleted: " + logFilePath);
//                    } else {
//                        log.debug("Failed to delete fabric.log file.");
//                    }
//                }
//            }
            return response;
        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
    }

    private void watchFabricLogs(String logFilePath, FabricStartupCallback callback) {
        File logFile = new File(logFilePath);
        log.debug("Monitoring log file: " + logFilePath);

        try {
            while (!logFile.exists() || logFile.length() == 0) {
                log.debug("Waiting for log file to be created...");
                Thread.sleep(3000);
            }

            long lastReadPosition = 0;
            while (true) {
                try (RandomAccessFile reader = new RandomAccessFile(logFile, "r")) {
                    reader.seek(lastReadPosition);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug(line);

                        if (line.contains(Constant.FABRIC_SUCCESS_CHAINCODE_MESSAGE) || line.contains(Constant.FABRIC_START_MESSAGE)) {
                            callback.onStartupComplete();
                            return;
                        }
                        if (line.contains(Constant.FABRIC_FAIL_CHAINCODE_MESSAGE) || line.contains(Constant.FABRIC_FAIL_DOCKER_MESSAGE)) {
                            callback.onStartupFailed();
                            return;
                        }
                    }
                    lastReadPosition = reader.getFilePointer();
                }
                Thread.sleep(3000);
            }
        } catch (InterruptedException | IOException e) {
            callback.onStartupFailed();
        }
    }

    @Override
    public OrchestratorResponseDto requestShutdownFabric() {
        log.debug("requestShutdownFabric");
        try {
            String fabricShellPath = System.getProperty("user.dir") + "/shells/Fabric";
            ProcessBuilder builder = new ProcessBuilder("sh", fabricShellPath + "/stop.sh");
            builder.directory(new File(fabricShellPath));
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.start();
        } catch (IOException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        OrchestratorResponseDto response = requestHealthCheckFabric();
        return response;
    }

    @Override
    public OrchestratorResponseDto requestHealthCheckFabric() {
        log.debug("requestHealthCheckFabric");
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        try {
            String fabricShellPath = System.getProperty("user.dir") + "/shells/Fabric";
            ProcessBuilder builder = new ProcessBuilder("sh", fabricShellPath + "/status.sh", blockChainProperties.getChannel(), blockChainProperties.getChaincodeName());
            builder.directory(new File(fabricShellPath));
            Process process = builder.start();
            String output = getProcessOutput(process);

            if (output.contains("200")) {
                response.setStatus("UP");
                return response;
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        response.setStatus("ERROR");
        return response;
    }

    @Override
    public OrchestratorResponseDto requestResetFabric() {
        log.debug("requestResetFabric");
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        try {
            String fabricShellPath = System.getProperty("user.dir") + "/shells/Fabric";
            ProcessBuilder builder = new ProcessBuilder("sh", fabricShellPath + "/reset.sh");
            builder.directory(new File(fabricShellPath));
            Process process = builder.start();
            String output = getProcessOutput(process);

            if (output.contains(Constant.FABRIC_RESET_MESSAGE)) {
                response.setStatus("UP");
                return response;
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        response.setStatus("ERROR");
        return response;
    }
    @Override
    public OrchestratorResponseDto requestStartupPostgre() {
        log.debug("requestStartupPostgre");
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        try {
            String postgreShellPath = System.getProperty("user.dir") + "/shells/Postgre";
            ProcessBuilder builder = new ProcessBuilder("sh", postgreShellPath + "/start.sh", databaseProperties.getPort(), databaseProperties.getUser(), databaseProperties.getPassword(), databaseProperties.getDb());
            builder.directory(new File(postgreShellPath));

            Process process = builder.start();
            String output = getProcessOutput(process);
            if (output.contains(Constant.POSTGRE_START_MESSAGE)) {
                response.setStatus("UP");
                return response;
            }
        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        response.setStatus("ERROR");
        return response;
    }

    @Override
    public OrchestratorResponseDto requestShutdownPostgre() {
        log.debug("requestShutdownPostgre");
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        try {
            String postgreShellPath = System.getProperty("user.dir") + "/shells/Postgre";
            ProcessBuilder builder = new ProcessBuilder("sh", postgreShellPath + "/stop.sh");
            builder.directory(new File(postgreShellPath));

            Process process = builder.start();
            String output = getProcessOutput(process);
            if (output.contains(Constant.POSTGRE_STOP_MESSAGE)) {
                response.setStatus("DOWN");
                return response;
            }
        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        response.setStatus("ERROR");
        return response;
    }

    @Override
    public OrchestratorResponseDto requestHealthCheckPostgre() {
        log.debug("requestHealthCheckPostgre");
        OrchestratorResponseDto response = new OrchestratorResponseDto();
        try {
            String postgreShellPath = System.getProperty("user.dir") + "/shells/Postgre";
            ProcessBuilder builder = new ProcessBuilder("sh", postgreShellPath + "/status.sh", databaseProperties.getUser(), databaseProperties.getPassword());
            builder.directory(new File(postgreShellPath));

            Process process = builder.start();
            String output = getProcessOutput(process);

            if (output.contains(Constant.POSTGRE_HEALTH_MESSAGE)) {
                response.setStatus("UP");
                return response;
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        response.setStatus("ERROR");
        return response;
    }

    @Override
    public OrchestratorResponseDto createAll(String password) {
        log.debug("createAll : " + password);
        OrchestratorResponseDto response = new OrchestratorResponseDto();

        Process process = null;

        try {
            ProcessBuilder builder = new ProcessBuilder("sh", CLI_TOOL_DIR + "/create_all.sh", password);
            builder.directory(new File(CLI_TOOL_DIR));
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);

            process = builder.start();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.debug("Wallet / DID DOC creation successful.");
                response.setStatus("SUCCESS");
            } else {
                log.error("Wallet / DID DOC  creation failed.");
                response.setStatus("ERROR");
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        return response;

    }

    @Override
    public OrchestratorResponseDto createWallet(String fileName, String password) {
        log.debug("createWallet : " + fileName + " / " + password);
        OrchestratorResponseDto response = new OrchestratorResponseDto();

        Process process = null;
        BufferedWriter writer = null;
        OutputStreamWriter outputStreamWriter = null;

        try {

            ProcessBuilder builder = new ProcessBuilder("sh", CLI_TOOL_DIR + "/create_wallet.sh", fileName);
            builder.directory(new File(CLI_TOOL_DIR));
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);

            process = builder.start();

            outputStreamWriter = new OutputStreamWriter(process.getOutputStream());
            writer = new BufferedWriter(outputStreamWriter);

            writer.write(password);
            writer.newLine();
            writer.flush();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.debug("Wallet creation successful.");
            } else {
                log.error("Wallet creation failed.");
                response.setStatus("ERROR");
                return response;
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    log.error("Error closing BufferedWriter.");
                }
            }

            if (outputStreamWriter != null) {
                try {
                    outputStreamWriter.close();
                } catch (IOException e) {
                    log.error("Error closing OutputStreamWriter.");
                }
            }

            if (process != null) {
                process.destroy();
            }
        }

        response.setStatus("SUCCESS");
        return response;
    }

    @Override
    public OrchestratorResponseDto createKeys(String fileName, String password, List<String> keyIds) {
        log.debug("createKeys : " + fileName + " / " + password);
        OrchestratorResponseDto response = new OrchestratorResponseDto();
//        String[] keyId = {"assert", "auth", "keyagree", "invoke"};
        Process process = null;
        BufferedWriter writer = null;
        OutputStreamWriter outputStreamWriter = null;
        for(int i = 0; i < keyIds.size(); i++) {
            try {
                log.debug("createKeys : " + fileName + " / " + password + " / " + keyIds.get(i));
                ProcessBuilder builder = new ProcessBuilder("sh", CLI_TOOL_DIR + "/create_keys.sh", fileName + ".wallet", keyIds.get(i));
                builder.directory(new File(CLI_TOOL_DIR));
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);

                process = builder.start();

                outputStreamWriter = new OutputStreamWriter(process.getOutputStream());
                writer = new BufferedWriter(outputStreamWriter);

                writer.write(password);
                writer.newLine();
                writer.flush();

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    log.debug("Keypair creation successful.");
                } else {
                    log.error("Keypair creation failed.");
                    response.setStatus("ERROR");
                    return response;
                }
                if (process.isAlive()) {
                    process.destroy();
                }
            } catch (IOException | InterruptedException e) {
                throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException e) {
                        log.error("Error closing BufferedWriter.");
                    }
                }

                if (outputStreamWriter != null) {
                    try {
                        outputStreamWriter.close();
                    } catch (IOException e) {
                        log.error("Error closing OutputStreamWriter.");
                    }
                }

                if (process != null) {
                    process.destroy();
                }
            }
        }
        response.setStatus("SUCCESS");
        return response;
    }

    @Override
    public OrchestratorResponseDto createDidDocument(String fileName, String password, String did, String controller, String type) {
        log.debug("createDidDocument : " + fileName + " / " + password + " / " + did + " / " + controller);
        OrchestratorResponseDto response = new OrchestratorResponseDto();

        Process process = null;
        BufferedWriter writer = null;
        OutputStreamWriter outputStreamWriter = null;

        try {
            ProcessBuilder builder = new ProcessBuilder("sh", CLI_TOOL_DIR + "/create_did_doc.sh", fileName + ".wallet", fileName + ".did", did, controller, type);
            builder.directory(new File(CLI_TOOL_DIR));
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);

            process = builder.start();

            outputStreamWriter = new OutputStreamWriter(process.getOutputStream());
            writer = new BufferedWriter(outputStreamWriter);

            writer.write(password);
            writer.newLine();
            writer.flush();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.debug("DID Documents creation successful.");
                response.setStatus("SUCCESS");
                return response;
            } else {
                log.error("DID Documents creation failed.");
            }

        } catch (IOException | InterruptedException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        } finally {

            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    log.error("Error closing BufferedWriter.");
                }
            }

            if (outputStreamWriter != null) {
                try {
                    outputStreamWriter.close();
                } catch (IOException e) {
                    log.error("Error closing OutputStreamWriter.");
                }
            }

            if (process != null) {
                process.destroy();
            }

        }
        response.setStatus("ERROR");
        return response;
    }

    @Override
    public String getServerIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }
        return "Unknown IP";
    }

    @Override
    public OrchestratorResponseDto updateConfig(Map<String, Object> updates) {
        OrchestratorResponseDto response = new OrchestratorResponseDto();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);
        Map<String, Object> yamlData = new HashMap<>();
        response.setStatus("SUCCESS");

        try (InputStream inputStream = new FileInputStream(YAML_FILE_PATH)) {
            Map<String, Object> loaded = yaml.load(inputStream);
            if (loaded != null) {
                yamlData = loaded;
            }
        } catch (IOException e) {
            response.setStatus("YAML read error: " + e.getMessage());
        }

        mergeMaps(yamlData, updates);

        try (Writer writer = new FileWriter(YAML_FILE_PATH)) {
            yaml.dump(yamlData, writer);
        } catch (IOException e) {
            response.setStatus("YAML write error: " + e.getMessage());
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    private void mergeMaps(Map<String, Object> originalMap, Map<String, Object> updateMap) {
        for (Map.Entry<String, Object> entry : updateMap.entrySet()) {
            String key = entry.getKey();
            Object updateValue = entry.getValue();
            if (originalMap.containsKey(key)) {
                Object originalValue = originalMap.get(key);
                if (originalValue instanceof Map && updateValue instanceof Map) {
                    mergeMaps((Map<String, Object>) originalValue, (Map<String, Object>) updateValue);
                } else {
                    originalMap.put(key, updateValue);
                }
            } else {
                originalMap.put(key, updateValue);
            }
        }
    }

    private String startServer(String port) throws IOException, InterruptedException {
        Map<String, String> server_jars = SERVER_JARS;
        server_jars = initializeServerJars();
        String jarFilePath = JARS_DIR + "/" + SERVER_JARS_FOLDER.get(port) + "/" + server_jars.get(port);
        File jarFile = new File(jarFilePath);
        File scriptFile = new File(JARS_DIR + "/start.sh");
        String serverPort = "";
        if (Integer.parseInt(port) > 0 && Integer.parseInt(port) < 65535)
            serverPort = port;

        ProcessBuilder builder = new ProcessBuilder("sh", scriptFile.getAbsolutePath(), jarFile.getAbsolutePath(), serverPort);
        builder.directory(new File(JARS_DIR));
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        log.debug("Server on port " + port + " started with nohup! Waiting for health check...");

        int retries = 5;
        while (retries-- > 0) {
            Thread.sleep(1000);
            if (isServerRunning(port)) {
                log.debug("Server on port " + port + " is running!");
                return "UP";
            }
        }
        log.error("Server on port " + port + " failed to start.");
        return "DOWN";
    }

    private String stopServer(String port) throws InterruptedException {

        String targetUrl = getServerUrl() + port + "/actuator/shutdown";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> requestEntity = new HttpEntity<>("", headers);
        restTemplate.postForEntity(targetUrl, requestEntity, OrchestratorResponseDto.class).getBody();

        int retries = 5;
        while (retries-- > 0) {
            Thread.sleep(1000);
            if (isServerRunning(port)) {
                log.debug("Server on port " + port + " is running!");
                return "UP";
            }
        }
        log.error("Server on port " + port + " failed to start.");
        return "DOWN";
    }
    private boolean isServerRunning(String port) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(getServerUrl() + port + "/actuator/health");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            int responseCode = connection.getResponseCode();
            log.debug("running code : " + responseCode);
            return (responseCode == 200);
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String getServerUrl() {
        return "http://" + getServerIp() + ":";
    }

    private String getProcessOutput(Process process) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[OUTPUT] " + line);
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
            }
        });

        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    log.debug("[OUTPUT] " + line);
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
            }
        });

        stdoutThread.start();
        stderrThread.start();

        stdoutThread.join();
        stderrThread.join();

        int exitCode = process.waitFor();
        log.debug("Process exited with code: " + exitCode);

        return output.toString().trim();
    }

}



