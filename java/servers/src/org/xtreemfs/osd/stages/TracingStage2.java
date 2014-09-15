/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import org.xtreemfs.osd.OSDRequestDispatcher;

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
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage.StageRequest)
     */
    @Override
    protected void processMethod(StageRequest method) {
        // TODO Auto-generated method stub

    }

}
