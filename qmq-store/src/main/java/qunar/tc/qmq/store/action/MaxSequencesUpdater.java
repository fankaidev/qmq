/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.store.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.store.CheckpointManager;
import qunar.tc.qmq.store.event.FixedExecOrderEventBus;

/**
 * @author keli.wang
 * @since 2017/8/21
 */
public class MaxSequencesUpdater implements FixedExecOrderEventBus.Listener<ActionEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(MaxSequencesUpdater.class);

    private final CheckpointManager manager;

    public MaxSequencesUpdater(final CheckpointManager manager) {
        this.manager = manager;
    }

    @Override
    public void onEvent(final ActionEvent event) {
//        LOG.info("action event type={} offset={}", event.getAction().type(), event.getOffset());
        final long offset = event.getOffset();

        switch (event.getAction().type()) {
            case PULL:
                manager.updateActionReplayState(offset, (PullAction) event.getAction());
                break;
            case RANGE_ACK:
                manager.updateActionReplayState(offset, (RangeAckAction) event.getAction());
                break;
        }

    }
}
