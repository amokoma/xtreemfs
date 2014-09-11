/*
 * Copyright (c) 2014 by Martin Wichner, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.ClientFactory;
import org.xtreemfs.common.libxtreemfs.FileHandle;
import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.client.RPCAuthentication;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.osd.NopTracingPolicyImpl;
import org.xtreemfs.osd.OSDConfig;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.TracingPolicy;
import org.xtreemfs.osd.TracingPolicyContainer;
import org.xtreemfs.osd.TracingThread;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SYSTEM_V_FCNTL;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.DirectoryEntry;

public class TracingStage extends Stage {

    private final OSDRequestDispatcher          master;

    // time left to next clean op
    private long                                timeToNextOFTclean;

    // last check of the OFT
    private long                                lastOFTcheck;

    private final static long                   OFT_CLEAN_INTERVAL    = 1000 * 60;

    private final BlockingQueue<TraceContainer> traceContainerQueue;

    public int                                  traceQCapacity;

    Client                                      client;

    Volume                                      v;

    UserCredentials                             userCredentials;

    Options                                     xtreemfsoptions;

    // private final String volumeID;

    private final String                        traceLogVolumeName    = "myVolume2";
    private final String                        traceLogDirectoryName = "TraceLogs";
    private final String                        traceLogFileName      = "traceLog.sav";

    int                                         countedByte           = 0;

    TracingPolicy                               tracingPolicy;

    TracingThread[]                             tracingThreads;
    int                                         numOfThreads;

    public TracingStage(OSDRequestDispatcher master, int maxRequestsQueueLength, int traceQCapacity, int numOfThreads,
            TracingPolicy tracingPolicy) {
        super("OSD TraceStage", maxRequestsQueueLength);
        this.master = master;

        this.traceContainerQueue = new LinkedBlockingQueue<TraceContainer>();
        this.traceQCapacity = traceQCapacity;

        this.tracingPolicy = tracingPolicy;


        int defaultNumberOfThreads = 4;
        if(numOfThreads <= 0){
            numOfThreads = defaultNumberOfThreads;
        }

        setupTracingThreads(master, maxRequestsQueueLength, numOfThreads);
    }

    /**
     * @param master
     * @param maxRequestsQueueLength
     * @param numOfThreads
     */
    private void setupTracingThreads(OSDRequestDispatcher master, int maxRequestsQueueLength, int numOfThreads) {
        tracingThreads = new TracingThread[numOfThreads];
        for (int i = 0; i < numOfThreads; i++) {
            tracingThreads[i] = new TracingThread(i, master, maxRequestsQueueLength, traceContainerQueue, this.q, this);
            tracingThreads[i].setLifeCycleListener(master);
            tracingThreads[i].start();
        }
    }

    public TracingStage(OSDRequestDispatcher master, int maxRequestsQueueLength, int traceQCapacity, int numOfThreads) {
        this(master, maxRequestsQueueLength, traceQCapacity, numOfThreads, new NopTracingPolicyImpl());

    }



    public void prepareRequest(OSDRequest request) {
        // Check if the request should be traced
        if (request.getCapability().getXCap().getTraceConfig().getTraceRequests() || true) { // TODO remove || true
            this.enqueueOperation(0, new Object[] { request }, null, null);

        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage .StageRequest)
     */
    @Override
    protected void processMethod(StageRequest stageRequest) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Write the tracelog in a file via the xtreemfs libary
     * 
     * @param directoryName
     *            name of the directory
     * @param traceLogFileName
     *            name of the trace log file
     */
    public void writeTraceLogLibXtreemFS(String directoryName, String traceLogFileName) {
        try {

            prepareClient();
            prepareVolume();

            String pathToTraceLogFile = "/" + directoryName + "/" + traceLogFileName;
            boolean foundTraceLogFile = checkForTraceLogFile(v, userCredentials, directoryName, traceLogFileName);

            TracingPolicyContainer container = new TracingPolicyContainer(getTraceLogMessage(traceContainerQueue));

            ArrayList<Object> list = new ArrayList<>();
            list.add(getTraceLogMessage(traceContainerQueue));

            String stringToWrite = (String) tracingPolicy.compact(list).get(0); // TODO change cast
            FileHandle fh = openTraceLogFile(foundTraceLogFile, userCredentials, pathToTraceLogFile);

            int w = fh.write(userCredentials, stringToWrite.getBytes(), stringToWrite.getBytes().length,
                    countedByte);

            countedByte += w;

            fh.flush();
            fh.close();

            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Bytes written: " + w);

            closeVolume();
            shutdownClient();

        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }

    }

    private void prepareClient() {
        xtreemfsoptions = createXtreemfsOptions();
        userCredentials = buildUserCredentials("amokoma", "xtreemfs"); // TODO flexibler
        this.client = ClientFactory.createClient("localhost", userCredentials, null, xtreemfsoptions);
        try {
            this.client.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 
     */
    private void prepareVolume() {
        try {
            boolean foundVolume = checkForVolume(client, traceLogVolumeName);

            if (!foundVolume) {
                client.createVolume("localhost", RPCAuthentication.authNone, userCredentials, traceLogVolumeName);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume created");

            } else {
                // client.deleteVolume(RPCAuthentication.authNone,
                // userCredentials, "myVolume2");
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume not created");
            }

            // Volume erstellt mit -a NULL
            v = client.openVolume(traceLogVolumeName, null, xtreemfsoptions);

            // v.start();
            boolean foundDir = checkForDirectory(v, userCredentials, traceLogDirectoryName);

            if (!foundDir) {
                v.createDirectory(userCredentials, traceLogDirectoryName, 0777);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Dir created");
            } else {
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Dir not created");
            }

        } catch (VolumeNotFoundException e) {
            try {
                client.createVolume("localhost", RPCAuthentication.authNone, userCredentials, traceLogVolumeName);

            } catch (IOException e1) {
                e1.printStackTrace();
            }
            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume created");

        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);

        }

    }

    private UserCredentials buildUserCredentials(String username, String group) {
        return UserCredentials.newBuilder().setUsername(username).addGroups(group).build();
    }

    private Options createXtreemfsOptions() {
        Options xtreemfsOptions = new Options();
        xtreemfsOptions.setMetadataCacheSize(0);
        return xtreemfsOptions;
    }

    private boolean checkForVolume(Client client, String volumeName) {
        boolean foundVolume = false;
        try {
            String[] list;
            list = client.listVolumeNames();
            for (int i = 0; i < list.length; i++) {
                if (list[i].equals(volumeName)) {
                    foundVolume = true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundVolume;

    }

    private boolean checkForDirectory(Volume volume, UserCredentials userCredentials, String directoryName) {
        boolean foundDirectory = false;
        try {
            for (DirectoryEntry e : v.readDir(userCredentials, "", 0, 0, true).getEntriesList()) {
                if (e.getName().equals(directoryName)) {
                    foundDirectory = true;
                }
            }
        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return foundDirectory;

    }

    /**
     * 
     */
    private void shutdownClient() {
        this.client.shutdown();
    }

    /**
     * 
     */
    private void closeVolume() {
        v.close();

    }

    private boolean checkForTraceLogFile(Volume volume, UserCredentials userCredentials, String directoryName,
            String traceLogFileName) {
        boolean foundTraceLogFile = false;
        try {
            for (DirectoryEntry e : volume.readDir(userCredentials, directoryName, 0, 0, true).getEntriesList()) {
                if (e.getName().equals(traceLogFileName)) {
                    foundTraceLogFile = true;
                }
            }
        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return foundTraceLogFile;
    }

    private String getTraceLogMessage(BlockingQueue<TraceContainer> traceQ) {

        String stringToWrite = "";
        for (TraceContainer t : traceQ) {
            stringToWrite += t.getLogString() + "\r\n";
        }
        return stringToWrite;
    }

    private FileHandle openTraceLogFile(boolean foundTraceLogFile, UserCredentials userCredentials,
            String pathToTraceLogFile) {
        FileHandle fileHandle = null;
        try {
            fileHandle = !foundTraceLogFile ? v.openFile(
                    userCredentials,
                    pathToTraceLogFile,
                    SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_CREAT.getNumber()
                    | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDWR.getNumber(), 0666) : v.openFile(userCredentials,
                    pathToTraceLogFile, SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_WRONLY.getNumber());

        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return fileHandle;
    }

    @Override
    public void run() {
        notifyStarted();

        timeToNextOFTclean = OSDConfig.OFT_CLEAN_INTERVAL;
        while (!quit || q.size() > 0) {
            // try {
            // sleep(timeToNextOFTclean);
            // } catch (InterruptedException e) {
            // e.printStackTrace();
            // }

            StageRequest request = q.peek();

            if (request == null) {

                if (!traceContainerQueue.isEmpty()) {


                    writeTraceLogLibXtreemFS(traceLogDirectoryName, traceLogFileName);

                    Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Wrote trace");
                    this.traceContainerQueue.clear();
                }

                continue;
            }
        }
        notifyStopped();
    }

}
