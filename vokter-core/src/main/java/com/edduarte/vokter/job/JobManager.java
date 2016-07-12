/*
 * Copyright 2015 Eduardo Duarte
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edduarte.vokter.job;

import com.edduarte.vokter.Constants;
import com.edduarte.vokter.diff.DiffDetector;
import com.edduarte.vokter.diff.DiffEvent;
import com.edduarte.vokter.diff.DiffMatcher;
import com.edduarte.vokter.diff.Match;
import com.edduarte.vokter.document.DocumentBuilder;
import com.edduarte.vokter.keyword.Keyword;
import com.edduarte.vokter.keyword.KeywordBuilder;
import com.edduarte.vokter.parser.Parser;
import com.edduarte.vokter.parser.ParserPool;
import com.edduarte.vokter.parser.SimpleParser;
import com.edduarte.vokter.persistence.Diff;
import com.edduarte.vokter.persistence.DiffCollection;
import com.edduarte.vokter.persistence.Document;
import com.edduarte.vokter.persistence.DocumentCollection;
import com.edduarte.vokter.persistence.Session;
import com.edduarte.vokter.persistence.SessionCollection;
import com.google.gson.Gson;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.listeners.JobChainingJobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Eduardo Duarte (<a href="mailto:hello@edduarte.com">hello@edduarte.com</a>)
 * @version 1.3.3
 * @since 1.0.0
 */
public class JobManager {

    private static final Logger logger = LoggerFactory.getLogger(JobManager.class);

    private static final Map<String, JobManager> activeManagers = new HashMap<>();

    private final String managerName;

    private final List<JobManagerListener> handlers;

    private final ParserPool parserPool;

    private final LanguageDetector langDetector;

    private final boolean ignoreCase;

    private final boolean filterStopwords;

    private DocumentCollection documentCollection;

    private DiffCollection diffCollection;

    private SessionCollection sessionCollection;

    private Scheduler scheduler;


    private JobManager(String managerName,
                       ParserPool parserPool,
                       LanguageDetector langDetector,
                       boolean ignoreCase,
                       boolean filterStopwords) {
        this.managerName = managerName;
        this.handlers = new ArrayList<>();
        this.parserPool = parserPool;
        this.langDetector = langDetector;
        this.ignoreCase = ignoreCase;
        this.filterStopwords = filterStopwords;
        this.documentCollection = null;
        this.diffCollection = null;
        this.sessionCollection = null;
    }


    public static JobManager create(String name) {
        return create(name, false, false);
    }


    public static JobManager create(String managerName,
                                    boolean ignoreCase,
                                    boolean filterStopwords) {
        JobManager existingManager = get(managerName);
        if (existingManager != null) {
            return null;
        }

        try {
            List<LanguageProfile> languageProfiles =
                    new LanguageProfileReader().readAllBuiltIn();
            LanguageDetector langDetector = LanguageDetectorBuilder
                    .create(NgramExtractors.standard())
                    .withProfiles(languageProfiles)
                    .build();

            ParserPool parserPool = new ParserPool();
            for (int i = 1; i < Constants.MAX_THREADS; i++) {
                Parser p = new SimpleParser();
                parserPool.place(p);
                p = null;
            }

            JobManager newManager = new JobManager(
                    managerName,
                    parserPool,
                    langDetector,
                    ignoreCase,
                    filterStopwords
            );
            activeManagers.put(managerName, newManager);
            return newManager;

        } catch (IOException | InterruptedException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }


    public static JobManager create(String managerName,
                                    ParserPool parserPool,
                                    LanguageDetector langDetector,
                                    boolean ignoreCase,
                                    boolean filterStopwords) {
        JobManager existingManager = get(managerName);
        if (existingManager != null) {
            return null;
        }

        JobManager newManager = new JobManager(
                managerName,
                parserPool,
                langDetector,
                ignoreCase,
                filterStopwords
        );
        activeManagers.put(managerName, newManager);
        return newManager;
    }


    public static JobManager get(final String managerName) {
        return activeManagers.get(managerName);
    }


    private static String detectJobGroup(String documentUrl,
                                         String documentContentType) {
        return "detect|" + documentUrl + "|" + documentContentType;
    }


    private static String matchJobName(String clientId) {
        return clientId;
    }


    private static String matchJobGroup(String documentUrl,
                                        String documentContentType) {
        return "match|" + documentUrl + "|" + documentContentType;
    }


    private static String chainName(String documentUrl,
                                    String documentContentType,
                                    String clientId) {
        return "chain|" + documentUrl + "|" + documentContentType + "|" + clientId;
    }


    private static JobKey detectJobKey(String documentUrl,
                                       String documentContentType) {
        return new JobKey(documentContentType,
                detectJobGroup(documentUrl, documentContentType));
    }


    private static TriggerKey detectTriggerKey(String documentUrl,
                                               String documentContentType,
                                               String clientId) {
        return new TriggerKey(clientId,
                detectJobGroup(documentUrl, documentContentType));
    }


    private static JobKey matchJobKey(String documentUrl,
                                      String documentContentType,
                                      String clientId) {
        return new JobKey(
                matchJobName(clientId),
                matchJobGroup(documentUrl, documentContentType)
        );
    }


    public JobManager register(DocumentCollection documentCollection) {
        this.documentCollection = documentCollection;
        return this;
    }


    public JobManager register(DiffCollection diffCollection) {
        this.diffCollection = diffCollection;
        return this;
    }


    public JobManager register(SessionCollection sessionCollection) {
        this.sessionCollection = sessionCollection;
        return this;
    }


    public JobManager listener(JobManagerListener listener) {
        if (listener != null) {
            this.handlers.add(listener);
        }
        return this;
    }


    public JobManager initialize() throws SchedulerException {
        StdSchedulerFactory factory = new StdSchedulerFactory();
        scheduler = factory.getScheduler();
        factory = null;
        scheduler.start();
        return this;
    }


    public Session add(Request.Add builder) {
        return add(
                builder.documentUrl, builder.documentContentType,
                builder.clientId,
                builder.keywords, builder.events,
                builder.filterStopwords, builder.enableStemming, builder.ignoreCase,
                builder.snippetOffset, builder.interval
        );
    }


    private Session add(
            String documentUrl, String documentContentType,
            String clientId,
            List<String> keywords, List<DiffEvent> events,
            boolean filterStopwords, boolean enableStemming, boolean ignoreCase,
            int snippetOffset, int interval) {

        if (handlers.isEmpty()) {
            logger.error("Cannot add new jobs without listeners.");
            return null;
        }

        // test if the document is readable
        boolean isReadable = DocumentBuilder
                .fromUrl(documentUrl, documentContentType)
                .isReadable();
        if (!isReadable) {
            return null;
        }

        try {
            // attempt to create a new detection job
            JobKey detectJKey = detectJobKey(documentUrl, documentContentType);
            if (scheduler.getJobDetail(detectJKey) == null) {
                JobDetail detectionJob = JobBuilder.newJob(DiffDetectorJob.class)
                        .withIdentity(detectJKey)
                        .usingJobData(DiffDetectorJob.PARENT_JOB_MANAGER, managerName)
                        .usingJobData(DiffDetectorJob.FAULT_COUNTER, 0)
                        .usingJobData(DiffDetectorJob.URL, documentUrl)
                        .usingJobData(DiffDetectorJob.CONTENT_TYPE, documentContentType)
                        .storeDurably(true)
                        .build();

                try {
                    scheduler.addJob(detectionJob, false);
                    logger.info("Started detection job for document '{}' ({}).",
                            documentUrl, documentContentType);
                } catch (ObjectAlreadyExistsException ignored) {
                    // there is already a job monitoring the specified document, so
                    // ignore this
                    logger.warn("Detection job for document '{}' ({}) already exists!",
                            documentUrl, documentContentType);
                }
            } else {
                logger.warn("Detection job for document '{}' ({}) already exists!",
                        documentUrl, documentContentType);
            }

            Gson gson = new Gson();
            String keywordJson = gson.toJson(keywords);
            String eventsJson = gson.toJson(events);

            // attempt to create a new matching job, chained to execute after
            // the detection job
            JobKey matchJKey = matchJobKey(
                    documentUrl, documentContentType, clientId);
            if (scheduler.getJobDetail(matchJKey) != null) {
                logger.warn("Matching job " +
                                "for document '{}' ({}) " +
                                "to client '{}' already exists!.",
                        documentUrl, documentContentType,
                        clientId);
                return null;
            }
            String token = Constants.bytesToHex(Constants.generateRandomBytes());
            JobDetail matchingJob = JobBuilder.newJob(DiffMatcherJob.class)
                    .withIdentity(matchJKey)
                    .usingJobData(DiffMatcherJob.PARENT_JOB_MANAGER, managerName)
                    .usingJobData(DiffMatcherJob.DOCUMENT_URL, documentUrl)
                    .usingJobData(DiffMatcherJob.DOCUMENT_CONTENT_TYPE, documentContentType)
                    .usingJobData(DiffMatcherJob.CLIENT_ID, clientId)
                    .usingJobData(DiffMatcherJob.CLIENT_TOKEN, token)
                    .usingJobData(DiffMatcherJob.KEYWORDS, keywordJson)
                    .usingJobData(DiffMatcherJob.EVENTS, eventsJson)
                    .usingJobData(DiffMatcherJob.FILTER_STOPWORDS, filterStopwords)
                    .usingJobData(DiffMatcherJob.ENABLE_STEMMING, enableStemming)
                    .usingJobData(DiffMatcherJob.IGNORE_CASE, ignoreCase)
                    .usingJobData(DiffMatcherJob.SNIPPET_OFFSET, snippetOffset)
                    .usingJobData(DiffMatcherJob.HAS_NEW_DIFFS, false)
                    .storeDurably(true)
                    .build();

//            GregorianCalendar cal = new GregorianCalendar();
//            cal.add(Calendar.SECOND, request.getInterval());
//
//            Trigger matchingTrigger = TriggerBuilder.newTrigger()
//                    .withIdentity(matchTKey)
//                    .startAt(cal.getTime())
//                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
//                            .withIntervalInSeconds(request.getInterval())
//                            .repeatForever())
//                    .build();

            try {
                scheduler.addJob(matchingJob, false);
                logger.info("Started matching job " +
                                "for document '{}' ({}) " +
                                "to client '{}'.",
                        documentUrl, documentContentType,
                        clientId);
            } catch (ObjectAlreadyExistsException ex) {
                // there is already a matching job for the specified document,
                // so return false which should be interpreted as "not created /
                // already exists"
                logger.warn("Matching job " +
                                "for document '{}' ({}) " +
                                "to client '{}' already exists!.",
                        documentUrl, documentContentType,
                        clientId);
                return null;
            }


            // attempt to create a new detection trigger that uses the interval
            // specified by the client
            TriggerKey detectTKey = detectTriggerKey(
                    documentUrl, documentContentType,
                    clientId
            );
            Trigger detectionTrigger = TriggerBuilder.newTrigger()
                    .forJob(detectJKey)
                    .withIdentity(detectTKey)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds(interval)
                            .repeatForever())
                    .build();

            try {
                scheduler.scheduleJob(detectionTrigger);
                logger.info("Started detection trigger " +
                                "for document '{}' ({}) " +
                                "to client '{}', with interval {}.",
                        documentUrl, documentContentType,
                        clientId,
                        interval);
            } catch (ObjectAlreadyExistsException ignored) {
                // there is already trigger for the specified document and for
                // the specified client, so ignore this
            }

            JobChainingJobListener chain = new JobChainingJobListener(chainName(
                    documentUrl, documentContentType,
                    clientId
            ));
            chain.addJobChainLink(detectJKey, matchJKey);
            scheduler.getListenerManager().addJobListener(chain);

            return sessionCollection.add(clientId, token);

        } catch (SchedulerException ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }


    final void timeoutDetectionJob(String documentUrl, String documentContentType) {
        try {
            Set<JobKey> keys = scheduler.getJobKeys(
                    GroupMatcher.groupEquals(
                            matchJobGroup(documentUrl, documentContentType)));
            for (JobKey k : keys) {
                JobDetail detail = scheduler.getJobDetail(k);
                JobDataMap m = detail.getJobDataMap();
                String clientId = m.getString(DiffMatcherJob.CLIENT_ID);
                String clientToken = m.getString(DiffMatcherJob.CLIENT_TOKEN);

                scheduler.getListenerManager().removeJobListener(chainName(
                        documentUrl, documentContentType,
                        clientId
                ));
                sendTimeoutToClient(
                        documentUrl, documentContentType,
                        clientId, clientToken
                );
                scheduler.interrupt(k);
                scheduler.deleteJob(k);
                logger.info("Timed out matching job " +
                                "for document '{}' ({}) " +
                                "to client '{}'.",
                        documentUrl, documentContentType,
                        clientId);
            }

            JobKey detectJobKey = detectJobKey(documentUrl, documentContentType);
            scheduler.interrupt(detectJobKey);
            scheduler.deleteJob(detectJobKey);
            diffCollection.removeDifferences(documentUrl, documentContentType);
            logger.info("Timed out detection job for document '{}' ({}).",
                    documentUrl, documentContentType);
        } catch (SchedulerException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }


    /**
     * Simplified API
     */
    public boolean cancel(String documentUrl, String clientId) {
        return cancel(documentUrl, MediaType.TEXT_HTML, clientId);
    }


    public boolean cancel(String documentUrl, String documentContentType,
                          String clientId) {
        if (handlers.isEmpty()) {
            logger.error("Cannot cancel jobs without listeners.");
            return false;
        }
        JobKey jobKey = matchJobKey(
                documentUrl, documentContentType,
                clientId
        );

        try {
            boolean wasDeleted;
            try {
                scheduler.interrupt(jobKey);
                wasDeleted = scheduler.deleteJob(jobKey);
                if (wasDeleted) {
                    scheduler.getListenerManager().removeJobListener(chainName(
                            documentUrl, documentContentType,
                            clientId
                    ));
                    logger.info("Canceled matching job " +
                                    "for document '{}' ({}) " +
                                    "to client '{}'.",
                            documentUrl, documentContentType,
                            clientId
                    );
                }
            } catch (JobPersistenceException ex) {
                // job was already deleted
                wasDeleted = true;
            }

            if (wasDeleted) {
                // check if there are more match jobs for the same document
                Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.groupEquals(
                        matchJobGroup(documentUrl, documentContentType)
                ));
                if (keys.isEmpty()) {
                    // no more matching jobs for this document! interrupt the
                    // detection job
                    JobKey detectJobKey =
                            detectJobKey(documentUrl, documentContentType);
                    scheduler.interrupt(detectJobKey);
                    scheduler.deleteJob(detectJobKey);
                    diffCollection.removeDifferences(
                            documentUrl, documentContentType);
                    logger.info("Canceled detection job for document '{}' ({}).",
                            documentUrl, documentContentType);
                }

                // check if there are more match jobs for the same client
                keys = scheduler.getJobKeys(GroupMatcher.anyGroup());
                boolean hasMatchingJob = keys.parallelStream()
                        .anyMatch(k -> k.getName().equals(
                                matchJobName(clientId)));

                if (!hasMatchingJob) {
                    // no more match jobs for this client, so remove the session
                    sessionCollection.removeSession(clientId);
                }
            }

            return wasDeleted;

        } catch (SchedulerException ex) {
            logger.error(ex.getMessage(), ex);
        }
        return false;
    }


    public void stop() {
        try {
            scheduler.shutdown();
        } catch (SchedulerException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }


    /**
     * Indexes the specified document and detects differences between an older
     * snapshot and the new one with the specified content-type.
     */
    final boolean callDetectDiffImpl(String documentUrl,
                                     String documentContentType) {

        // check if there is a older document in the collection
        DocumentCollection.Pair pair = documentCollection
                .get(documentUrl, documentContentType);
        boolean wasSuccessful = false;
        boolean hasNewDiffs = false;

        if (pair != null) {
            // there was already a document for this url on the collection, so
            // detect differences between this and a new snapshot

            Document oldDocument = pair.latest();
            // remove the oldest document with this url and content type and add
            // the new one to the collection
            Document newDocument = documentCollection.addNewSnapshot(
                    oldDocument, langDetector,
                    filterStopwords, ignoreCase
            );
            if (newDocument != null) {
                DiffDetector detector = new DiffDetector(oldDocument, newDocument);
                List<DiffDetector.Result> results = detector.call();
                if (!results.isEmpty()) {
                    hasNewDiffs = true;
                    diffCollection.addDifferences(documentUrl, documentContentType, results);
                }

                wasSuccessful = true;

            }
        } else {
            // this is a new document, so process it and add to the collection
            Document newDocument = documentCollection.addNewDocument(
                    documentUrl, documentContentType, langDetector,
                    filterStopwords, ignoreCase
            );

            if (newDocument != null) {
                wasSuccessful = true;
            }
        }

        // notify all matching jobs of that url that there are new differences
        // to match
        if (wasSuccessful && hasNewDiffs) {
            try {
                Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.groupEquals(
                        matchJobGroup(documentUrl, documentContentType)
                ));
                for (JobKey k : keys) {
                    JobDetail detail = scheduler.getJobDetail(k);

                    // update job to say that he has new diffs
                    attemptRefreshMatchingJob(detail, 10);
                }
            } catch (SchedulerException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }

        return wasSuccessful;
    }


    private void attemptRefreshMatchingJob(JobDetail detail, int attemptsLeft) {
        if (attemptsLeft > 0) {
            try {
                JobDataMap m = detail.getJobDataMap();
                JobDetail matchingJob = JobBuilder.newJob(DiffMatcherJob.class)
                        .withIdentity(detail.getKey())
                        .usingJobData(DiffMatcherJob.PARENT_JOB_MANAGER,
                                managerName)
                        .usingJobData(DiffMatcherJob.DOCUMENT_URL,
                                m.getString(DiffMatcherJob.DOCUMENT_URL))
                        .usingJobData(DiffMatcherJob.DOCUMENT_CONTENT_TYPE,
                                m.getString(DiffMatcherJob.DOCUMENT_CONTENT_TYPE))
                        .usingJobData(DiffMatcherJob.CLIENT_ID,
                                m.getString(DiffMatcherJob.CLIENT_ID))
                        .usingJobData(DiffMatcherJob.CLIENT_TOKEN,
                                m.getString(DiffMatcherJob.CLIENT_TOKEN))
                        .usingJobData(DiffMatcherJob.KEYWORDS,
                                m.getString(DiffMatcherJob.KEYWORDS))
                        .usingJobData(DiffMatcherJob.EVENTS,
                                m.getString(DiffMatcherJob.EVENTS))
                        .usingJobData(DiffMatcherJob.FILTER_STOPWORDS,
                                m.getBoolean(DiffMatcherJob.FILTER_STOPWORDS))
                        .usingJobData(DiffMatcherJob.ENABLE_STEMMING,
                                m.getBoolean(DiffMatcherJob.ENABLE_STEMMING))
                        .usingJobData(DiffMatcherJob.IGNORE_CASE,
                                m.getBoolean(DiffMatcherJob.IGNORE_CASE))
                        .usingJobData(DiffMatcherJob.SNIPPET_OFFSET,
                                m.getInt(DiffMatcherJob.SNIPPET_OFFSET))
                        .usingJobData(DiffMatcherJob.HAS_NEW_DIFFS, true)
                        .storeDurably(true)
                        .build();

                scheduler.addJob(matchingJob, true);

            } catch (SchedulerException ignored) {
                // could not refresh job because the it did not unschedule
                // properly
                logger.info(ignored.toString());
                int newCount = attemptsLeft - 1;
                logger.info("Error while refreshing job, attempting "
                        + newCount + " more times.");
                attemptRefreshMatchingJob(detail, newCount);
            }
        }
    }


    /**
     * Matches the existing differences that were stored in the database with
     * the provided keywords
     */
    final Set<Match> callGetMatchesImpl(String documentUrl,
                                        String documentContentType,
                                        List<Keyword> keywords,
                                        boolean filterStopwords,
                                        boolean enableStemming,
                                        boolean ignoreCase,
                                        boolean ignoreAdded,
                                        boolean ignoreRemoved,
                                        int snippetOffset) {
        List<Diff> diffs = diffCollection.getDifferences(documentUrl, documentContentType);
        if (diffs.isEmpty()) {
            return Collections.emptySet();
        }

        DocumentCollection.Pair pair = documentCollection.get(documentUrl, documentContentType);
        if (pair == null) {
            return Collections.emptySet();
        }

        String oldText = pair.oldest().getText();
        String newText = pair.latest().getText();
        DiffMatcher matcher = new DiffMatcher(
                oldText, newText,
                keywords, diffs, parserPool, langDetector,
                filterStopwords, enableStemming, ignoreCase,
                ignoreAdded, ignoreRemoved,
                snippetOffset
        );
        try {
            return matcher.call();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        return Collections.emptySet();
    }


    final Keyword callBuildKeyword(String keywordInput,
                                   boolean isStoppingEnabled,
                                   boolean isStemmingEnabled,
                                   boolean ignoreCase) {
        KeywordBuilder builder = KeywordBuilder
                .fromText(keywordInput)
                .withLanguageDetector(langDetector);

        if (isStoppingEnabled) {
            builder.withStopwords();
        }
        if (isStemmingEnabled) {
            builder.withStemming();
        }
        if (ignoreCase) {
            builder.ignoreCase();
        }
        return builder.build(parserPool);
    }


    final List<Boolean> sendTimeoutToClient(String documentUrl, String documentContentType,
                                            String clientId, String clientToken) {
        Session session = sessionCollection.validateToken(clientId, clientToken);
        return handlers.parallelStream()
                .map(l -> l.onTimeout(documentUrl, documentContentType, session))
                .collect(Collectors.toList());
    }


    final List<Boolean> sendNotificationToClient(String documentUrl, String documentContentType,
                                                 String clientId, String clientToken,
                                                 Set<Match> results) {
        Session session = sessionCollection.validateToken(clientId, clientToken);
        return handlers.parallelStream()
                .map(l -> l.onNotification(documentUrl, documentContentType, session, results))
                .collect(Collectors.toList());
    }
}
