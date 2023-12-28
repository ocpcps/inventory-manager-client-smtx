/*
 * Copyright (C) 2023 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.osstelecom.db.inventory.manager.client.smtx.jobs;

import com.osstelecom.db.inventory.manager.annotations.ImportJobStep;
import com.osstelecom.db.inventory.manager.dto.CircuitPathDTO;
import com.osstelecom.db.inventory.manager.http.client.NetcompassAPIClient;
import com.osstelecom.db.inventory.manager.http.client.configuration.ConfigurationManager;
import com.osstelecom.db.inventory.manager.http.client.configuration.NetcompassClientConfiguration;
import com.osstelecom.db.inventory.manager.http.exception.InvalidRequestException;
import com.osstelecom.db.inventory.manager.http.exception.LocalQueueException;
import com.osstelecom.db.inventory.manager.jobs.JobConfiguration;
import com.osstelecom.db.inventory.manager.resources.CircuitResource;
import com.osstelecom.db.inventory.manager.resources.ManagedResource;
import com.osstelecom.db.inventory.manager.resources.ResourceConnection;
import com.osstelecom.db.inventory.manager.utils.ResultSetStreamReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;


@DisallowConcurrentExecution
public class SMTXMetroJob implements Job {

    @Autowired
    private DataSource dataSource;

    private final Logger logger = LoggerFactory.getLogger(SMTXMetroJob.class);

    private Connection sqlConnection;

    private JobConfiguration configuration;

    private NetcompassClientConfiguration netcompassClientConfiguration;

    private NetcompassAPIClient netcompassApiClient;

    @Autowired
    private ApplicationArguments applicationArguments;

    private void initNetcompassClient() throws SQLException, FileNotFoundException {
        logger.debug("Trying to Create Netcompass Client");
        this.netcompassClientConfiguration = new ConfigurationManager().loadConfiguration();
        this.netcompassApiClient = new NetcompassAPIClient(netcompassClientConfiguration);
        this.netcompassApiClient.initLoadSession("SMTX_IP_METRO", this.configuration.getFlushThreads());
    }

    private void initConnection() {
        while (true) {
            logger.debug("Trying to get Connection");
            try {
                if (this.sqlConnection != null) {
                    try {
                        this.sqlConnection.close();
                        logger.info("Connection Gracefully closed :)");
                    } catch (SQLException ex) {
                        logger.warn("Connection Close error But OK", ex);
                    }
                }
                this.sqlConnection = this.dataSource.getConnection();
                logger.info("Connected OK");
                return;
            } catch (SQLException ex) {
                logger.error("Failed To Acquire Connection");
            }
            logger.debug("Retrying in 5s");
            try {
                Thread.sleep(1000 * 5);
            } catch (InterruptedException ex) {
            }
        }
    }

    /**
     * Executa o Contexto de execução da JOB
     *
     * @param jec
     * @throws JobExecutionException
     */
    @Override
    public void execute(JobExecutionContext jec) throws JobExecutionException {
        JobDataMap jdm = jec.getJobDetail().getJobDataMap();
        if (jdm.containsKey("config")) {
            this.configuration = (JobConfiguration) jdm.get("config");
        }

        try {
            this.initNetcompassClient();

            this.initCollector();
        } catch (SQLException | FileNotFoundException ex) {
            java.util.logging.Logger.getLogger(SMTXDwdmJob.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    /**
     * Aqui de fato vamos iniciar a Job do SMTX
     *
     * @throws SQLException
     */
    private void initCollector() throws SQLException {
        if (applicationArguments.getNonOptionArgs().contains("upload-only")) {
            logger.info("Upload Only Mode Starting..");
            logger.debug("Uploading Data");
            this.netcompassApiClient.upload();
            logger.debug("Uploading Data Done");
            System.exit(0);
        } else {

            this.initConnection();
            this.importMetro();
            this.initConnection();
            this.importMetroShelf();
            this.initConnection();
            this.importMetroSlot();
            this.initConnection();
            this.importMetroModulo();
            this.initConnection();
            this.importMetroPortaSemModulo();
            this.initConnection();
            this.importMetroPortaComModulo();


            if (!applicationArguments.getNonOptionArgs().contains("dont-upload")) {
                logger.debug("Uploading Data");
                this.netcompassApiClient.upload();
                logger.debug("Uploading Data Done");

            } else {
                logger.warn("NOT Uploading Data [dont-upload] Flag Set");
                System.exit(0);
            }

            if (applicationArguments.getNonOptionArgs().contains("exit")) {
                System.exit(0);
            }
        }
    }


    /**
     * @throws SQLException
     */
    @ImportJobStep(stepIndex = 0, stepName = "importMetro")
    private void importMetro() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetro"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull()) {

                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");
                            metroResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asString("N/A"));
                            metroResource.getAttributes().put("status", a.get("Status").asString("N/A"));
                            metroResource.getAttributes().put("camada", a.get("Camada").asString("N/A"));
                            metroResource.getAttributes().put("fabricante", a.get("Fabricante").asString("N/A"));
                            metroResource.getAttributes().put("modelo", a.get("Modelo").asString("N/A"));
                            metroResource.getAttributes().put("numeroShelfs", a.get("NumShelfs").asString("N/A"));
                            metroResource.getAttributes().put("siglaSite", a.get("SiglaSite").asString("N/A"));
                            metroResource.getAttributes().put("uf", a.get("UfSite").asString("N/A"));
                            metroResource.getAttributes().put("funcaoEnderecoIP", a.get("FuncaoEnderecoIP").asString("N/A"));
                            metroResource.getAttributes().put("enderecoIP", a.get("EnderecoIP").asString("N/A"));
                            metroResource.getAttributes().put("funcaoEnderecoIP2", a.get("FuncaoEnderecoIP2").asString("N/A"));
                            metroResource.getAttributes().put("enderecoIP2", a.get("EnderecoIP2").asString("N/A"));
                            metroResource.getAttributes().put("funcaoEnderecoIP3", a.get("FuncaoEnderecoIP3").asString("N/A"));
                            metroResource.getAttributes().put("enderecoIP3", a.get("EnderecoIP3").asString("N/A"));
                            metroResource.getAttributes().put("funcaoEnderecoIP4", a.get("FuncaoEnderecoIP4").asString("N/A"));
                            metroResource.getAttributes().put("enderecoIP4", a.get("EnderecoIP4").asString("N/A"));
                            metroResource.getAttributes().put("configuracao", a.get("Configuracao").asString("N/A"));
                            metroResource.getAttributes().put("numeroOe", a.get("NumOE").asString("N/A"));
                            this.netcompassApiClient.addManagedResource(metroResource);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Metro");
            }
        }
    }

    @ImportJobStep(stepIndex = 1, stepName = "importMetroShelf")
    private void importMetroShelf() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Shelf Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetroShelf"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull()) {

                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");


                            String shelfName = "shelf-" + a.get("Shelf").asString("N/A");
                            String shelfNodeAddress = metroResource.getNodeAddress() + "." + shelfName;

                            ManagedResource metroShelfResource = this.netcompassApiClient.getManagedResource(shelfNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.shelf", "resource.smtx.ip_metro.shelf");
                            metroShelfResource.setName(shelfName);
                            metroShelfResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            metroShelfResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            metroShelfResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            metroShelfResource.setStructureId(metroResource.getKey());
                            this.netcompassApiClient.addManagedResource(metroShelfResource);

                            ResourceConnection metroShelfConnection = this.netcompassApiClient.getResourceConnection(metroResource, metroShelfResource, "smtx_ip_metro", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(metroShelfConnection);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Metro Shelf");
            }
        }
    }

    @ImportJobStep(stepIndex = 2, stepName = "importMetroSlot")
    private void importMetroSlot() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Slot Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetroSlot"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Slot").isNotNull()) {

                            String getShelfNodeAddress = a.get("Hostname").asString() + ".shelf-" + a.get("Shelf").asString();
                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");
                            ManagedResource metroShelfResource = this.netcompassApiClient.getManagedResource(getShelfNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.shelf", "resource.smtx.ip_metro.shelf");

                            String slotName = "slot-" + a.get("Slot").asString("N/A");
                            String slotNodeAddress = getShelfNodeAddress + "." + slotName;

                            ManagedResource metroSlotResource = this.netcompassApiClient.getManagedResource(slotNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.slot", "resource.smtx.ip_metro.slot");
                            metroSlotResource.setName(slotName);
                            metroSlotResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            metroSlotResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            metroSlotResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            metroSlotResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            metroSlotResource.getAttributes().put("tipoPlaca", a.get("TipoPlaca").asString("-"));
                            metroSlotResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            metroSlotResource.getAttributes().put("numeroPortas", a.get("NumPortas").asString("0"));
                            metroSlotResource.getAttributes().put("numeroPortasLogicas", a.get("NumPortasLogicas").asString("0"));
                            metroSlotResource.getAttributes().put("numeroDIDs", a.get("NumDIDs").asString("0"));
                            metroSlotResource.getAttributes().put("numeroPlacas", a.get("NumPlacas").asString("0"));
                            metroSlotResource.setStructureId(metroResource.getKey());
                            this.netcompassApiClient.addManagedResource(metroSlotResource);

                            ResourceConnection metroSlotConnection = this.netcompassApiClient.getResourceConnection(metroShelfResource, metroSlotResource, "smtx_ip_metro", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(metroSlotConnection);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Metro Slot");
            }
        }
    }

    @ImportJobStep(stepIndex = 3, stepName = "importMetroModulo")
    private void importMetroModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetroModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Modulo").isNotNull()) {

                            String getSlotNodeAddress = a.get("Hostname").asString() + ".shelf-" + a.get("Shelf").asString() + ".slot-" + a.get("Slot").asString();

                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");
                            ManagedResource metroSlotResource = this.netcompassApiClient.getManagedResource(getSlotNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.slot", "resource.smtx.ip_metro.slot");

                            String moduloName = "modulo-" + a.get("Modulo").asString("N/A");
                            String moduloNodeAddress = getSlotNodeAddress + "." + moduloName;

                            ManagedResource metroModuloResource = this.netcompassApiClient.getManagedResource(moduloNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.modulo", "resource.smtx.ip_metro.modulo");
                            metroModuloResource.setName(moduloName);
                            metroModuloResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            metroModuloResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            metroModuloResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            metroModuloResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            metroModuloResource.getAttributes().put("modulo", a.get("Modulo").asString("0"));
                            metroModuloResource.getAttributes().put("tipoPlaca", a.get("TipoPlaca").asString("-"));
                            metroModuloResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            metroModuloResource.getAttributes().put("numeroPortas", a.get("NumPortas").asString("0"));
                            metroModuloResource.getAttributes().put("numeroPortasLogicas", a.get("NumPortasLogicas").asString("0"));
                            metroModuloResource.getAttributes().put("numeroDIDs", a.get("NumDIDs").asString("0"));
                            metroModuloResource.getAttributes().put("numeroPlacas", a.get("NumPlacas").asString("0"));
                            metroModuloResource.setStructureId(metroResource.getKey());
                            this.netcompassApiClient.addManagedResource(metroModuloResource);

                            ResourceConnection metroSlotConnection = this.netcompassApiClient.getResourceConnection(metroSlotResource, metroModuloResource, "smtx_ip_metro", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(metroSlotConnection);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Modulo Slot");
            }
        }
    }

    @ImportJobStep(stepIndex = 4, stepName = "importMetroPortaSemModulo")
    private void importMetroPortaSemModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Porta S/ Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetroPortaSemModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Porta").isNotNull()) {

                            String getSlotNodeAddress = a.get("Hostname").asString() + ".shelf-" + a.get("Shelf").asString() + ".slot-" + a.get("Slot").asString();
                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");
                            ManagedResource metroSlotResource = this.netcompassApiClient.getManagedResource(getSlotNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.slot", "resource.smtx.ip_metro.slot");

                            String portaName = "porta-" + a.get("Porta").asString("N/A");
                            String portaNodeAddress = getSlotNodeAddress + "." + portaName;

                            ManagedResource metroPortaResource = this.netcompassApiClient.getManagedResource(portaNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.porta", "resource.smtx.ip_metro.porta");
                            metroPortaResource.setName(portaName);
                            metroPortaResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            metroPortaResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            metroPortaResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            metroPortaResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            metroPortaResource.getAttributes().put("porta", a.get("Porta").asString("0"));
                            metroPortaResource.getAttributes().put("nome", a.get("Nome").asString("-"));
                            metroPortaResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            metroPortaResource.getAttributes().put("funcaoPorta", a.get("FuncaoPorta").asString("-"));
                            metroPortaResource.getAttributes().put("velocidadePorta", a.get("VelocidadePorta").asString("-"));
                            metroPortaResource.getAttributes().put("switchMode", a.get("SwitchMode").asString("-"));
                            metroPortaResource.getAttributes().put("autoNegociacao", a.get("AutoNegociacao").asString("-"));
                            metroPortaResource.getAttributes().put("eletrico", a.get("Eletrico").asString("-"));
                            metroPortaResource.setStructureId(metroResource.getKey());
                            this.netcompassApiClient.addManagedResource(metroPortaResource);

                            ResourceConnection metroPortaConnection = this.netcompassApiClient.getResourceConnection(metroSlotResource, metroPortaResource, "smtx_ip_metro", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(metroPortaConnection);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Porta S/ Modulo Slot");
            }
        }
    }

    @ImportJobStep(stepIndex = 5, stepName = "importMetroPortaComModulo")
    private void importMetroPortaComModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Metro Porta S/ Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getMetroPortaComModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Porta").isNotNull()) {

                            String getModuloNodeAddress = a.get("Hostname").asString() + ".shelf-" + a.get("Shelf").asString() + ".slot-" + a.get("Slot").asString() + ".modulo-" + a.get("Modulo").asString();
                            ManagedResource metroResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), "smtx_ip_metro", "resource.smtx.ip_metro", "resource.smtx.ip_metro");
                            ManagedResource metroModuloResource = this.netcompassApiClient.getManagedResource(getModuloNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.modulo", "resource.smtx.ip_metro.modulo");

                            String portaModuloName = "porta-" + a.get("Porta").asString("N/A");
                            String portaModuloNodeAddress = getModuloNodeAddress + "." + portaModuloName;

                            ManagedResource metroPortaModuloResource = this.netcompassApiClient.getManagedResource(portaModuloNodeAddress, "smtx_ip_metro", "resource.smtx.ip_metro.porta", "resource.smtx.ip_metro.porta");
                            metroPortaModuloResource.setName(portaModuloName);
                            metroPortaModuloResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            metroPortaModuloResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            metroPortaModuloResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            metroPortaModuloResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            metroPortaModuloResource.getAttributes().put("modulo", a.get("Modulo").asString("0"));
                            metroPortaModuloResource.getAttributes().put("porta", a.get("Porta").asString("0"));
                            metroPortaModuloResource.getAttributes().put("nome", a.get("Nome").asString("-"));
                            metroPortaModuloResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            metroPortaModuloResource.getAttributes().put("funcaoPorta", a.get("FuncaoPorta").asString("-"));
                            metroPortaModuloResource.getAttributes().put("velocidadePorta", a.get("VelocidadePorta").asString("-"));
                            metroPortaModuloResource.getAttributes().put("switchMode", a.get("SwitchMode").asString("-"));
                            metroPortaModuloResource.getAttributes().put("autoNegociacao", a.get("AutoNegociacao").asString("-"));
                            metroPortaModuloResource.getAttributes().put("eletrico", a.get("Eletrico").asString("-"));
                            metroPortaModuloResource.setStructureId(metroResource.getKey());
                            this.netcompassApiClient.addManagedResource(metroPortaModuloResource);

                            ResourceConnection metroPortaConnection = this.netcompassApiClient.getResourceConnection(metroModuloResource, metroPortaModuloResource, "smtx_ip_metro", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(metroPortaConnection);
                        }

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }

                });
                importing = false;
                attempt++;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Porta C/ Modulo Slot");
            }
        }
    }

}
