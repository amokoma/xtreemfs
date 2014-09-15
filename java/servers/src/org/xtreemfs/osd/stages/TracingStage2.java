/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.operations.ReadOperation;
import org.xtreemfs.osd.operations.TruncateOperation;
import org.xtreemfs.osd.operations.WriteOperation;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class TracingStage2 extends Stage {

    private final OSDRequestDispatcher master;
    private final int                  queueCapacity;
    /**
     * @param stageName
     * @param queueCapacity
     */
    public TracingStage2(OSDRequestDispatcher master, String stageName, int queueCapacity) {
        super(stageName, queueCapacity);
        this.master = master;
        this.queueCapacity = queueCapacity;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Tracing stage 2");
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage.StageRequest)
     */
    @Override
    protected void processMethod(StageRequest method) {
        // TODO Auto-generated method stub

    }

    public void prepareRequest(OSDRequest rq) {

        OSDOperation op = rq.getOperation();

        if (op instanceof ReadOperation) {
            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "###########ReadOperation###########");
        } else {
            if (op instanceof WriteOperation) {
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "###########WriteOperation###########");
            } else {
                if (op instanceof TruncateOperation) {
                    Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                            "###########TruncateOperation###########");
                }
            }
        }
    }

}
