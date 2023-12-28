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

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 09.03.2023
 */
@DisallowConcurrentExecution
public class SMTXRadioJob implements Job {

    @Autowired
    private DataSource dataSource;

    private final Logger logger = LoggerFactory.getLogger(SMTXRadioJob.class);

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
        this.netcompassApiClient.initLoadSession("SMTX_RADIO", this.configuration.getFlushThreads());
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
            java.util.logging.Logger.getLogger(SMTXRadioJob.class.getName()).log(Level.SEVERE, null, ex);
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
            /**
             * 1. Importa os rádios... que vão ser estruturas
             */
            this.initConnection();
            this.importRadios();
            /**
             * Depois importa as portas dos rádios já ligando a porta ao rádio
             */
            this.initConnection();
            this.importPortas();
            this.initConnection();
            this.importAntenas();
            this.initConnection();
            this.importEnlaces();
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

    @ImportJobStep(stepIndex = 3, stepName = "importEnlaces")
    private void importEnlaces() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Enlace Import Step  Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getEnlaces"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                //
                // Vamos ter que recriar todas as conexões relacionadas ao enlace...
                //
                r.forEach(a -> {
                    try {
                        List<ResourceConnection> paths = new ArrayList<>();

                        /**
                         * Recupera a referencia do radio da PONTA_A
                         */
                        ManagedResource radioA = this.netcompassApiClient.getManagedResource(a.get("PONTA_A").asString(), "smtx_radios", "resource.smtx.radio", "resource.smtx.radio");

                        /**
                         * Vamos criar um padrão para o nome da Atena
                         */
                        String antenaA = radioA.getNodeAddress() + ".ANT-" + a.get("SIGLA_ANTENA_A").asString("N/A");

                        ManagedResource antenaAResource = this.netcompassApiClient.getManagedResource(antenaA, "smtx_radios", "resource.smtx.radio.antena", "resource.smtx.radio.antena");

                        paths.add(this.netcompassApiClient.getResourceConnection(radioA, antenaAResource, "smtx_radios", "connection.default", "connection.default"));

                        ManagedResource radioB = this.netcompassApiClient.getManagedResource(a.get("PONTA_B").asString(), "smtx_radios", "resource.smtx.radio", "resource.smtx.radio");
                        /**
                         * Vamos criar um padrão para o nome da Atena
                         */
                        String antenaB = radioA.getNodeAddress() + ".ANT-" + a.get("SIGLA_ANTENA_B").asString("N/A");

                        ManagedResource antenaBResource = this.netcompassApiClient.getManagedResource(antenaB, "smtx_radios", "resource.smtx.radio.antena", "resource.smtx.radio.antena");

                        paths.add(this.netcompassApiClient.getResourceConnection(radioB, antenaBResource, "smtx_radios", "connection.default", "connection.default"));

                        ResourceConnection antenaConnection = this.netcompassApiClient.getResourceConnection(antenaAResource, antenaBResource, "smtx_radios", "connection.smtx.radio.enlace", "connection.smtx.radio.enlace");

                        this.netcompassApiClient.addResourceConnection(antenaConnection);

                        //
                        // Conecta as antenas com: connection.smtx.radio.enlace
                        //
                        paths.add(antenaConnection);

                        //
                        // Vamos criar o circuito Primeiro
                        //
                        CircuitResource enlaceCircuit = this.netcompassApiClient.getCircuitResource(a.get("SINOTICO").asString(), "smtx_radios", radioA, radioB, "circuit.smtx.radio.enlace", "circuit.smtx.radio.enlace");

                        enlaceCircuit.getAttributes().put("pontaA", a.get("PONTA_A").asString("N/A"));
                        enlaceCircuit.getAttributes().put("pontaB", a.get("PONTA_B").asString("N/A"));
                        enlaceCircuit.getAttributes().put("alturaA", a.get("ALTURA_A").asString("0"));
                        enlaceCircuit.getAttributes().put("alturaB", a.get("ALTURA_B").asString("N/A"));
                        enlaceCircuit.getAttributes().put("diametroAntenaA", a.get("DIAMETRO_A").asString("N/A"));
                        enlaceCircuit.getAttributes().put("diametroAntenaB", a.get("DIAMETRO_B").asString("N/A"));
                        enlaceCircuit.getAttributes().put("endereco.IP.A", a.get("ENDERECO_IP_A").asString("N/A"));
                        enlaceCircuit.getAttributes().put("endereco.IP.B", a.get("ENDERECO_IP_B").asString("N/A"));
                        enlaceCircuit.getAttributes().put("capacidadeMinMBPS", a.get("CAPACIDADE_MB_MIN").asString("0"));
                        enlaceCircuit.getAttributes().put("capacidadeMaxMbps", a.get("CAPACIDADE_MB_MAX").asString("0"));
                        enlaceCircuit.getAttributes().put("nomenclaturaSiteA", a.get("UF_SITE_SIGLA_PONTA_A").asString("N/A"));
                        enlaceCircuit.getAttributes().put("nomenclaturaSiteB", a.get("UF_SITE_SIGLA_PONTA_B").asString("N/A"));

                        this.netcompassApiClient.addCircuitResource(enlaceCircuit);

                        CircuitPathDTO pathDto = this.netcompassApiClient.getCircuitPath(enlaceCircuit, paths);
                        this.netcompassApiClient.addCircuitPath(pathDto);

                    } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                        logger.error("Error Fetching Data", ex);
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Enlaces");
            }
        }
    }

    /**
     * Importa as antenas
     *
     * @throws SQLException
     */
    @ImportJobStep(stepIndex = 2, stepName = "importAntenas")
    private void importAntenas() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Antenas Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getAntenas"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    if (a.get("HOSTNAME").isNotNull() && a.get("SIGLA_ANTENA").isNotNull()) {
                        try {
                            /**
                             * Recupera a referencia do radio
                             */
                            ManagedResource radioResource = this.netcompassApiClient.getManagedResource(a.get("HOSTNAME").asString(), "smtx_radios", "resource.smtx.radio", "resource.smtx.radio");

                            /**
                             * Vamos criar um padrão para o nome da Atena
                             */
                            String antenaName = radioResource.getNodeAddress() + ".ANT-" + a.get("SIGLA_ANTENA").asString("N/A");

                            ManagedResource antenaResource = this.netcompassApiClient.getManagedResource(antenaName, "smtx_radios", "resource.smtx.radio.antena", "resource.smtx.radio.antena");
                            antenaResource.getAttributes().put("siglaAntena", a.get("SIGLA_ANTENA").asString("N/A"));
                            antenaResource.getAttributes().put("altura", a.get("ALTURA_ANTENA").asString("N/A"));
                            antenaResource.getAttributes().put("diametroAntena", a.get("DIAMETRO_ANTENA").asString("N/A"));
                            antenaResource.getAttributes().put("azimuth", a.get("AZIMUTE").asString("N/A"));
                            antenaResource.getAttributes().put("vendor", a.get("VENDOR_ANTENA").asString("N/A"));
                            antenaResource.getAttributes().put("modelo", a.get("MODELO_ANTENA").asString("N/A"));
                            antenaResource.getAttributes().put("anguloElevacao", a.get("ANGULO_ELEVACAO").asString("N/A"));
                            antenaResource.getAttributes().put("modulacao", a.get("MODULACAO").asString("N/A"));
                            antenaResource.getAttributes().put("modulacaoMax", a.get("MODULACAO_MAX").asString("N/A"));
                            antenaResource.getAttributes().put("modulacaoAdaptativa", a.get("MODULACAO_ADAPTATIVA").asString("N/A"));
                            antenaResource.getAttributes().put("espacamentoCanal", a.get("ESPACAMENTO_CANAL").asString("N/A"));
                            antenaResource.getAttributes().put("capacidadeMb", a.get("CAPACIDADE_MB").asString("N/A"));
                            antenaResource.getAttributes().put("capacidadeTotal", a.get("CAPACIDADE_MAX").asString("N/A"));

                            if (a.get("POLARIZACAO").asString("").toUpperCase().contains("VERTICAL")) {
                                antenaResource.getAttributes().put("freqTxVertical", a.get("FREQ_TX").asString("N/A"));
                                antenaResource.getAttributes().put("freqRxVertical", a.get("FREQ_RX").asString("N/A"));
                                antenaResource.getAttributes().put("polarizacao", "vertical");
//                            logger.debug("Created :POLARIZACAO:[{}]", a.get("POLARIZACAO").asString(""));
                            } else if (a.get("POLARIZACAO").asString("").toUpperCase().contains("HORIZONTAL")) {
                                antenaResource.getAttributes().put("freqTxHorizontal", a.get("FREQ_TX").asString("N/A"));
                                antenaResource.getAttributes().put("freqRxHorizontal", a.get("FREQ_RX").asString("N/A"));
                                antenaResource.getAttributes().put("polarizacao", "horizontal");
//                            logger.debug("Created :POLARIZACAO:[{}]", a.get("POLARIZACAO").asString(""));
                            }

//                        logger.debug("-------------------------------------------------------");
//                        logger.debug(this.netcompassApiClient.getGson().toJson(antenaResource));
//                        logger.debug("-------------------------------------------------------");
                            antenaResource.setStructureId(radioResource.getKey());
                            this.netcompassApiClient.addManagedResource(antenaResource);

                            /**
                             * Conecta o Radio a Porta
                             */
                            ResourceConnection radioAntenaConnection = this.netcompassApiClient.getResourceConnection(radioResource, antenaResource, "smtx_radios", "connection.default", "connection.default");
                            this.netcompassApiClient.addResourceConnection(radioAntenaConnection);

                        } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                            logger.error("Error Fetching Data", ex);
                        }
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Antenas");
            }
        }
    }

    /**
     * Importa as Portas Fast
     *
     * @throws SQLException
     */
    @ImportJobStep(stepIndex = 1, stepName = "importPortas")
    private void importPortas() throws SQLException {
        int attempt = 1;
        boolean importing = true;
        while (importing) {
            logger.debug("Starting Portas Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getPortas"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(p -> {
                    if (p.get("HOSTNAME").isNotNull() && p.get("PORTA").isNotNull()) {
                        try {
                            /**
                             * Vamos criar as portas do Radio... Mas para
                             * Criarmos a porta, precisamos do "Pai", lembre que
                             * se que na criação do radio usamos as seguintes
                             * caracteristicas: resource.smtx.radio
                             * AttributeSchemaName: resource.smtx.radio, Então
                             * vamos usar isso novamente para receber a
                             * referencia do recurso
                             */

                            ManagedResource radioResource = this.netcompassApiClient.getManagedResource(p.get("HOSTNAME").asString(), "smtx_radios", "resource.smtx.radio", "resource.smtx.radio");
                            /**
                             * Neste passo temos a referencia do PAI, então
                             * podemos criar o recurso e suas conexões dentro de
                             * sua estrutura inclusive. Note que há um padrão de
                             * nome encodado
                             */

                            String portaName = radioResource.getNodeAddress() + ".FE-" + p.get("PORTA").asString("N/A");

                            //
                            // Para cadastrar a porta
                            //
                            ManagedResource portaResource = this.netcompassApiClient.getManagedResource(portaName, "smtx_radios", "resource.smtx.radio.porta", "resource.smtx.radio.porta");
                            portaResource.getAttributes().put("velocidade", p.get("VELOCIDADE_PORTA").asString("-"));
                            portaResource.getAttributes().put("porta", p.get("PORTA").asString("-"));
                            portaResource.getAttributes().put("idDID", p.get("ID_DID").asString("-"));
                            portaResource.getAttributes().put("idFast", p.get("ID_FAST").asString("-"));
                            portaResource.setStructureId(radioResource.getKey());
                            this.netcompassApiClient.addManagedResource(portaResource);

                            /**
                             * Conecta o Radio a Porta
                             */
                            ResourceConnection radioPortaConnection = this.netcompassApiClient.getResourceConnection(radioResource, portaResource, "smtx_radios", "connection.default", "connection.default");

                            this.netcompassApiClient.addResourceConnection(radioPortaConnection);
                        } catch (SQLException | IOException | InvalidRequestException | LocalQueueException ex) {
                            logger.error("Error Fetching Data", ex);
                        }
                    }
                });
                importing = false;
            } catch (IllegalStateException | SQLException ex) {
                logger.error("Error Fetching Data", ex);
            } finally {
                logger.debug("Done Creating Portas");
            }
        }
    }

    /**
     * Importa e cria os recursos de radios
     *
     * @throws SQLException
     */
    @ImportJobStep(stepIndex = 0, stepName = "importRadios")
    private void importRadios() throws SQLException {
        int attempt = 1;
        boolean importing = true;

        while (importing) {
            logger.debug("Starting Radios Import Step Attempt[{}]", attempt);
            PreparedStatement pst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getRadios"));
            try (ResultSetStreamReader r = new ResultSetStreamReader(pst)) {
                r.forEach(a -> {
                    try {

                        //
                        // Somente se existir o campo hostname, poderia tratar isso na query né
                        // Mas deixei aqui para ilustrar a verificação de Null no campo
                        //
                        if (a.get("HOSTNAME").isNotNull()) {
                            /**
                             * É importante notar que o radio é criado com o
                             * nodeaddress de seu hostname ClassName:
                             * resource.smtx.radio AttributeSchemaName:
                             * resource.smtx.radio
                             */
                            ManagedResource radioResource = this.netcompassApiClient.getManagedResource(a.get("HOSTNAME").asString(), "smtx_radios", "resource.smtx.radio", "resource.smtx.radio");
                            radioResource.getAttributes().put("tecnologia", a.get("TECNOLOGIA").asString("N/A"));
                            radioResource.getAttributes().put("hosnamePlataforma", a.get("HOSTNAME_PLATAFORMA").asString("N/A"));
                            radioResource.getAttributes().put("hostname", a.get("HOSTNAME").asString("N/A"));
                            radioResource.getAttributes().put("siglaSite", a.get("SIGLA_SITE").asString("N/A"));
                            radioResource.getAttributes().put("fabricante", a.get("FABRICANTE").asString("N/A"));
                            radioResource.getAttributes().put("tecnologia", a.get("TECNOLOGIA").asString("N/A"));
                            radioResource.getAttributes().put("modelo", a.get("MODELO").asString("N/A"));
                            radioResource.getAttributes().put("ipGerencia", a.get("IP_GERENCIA").asString("N/A"));
                            radioResource.getAttributes().put("mascGerencia", a.get("MASC_GERENCIA").asString("N/A"));
                            radioResource.getAttributes().put("regional", a.get("REGIONAL").asString("N/A"));
                            radioResource.getAttributes().put("configuracao", a.get("CONFIGURACAO").asString("N/A"));
                            radioResource.getAttributes().put("gerenciado", a.get("GERENCIADO").asString("N/A"));
                            radioResource.getAttributes().put("hostnameAntena", radioResource.getNodeAddress() + ".ANT-" + a.get("SIGLA_ANTENA").asString("N/A"));
                            //
                            // Para cada Rádio vamos importar as Frequencias, bloco bem isoladinho
                            //
                            try {
                                PreparedStatement freqPst = this.sqlConnection.prepareStatement(this.configuration.getQueries().get("getFrequencias"));
                                freqPst.setString(1, a.get("HOSTNAME").asString("N/A"));
                                try (ResultSetStreamReader f = new ResultSetStreamReader(freqPst)) {
                                    AtomicInteger freqIdx = new AtomicInteger(1);
                                    f.forEach(d -> {
                                        String freqTxKey = "frequenciaTx" + freqIdx.get();
                                        String freqrxKey = "frequenciaRx" + freqIdx.get();
                                        String polarizacaoKey = "polarizacao" + freqIdx.get();

                                        radioResource.getAttributes().put(freqTxKey, d.get("FREQ_TX").asString("N/A"));
                                        radioResource.getAttributes().put(freqrxKey, d.get("FREQ_RX").asString("N/A"));
                                        radioResource.getAttributes().put(polarizacaoKey, d.get("POLARIZACAO").asString("N/A"));
                                        freqIdx.incrementAndGet();
                                    });
                                } catch (SQLException ex) {
                                    logger.error("Error Fetching Frequency Data", ex);
                                }
                            } catch (Exception ex) {
                                logger.error("Generic Error on Frequency", ex);
                            }

                            this.netcompassApiClient.addManagedResource(radioResource);
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
                logger.debug("Done Creating Radios");
            }
        }
    }
}
