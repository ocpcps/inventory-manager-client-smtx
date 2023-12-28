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
package com.osstelecom.db.inventory.manager.client.smtx.service;

import com.osstelecom.db.inventory.manager.http.client.configuration.ConfigurationManager;
import com.osstelecom.db.inventory.manager.jobs.JobConfiguration;
import java.io.IOException;
import java.util.List;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;

/**
 * Cuida da Instancialização da JOB
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 10.03.2023
 */
@Service
public class JobManagerService {

    private final Logger logger = LoggerFactory.getLogger(JobManagerService.class);
    private final ConfigurationManager configurationManager = new ConfigurationManager();

    @Autowired
    private SchedulerFactoryBean schedulerFactoryBean;

    private Scheduler scheduler;

    private List<JobConfiguration> jobs;

    /**
     * Inicia e procura por Jobs configuradas no arquivo YML
     */
    @EventListener(ApplicationReadyEvent.class)
    private void onStartUp() throws IOException, SchedulerException {
        logger.debug("Hello There xD");
        this.jobs = configurationManager.loadJobsFromYml("config/jobs.yml");
        logger.debug("Found :[{}] Jobs ", this.jobs.size());
        this.scheduleJobs();

    }

    /**
     * Agenda a job para ser executada se estiver habilitada
     *
     * @throws SchedulerException
     */
    private void scheduleJobs() throws SchedulerException {
        this.scheduler = this.schedulerFactoryBean.getScheduler();
        this.scheduler.start();
        logger.debug("Scheduler Started!");

        this.jobs.forEach(job -> {
            if (job.isEnabled()) {
                try {
                    //
                    // Cria uma instancia da classe dinamicamente com base no nome
                    //
                    Class<? extends Job> act = Class.forName(job.getClassName()).asSubclass(Job.class);
                    JobDataMap jdm = new JobDataMap();
                    jdm.put("config", job);

                    JobDetail importerJob = JobBuilder
                            .newJob(act)
                            .setJobData(jdm)
                            .build();

                    Trigger trigger = TriggerBuilder.newTrigger()
                            .withDescription("(" + job.getName() + ") CRON: [" + job.getCron() + "]")
                            .withIdentity(job.getGroup() + "." + job.getName())
                            .withSchedule(CronScheduleBuilder.cronSchedule(job.getCron())).build();
                    this.scheduler.scheduleJob(importerJob, trigger);
                    logger.info("JOB:[" + job.getGroup() + "." + job.getName() + "] Scheduled :)");
                } catch (ClassNotFoundException | SchedulerException ex) {
                    logger.error("Failed To Schedule Job:[{}]", job.getName());
                }
            } else {
                logger.info("JOB:[" + job.getGroup() + "." + job.getName() + "] Not Enabled");
            }

        });
    }

}
