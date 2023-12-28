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
import com.osstelecom.db.inventory.manager.resources.*;
import com.osstelecom.db.inventory.manager.utils.ResultSetStreamReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
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

import io.swagger.v3.oas.annotations.media.Schema;
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
public class SMTXDwdmJob implements Job {

    @Autowired
    private DataSource dataSource;

    private String Domain = "smtx_dwdm";

    private final Logger logger = LoggerFactory.getLogger(SMTXDwdmJob.class);

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
        this.netcompassApiClient.initLoadSession(Domain.toUpperCase(), this.configuration.getFlushThreads());
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
            this.importDwdm();

            this.initConnection();
            this.importDwdmShelf();

            this.initConnection();
            this.importDwdmSlot();

            this.initConnection();
            this.importDwdmModulo();

            this.initConnection();
            this.importDwdmPortaSemModulo();

            this.initConnection();
            this.importDwdmPortaComModulo();

            /*this.initConnection();
            this.importCircuitoOtsRx();

            this.initConnection();
            this.importCircuitoOtsTx();

            this.initConnection();
            this.importCircuitoOts();

            this.initConnection();
            this.importCircuitoOms();

            this.initConnection();
            this.importCircuitoOch();

            this.initConnection();
            this.importCircuitoOdu();*/

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
    @ImportJobStep(stepIndex = 0, stepName = "importDwdm")
    private void importDwdm() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdm"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull()) {

                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                            dwdmResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmResource.getAttributes().put("status", a.get("Status").asString("-"));
                            dwdmResource.getAttributes().put("camada", a.get("Camada").asString("-"));
                            dwdmResource.getAttributes().put("fabricante", a.get("Fabricante").asString("-"));
                            dwdmResource.getAttributes().put("modelo", a.get("Modelo").asString("-"));
                            dwdmResource.getAttributes().put("numeroShelfs", a.get("NumShelfs").asString("-"));
                            dwdmResource.getAttributes().put("siglaSite", a.get("SiglaSite").asString("-"));
                            dwdmResource.getAttributes().put("uf", a.get("UfSite").asString("-"));
                            dwdmResource.getAttributes().put("funcaoEnderecoIP", a.get("FuncaoEnderecoIP").asString("-"));
                            dwdmResource.getAttributes().put("enderecoIP", a.get("EnderecoIP").asString("-"));
                            dwdmResource.getAttributes().put("funcaoEnderecoIP2", a.get("FuncaoEnderecoIP2").asString("-"));
                            dwdmResource.getAttributes().put("enderecoIP2", a.get("EnderecoIP2").asString("-"));
                            dwdmResource.getAttributes().put("funcaoEnderecoIP3", a.get("FuncaoEnderecoIP3").asString("-"));
                            dwdmResource.getAttributes().put("enderecoIP3", a.get("EnderecoIP3").asString("-"));
                            dwdmResource.getAttributes().put("funcaoEnderecoIP4", a.get("FuncaoEnderecoIP4").asString("-"));
                            dwdmResource.getAttributes().put("enderecoIP4", a.get("EnderecoIP4").asString("-"));
                            dwdmResource.getAttributes().put("configuracao", a.get("Configuracao").asString("-"));
                            dwdmResource.getAttributes().put("numeroOe", a.get("NumOE").asString("-"));
                            this.netcompassApiClient.addManagedResource(dwdmResource);
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
                logger.debug("Done Creating Dwdm");
            }
        }
    }

    @ImportJobStep(stepIndex = 1, stepName = "importDwdmShelf")
    private void importDwdmShelf() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Shelf Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmShelf"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Shelf").isNotNull()) {

                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                            String shelfName = "SHELF-" + a.get("Shelf").asString("N/A");
                            String shelfNodeAddress = dwdmResource.getNodeAddress() + "." + shelfName;

                            ManagedResource dwdmShelfResource = this.netcompassApiClient.getManagedResource(shelfNodeAddress, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                            dwdmShelfResource.setName(shelfName);
                            dwdmShelfResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmShelfResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            dwdmShelfResource.getAttributes().put("tipoAddDrop", a.get("TipoAddDrop").asString("-"));
                            dwdmShelfResource.getAttributes().put("statusShelf", a.get("StatusShelf").asString("-"));
                            dwdmShelfResource.getAttributes().put("shelf", a.get("Shelf").asString("-"));
                            dwdmShelfResource.getAttributes().put("subModeloEquipamento", a.get("SubModeloEquipamento").asString("-"));
                            dwdmShelfResource.setStructureId(dwdmResource.getKey());
                            this.netcompassApiClient.addManagedResource(dwdmShelfResource);

                            ResourceConnection dwdmShelfConnection = this.netcompassApiClient.getResourceConnection(dwdmResource, dwdmShelfResource, Domain, "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(dwdmShelfConnection);
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
                logger.debug("Done Creating Dwdm Shelf");
            }
        }
    }

    @ImportJobStep(stepIndex = 2, stepName = "importDwdmSlot")
    private void importDwdmSlot() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Slot Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmSlot"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Slot").isNotNull()) {

                            String getShelfNodeAddress = a.get("Hostname").asString() + ".SHELF-" + a.get("Shelf").asString();
                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                            ManagedResource dwdmShelfResource = this.netcompassApiClient.getManagedResource(getShelfNodeAddress, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");

                            String slotName = "SLOT-" + a.get("Slot").asString("N/A");
                            String slotNodeAddress = getShelfNodeAddress + "." + slotName;

                            ManagedResource dwdmSlotResource = this.netcompassApiClient.getManagedResource(slotNodeAddress, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                            dwdmSlotResource.setName(slotName);
                            dwdmSlotResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmSlotResource.getAttributes().put("shelf", a.get("Shelf").asInteger(0));
                            dwdmSlotResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            dwdmSlotResource.getAttributes().put("slot", a.get("Slot").asInteger(0));
                            dwdmSlotResource.getAttributes().put("statusSlot", a.get("StatusSlot").asString("-"));
                            dwdmSlotResource.getAttributes().put("tipoPlaca", a.get("TipoPlaca").asString("-"));
                            dwdmSlotResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            dwdmSlotResource.getAttributes().put("numeroPortas", a.get("NumPortas").asInteger(0));
                            dwdmSlotResource.getAttributes().put("numeroPortasLogicas", a.get("NumPortasLogicas").asInteger(0));
                            dwdmSlotResource.getAttributes().put("numeroDIDs", a.get("NumDIDs").asInteger(0));
                            dwdmSlotResource.getAttributes().put("numeroPlacas", a.get("NumPlacas").asInteger(0));
                            dwdmSlotResource.getAttributes().put("numeroOe", a.get("NumOE").asString("-"));
                            dwdmSlotResource.setStructureId(dwdmResource.getKey());
                            this.netcompassApiClient.addManagedResource(dwdmSlotResource);

                            ResourceConnection dwdmSlotConnection = this.netcompassApiClient.getResourceConnection(dwdmShelfResource, dwdmSlotResource, Domain, "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(dwdmSlotConnection);
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
                logger.debug("Done Creating Dwdm Slot");
            }
        }
    }

    @ImportJobStep(stepIndex = 3, stepName = "importDwdmModulo")
    private void importDwdmModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Modulo").isNotNull()) {

                            String getSlotNodeAddress = a.get("Hostname").asString() + ".SHELF-" + a.get("Shelf").asString() + ".SLOT-" + a.get("Slot").asString();

                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                            ManagedResource dwdmSlotResource = this.netcompassApiClient.getManagedResource(getSlotNodeAddress, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");

                            String moduloName = "MODULO-" + a.get("Modulo").asString("N/A");
                            String moduloNodeAddress = getSlotNodeAddress + "." + moduloName;

                            ManagedResource dwdmModuloResource = this.netcompassApiClient.getManagedResource(moduloNodeAddress, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                            dwdmModuloResource.setName(moduloName);
                            dwdmModuloResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmModuloResource.getAttributes().put("shelf", a.get("Shelf").asInteger(0));
                            dwdmModuloResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            dwdmModuloResource.getAttributes().put("slot", a.get("Slot").asInteger(0));
                            dwdmModuloResource.getAttributes().put("modulo", a.get("Modulo").asInteger(0));
                            dwdmModuloResource.getAttributes().put("statusSlot", a.get("StatusSlot").asString("-"));
                            dwdmModuloResource.getAttributes().put("tipoPlaca", a.get("TipoPlaca").asString("-"));
                            dwdmModuloResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            dwdmModuloResource.getAttributes().put("numeroPortas", a.get("NumPortas").asInteger(0));
                            dwdmModuloResource.getAttributes().put("numeroPortasLogicas", a.get("NumPortasLogicas").asInteger(0));
                            dwdmModuloResource.getAttributes().put("numeroDIDs", a.get("NumDIDs").asInteger(0));
                            dwdmModuloResource.getAttributes().put("numeroPlacas", a.get("NumPlacas").asInteger(0));
                            dwdmModuloResource.getAttributes().put("numeroOe", a.get("NumOE").asString("-"));
                            dwdmModuloResource.setStructureId(dwdmResource.getKey());
                            this.netcompassApiClient.addManagedResource(dwdmModuloResource);

                            ResourceConnection dwdmSlotConnection = this.netcompassApiClient.getResourceConnection(dwdmSlotResource, dwdmModuloResource, Domain, "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(dwdmSlotConnection);
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
                logger.debug("Done Creating Dwdm Modulo");
            }
        }
    }

    @ImportJobStep(stepIndex = 4, stepName = "importDwdmPortaSemModulo")
    private void importDwdmPortaSemModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Porta S/ Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmPortaSemModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Porta").isNotNull()) {

                            String getSlotNodeAddress = a.get("Hostname").asString() + ".SHELF-" + a.get("Shelf").asString() + ".SLOT-" + a.get("Slot").asString();
                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                            ManagedResource dwdmSlotResource = this.netcompassApiClient.getManagedResource(getSlotNodeAddress, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");

                            String portaName = "PORTA-" + a.get("Porta").asString("N/A");
                            String portaNodeAddress = getSlotNodeAddress + "." + portaName;

                            ManagedResource dwdmPortaResource = this.netcompassApiClient.getManagedResource(portaNodeAddress, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");
                            dwdmPortaResource.setName(portaName);
                            dwdmPortaResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmPortaResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            dwdmPortaResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            dwdmPortaResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            dwdmPortaResource.getAttributes().put("porta", a.get("Porta").asString("0"));
                            dwdmPortaResource.getAttributes().put("nome", a.get("Nome").asString("-"));
                            dwdmPortaResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            dwdmPortaResource.getAttributes().put("funcaoPorta", a.get("FuncaoPorta").asString("-"));
                            dwdmPortaResource.getAttributes().put("velocidadePorta", a.get("VelocidadePorta").asString("-"));
                            dwdmPortaResource.getAttributes().put("switchMode", a.get("SwitchMode").asString("-"));
                            dwdmPortaResource.getAttributes().put("autoNegociacao", a.get("AutoNegociacao").asString("-"));
                            dwdmPortaResource.getAttributes().put("eletrico", a.get("Eletrico").asString("-"));
                            dwdmPortaResource.setStructureId(dwdmResource.getKey());
                            this.netcompassApiClient.addManagedResource(dwdmPortaResource);

                            ResourceConnection dwdmPortaConnection = this.netcompassApiClient.getResourceConnection(dwdmSlotResource, dwdmPortaResource, Domain, "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(dwdmPortaConnection);
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
                logger.debug("Done Creating Dwdm Porta S/ Modulo");
            }
        }
    }

    @ImportJobStep(stepIndex = 5, stepName = "importDwdmPortaComModulo")
    private void importDwdmPortaComModulo() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Porta C/ Modulo Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmPortaComModulo"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        if (a.get("Hostname").isNotNull() && a.get("Porta").isNotNull()) {

                            String getModuloNodeAddress = a.get("Hostname").asString() + ".SHELF-" + a.get("Shelf").asString() + ".SLOT-" + a.get("Slot").asString() + ".MODULO-" + a.get("Modulo").asString();
                            ManagedResource dwdmResource = this.netcompassApiClient.getManagedResource(a.get("Hostname").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                            ManagedResource dwdmModuloResource = this.netcompassApiClient.getManagedResource(getModuloNodeAddress, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");

                            String portaModuloName = "PORTA-" + a.get("Porta").asString("N/A");
                            String portaModuloNodeAddress = getModuloNodeAddress + "." + portaModuloName;

                            ManagedResource dwdmPortaModuloResource = this.netcompassApiClient.getManagedResource(portaModuloNodeAddress, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");
                            dwdmPortaModuloResource.setName(portaModuloName);
                            dwdmPortaModuloResource.getAttributes().put("idEquipamento", a.get("IdEquipamento").asInteger(0));
                            dwdmPortaModuloResource.getAttributes().put("shelf", a.get("Shelf").asString("0"));
                            dwdmPortaModuloResource.getAttributes().put("hostname", a.get("Hostname").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("slot", a.get("Slot").asString("0"));
                            dwdmPortaModuloResource.getAttributes().put("modulo", a.get("Modulo").asString("0"));
                            dwdmPortaModuloResource.getAttributes().put("porta", a.get("Porta").asString("0"));
                            dwdmPortaModuloResource.getAttributes().put("nome", a.get("Nome").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("funcaoPorta", a.get("FuncaoPorta").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("velocidadePorta", a.get("VelocidadePorta").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("switchMode", a.get("SwitchMode").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("autoNegociacao", a.get("AutoNegociacao").asString("-"));
                            dwdmPortaModuloResource.getAttributes().put("eletrico", a.get("Eletrico").asString("-"));
                            dwdmPortaModuloResource.setStructureId(dwdmResource.getKey());
                            this.netcompassApiClient.addManagedResource(dwdmPortaModuloResource);

                            ResourceConnection dwdmPortaModuloConnection = this.netcompassApiClient.getResourceConnection(dwdmModuloResource, dwdmPortaModuloResource, Domain, "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(dwdmPortaModuloConnection);
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
                logger.debug("Done Creating Dwdm Porta C/ Modulo");
            }
        }
    }

    @ImportJobStep(stepIndex = 6, stepName = "importCircuitoOtsRx")
    private void importCircuitoOtsRx() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Dwdm Circuito Ots Rx Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOts"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {

                r.forEach(a -> {
                    try {

                        List<ResourceConnection> pathsRx = new ArrayList<>();

                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                        /**
                         * Trechos RX Ponta A
                         */

                        String rxShelfA = hostnameA.getNodeAddress() + ".SHELF-" + a.get("RxShelfA").asString("N/A");
                        String rxSlotA = rxShelfA + ".SLOT-" + a.get("RxSlotA").asString("N/A");

                        ManagedResource rxShelfAResource = this.netcompassApiClient.getManagedResource(rxShelfA, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                        ManagedResource rxSlotAResource = this.netcompassApiClient.getManagedResource(rxSlotA, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                        ManagedResource rxPortaAResource;

                        if (a.get("RxModuloA").isNotNull()) {
                            String rxModuloA = rxSlotA + ".MODULO-" + a.get("RxModuloA").asString("N/A");
                            String rxPortaA = rxModuloA + ".PORTA-" + a.get("RxPortaA").asString("N/A");

                            ManagedResource rxModuloAResource = this.netcompassApiClient.getManagedResource(rxModuloA, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                            rxPortaAResource = this.netcompassApiClient.getManagedResource(rxPortaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsRx.add(this.netcompassApiClient.getResourceConnection(hostnameA, rxShelfAResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxShelfAResource, rxSlotAResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxSlotAResource, rxModuloAResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxModuloAResource, rxPortaAResource, Domain, "connection.default", "connection.default"));

                        } else {
                            String rxPortaA = rxSlotA + ".PORTA-" + a.get("RxPortaA").asString("N/A");

                            rxPortaAResource = this.netcompassApiClient.getManagedResource(rxPortaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsRx.add(this.netcompassApiClient.getResourceConnection(hostnameA, rxShelfAResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxShelfAResource, rxSlotAResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxSlotAResource, rxPortaAResource, Domain, "connection.default", "connection.default"));
                        }


                        /**
                         * Trechos RX Ponta B
                         */
                        String rxShelfB = hostnameB.getNodeAddress() + ".SHELF-" + a.get("RxShelfB").asString("N/A");
                        String rxSlotB = rxShelfB + ".SLOT-" + a.get("RxSlotB").asString("N/A");

                        ManagedResource rxShelfBResource = this.netcompassApiClient.getManagedResource(rxShelfB, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                        ManagedResource rxSlotBResource = this.netcompassApiClient.getManagedResource(rxSlotB, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                        ManagedResource rxPortaBResource;

                        if (a.get("RxModuloB").isNotNull()) {

                            String rxModuloB = rxSlotB + ".MODULO-" + a.get("RxModuloB").asString("N/A");
                            String rxPortaB = rxModuloB + ".PORTA-" + a.get("RxPortaB").asString("N/A");

                            ManagedResource rxModuloBResource = this.netcompassApiClient.getManagedResource(rxModuloB, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                            rxPortaBResource = this.netcompassApiClient.getManagedResource(rxPortaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsRx.add(this.netcompassApiClient.getResourceConnection(hostnameB, rxShelfBResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxShelfBResource, rxSlotBResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxSlotBResource, rxModuloBResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxModuloBResource, rxPortaBResource, Domain, "connection.default", "connection.default"));
                        } else {
                            String rxPortaB = rxSlotB + ".PORTA-" + a.get("RxPortaB").asString("N/A");

                            rxPortaBResource = this.netcompassApiClient.getManagedResource(rxPortaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsRx.add(this.netcompassApiClient.getResourceConnection(hostnameB, rxShelfBResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxShelfBResource, rxSlotBResource, Domain, "connection.default", "connection.default"));
                            pathsRx.add(this.netcompassApiClient.getResourceConnection(rxSlotBResource, rxPortaBResource, Domain, "connection.default", "connection.default"));

                        }


                        ResourceConnection portaRxConnection = this.netcompassApiClient.getResourceConnection(rxPortaAResource, rxPortaBResource, Domain, "connection.default", "connection.default");
                        this.netcompassApiClient.addResourceConnection(portaRxConnection);
                        pathsRx.add(portaRxConnection);

                        String nameCircuitoRx = "OTS-RX." + a.get("IdOts").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOtsRx = this.netcompassApiClient.getCircuitResource(nameCircuitoRx, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.ots.rx", "circuit.smtx.dwdm.ots.rx");
                        circuitOtsRx.setName(nameCircuitoRx);
                        circuitOtsRx.getAttributes().put("idOts", a.get("IdOts").asInteger(0));
                        this.netcompassApiClient.addCircuitResource(circuitOtsRx);

                        CircuitPathDTO pathRx = this.netcompassApiClient.getCircuitPath(circuitOtsRx, pathsRx);
                        this.netcompassApiClient.addCircuitPath(pathRx);


                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Ots Rx");
            }
        }
    }

    @ImportJobStep(stepIndex = 7, stepName = "importCircuitoOtsTx")
    private void importCircuitoOtsTx() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Dwdm Circuito Ots Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOts"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {

                r.forEach(a -> {
                    try {

                        List<ResourceConnection> pathsTx = new ArrayList<>();

                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                        /**
                         * Trechos TX Ponta A
                         */

                        String txShelfA = hostnameA.getNodeAddress() + ".SHELF-" + a.get("TxShelfA").asString("N/A");
                        String txSlotA = txShelfA + ".SLOT-" + a.get("TxSlotA").asString("N/A");

                        ManagedResource txShelfAResource = this.netcompassApiClient.getManagedResource(txShelfA, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                        ManagedResource txSlotAResource = this.netcompassApiClient.getManagedResource(txSlotA, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                        ManagedResource txPortaAResource;


                        if (a.get("TxModuloA").isNotNull()) {

                            String txModuloA = txSlotA + ".MODULO-" + a.get("TxModuloA").asString("N/A");
                            String txPortaA = txModuloA + ".PORTA-" + a.get("TxPortaA").asString("N/A");

                            ManagedResource txModuloAResource = this.netcompassApiClient.getManagedResource(txModuloA, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                            txPortaAResource = this.netcompassApiClient.getManagedResource(txPortaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsTx.add(this.netcompassApiClient.getResourceConnection(hostnameA, txShelfAResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txShelfAResource, txSlotAResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txSlotAResource, txModuloAResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txModuloAResource, txPortaAResource, Domain, "connection.default", "connection.default"));

                        } else {
                            String txPortaA = txSlotA + ".PORTA-" + a.get("TxPortaA").asString("N/A");

                            txPortaAResource = this.netcompassApiClient.getManagedResource(txPortaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsTx.add(this.netcompassApiClient.getResourceConnection(hostnameA, txShelfAResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txShelfAResource, txSlotAResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txSlotAResource, txPortaAResource, Domain, "connection.default", "connection.default"));

                        }


                        /**
                         * Trechos TX Ponta B
                         */
                        String txShelfB = hostnameB.getNodeAddress() + ".SHELF-" + a.get("TxShelfB").asString("N/A");
                        String txSlotB = txShelfB + ".SLOT-" + a.get("TxSlotB").asString("N/A");

                        ManagedResource txShelfBResource = this.netcompassApiClient.getManagedResource(txShelfB, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                        ManagedResource txSlotBResource = this.netcompassApiClient.getManagedResource(txSlotB, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                        ManagedResource txPortaBResource;


                        if (a.get("TxModuloB").isNotNull()) {

                            String txModuloB = txSlotB + ".MODULO-" + a.get("TxModuloB").asString("N/A");
                            String txPortaB = txModuloB + ".PORTA-" + a.get("TxPortaB").asString("N/A");

                            ManagedResource txModuloBResource = this.netcompassApiClient.getManagedResource(txModuloB, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                            txPortaBResource = this.netcompassApiClient.getManagedResource(txPortaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsTx.add(this.netcompassApiClient.getResourceConnection(hostnameB, txShelfBResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txShelfBResource, txSlotBResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txSlotBResource, txModuloBResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txModuloBResource, txPortaBResource, Domain, "connection.default", "connection.default"));

                        } else {

                            String txPortaB = txSlotB + ".PORTA-" + a.get("TxPortaB").asString("N/A");

                            txPortaBResource = this.netcompassApiClient.getManagedResource(txPortaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                            pathsTx.add(this.netcompassApiClient.getResourceConnection(hostnameB, txShelfBResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txShelfBResource, txSlotBResource, Domain, "connection.default", "connection.default"));
                            pathsTx.add(this.netcompassApiClient.getResourceConnection(txSlotBResource, txPortaBResource, Domain, "connection.default", "connection.default"));

                        }


                        ResourceConnection portasTxConnection = this.netcompassApiClient.getResourceConnection(txPortaAResource, txPortaBResource, Domain, "connection.default", "connection.default");
                        this.netcompassApiClient.addResourceConnection(portasTxConnection);
                        pathsTx.add(portasTxConnection);

                        String nameCircuitoTx = "OTS-TX." + a.get("IdOts").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource CircuitOtsTx = this.netcompassApiClient.getCircuitResource(nameCircuitoTx, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.ots.tx", "circuit.smtx.dwdm.ots.tx");
                        CircuitOtsTx.setName(nameCircuitoTx);
                        CircuitOtsTx.getAttributes().put("idOts", a.get("IdOts").asInteger(0));
                        this.netcompassApiClient.addCircuitResource(CircuitOtsTx);

                        CircuitPathDTO pathTx = this.netcompassApiClient.getCircuitPath(CircuitOtsTx, pathsTx);
                        this.netcompassApiClient.addCircuitPath(pathTx);


                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Ots Tx");
            }
        }
    }

    @ImportJobStep(stepIndex = 8, stepName = "importCircuitoOts")
    private void importCircuitoOts() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Dwdm Circuito Ots Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOts"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {

                r.forEach(a -> {
                    try {

                        List<ResourceConnection> paths = new ArrayList<>();
                        List<ResourceConnection> connections = new ArrayList<>();

                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");


                        /**
                         * Circuito Ots
                         */

                        String nameCircuitoOtsRx = "OTS-RX." + a.get("IdOts").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOtsRx = this.netcompassApiClient.getCircuitResource(nameCircuitoOtsRx, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.ots.rx", "circuit.smtx.dwdm.ots.rx");
                        CircuitPathDTO pathDtoRx = this.netcompassApiClient.getCircuitPath(circuitOtsRx, connections);
                        pathDtoRx.getPaths().forEach(n -> paths.add(n));

                        String nameCircuitoOtsTx = "OTS-TX." + a.get("IdOts").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOtsTx = this.netcompassApiClient.getCircuitResource(nameCircuitoOtsTx, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.ots.tx", "circuit.smtx.dwdm.ots.tx");
                        CircuitPathDTO pathDtoTx = this.netcompassApiClient.getCircuitPath(circuitOtsTx, connections);
                        pathDtoTx.getPaths().forEach(n -> paths.add(n));


                        String nameCircuitoOts = "OTS." + a.get("IdOts").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOts = this.netcompassApiClient.getCircuitResource(nameCircuitoOts, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.ots", "circuit.smtx.dwdm.ots");
                        circuitOts.setName(nameCircuitoOts);
                        circuitOts.getAttributes().put("idOts", a.get("IdOts").asInteger(0));
                        circuitOts.getAttributes().put("statusOts", a.get("StatusOts").asString("-"));
                        circuitOts.getAttributes().put("sigla", a.get("Sigla").asString("-"));
                        circuitOts.getAttributes().put("idEquipamentoA", a.get("IdEquipamentoA").asInteger(0));
                        circuitOts.getAttributes().put("idEquipamentoB", a.get("IdEquipamentoB").asInteger(0));
                        circuitOts.getAttributes().put("distanciaReal", a.get("DistanciaReal").asString("0"));
                        circuitOts.getAttributes().put("distanciaTeorica", a.get("DistanciaTeorica").asString("0"));
                        circuitOts.getAttributes().put("atenuacaoRealTX", a.get("AtenuacaoRealTX").asString("0"));
                        circuitOts.getAttributes().put("atenuacaoRealRX", a.get("AtenuacaoRealRX").asString("0"));
                        circuitOts.getAttributes().put("atenuacaoTeorica", a.get("AtenuacaoTeorica").asString("0"));
                        circuitOts.getAttributes().put("tipoRedeOptica", a.get("TipoRedeOptica").asString("-"));
                        circuitOts.getAttributes().put("tipoFibra", a.get("TipoFibra").asString("-"));
                        circuitOts.getAttributes().put("margemSistemica", a.get("MargemSistemica").asInteger(0));
                        circuitOts.getAttributes().put("coerente", (a.get("Coerente").asString().equals("S") ? true : false));
                        circuitOts.getAttributes().put("chaveOts", a.get("ChaveOTS").asString("0"));
                        circuitOts.getAttributes().put("numeroOeAtivacao", a.get("NumOEAtivacao").asString("-"));
                        circuitOts.getAttributes().put("numeroOeDesativado", a.get("NumOEDesativ").asString("-"));
                        circuitOts.getAttributes().put("dbKm", a.get("dB_Km").asString("0"));
                        circuitOts.getAttributes().put("idEtp", a.get("idETP").asInteger(0));
                        circuitOts.getAttributes().put("rxShelfA", a.get("RxShelfA").asInteger(0));
                        circuitOts.getAttributes().put("rxSlotA", a.get("RxSlotA").asInteger(0));
                        circuitOts.getAttributes().put("rxModuloA", a.get("RxModuloA").asInteger(0));
                        circuitOts.getAttributes().put("rxPortaA", a.get("RxPortaA").asInteger(0));
                        circuitOts.getAttributes().put("txShelfA", a.get("TxShelfA").asInteger(0));
                        circuitOts.getAttributes().put("txSlotA", a.get("TxSlotA").asInteger(0));
                        circuitOts.getAttributes().put("txModuloA", a.get("TxModuloA").asInteger(0));
                        circuitOts.getAttributes().put("txPortaA", a.get("TxPortaA").asInteger(0));
                        circuitOts.getAttributes().put("rxShelfB", a.get("RxShelfB").asInteger(0));
                        circuitOts.getAttributes().put("rxSlotB", a.get("RxSlotB").asInteger(0));
                        circuitOts.getAttributes().put("rxModuloB", a.get("RxModuloB").asInteger(0));
                        circuitOts.getAttributes().put("rxPortaB", a.get("RxPortaB").asInteger(0));
                        circuitOts.getAttributes().put("txShelfB", a.get("TxShelfB").asInteger(0));
                        circuitOts.getAttributes().put("txSlotB", a.get("TxSlotB").asInteger(0));
                        circuitOts.getAttributes().put("txModuloB", a.get("TxModuloB").asInteger(0));
                        circuitOts.getAttributes().put("txPortaB", a.get("TxPortaB").asInteger(0));
                        this.netcompassApiClient.addCircuitResource(circuitOts);

                        CircuitPathDTO pathDto = this.netcompassApiClient.getCircuitPath(circuitOts, paths);
                        this.netcompassApiClient.addCircuitPath(pathDto);

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Ots");
            }
        }
    }

    @ImportJobStep(stepIndex = 9, stepName = "importCircuitoOms")
    private void importCircuitoOms() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Circuito Oms Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOms"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {
                        List<ResourceConnection> pathsOts = new ArrayList<>();
                        List<Integer> iDsOts = new ArrayList<>();

                        try {
                            PreparedStatement smt = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCamadaOmsOts"));
                            smt.setString(1, a.get("IdOms").asString("N/A"));
                            try (ResultSetStreamReader f = new ResultSetStreamReader(smt)) {
                                f.forEach(d -> {
                                    try {
                                        // Criar uma lista de conexões vazia para pegar todos os patch do circuito
                                        List<ResourceConnection> connections = new ArrayList<>();
                                        ManagedResource hostnameTrechoA = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                        ManagedResource hostnameTrechoB = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                                        String nameCircuitoOts = "OTS." + d.get("IdOts").asString() + "." + hostnameTrechoA.getNodeAddress() + "." + hostnameTrechoB.getNodeAddress();
                                        CircuitResource circuitOts = this.netcompassApiClient.getCircuitResource(nameCircuitoOts, Domain, hostnameTrechoA, hostnameTrechoB, "circuit.smtx.dwdm.ots", "circuit.smtx.dwdm.ots");
                                        CircuitPathDTO pathDto = this.netcompassApiClient.getCircuitPath(circuitOts, connections);

                                        pathDto.getPaths().forEach(n -> pathsOts.add(n));
                                        iDsOts.add(d.get("IdOts").asInteger());

                                    } catch (SQLException | IOException | InvalidRequestException e) {
                                        throw new RuntimeException(e);
                                    }


                                });
                            } catch (SQLException ex) {
                                logger.error("Error Fetching Data", ex);
                            }
                        } catch (Exception ex) {
                            logger.error("Generic Error", ex);
                        }


                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                        String nameCircuitoOms = "OMS." + a.get("IdOms").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOms = this.netcompassApiClient.getCircuitResource(nameCircuitoOms, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.oms", "circuit.smtx.dwdm.oms");
                        circuitOms.setName(nameCircuitoOms);
                        circuitOms.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                        circuitOms.getAttributes().put("sigla", a.get("Sigla").asString("-"));
                        circuitOms.getAttributes().put("velocidade", a.get("Velocidade").asString("-"));
                        circuitOms.getAttributes().put("NumeroCanais", a.get("NumCanais").asInteger(0));
                        circuitOms.getAttributes().put("statusOms", a.get("StatusOms").asString("-"));
                        circuitOms.getAttributes().put("idOms", a.get("IdOms").asInteger(0));
                        circuitOms.getAttributes().put("idEquipamentoA", a.get("IdEquipamentoA").asString("-"));
                        circuitOms.getAttributes().put("idEquipamentoB", a.get("IdEquipamentoB").asString("-"));
                        circuitOms.getAttributes().put("idOts", iDsOts );
                        this.netcompassApiClient.addCircuitResource(circuitOms);

                        CircuitPathDTO pathOms = this.netcompassApiClient.getCircuitPath(circuitOms, pathsOts);
                        this.netcompassApiClient.addCircuitPath(pathOms);


                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Oms");
            }
        }
    }

    @ImportJobStep(stepIndex = 10, stepName = "importCircuitoOch")
    private void importCircuitoOch() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Circuito Och Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOch"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {
                        List<ResourceConnection> pathsOms = new ArrayList<>();
                        List<Integer> iDsOms = new ArrayList<>();
                        try {
                            String idOch = a.get("IdOch").asString("N/A");
                            PreparedStatement smt = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCamadaOchOms"));
                            smt.setString(1, idOch);
                            try (ResultSetStreamReader f = new ResultSetStreamReader(smt)) {
                                f.forEach(d -> {
                                    try {
                                        // Criar uma lista de conexões vazia para pegar todos os patch do circuito
                                        List<ResourceConnection> connections = new ArrayList<>();
                                        ManagedResource hostnameTrechoA = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                        ManagedResource hostnameTrechoB = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                        try {
                                            //Conectar o HostnameA com o primeiro hostaname do trecho e busca os patchs do hostname A
                                            if (d.get("OrdemA").asInteger() == 0) {

                                                String hostnameA = d.get("HostnameA").asString("N/A");
                                                String shelfA = hostnameA + ".SHELF-" + d.get("ShelfA").asString("N/A");
                                                String slotA = shelfA + ".SLOT-" + d.get("SlotA").asString("N/A");

                                                ManagedResource hostnameAResource = this.netcompassApiClient.getManagedResource(hostnameA, Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                                ManagedResource shelfAResource = this.netcompassApiClient.getManagedResource(shelfA, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                                                ManagedResource slotAResource = this.netcompassApiClient.getManagedResource(slotA, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                                                ManagedResource portaAResource;


                                                if (d.get("ModuloA").isNotNull()) {

                                                    String moduloA = slotA + ".MODULO-" + d.get("ModuloA").asString("N/A");
                                                    String portaA = moduloA + ".PORTA-" + d.get("PortaA").asString("N/A");

                                                    ManagedResource moduloAResource = this.netcompassApiClient.getManagedResource(moduloA, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                                                    portaAResource = this.netcompassApiClient.getManagedResource(portaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(hostnameAResource, shelfAResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(shelfAResource, slotAResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(slotAResource, moduloAResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(moduloAResource, portaAResource, Domain, "connection.default", "connection.default"));


                                                } else {
                                                    String portaA = slotA + ".PORTA-" + d.get("PortaA").asString("N/A");

                                                    portaAResource = this.netcompassApiClient.getManagedResource(portaA, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(hostnameAResource, shelfAResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(shelfAResource, slotAResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(slotAResource, portaAResource, Domain, "connection.default", "connection.default"));
                                                }

                                                ManagedResource nodeAddressA = this.netcompassApiClient.getManagedResource(portaAResource.getNodeAddress(), Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");
                                                ResourceConnection ochOrdemAConnection = this.netcompassApiClient.getResourceConnection(nodeAddressA, hostnameTrechoA, Domain, "connection.default", "connection.default");
                                                this.netcompassApiClient.addResourceConnection(ochOrdemAConnection);
                                                pathsOms.add(ochOrdemAConnection);

                                            //Conectar o HostnameB com o ultimo hostaname do trecho e busca os patchs do hostname B
                                            } else if (d.get("OrdemB").asInteger() == 1) {

                                                String hostnameB = d.get("HostnameB").asString("N/A");
                                                String shelfB = hostnameB + ".SHELF-" + d.get("ShelfB").asString("N/A");
                                                String slotB = shelfB + ".SLOT-" + d.get("SlotB").asString("N/A");

                                                ManagedResource hostnameBResource = this.netcompassApiClient.getManagedResource(hostnameB, Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                                ManagedResource shelfBResource = this.netcompassApiClient.getManagedResource(shelfB, Domain, "resource.smtx.dwdm.shelf", "resource.smtx.dwdm.shelf");
                                                ManagedResource slotBResource = this.netcompassApiClient.getManagedResource(slotB, Domain, "resource.smtx.dwdm.slot", "resource.smtx.dwdm.slot");
                                                ManagedResource portaBResource;


                                                if (d.get("ModuloB").isNotNull()) {

                                                    String moduloB = slotB + ".MODULO-" + d.get("ModuloB").asString("N/A");
                                                    String portaB = moduloB + ".PORTA-" + d.get("PortaB").asString("N/A");

                                                    ManagedResource moduloBResource = this.netcompassApiClient.getManagedResource(moduloB, Domain, "resource.smtx.dwdm.modulo", "resource.smtx.dwdm.modulo");
                                                    portaBResource = this.netcompassApiClient.getManagedResource(portaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(hostnameBResource, shelfBResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(shelfBResource, slotBResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(slotBResource, moduloBResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(moduloBResource, portaBResource, Domain, "connection.default", "connection.default"));


                                                } else {
                                                    String portaB = slotB + ".PORTA-" + d.get("PortaB").asString("N/A");

                                                    portaBResource = this.netcompassApiClient.getManagedResource(portaB, Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");

                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(hostnameBResource, shelfBResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(shelfBResource, slotBResource, Domain, "connection.default", "connection.default"));
                                                    pathsOms.add(this.netcompassApiClient.getResourceConnection(slotBResource, portaBResource, Domain, "connection.default", "connection.default"));
                                                }

                                                ManagedResource nodeAddressB = this.netcompassApiClient.getManagedResource(portaBResource.getNodeAddress(), Domain, "resource.smtx.dwdm.porta", "resource.smtx.dwdm.porta");
                                                ResourceConnection ochOrdemBConnection = this.netcompassApiClient.getResourceConnection(nodeAddressB, hostnameTrechoB, Domain, "connection.default", "connection.default");
                                                this.netcompassApiClient.addResourceConnection(ochOrdemBConnection);
                                                pathsOms.add(ochOrdemBConnection);
                                        }

                                        } catch (SQLException | IOException | InvalidRequestException |
                                                 LocalQueueException ex) {
                                            logger.error("Error Fetching Data", ex);
                                        }

                                        //Cria conexão entre Trechos(oms)
                                        if (d.get("EnlaceOms").isNotNull())
                                        {
                                            ManagedResource enlaceOms = this.netcompassApiClient.getManagedResource(d.get("EnlaceOms").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                            ResourceConnection enlaceOmsConnection = this.netcompassApiClient.getResourceConnection(hostnameTrechoB, enlaceOms, Domain, "connection.default", "connection.default");
                                            this.netcompassApiClient.addResourceConnection(enlaceOmsConnection);
                                            pathsOms.add(enlaceOmsConnection);
                                        }

                                        //Criado apenas para pegar a ordem certa dos nomes do OMS
                                        ManagedResource hostnameTrechoOmsA = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoOmsA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                        ManagedResource hostnameTrechoOmsB = this.netcompassApiClient.getManagedResource(d.get("HostnameTrechoOmsB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");


                                        // Busca os paths dos Oms Cadastrados
                                        String nameCircuitoOms = "OMS." + d.get("IdOms").asString() + "." + hostnameTrechoOmsA.getNodeAddress() + "." + hostnameTrechoOmsB.getNodeAddress();
                                        CircuitResource circuitOms = this.netcompassApiClient.getCircuitResource(nameCircuitoOms, Domain, hostnameTrechoOmsA, hostnameTrechoOmsB, "circuit.smtx.dwdm.oms", "circuit.smtx.dwdm.oms");
                                        CircuitPathDTO pathDto = this.netcompassApiClient.getCircuitPath(circuitOms, connections);
                                        pathDto.getPaths().forEach(n -> pathsOms.add(n));
                                        iDsOms.add(d.get("IdOms").asInteger());

                                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                                        logger.error("Error Fetching Data", ex);
                                    }

                                });
                            } catch (SQLException ex) {
                                logger.error("Error Fetching Data", ex);
                            }
                        } catch (Exception ex) {
                            logger.error("Generic Error", ex);
                        }

                        //Cria o circuito de Och
                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                        String nameCircuitoOch = "OCH." + a.get("IdOch").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress() + ".CANAL-" + a.get("Canal").asString();
                        CircuitResource circuitOch = this.netcompassApiClient.getCircuitResource(nameCircuitoOch, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.och", "circuit.smtx.dwdm.och");
                        circuitOch.setName(nameCircuitoOch);
                        circuitOch.getAttributes().put("idOch", a.get("IdOch").asInteger(0));
                        circuitOch.getAttributes().put("statusOch", a.get("StatusOch").asString("-"));
                        circuitOch.getAttributes().put("idEquipamentoA", a.get("IdEquipamentoA").asInteger(0));
                        circuitOch.getAttributes().put("idEquipamentoB", a.get("IdEquipamentoB").asInteger(0));
                        circuitOch.getAttributes().put("tipoOch", a.get("TipoOch").asString("-"));
                        circuitOch.getAttributes().put("lambdaAlien", a.get("LambdaAlien").asString("-"));
                        circuitOch.getAttributes().put("sigla", a.get("Sigla").asString("-"));
                        circuitOch.getAttributes().put("funcao", a.get("Funcao").asString("-"));
                        circuitOch.getAttributes().put("velocidade", a.get("Velocidade").asString("-"));
                        circuitOch.getAttributes().put("canal", a.get("Canal").asString("-"));
                        circuitOch.getAttributes().put("idOms",iDsOms);
                        this.netcompassApiClient.addCircuitResource(circuitOch);

                        CircuitPathDTO pathOms = this.netcompassApiClient.getCircuitPath(circuitOch, pathsOms);
                        this.netcompassApiClient.addCircuitPath(pathOms);


                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Och");
            }
        }
    }

    @ImportJobStep(stepIndex = 11, stepName = "importCircuitoOdu")
    private void importCircuitoOdu() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Dwdm Circuito Odu Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCircuitoOdu"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        List<ResourceConnection> pathsOch = new ArrayList<>();
                        List<Integer> iDsOch = new ArrayList<>();
                        try {
                            PreparedStatement smt = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getDwdmCamadaOduOch"));
                            smt.setString(1, a.get("IdOdu").asString("N/A"));
                            smt.setString(2, a.get("Caminho").asString("N/A"));
                            try (ResultSetStreamReader f = new ResultSetStreamReader(smt)) {
                                f.forEach(d -> {
                                    try {
                                        // Criar uma lista de conexões vazia para pegar todos os patch do circuito
                                        List<ResourceConnection> connections = new ArrayList<>();
                                        ManagedResource hostnameOchA = this.netcompassApiClient.getManagedResource(d.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                                        ManagedResource hostnameOchB = this.netcompassApiClient.getManagedResource(d.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                                        String nameCircuitoOch = "OCH." + d.get("IdOch").asString() + "." + hostnameOchA.getNodeAddress() + "." + hostnameOchB.getNodeAddress();
                                        CircuitResource circuitOch = this.netcompassApiClient.getCircuitResource(nameCircuitoOch, Domain, hostnameOchA, hostnameOchB, "circuit.smtx.dwdm.och", "circuit.smtx.dwdm.och");
                                        CircuitPathDTO pathDto = this.netcompassApiClient.getCircuitPath(circuitOch, connections);

                                        pathDto.getPaths().forEach(n -> pathsOch.add(n));
                                        iDsOch.add(d.get("IdOch").asInteger());

                                    } catch (SQLException | IOException | InvalidRequestException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                            } catch (SQLException ex) {
                                logger.error("Error Fetching Data", ex);
                            }
                        } catch (Exception ex) {
                            logger.error("Generic Error", ex);
                        }



                        ManagedResource hostnameA = this.netcompassApiClient.getManagedResource(a.get("HostnameA").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");
                        ManagedResource hostnameB = this.netcompassApiClient.getManagedResource(a.get("HostnameB").asString(), Domain, "resource.smtx.dwdm", "resource.smtx.dwdm");

                        String nameCircuitoOdu = "ODU."+ a.get("Caminho").asString().toUpperCase() + "." + a.get("IdOdu").asString() + "." + hostnameA.getNodeAddress() + "." + hostnameB.getNodeAddress();
                        CircuitResource circuitOdu = this.netcompassApiClient.getCircuitResource(nameCircuitoOdu, Domain, hostnameA, hostnameB, "circuit.smtx.dwdm.odu", "circuit.smtx.dwdm.odu");
                        circuitOdu.setName(nameCircuitoOdu);
                        circuitOdu.getAttributes().put("caminhoAlternativo", (a.get("CaminhoAlternativo").equals("1")? true:false));
                        circuitOdu.getAttributes().put("idOdu", a.get("IdOdu").asInteger(0));
                        circuitOdu.getAttributes().put("sigla", a.get("Sigla").asString("-"));
                        circuitOdu.getAttributes().put("velocidade", a.get("Velocidade").asString("-"));
                        circuitOdu.getAttributes().put("statusOdu", a.get("StatusOdu").asString("-"));
                        circuitOdu.getAttributes().put("idEquipamentoA", a.get("IdEquipamentoA").asInteger(0));
                        circuitOdu.getAttributes().put("idEquipamentoB", a.get("IdEquipamentoB").asInteger(0));
                        circuitOdu.getAttributes().put("numeroOe", a.get("NumOE").asInteger(0));
                        circuitOdu.getAttributes().put("caminho", a.get("Caminho").asString("-"));
                        circuitOdu.getAttributes().put("idOch", iDsOch);
                        this.netcompassApiClient.addCircuitResource(circuitOdu);

                        CircuitPathDTO pathOdu = this.netcompassApiClient.getCircuitPath(circuitOdu, pathsOch);
                        this.netcompassApiClient.addCircuitPath(pathOdu);


                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Circuito Odu");
            }
        }
    }

}
