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

package qunar.tc.qmq.protocol.consumer;

import lombok.Data;
import qunar.tc.qmq.TagType;

import java.util.Collections;
import java.util.List;

/**
 * @author yiqun.fan create on 17-7-4.
 */
@Data
public class PullRequest {
    private String subject;
    private String group;
    private int requestNum;
    private long timeoutMillis;
    private long offset;
    private long pullOffsetBegin;
    private long pullOffsetLast;
    private String consumerId;
    private boolean isBroadcast;

    private int tagTypeCode = TagType.NO_TAG.getCode();

    private List<byte[]> tags = Collections.emptyList();

    public boolean isBroadcast() {
        return isBroadcast;
    }

    public void setBroadcast(boolean broadcast) {
        isBroadcast = broadcast;
    }

}
